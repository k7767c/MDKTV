package com.fenghuolun.ktv

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 手机当话筒：接收手机浏览器采集的 PCM 音频流，在车机上播放出来。
 *
 * 场景：车里 K 歌大家各拿一个手机唱，不用抢那一个外接麦 —— 扫码打开话筒页，
 * 手机麦克风采集 → 局域网分块回传 → 这里写进 AudioTrack 与伴奏一起从车机喇叭出。
 *
 * 设计要点：
 * - **独立端口**（默认 8780-8799 找空闲），不掺和 [LocalRemoteServer] 的点歌 HTTP，
 *   那边是"一次请求一次响应"，这边是持续的音频流，混在一起会让两边都难维护。
 *   端口区间与点歌服务的 8765-8775 刻意错开，避免互相抢占（见 start 里的注释）。
 * - **PCM 直传**：手机端传 Int16LE / 16kHz / 单声道 裸 PCM，服务端不再转码，
 *   CPU 开销最低（车机弱 SoC 上这点很关键）。
 * - **每客户端一个播放器**：多人各用一个手机同时唱，各占一个 AudioTrack 混音输出。
 * - **防啸叫伺服**：手机麦会收到车机喇叭漏过来的伴奏（正反馈 = 啸叫）。这里持续测
 *   输入 RMS，连续超阈值就判定为啸叫 → 回一个降增益指令让手机端把采集增益压低，
 *   并对本路播放加衰减。手机端每次上传都会拿到这个指令（响应体里带），无需额外通道。
 * - **HTTP keep-alive**：一个连接收多个请求。HTTPS 每次新建连接都要完整 TLS 握手
 *   （RSA/ECDHE），弱车机 SoC 上每秒 25 次握手能把 CPU 打满、把推送延迟抬高，
 *   进而触发 3 秒超时释放 → 竞态。keep-alive 把握手降到每连接一次。
 * - **AudioTrack 生命周期加锁**：release 与 write 跨线程并发会打崩 native 层
 *   （无声崩溃的真凶之一），全部走客户端锁 + released 标志，释放改异步执行，
 *   主线程永不被锁堵住（ANR 防线）。
 *
 * 限制：iPhone 的 Safari/微信在 http 内网页面上不给麦克风权限（浏览器安全策略），
 * 安卓 Chrome/微信（X5 内核）可用 —— 所以正式链路走 [HttpsMicServer] 的 https。
 */
class MicStreamServer(
    context: android.content.Context,
    private val onClientChanged: (Int) -> Unit = {},
) {
    companion object {
        private const val TAG = "MicStreamServer"

        /** 采样率：人声/伴奏语音带宽够用，且比 44.1k 省一半带宽与 CPU。 */
        const val SAMPLE_RATE = 16000

        /** 与手机端 mic.html 约定的帧长（毫秒）。 */
        const val FRAME_MS = 40

        /** 单包最大字节：20 帧 = 800ms ≈ 25.6KB，给弱网留出重试余量。 */
        private const val MAX_BODY = 256 * 1024

        /** 连续多少个采样块超阈值才判为啸叫（避免正常大嗓门被误杀）。 */
        private const val HOWL_HOT_STREAK = 12

        /** 啸叫判定阈值（RMS，0~32767 归一化前的均方根）。 */
        private const val HOWL_RMS_THRESHOLD = 12000.0

        /** 检出啸叫后回给手机的采集增益倍率。 */
        private const val HOWL_GAIN_SENT = 0.25

        /** 未检出时回 1.0（手机端按它平滑恢复）。 */
        private const val NORMAL_GAIN_SENT = 1.0

        /** 连接空闲多久没数据就断开（keep-alive 连接的保活上限）。 */
        private const val SOCKET_TIMEOUT_MS = 20_000

        /**
         * 同时允许的话筒客户端上限（v1.2.6）。
         *
         * 每客户端一个 AudioTrack，Android 混音器按路数线性增加 CPU 与延迟；
         * 车机 SoC 上十几路同时推流会把播放主线程一起拖垮（伴奏都卡）。
         * 超出后新连接明确拒绝，手机端会看到提示，不再无声挤占。
         */
        const val MAX_MIC_CLIENTS = 8

        /** 抖动缓冲队列容量（帧，40ms/帧 → 32 帧 ≈ 1.3s 上限）。 */
        private const val JITTER_QUEUE_FRAMES = 32

        /**
         * 起播预缓冲帧数（弱网自适应 3..6 帧 = 120..240ms）。
         *
         * 延迟优化（v1.2.12）：起播从 3 帧降到 2 帧（-40ms），自适应上限从 10 帧压到 6 帧
         * ——原来网络一差就攒到 400ms，唱的人会明显感觉自己声音"慢半拍"。
         * 队列本身（32 帧）仍能吸收 1.28s 抖动，抗弱网能力没丢，只是不再白攒延迟。
         *
         * 稳定回退（v1.2.13）：实测 v1.2.12 的 2 帧起播在弱网下"时好时坏"——
         * 起播缓冲太薄，网络一抖就抠出断音/爆音，且 AudioTrack 冷启动频繁。
         * 起播回到 3 帧（+40ms 延迟，可接受），自适应下限同步回到 3 帧，
         * 保留 v1.2.12 的 6 帧上限（不再白攒到 400ms）。
         */
        private const val JITTER_PREBUFFER_START = 3
        private const val JITTER_PREBUFFER_MIN = 3
        private const val JITTER_PREBUFFER_MAX = 6

        /** 预缓冲等待上限（毫秒）：等不够也起播，靠 AudioTrack 自身缓冲兜底。 */
        private const val JITTER_PREBUFFER_TIMEOUT_MS = 600L

        /** 写线程取空包的轮询间隔（毫秒）。 */
        private const val WRITER_IDLE_POLL_MS = 250L

        /** 连续这么多帧队列满 → 降低预缓冲目标（网络变好，别白攒延迟）。与欠载侧 4 次对称，避免缓冲只涨不跌。 */
        private const val OVERFULL_ADAPT_FRAMES = 4

        /** 每 500 个音频块记一条推送日志（约 20 秒一条，够看速率又不刷屏）。 */
        private const val PUSH_LOG_EVERY = 500L

        /**
         * 空闲宽限（v1.2.13）：手机 12s 没上传仍保留客户端（不清队列、不释放 track）。
         * 覆盖"瞬时断网 / 锁屏 / 切后台"，回来即续播 —— 根治"一下有一下没有"。
         */
        private const val RELEASE_IDLE_MS = 12_000L

        /** 硬回收：彻底 30s 没回来的客户端才真释放（防客户端无限堆积内存泄漏）。 */
        private const val RELEASE_HARD_MS = 30_000L
    }

    /** 只用于读 assets 里的 mic.html，不持有 Activity。 */
    private val appContext: android.content.Context = context.applicationContext

    /** 一路手机的实时播放状态。 */
    private class Client(val id: String) {
        @Volatile var lastSeen = System.currentTimeMillis()
        @Volatile var howlGain = NORMAL_GAIN_SENT
        @Volatile var playGain = 1f
        val hotStreak = AtomicInteger(0)
        val blocks = AtomicLong(0)
        /** 已进入释放流程：不再建 track、不再 write。 */
        val released = AtomicBoolean(false)
        /** 保护 [track] 的创建/写入/释放 —— 跨线程并发会打崩 native 层。 */
        val lock = Any()
        var track: AudioTrack? = null

        // ---- 抖动缓冲（v1.2.6）----
        /**
         * 网络来的 PCM 帧先入队，写线程按真实 40ms 节奏放流。
         * 没有它，弱网一个迟到 300ms 的包就会在 AudioTrack 里抠出断音/爆音。
         */
        val queue = ArrayBlockingQueue<ByteArray>(JITTER_QUEUE_FRAMES)

        /** 起播预缓冲目标帧数（欠载自适应上调、持续过载下调）。 */
        @Volatile var preBufferTarget = JITTER_PREBUFFER_START

        /** 是否还在预缓冲阶段（写线程置 false 后开始放流）。 */
        @Volatile var preBuffering = true

        /** 连续入队被拒（队列满）计数，用于自适应降预缓冲。 */
        @Volatile var overfullCount = 0

        /** 写线程只启动一次。 */
        val writerStarted = AtomicBoolean(false)

        /** 是否处于"空闲宽限"档（idle > RELEASE_IDLE_MS 但 < HARD）。唤醒时据此清旧帧。 */
        @Volatile var idlePruned = false
    }

    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var pool = Executors.newCachedThreadPool()
    private val clients = ConcurrentHashMap<String, Client>()
    private var boundPort = -1
    @Volatile private var boundAddress: String? = null
    @Volatile private var lastError: String? = null

    /**
     * AudioTrack 释放专用单线程执行器。
     *
     * 为什么不能就地释放：release 可能发生在主线程（pruneStale 每 5 秒跑一次），
     * 而同一时刻 pool 线程可能正压着 [Client.lock] 在 write —— 主线程等锁 = ANR。
     * 改成异步释放后主线程只翻标志位，永远不阻塞。
     */
    private val releaseExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mic-track-release").apply { isDaemon = true }
    }

    /**
     * 全局人声音量 0.0~1.0（车机端统一控制，与伴奏音量分开）。
     *
     * K 歌里人声必须能单独调：伴奏压太大就听不清唱的，人声压太大又盖过伴奏 ——
     * 之前人声跟着系统音量走，等于没有独立控制，这功能等于半成品。
     */
    @Volatile private var outputVolume = 0.8f

    /** 设置人声音量（0~100）。对所有已连接的客户端立即生效。 */
    fun setVolume(percent: Int) {
        val v = (percent.coerceIn(0, 100)) / 100f
        outputVolume = v
        for (client in clients.values) {
            synchronized(client.lock) {
                runCatching {
                    val t = client.track
                    if (t != null && t.state == AudioTrack.STATE_INITIALIZED) t.setVolume(v)
                }
            }
        }
    }

    fun volume(): Int = (outputVolume * 100).toInt()

    /**
     * PCM 旁路 tap（v1.2.6 评分引擎消费）。
     *
     * 每包话筒音频在写入播放队列前回调一次，参数是**增益缩放前的原始采集**。
     * 约定：只读消费，不得修改缓冲区；回调里抛异常会被吞掉不影响音频主链路。
     */
    @Volatile
    var pcmTap: ((String, ByteArray, Int) -> Unit)? = null

    /** 服务绑定的地址变了（如车机从 4G 切到热点 IP），上层据此重启。 */
    @Volatile var address: String? = null
        private set

    /**
     * P0-1 修复：API token 鉴权（v1.2.10）。
     *
     * 此前点歌 API（api/v1）与话筒端点（mic）零鉴权，同局域网任意设备可点歌/推流/改状态。
     * 现在：
     * - 车机端生成一个 UUID token，通过页面注入（window.KTV_TOKEN）给手机，
     *   手机侧所有请求带 X-Ktv-Token 头或 token= 参数；
     * - [handle] 校验 api/v1 与 mic/push、mic/status 请求携带正确 token；
     * - 旧版手机端（不带 token）请求会被拒，但开麦首次注册（mic/status probe）
     *   仍放行 —— 车机端从该请求里拿到的 token 会回填给手机，实现平滑升级。
     * 注意：注释里不要写「斜杠星号」路径通配（Kotlin 块注释支持嵌套，会吃掉整个文件）。
     */
    @Volatile
    var apiToken: String = UUID.randomUUID().toString()

    /** 上层（LocalRemoteServer）在注入页面时读取当前 token。 */
    fun token(): String = apiToken

    /** 旋转 token（车机重启/用户手动触发），让旧 token 立即失效。 */
    fun rotateToken() {
        apiToken = UUID.randomUUID().toString()
        KtvLog.i(TAG, "API token rotated: ${apiToken.take(8)}…")
    }

    /** 校验请求携带的 token：query 参数 ?token= 或 header X-Ktv-Token 任一匹配即通过。 */
    private fun isValidToken(queryToken: String?, headerToken: String?): Boolean {
        val t = when {
            !queryToken.isNullOrBlank() -> queryToken
            !headerToken.isNullOrBlank() -> headerToken
            else -> return false
        }
        return t == apiToken
    }

    fun isRunning(): Boolean = running.get()

    fun port(): Int = boundPort

    fun error(): String? = lastError

    fun clientCount(): Int = clients.size

    /** 一行式状态（日志/诊断面板用）。 */
    fun statusLine(): String =
        "mic running=${running.get()} addr=$boundAddress port=$boundPort clients=${clients.size}" +
            (lastError?.let { " error=$it" } ?: "")

    /**
     * 释放空闲客户端。两档（v1.2.13）：
     * - [RELEASE_IDLE_MS]（12s）：手机短暂断网/锁屏/切后台，**保留队列与 AudioTrack**
     *   不重建 —— 用户回来声音继续，不再"一下有一下没有"反复断流。
     * - [RELEASE_HARD_MS]（30s）：彻底没回来的客户端才真回收，防无限堆积内存泄漏。
     *
     * 此前只有 3s 一档：手机一卡顿/锁屏就触发 AudioTrack 冷启动重建（预缓冲 + 重连），
     * 是"时好时坏"的主因。
     */
    fun pruneStale() {
        val now = System.currentTimeMillis()
        var changed = false
        val hardDead = mutableListOf<Client>()
        for (client in clients.values) {
            val idle = now - client.lastSeen
            when {
                idle > RELEASE_HARD_MS -> hardDead.add(client)
                idle > RELEASE_IDLE_MS && !client.idlePruned -> {
                    client.idlePruned = true   // 进"宽限"档：不清队列不释放
                    KtvLog.d(TAG, "mic client ${client.id} idle ${idle}ms, keep track for up to ${RELEASE_HARD_MS / 1000}s")
                }
                idle <= RELEASE_IDLE_MS -> {
                    if (client.idlePruned) {
                        client.idlePruned = false
                        client.queue.clear()   // 唤醒：清掉空闲期堆积的旧帧，重新预缓冲
                    }
                }
            }
        }
        for (client in hardDead) releaseClient(client.id)
        if (hardDead.isNotEmpty()) {
            changed = true
            onClientChanged(clients.size)
        }
        // 宽限档变化也通知一次 UI（话筒数量显示可能因硬回收而变化）
        if (changed) { /* already fired above */ }
        // 即使只有宽限态切换，也同步一次最新数量给调用方
        onClientChanged(clients.size)
    }

    @Synchronized
    fun start(bindIp: String) {
        if (running.get() && bindIp == boundAddress) return
        stop()
        if (!LocalRemoteServer.isPrivateIpv4(bindIp)) {
            lastError = "未检测到可用局域网地址"
            KtvLog.w(TAG, "start refused: $bindIp is not a private ipv4")
            return
        }
        if (pool.isShutdown) pool = Executors.newCachedThreadPool()
        var socket: ServerSocket? = null
        // ⚠️ 端口区间必须与 [LocalRemoteServer] 的 8765..8775 **完全不重叠**：
        // 原来这里扫 8766..8776，重叠段 8766-8775 会被点歌服务占走，两个服务互相
        // 抢端口 —— 车机上表现为话筒服务起不来（手机看到"车机未开放话筒服务"）。
        // 现在话筒独占 8780..8799，点歌怎么重启都抢不走。
        for (port in 8780..8799) {
            try {
                socket = ServerSocket(port, 16)
                boundPort = port
                break
            } catch (_: Exception) {
                // 端口占用则试下一个
            }
        }
        if (socket == null) {
            lastError = "话筒端口 8780–8799 均被占用"
            KtvLog.e(TAG, "start failed: $lastError")
            return
        }
        server = socket
        boundAddress = bindIp
        address = bindIp
        lastError = null
        running.set(true)
        pool.execute {
            try {
                while (running.get()) {
                    val client = server?.accept() ?: break
                    pool.execute { handle(client) }
                }
            } catch (e: Exception) {
                if (running.get()) lastError = e.message ?: "话筒服务异常"
                KtvLog.w(TAG, "accept loop ended: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                running.set(false)
            }
        }
        KtvLog.i(TAG, "mic server listening on $bindIp:$boundPort")
    }

    @Synchronized
    fun stop() {
        val wasRunning = running.getAndSet(false)
        runCatching { server?.close() }
        server = null
        boundPort = -1
        boundAddress = null
        for (id in clients.keys.toList()) releaseClient(id)
        pool.shutdownNow()
        if (wasRunning) KtvLog.i(TAG, "mic server stopped (clients=${clients.size})")
    }

    /**
     * 释放一路客户端：先翻 [Client.released] 标志（后续推送直接丢弃），
     * 真正的 AudioTrack 释放丢给 [releaseExecutor] 异步做 —— 见该执行器的注释。
     */
    private fun releaseClient(id: String) {
        val client = clients.remove(id) ?: return
        client.released.set(true)
        client.queue.clear()
        releaseExecutor.execute {
            synchronized(client.lock) {
                runCatching {
                    val track = client.track
                    client.track = null
                    if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                        runCatching { track.pause() }
                        runCatching { track.flush() }
                        runCatching { track.release() }
                    }
                }.onFailure { KtvLog.w(TAG, "release track failed for $id: ${it.message}") }
            }
            KtvLog.i(TAG, "mic client released: $id (left=${clients.size}, blocks=${client.blocks.get()})")
        }
        onClientChanged(clients.size)
    }

    /**
     * 处理一条已建立的连接（明文或 TLS 解密后的都走这里）。
     *
     * 抽出来是为了让 [HttpsMicServer] 能复用同一套协议处理：HTTPS 只在外层套一层
     * TLS，里面的页面托管、PCM 上传、啸叫伺服逻辑完全一致，不写第二份。
     *
     * **keep-alive**：一个连接里循环收请求，直到客户端要求关闭 / 出错 / 读超时。
     * 手机每 40ms 推一包，不 keep-alive 就是每秒 25 次 TCP+TLS 握手。
     */
    fun acceptTls(socket: java.net.Socket) {
        if (pool.isShutdown) pool = Executors.newCachedThreadPool()
        pool.execute { handle(socket) }
    }

    /**
     * 点歌 API 的转发钩子。
     *
     * 为什么需要：https 页面里请求点歌接口会打到 HTTPS 端口（8890），
     * 而点歌服务在另一个端口（8765+）。浏览器又禁止 https 页面请求 http 明文接口
     * （混合内容拦截）。所以 HTTPS 侧必须能代答点歌接口 —— 手机在 https 页面里
     * 点歌和开麦就都走同一个安全连接，无需切端口、无需混合内容豁免。
     */
    @Volatile
    var orderApiProxy: ((method: String, path: String, query: Map<String, String>, body: String) -> String?)? = null

    private fun handle(socket: Socket) {
        runCatching {
            socket.soTimeout = SOCKET_TIMEOUT_MS
            socket.use { conn ->
                val remote = conn.inetAddress
                if (!isPrivateAddress(remote)) {
                    KtvLog.w(TAG, "reject non-lan client: $remote")
                    return
                }
                val input = BufferedInputStream(conn.getInputStream(), 64 * 1024)
                // keep-alive 主循环：break = 关连接
                conn@ while (true) {
                    val requestLine = readLine(input) ?: break@conn
                    val parts = requestLine.split(' ')
                    if (parts.size < 2) break@conn
                    val method = parts[0].uppercase(Locale.US)
                    val target = parts[1]
                    val version = parts.getOrNull(2) ?: "HTTP/1.1"
                    val path = target.substringBefore('?')
                    val query = parseQuery(target.substringAfter('?', ""))
                    val headers = readHeaders(input)

                    // HTTP/1.1 默认 keep-alive；客户端显式 close 才关
                    val wantClose = headers["connection"]?.lowercase(Locale.US)?.contains("close") == true ||
                        (version.startsWith("HTTP/1.0") && headers["connection"]?.lowercase(Locale.US)?.contains("keep-alive") != true)

                    // P0-1 鉴权：受保护路径校验 token。旧版手机端首次注册（/mic/status probe 且
                    // 未带任何 token）放行，响应里会把当前 apiToken 回填给手机，实现平滑升级。
                    val isProtectedPath = path.startsWith("/api/v1/") ||
                        (path.startsWith("/mic/") && path != "/mic/status" && path != "/mic/push") ||
                        (path == "/mic/status" && !query["state"].equals("probe", true))
                    if (isProtectedPath && !isValidToken(query["token"], headers["x-ktv-token"])) {
                        // 与 LocalRemoteServer 一致：401 回传当前 token，手机端可自愈重试一次。
                        writeText(
                            conn.getOutputStream(),
                            401,
                            "{\"ok\":false,\"code\":\"UNAUTHORIZED\",\"error\":\"token 无效或过期\",\"token\":\"$apiToken\"}"
                        )
                        if (wantClose) break@conn
                        continue@conn
                    }

                    // ---- 点歌 API 转发（https 页面用：避免 https→http 混合内容被浏览器拦截）----
                    if (path.startsWith("/api/v1/")) {
                        val length0 = headers["content-length"]?.toIntOrNull()?.coerceIn(0, MAX_BODY) ?: 0
                        val body0 = ByteArray(length0)
                        var off0 = 0
                        while (off0 < length0) {
                            val c = input.read(body0, off0, length0 - off0)
                            if (c < 0) break
                            off0 += c
                        }
                        val bodyText = String(body0, 0, off0, Charsets.UTF_8)
                        val proxied = orderApiProxy?.invoke(method, path, query, bodyText)
                        if (proxied != null) {
                            writeRaw(conn.getOutputStream(), 200, "application/json; charset=utf-8", proxied.toByteArray(Charsets.UTF_8))
                        } else {
                            writeText(conn.getOutputStream(), 404, "{\"ok\":false,\"error\":\"接口不可用\"}")
                        }
                        if (wantClose) break@conn
                        continue@conn
                    }
                    // ---- 静态资源与页面（走 HTTPS 时要点歌页，https 页面同源所以 microphone 与 api 都能用）----
                    if (method == "GET") {
                        when {
                            path == "/" || path == "/mobile" || path == "/mobile/" ||
                                path == "/mic" || path == "/mic/" || path == "/mic.html" -> {
                                    writePage(conn.getOutputStream(), pageProvider?.invoke() ?: defaultPage())
                                    if (wantClose) break@conn
                                    continue@conn
                                }

                            path == "/mobile/app.css" -> {
                                writeRaw(conn.getOutputStream(), 200, "text/css; charset=utf-8", assetBytes("mobile/app.css"))
                                if (wantClose) break@conn
                                continue@conn
                            }

                            path == "/mobile/app.js" -> {
                                writeRaw(conn.getOutputStream(), 200, "application/javascript; charset=utf-8", assetBytes("mobile/app.js"))
                                if (wantClose) break@conn
                                continue@conn
                            }

                            path == "/mobile/logo.css" -> {
                                writeRaw(conn.getOutputStream(), 200, "text/css; charset=utf-8", assetBytes("mobile/logo.css"))
                                if (wantClose) break@conn
                                continue@conn
                            }

                            // 手机浏览器直接开 https://<车机IP>:8890/logs 就能看车机日志
                            // （纯文本，不依赖任何 JS；出问题时让用户截图/转发这一段）
                            path == "/logs" || path == "/logs.txt" -> {
                                val text = buildString {
                                    appendLine("风火轮K歌版 运行日志（最近 500 条）")
                                    appendLine("时间: " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date()))
                                    appendLine(statusLine())
                                    appendLine("---")
                                    append(KtvLog.recentText(500))
                                }
                                writeRaw(conn.getOutputStream(), 200, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8))
                                if (wantClose) break@conn
                                continue@conn
                            }
                        }
                    }
                    if (method != "POST") {
                        writeText(conn.getOutputStream(), 405, "{\"ok\":false}")
                        if (wantClose) break@conn
                        continue@conn
                    }
                    // 允许手机连上后立刻上报麦克风权限失败/打开成功（0 字节体）
                    if (path == "/mic/status") {
                        val statusId = query["id"] ?: "unknown"
                        val state = query["state"] ?: ""
                        val statusClient = ensureClient(statusId)
                        if (statusClient == null) {
                            writeText(
                                conn.getOutputStream(),
                                200,
                                "{\"ok\":false,\"error\":\"话筒数量已满（最多${MAX_MIC_CLIENTS}个）\"}"
                            )
                            if (wantClose) break@conn else continue@conn
                        }
                        statusClient.lastSeen = System.currentTimeMillis()
                        // P0-1：响应体回填当前 token，旧版手机端据此升级；新版手机端从页面注入 token，不再依赖此字段。
                        writeText(
                            conn.getOutputStream(),
                            200,
                            "{\"ok\":true,\"gain\":${statusClient.howlGain},\"token\":\"$apiToken\"}"
                        )
                        KtvLog.i(TAG, "mic status from $statusId: $state")
                        if (wantClose) break@conn
                        continue@conn
                    }
                    if (path != "/mic/push") {
                        writeText(conn.getOutputStream(), 404, "{\"ok\":false}")
                        if (wantClose) break@conn
                        continue@conn
                    }
                    val clientId = query["id"] ?: break@conn
                    val micClient = ensureClient(clientId)
                    if (micClient == null) {
                        // 话筒位已满：明确拒绝，手机端会停推并提示，不再无声挤占带宽
                        writeText(
                            conn.getOutputStream(),
                            200,
                            "{\"ok\":false,\"error\":\"话筒数量已满（最多${MAX_MIC_CLIENTS}个），请稍后再试\"}"
                        )
                        if (wantClose) break@conn else continue@conn
                    }
                    val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, MAX_BODY) ?: break@conn
                    val pcm = ByteArray(length)
                    var offset = 0
                    while (offset < length) {
                        val count = input.read(pcm, offset, length - offset)
                        if (count < 0) break
                        offset += count
                    }
                    if (offset > 0) {
                        micClient.lastSeen = System.currentTimeMillis()
                        processPcm(micClient, pcm, offset)
                        val blocks = micClient.blocks.get()
                        if (blocks % PUSH_LOG_EVERY == 0L) {
                            KtvLog.i(TAG, "mic push $clientId blocks=$blocks (${length}B/frame)")
                        }
                    }
                    // 响应体带啸叫伺服指令 —— 手机端据此调整采集增益，这是唯一的反向通道
                    writeText(
                        conn.getOutputStream(),
                        200,
                        "{\"gain\":${micClient.howlGain},\"hot\":${micClient.hotStreak.get()}}"
                    )
                    if (wantClose) break@conn
                }
            }
        }.onFailure { e ->
            // TLS 握手失败 / 客户端暴力断开 / 读写异常都落这里。
            // 关键是**不能让它逃逸成未捕获异常** —— 以前 app"打开麦克风就消失"
            // 这类无声崩溃，大概率就是服务线程异常无人接。
            // keep-alive 空闲 20s 主动断连属正常 lifecycle，降级为 debug 不刷屏。
            val timeout = e is java.net.SocketTimeoutException
            val msg = "conn handling failed: ${e.javaClass.simpleName}: ${e.message}"
            if (timeout) KtvLog.d(TAG, msg) else KtvLog.w(TAG, msg)
        }
    }

    /**
     * 取（必要时创建）客户端。
     *
     * @return 已达 [MAX_MIC_CLIENTS] 上限且是新 id 时返回 null（调用方应拒绝并提示），
     *   已存在的 id 永远放行（不允许把正在唱的人踢掉）。
     */
    private fun ensureClient(id: String): Client? {
        clients[id]?.let { return it }
        if (clients.size >= MAX_MIC_CLIENTS) {
            KtvLog.w(TAG, "reject mic client $id: cap $MAX_MIC_CLIENTS reached")
            return null
        }
        return clients.getOrPut(id) {
            KtvLog.i(TAG, "mic client connected: $id (total=${clients.size})")
            onClientChanged(clients.size)
            Client(id)
        }
    }

    /**
     * 处理一包 PCM：
     * 1. 算 RMS 做啸叫检测（连续超阈值 → 压增益指令 + 本路衰减）；
     * 2. 旁路给评分引擎（增益缩放前的原始采集）；
     * 3. 按 playGain 缩放后**进入抖动缓冲队列**，由写线程按真实节奏放流。
     *
     * 为什么不再直接写 AudioTrack：网络来包是突发的，弱网下一个迟到 300ms 的包
     * 会在 AudioTrack 里抠出断音/爆音。队列吸收抖动，写线程匀速消费，
     * 代价是起播多等一个预缓冲（默认 3 帧 = 120ms）。
     *
     * track 的创建与写入都在写线程的 [Client.lock] 内，与 [releaseClient] 的
     * 释放互斥；released 后直接丢弃，杜绝"释放中的 track 被 write"打崩 native 层。
     */
    private fun processPcm(client: Client, pcm: ByteArray, length: Int) {
        client.blocks.incrementAndGet()
        if (client.released.get()) return
        // 啸叫检测：RMS
        var sum = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < length) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            sum += (s * s).toDouble()
            samples++
            i += 2
        }
        val rms = if (samples > 0) sqrt(sum / samples) else 0.0
        if (rms > HOWL_RMS_THRESHOLD) {
            val streak = client.hotStreak.incrementAndGet()
            if (streak >= HOWL_HOT_STREAK && client.howlGain != HOWL_GAIN_SENT) {
                client.howlGain = HOWL_GAIN_SENT
                client.playGain = 0.4f
                KtvLog.w(TAG, "howl detected on ${client.id} (rms=${rms.toInt()}), gain->$HOWL_GAIN_SENT")
            }
        } else {
            // 连续 6 块正常就认为啸叫结束，恢复
            if (client.hotStreak.get() > 6) {
                if (client.howlGain != NORMAL_GAIN_SENT) {
                    client.howlGain = NORMAL_GAIN_SENT
                    client.playGain = 1f
                    KtvLog.i(TAG, "howl cleared on ${client.id}, gain->1.0")
                }
            }
            client.hotStreak.set(0)
        }
        // 评分旁路：原始采集（缩放前的人声）。拷贝后异步投递给消费者线程，
        // 避免 640 点自相关（~13 万次乘加）阻塞网络接收线程，8 人同推时抖动缓冲形同虚设。
        pcmTap?.let { tap ->
            val copy = pcm.copyOfRange(0, minOf(length, pcm.size))
            pool.execute { runCatching { tap(client.id, copy, copy.size) } }
        }
        // 按 playGain 缩放（啸叫时衰减），原地改写后入队
        val gain = client.playGain
        if (gain != 1f) {
            var k = 0
            while (k + 1 < length) {
                val s = (((pcm[k + 1].toInt() shl 8) or (pcm[k].toInt() and 0xFF)).toShort().toInt() * gain).toInt()
                pcm[k] = (s and 0xFF).toByte()
                pcm[k + 1] = ((s shr 8) and 0xFF).toByte()
                k += 2
            }
        }
        // 入抖动缓冲队列；满了丢最旧一帧（保延迟低优先于保完整，KTV 场景
        // 一个 40ms 旧帧造成的断裂比整体延迟 40ms 更可感知）。
        if (!client.queue.offer(pcm)) {
            client.queue.poll()
            client.queue.offer(pcm)
            client.overfullCount++
        }
        startWriterIfNeeded(client)
    }

    /** 每客户端的播放写线程（只启动一次）。 */
    private fun startWriterIfNeeded(client: Client) {
        if (client.writerStarted.compareAndSet(false, true)) {
            if (pool.isShutdown) return
            pool.execute { writerLoop(client) }
        }
    }

    /**
     * 抖动缓冲放流循环：预缓冲等 [JITTER_PREBUFFER_START] 帧（或超时）后，
     * 按包的到达节奏匀速写 AudioTrack；连续取空 → 提高预缓冲目标（网络变差），
     * 持续队列满 → 降低目标（网络变好，别白攒延迟）。
     */
    private fun writerLoop(client: Client) {
        val prebufferStart = System.currentTimeMillis()
        while (!client.released.get() && client.preBuffering) {
            if (client.queue.size >= client.preBufferTarget) break
            if (System.currentTimeMillis() - prebufferStart > JITTER_PREBUFFER_TIMEOUT_MS) break
            try {
                Thread.sleep(10)
            } catch (_: InterruptedException) {
                return
            }
        }
        client.preBuffering = false
        var idlePolls = 0
        while (!client.released.get()) {
            val frame = try {
                client.queue.poll(WRITER_IDLE_POLL_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                return
            }
            if (frame == null) {
                idlePolls++
                if (idlePolls >= 4) {
                    // 连续 1 秒取不到帧 = 欠载，网络变差，多攒点缓冲
                    client.preBufferTarget =
                        min(JITTER_PREBUFFER_MAX, client.preBufferTarget + 1)
                    idlePolls = 0
                }
                continue
            }
            idlePolls = 0
            if (client.overfullCount >= OVERFULL_ADAPT_FRAMES) {
                client.preBufferTarget =
                    max(JITTER_PREBUFFER_MIN, client.preBufferTarget - 1)
                client.overfullCount = 0
            }
            synchronized(client.lock) {
                if (client.released.get()) return
                var track = ensureTrackLocked(client)
                if (track == null) {
                    // 创建失败（弱车机音频通道回收）：原地重试 3 次，全失败才放弃
                    for (attempt in 1..3) {
                        KtvLog.w(TAG, "AudioTrack create failed (${client.id}), retry $attempt/3 in 500ms")
                        Thread.sleep(500)
                        if (client.released.get()) return
                        track = ensureTrackLocked(client) ?: break
                    }
                    if (track == null) {
                        KtvLog.e(TAG, "AudioTrack permanently failed for ${client.id}, releasing client")
                        client.released.set(true)
                        releaseClient(client.id)
                        return
                    }
                }
                runCatching { track.write(frame, 0, frame.size) }
                    .onFailure { KtvLog.w(TAG, "track write failed on ${client.id}: ${it.message}") }
            }
        }
    }

    /** 取（必要时创建）该客户端的 AudioTrack。**必须在 [Client.lock] 内调用**。 */
    private fun ensureTrackLocked(client: Client): AudioTrack? {
        var t = client.track
        if (t != null && t.state == AudioTrack.STATE_INITIALIZED) return t
        // 旧 track 非 INITIALIZED（STOPPED/RELEASED）：先释放再重建，否则 native 层泄漏
        if (t != null) {
            runCatching { t.pause() }
            runCatching { t.flush() }
            runCatching { t.release() }
            KtvLog.w(TAG, "replacing stale AudioTrack for ${client.id}")
            client.track = null
        }
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            KtvLog.w(TAG, "getMinBufferSize failed (${client.id})")
            return null
        }
        // 缓冲取 minBuf 的 3 倍（原为 4 倍，v1.2.12 降到 2 倍）。
        // 延迟优化（v1.2.12）：4 倍在 16k 单声道下约 200~400ms，是人声滞后的第二大来源；
        // 降到 2 倍约省一半。仍有 1 倍余量吸收车机写抖动，配合下面的低延迟性能模式，
        // 破音风险可控（若实车出现破音，把倍数调回 3 即可）。
        // 稳定回退（v1.2.13）：实测 2 倍在弱网下"时好时坏"——缓冲太薄，网络一抖
        // AudioTrack 就抠出断音，且冷启动频繁。回到 3 倍（+40~100ms 延迟，可接受），
        // 保留低延迟性能模式，延迟与稳定取平衡。
        val bufSize = minBuf * 3
        return runCatching {
            val builder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
            // 延迟优化（v1.2.12）：请求低延迟输出通道。
            // 车机是 API 29（>26），该 API 可用；与上面的小缓冲配合能再压一截输出延迟。
            // 注意：低延迟模式不保证被底层接受，失败也只是退回普通模式，不影响功能。
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            val track = builder.build()
            // 新建时立刻套用当前人声音量，否则第一秒是全音量（爆音）
            track.setVolume(outputVolume)
            track.play()
            client.track = track
            KtvLog.i(TAG, "AudioTrack created for ${client.id} (buf=$bufSize)")
            track
        }.onFailure {
            KtvLog.e(TAG, "AudioTrack create failed for ${client.id}: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()
    }

    // ---- 极简 HTTP 解析（只够读请求头与 Content-Length 体，不做 chunked）----

    private fun readHeaders(input: BufferedInputStream): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).lowercase(Locale.US)] = line.substring(colon + 1).trim()
            }
        }
        return headers
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>()
        while (bytes.size < 8192) {
            val b = input.read()
            if (b < 0) return if (bytes.isEmpty()) null else String(bytes.toByteArray(), Charsets.UTF_8)
            if (b == '\n'.code) break
            if (b != '\r'.code) bytes.add(b.toByte())
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun parseQuery(query: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (pair in query.split('&')) {
            if (pair.isBlank()) continue
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            val key = pair.substring(0, idx)
            val value = pair.substring(idx + 1)
            out[key] = java.net.URLDecoder.decode(value, "UTF-8")
        }
        return out
    }

    private fun writeText(out: OutputStream, status: Int, body: String) {
        runCatching {
            val bytes = body.toByteArray(Charsets.UTF_8)
            out.write(
                (
                    "HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Connection: keep-alive\r\n\r\n"
                    ).toByteArray(Charsets.UTF_8)
            )
            out.write(bytes)
            out.flush()
        }.onFailure { KtvLog.w(TAG, "write response failed: ${it.message}") }
    }

    /**
     * 页面内容提供者：HTTPS 场景下由 [LocalRemoteServer.micBaseProvider] 那一套
     * 注入逻辑提供（把话筒基址塞进页面），避免两处各写一份注入代码。
     */
    @Volatile
    var pageProvider: (() -> String)? = null

    /** 默认页面：assets 里的点歌首页（点歌+话筒合一的那个）。 */
    private fun defaultPage(): String = runCatching {
        appContext.assets.open("mobile/index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrDefault("<html><body>页面资源缺失</body></html>")

    private fun assetBytes(name: String): ByteArray = runCatching {
        appContext.assets.open(name).use { it.readBytes() }
    }.getOrDefault(ByteArray(0))

    /** 通用响应写入（带 Content-Type 与长度）。 */
    private fun writeRaw(out: OutputStream, status: Int, contentType: String, bytes: ByteArray) {
        runCatching {
            out.write(
                (
                    "HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\n" +
                        "Content-Type: $contentType\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Cache-Control: no-store\r\n" +
                        "Connection: keep-alive\r\n\r\n"
                    ).toByteArray(Charsets.UTF_8)
            )
            out.write(bytes)
            out.flush()
        }.onFailure { KtvLog.w(TAG, "write raw failed: ${it.message}") }
    }

    /** 托管页面（默认点歌首页，可由 [pageProvider] 替换）。 */
    private fun writePage(out: OutputStream, html: String) {
        writeRaw(out, 200, "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8))
    }

    /** 与 [LocalRemoteServer] 同一套内网判定（只放行局域网来源）。 */
    private fun isPrivateAddress(address: InetAddress?): Boolean {
        if (address == null) return false
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
        val raw = address.address ?: return false
        if (raw.size != 4) {
            // IPv6：fc00::/7 唯一本地、fe80::/10 链路本地、NAT64 内嵌 v4
            val first = raw[0].toInt() and 0xFF
            if ((first and 0xFE) == 0xFC) return true
            if ((first and 0xC0) == 0x80) return true
            if (raw.size == 16) {
                for (i in 0 until 10) {
                    if (raw[i].toInt() != 0) return false
                }
                return (raw[10].toInt() and 0xFF) == 0xFF && (raw[11].toInt() and 0xFF) == 0xFF
            }
            return false
        }
        val ip = Inet4Address.getByAddress(raw)
        val host = ip.hostAddress ?: return false
        val first = raw[0].toInt() and 0xFF
        val second = raw[1].toInt() and 0xFF
        return first == 10 || (first == 172 && second in 16..31) || (first == 192 && second == 168) ||
            (first == 169 && second == 254) || (first == 100 && second in 64..127)
    }
}

