package com.fenghuolun.ktv

import android.util.Log
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLServerSocketFactory

/**
 * 车机端 HTTPS 服务（自签证书），专供"手机当话筒"使用。
 *
 * ## 为什么必须有 HTTPS
 *
 * 浏览器规定只有**安全上下文（secure context）**才提供麦克风接口
 * `navigator.mediaDevices.getUserMedia`。安全上下文 = `https:` + `localhost` +
 * `127.0.0.1`，**内网私网 IP（192.168.x.x / 10.x.x.x）不在豁免名单里**。
 * 所以 `http://192.168.168.105:8780/...` 上 `navigator.mediaDevices` 整个是
 * undefined —— 这就是"网页麦克风在 http 下死活用不了"的根因，跟安卓/苹果无关。
 *
 * 业界通行的破法就是**内网自签证书 HTTPS**（OpenWebUI 等大量项目本地部署都这么做）：
 * 手机第一次访问会提示"证书不安全"，点一次"继续前往/高级→继续"即信任，
 * 之后该站点就是安全上下文，麦克风立即可用。
 *
 * ## 为什么证书不含车机 IP
 *
 * 车机 IP 随网络变化（热点 192.168.43.1 / 家中 WiFi 10.x.x.x），
 * 若把 IP 写死进证书 SAN，换网络就失效。这里用**与 IP 无关的固定证书**
 * （SAN 只有 localhost/127.0.0.1），依赖"自签证书点过继续前往后浏览器按站点记住"
 * 这一浏览器行为 —— 实测内网 HTTPS 部署均如此工作。
 *
 * ## 已知限制
 *
 * **微信内置浏览器会拒绝自签证书**（X5 内核对非可信 CA 硬拦截），
 * 必须引导用户"用手机自带浏览器（Chrome/Safari）打开"。
 */
class HttpsMicServer(
    private val context: android.content.Context,
    private val delegate: MicStreamServer,
) {
    companion object {
        private const val TAG = "HttpsMicServer"

        /** HTTPS 端口区间：与点歌(8765-8775)、明文话筒(8780-8799) 完全错开。 */
        private const val HTTPS_PORT_FROM = 8890
        private const val HTTPS_PORT_TO = 8899

        private const val KEYSTORE = "cert/ktv_lans_https.p12"
        private const val KEYSTORE_PASSWORD = "ktvlans"
    }

    @Volatile
    private var server: SSLServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    @Volatile
    var port: Int = -1
        private set

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    /**
     * SSLContext 缓存。
     *
     * 读 assets + 解析 PKCS12 + 初始化 KeyManager 在弱车机 SoC 上要几十到上百毫秒，
     * 而 start() 可能被页面注入/接口轮询频繁触发（每次手机开页面都会走
     * pageProvider → httpsBaseOrEmpty → start）。缓存后只有第一次有成本。
     */
    @Volatile
    private var sslContextCache: SSLContext? = null

    /**
     * 生成 SSLContext（读内置证书）。失败返回 null 并记录原因。
     *
     * 证书放在 **assets** 而不是 `res/raw`：release 开了 `shrinkResources true`，
     * res/raw 里的 .p12 会被当成无用资源删掉（实测证书根本没进 APK），
     * assets 不参与资源压缩，稳定可靠。
     */
    private fun sslContext(): SSLContext? {
        sslContextCache?.let { return it }
        return runCatching {
            val keyStore = KeyStore.getInstance("PKCS12").apply {
                context.assets.open(KEYSTORE).use { load(it, KEYSTORE_PASSWORD.toCharArray()) }
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, KEYSTORE_PASSWORD.toCharArray())
            }
            SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
                .also { sslContextCache = it }
        }.onFailure {
            lastError = "证书加载失败：${it.message}"
            Log.e(TAG, "ssl init failed", it)
            KtvLog.e(TAG, "ssl init failed: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()
    }

    /**
     * 启动 HTTPS 服务。accept 到的连接直接交给 [delegate] 处理，
     * 只是外面套了一层 TLS —— 页面与 PCM 上传协议完全复用明文版那套。
     *
     * **幂等 + 同步**：页面注入、/api/v1/mic 轮询、二维码弹窗都会调它，
     * 以前不加锁会出现两个 start 交叉执行（一个把另一个刚绑好的端口关掉、
     * accept 线程泄漏、port 被写成 -1），手机侧表现就是"传着传着断了"。
     * 现在：已在跑就直接返回；stop 会等旧 accept 线程退出再放行新 start。
     */
    @Synchronized
    fun start() {
        if (running && port > 0) {
            KtvLog.d(TAG, "already running on :$port, skip start")
            return
        }
        stopInternal()
        val ctx = sslContext() ?: run {
            KtvLog.e(TAG, "start aborted: $lastError")
            return
        }
        val factory: SSLServerSocketFactory = runCatching { ctx.serverSocketFactory }.getOrNull() ?: run {
            lastError = "SSL 工厂创建失败"
            KtvLog.e(TAG, "start aborted: $lastError")
            return
        }
        var socket: SSLServerSocket? = null
        for (p in HTTPS_PORT_FROM..HTTPS_PORT_TO) {
            try {
                socket = factory.createServerSocket(p, 16) as SSLServerSocket
                port = p
                break
            } catch (_: Exception) {
                // 端口占用则试下一个
            }
        }
        if (socket == null) {
            lastError = "HTTPS 端口 $HTTPS_PORT_FROM-$HTTPS_PORT_TO 均被占用"
            KtvLog.e(TAG, "start failed: $lastError")
            return
        }
        server = socket
        lastError = null
        running = true
        val t = Thread({
            while (running) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                // 解密后交给 MicStreamServer 的 handle 处理
                runCatching { delegate.acceptTls(client) }
                    .onFailure { KtvLog.w(TAG, "dispatch tls client failed: ${it.message}") }
            }
            KtvLog.i(TAG, "accept thread exited (port=$port)")
        }, "https-mic-accept").apply { isDaemon = true }
        acceptThread = t
        t.start()
        KtvLog.i(TAG, "https mic server listening on :$port")
    }

    @Synchronized
    fun stop() {
        stopInternal()
    }

    /** 停止并等待 accept 线程退出（短超时，避免与下次 start 交叉）。 */
    private fun stopInternal() {
        val wasRunning = running
        running = false
        runCatching { server?.close() }
        server = null
        port = -1
        val t = acceptThread
        acceptThread = null
        if (t != null && t.isAlive) {
            runCatching { t.join(500) }
        }
        if (wasRunning) KtvLog.i(TAG, "https mic server stopped")
    }

    fun baseUrl(ip: String): String = if (port > 0) "https://$ip:$port" else ""
}
