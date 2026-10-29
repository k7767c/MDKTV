package com.fenghuolun.ktv

import android.Manifest
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.DialogInterface.OnShowListener
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaPlayer.OnCompletionListener
import android.media.MediaPlayer.OnPreparedListener
import android.media.MediaPlayer.TrackInfo
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.View.OnFocusChangeListener
import android.view.View.OnTouchListener
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.AdapterView.OnItemClickListener
import android.widget.AdapterView.OnItemLongClickListener
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.TextView
import android.widget.TextView.OnEditorActionListener
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.fenghuolun.ktv.KtvStore.Companion.stableId
import com.fenghuolun.ktv.Song.Companion.local
import com.fenghuolun.ktv.SongApiClient.getSongDownloadUrl
import com.fenghuolun.ktv.SongApiClient.init
import com.fenghuolun.ktv.SongOkDownloadManager.DownloadCallback
import com.fenghuolun.ktv.SongOkDownloadManager.download
import com.fenghuolun.ktv.SongOkDownloadManager.getLocalFile
import com.fenghuolun.ktv.SongOkDownloadManager.isDownloaded
import com.fenghuolun.ktv.player.PlayerControllerView
import com.fenghuolun.ktv.player.PlayerControllerView.OnControlListener
import com.fenghuolun.ktv.player.KtvPlaybackEngine
import com.fenghuolun.ktv.player.KtvVideoView
import com.fenghuolun.ktv.player.VocalSwitchHelper
import com.fenghuolun.ktv.player.VocalSwitchHelper.switchVocal
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.ArrayDeque
import java.util.Locale
import java.util.LinkedHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Function
import java.util.function.ToIntFunction
import kotlin.Any
import kotlin.Array
import kotlin.Boolean
import kotlin.ByteArray
import kotlin.Comparator
import kotlin.Exception
import kotlin.Float
import kotlin.FloatArray
import kotlin.Int
import kotlin.IntArray
import kotlin.String
import kotlin.Throws
import kotlin.also
import kotlin.arrayOf
import kotlin.arrayOfNulls
import kotlin.check
import kotlin.floatArrayOf
import kotlin.intArrayOf
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.plus
import kotlin.toString

class MainActivity : AppCompatActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val keyboardSearchRunnable = Runnable { performSearch() }
    private val io: ExecutorService = Executors.newFixedThreadPool(4)
    private val stateIo: ExecutorService = Executors.newSingleThreadExecutor()
    private val library = SongLibrary()
    private lateinit var stateDatabase: KtvStateDatabase
    private lateinit var playbackEngine: KtvPlaybackEngine

    /**
     * 获取 KTV 状态存储对象(供 Fragment 访问已点/已唱/收藏/歌单数据)。
     * 
     * @return 全局 KtvStore 实例
     */
    val store: KtvStore = KtvStore()
    private val remoteServer by lazy {
        LocalRemoteServer(this).apply {
            // 点歌页注入话筒服务基址 —— 让"扫一次码"既能点歌又能当话筒。
            // 端口可能变（8780 占用会顺延），每次取页面都实时算，不写死。
            // inject 里顺带"确保服务已起"：手机扫码那一刻可能比车机首次检测到
            // 局域网地址更早，provider 只负责给地址，起服务的活交给 ensureMicServer。
            micBaseProvider = { ensureMicServer(); micBaseUrl() }
            // P0-1：注入 API token 提供器，让车机端生成的 UUID 能被手机页面读走，
            // 手机侧 /api/v1/* 与 /mic/* 请求都带它（X-Ktv-Token 头或 ?token=）。
            apiTokenProvider = { micServer.apiToken }
        }
    }

    /** 话筒服务基址（如 http://192.168.43.1:8780）；不可用返回空串。 */
    private fun micBaseUrl(): String {
        val ip = localIp().takeIf { LocalRemoteServer.isPrivateIpv4(it) } ?: return ""
        val port = micServer.port()
        return if (port > 0) "http://$ip:$port" else ""
    }

    /**
     * `/api/v1/mic`：手机端运行时获取话筒服务地址与状态。
     *
     * 比"页面注入"可靠的原因：注入是页面加载那一刻的快照 —— ①车机如果那一刻还没拿到
     * 局域网地址就注入空串；②微信/浏览器会缓存 HTML，下次打开拿的是旧快照，车机
     * 后来把话筒服务起好了手机也不知道。改成运行时调接口，两种情况都能自愈。
     */
    private fun remoteMicJson(): JSONObject {
        val ok = ensureMicServer()
        val ip = localIp().takeIf { LocalRemoteServer.isPrivateIpv4(it) }
        // https 优先：只有 https 页面才能拿到浏览器麦克风接口，明文 http 地址一并给出
        // 供降级/诊断用（手机端会优先用 https 那个）。
        val httpsBase = ensureHttpsMicServer()
        return JSONObject()
            .put("available", ok || httpsBase.isNotBlank())
            .put("base", if (httpsBase.isNotBlank()) httpsBase else micBaseUrl())
            .put("httpBase", micBaseUrl())
            .put("port", micServer.port())
            .put("httpsPort", httpsMicServer.port)
            .put("clients", micServer.clientCount())
            .put("error", micServer.error() ?: if (ip == null) "未检测到可用局域网地址" else "")
            .put("address", ip ?: "")
    }

    /**
     * 确保话筒服务已启动（幂等）。
     *
     * 之前只在 onCreate + 5 秒网络轮询里起服务，存在竞态：手机扫码那一刻如果车机
     * 还没拿到局域网 IP，注入的就是空串，手机端会看到"车机未开放话筒服务"。
     * 现在所有取话筒地址的入口（页面注入/二维码弹窗/播放页状态）都先走这里，
     * 拿到 IP 就立刻起服务。
     */
    private fun ensureMicServer(): Boolean {
        val ip = localIp().takeIf { LocalRemoteServer.isPrivateIpv4(it) } ?: run {
            KtvLog.w("MicStreamServer", "ensure skipped: no private ipv4")
            return false
        }
        if (micServer.isRunning() && micServer.address == ip) return true
        micServer.start(ip)
        // start 失败（如端口全占）时把真实原因记下来，别静默
        micServer.error()?.let {
            Log.w("MicStreamServer", "ensure failed: $it")
            KtvLog.w("MicStreamServer", "ensure failed: $it")
        }
        return micServer.isRunning()
    }

    /**
     * 手机当话筒服务：手机扫码后采麦克风 → 局域网回传 → 车机 AudioTrack 播放。
     * 独立端口，不与点歌 HTTP 混用；随局域网地址变化一起重启。
     */
    private val micServer by lazy {
        MicStreamServer(this) { count -> main.post { onMicClientChanged(count) } }
    }

    /**
     * HTTPS 话筒服务（自签证书）。
     *
     * 浏览器只在安全上下文（https / localhost）提供麦克风接口，内网私网 IP 的 http
     * 下 `navigator.mediaDevices` 整个是 undefined —— 这是网页麦克风的唯一出路。
     * 与 [micServer] 复用同一套协议（页面托管 + PCM 上传 + 啸叫伺服），只外层加 TLS。
     */
    private val httpsMicServer: HttpsMicServer by lazy {
        // 必须传 applicationContext：HttpsMicServer 生命周期可能长于 Activity
        // （服务线程/轮询都在跑），直接持 Activity 会泄漏。
        val server = HttpsMicServer(applicationContext, micServer)
        // https 页面同源，所以点歌 API 也要由 HTTPS 侧代答：
        // 浏览器禁止 https 页面请求 http 明文接口（混合内容拦截）。
        micServer.orderApiProxy = { method, path, query, body ->
            runCatching { remoteApi(method, path, query, body).body }.getOrNull()
        }
        // 页面由 MainActivity 统一注入话筒地址。
        // 注意必须走方法引用而不是在这里调用：初始化期调用会递归触发 httpsMicServer
        // 的 lazy（pageProvider ← httpsBaseOrEmpty ← httpsMicServer），导致栈溢出。
        micServer.pageProvider = { pageWithMicBase(httpsBaseOrEmpty()) }
        server
    }

    /** https 基址（服务未起则先启动；不可用返回空串）。 */
    private fun httpsBaseOrEmpty(): String {
        val server = httpsMicServer
        if (!server.running) {
            server.start()
            server.lastError?.let {
                Log.w("HttpsMicServer", "start failed: $it")
                KtvLog.w("HttpsMicServer", "start failed: $it")
            }
        }
        val ip = localIp().takeIf { LocalRemoteServer.isPrivateIpv4(it) } ?: return ""
        return server.baseUrl(ip)
    }

    /** 把话筒基址注入点歌页（供 https 页面使用，与明文页共用同一份页面资源）。 */
    private fun pageWithMicBase(micBase: String): String {
        if (micBase.isBlank()) return ""
        val html = runCatching {
            assets.open("mobile/index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrDefault("")
        if (html.isBlank()) return ""
        // P0-1：注入 API token，手机话筒页所有 /api/v1/* 与 /mic/* 请求都带它（X-Ktv-Token 头或 ?token=）。
        val apiToken = micServer.apiToken
        val scripts = (
            (if (apiToken.isNotEmpty()) "<script>window.__KTV_TOKEN__=\"$apiToken\";</script>\n  " else "") +
            "<script>window.__MIC_BASE__=\"$micBase\";</script>\n  "
        )
        return html.replace(
            "<script src=\"/mobile/app.js\"></script>",
            "$scripts<script src=\"/mobile/app.js\"></script>"
        )
    }

    /** 确保 HTTPS 话筒服务已启动（幂等），返回 https 基址；不可用返回空串。 */
    private fun ensureHttpsMicServer(): String = httpsBaseOrEmpty()

    /**
     * 【v1.2.8 已移除蓝牙麦】原 BluetoothMicManager（HFP/SCO 手机当麦）+ 首页/
     * 工具中心入口 + 弹窗全部拆除：老板真车验证"行不通"（2026-10-12 拍板）。
     * 人声进车机的正路 = 手机话筒（HTTPS 网页推 PCM，已有抖动缓冲+8人上限）
     * + 有线麦（MicActivator 占位，未动）。蓝牙权限声明同步移除。
     * 备份见 git 历史 / BluetoothMicManager.kt 删除前版本。
     */

    @Volatile private var remoteStateVersion = 1L
    private val remoteSongCache = Collections.synchronizedMap(LinkedHashMap<String, Song>())
    private var mobileQrOverlay: View? = null
    private var fullScreenQrOverlay: View? = null
    private var updateCheckStarted = false
    private var compactUi = false
    private val remoteNetworkMonitor = object : Runnable {
        private var ticks = 0
        override fun run() {
            ticks++
            val ip = localIp().takeIf { LocalRemoteServer.isPrivateIpv4(it) }
            if (ip != remoteServer.address()) {
                KtvLog.i("NetMonitor", "ip changed: ${remoteServer.address()} -> $ip, restarting servers")
                remoteServer.stop()
                startRemoteServer()
                updateMobileQrOverlay()
                updateFullScreenQrOverlay()
            }
            // 话筒服务与点歌服务共用同一个局域网地址，IP 变了必须一起重启，
            // 否则手机连的是旧 IP 上的话筒端口（扫码页会直接"连不上车机"）。
            if (ip != null) {
                ensureMicServer()
            } else if (micServer.isRunning()) {
                KtvLog.w("NetMonitor", "no private ip, stopping mic server")
                micServer.stop()
            }
            micServer.pruneStale()
            // 每 60 秒记一次堆占用：车机内存紧，压缩效果要看得见（工具→运行日志）
            if (ticks % 12 == 0) logHeapSnapshot()
            main.postDelayed(this, 5000)
        }
    }

    /** 当前 Java 堆占用一行（used/max，MB）。 */
    private fun logHeapSnapshot() {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / 1048576
        val maxMb = rt.maxMemory() / 1048576
        KtvLog.i("Mem", "java heap used=${usedMb}MB max=${maxMb}MB clients=${micServer.clientCount()}")
    }
    private val visibleSongs: MutableList<Song> = ArrayList<Song>()
    private val visibleSingers: MutableList<Array<String?>?> = ArrayList<Array<String?>?>()
    private val visiblePlaylists: MutableList<Array<String?>> = ArrayList()
    private val orderQueue: MutableList<Song>? = store.orderQueue
    private val sangHistory = store.sangHistory

    /**
     * 获取当前下载任务列表(供 DownloadListFragment 展示下载队列)。
     * 
     * @return 全局下载任务列表
     */
    /**
     * 下载任务队列。
     * 用 CopyOnWriteArrayList：主线程增删（addToQueue/删除任务），
     * 而 downloadSong() 的回调线程会 add，且列表 getView 与 LocalRemoteServer 线程都会遍历它，
     * 裸 ArrayList 并发遍历会抛 ConcurrentModificationException。
     */
val downloads: MutableList<DownloadTask> = java.util.concurrent.CopyOnWriteArrayList<DownloadTask>()

    private var content: LinearLayout? = null
    private var nav: LinearLayout? = null
    private var header: TextView? = null
    private var status: TextView? = null
    private var progress: ProgressBar? = null
    private var databaseLoadingOverlay: FrameLayout? = null
    private var databaseLoadingText: TextView? = null
    private var databaseLoadingProgress: ProgressBar? = null
    private var databaseRetryButton: TextView? = null
    @Volatile private var databaseBootstrapRunning = false
    private var player: KtvVideoView? = null

    /** 播放器控制层(叠加在 player 之上)  */
    private var playerController: PlayerControllerView? = null
    private var currentMediaPlayer: MediaPlayer? = null
    private var vocalPlayer: MediaPlayer? = null
    private var embeddedVocalEngine: KtvPlaybackEngine? = null
    private var vocalPlayerPrepared = false
    private var pendingVocalPosition = 0
    private var embeddedDualTrackMode = false
    private var embeddedMainTrackOriginal = true

    /**
     * 获取当前正在播放的歌曲(供已点列表标记播放状态)。
     * 
     * @return 当前播放的歌曲,未播放时返回 null
     */
    var currentSong: Song? = null
        private set
    private var currentVocalPath = ""
    private var lyricCurrent: TextView? = null
    private var lyricNext: TextView? = null
    private val lyricLines: MutableList<LyricLine?> = ArrayList<LyricLine?>()
    private val lyricTicker: Runnable = object : Runnable {
        override fun run() {
            // P1 修复：App 后台/停止时 lyricTicker 仍每 500ms 自续，主线程空转耗电。
            // 加生命周期标志：onStop 置 false 不再自续，onResume 置 true 并 post 重启。
            if (!lyricTickerActive) return
            updateFullScreenProgress()
            updateLyricView()
            // v1.2.6：评分进度 tick + EQ 会话对齐（500ms 一次，成本可忽略）
            val pos = player?.currentPosition?.toLong() ?: 0L
            if (vocalScorer.isActive) vocalScorer.tick(pos)
            KtvEqualizer.syncSessions(
                listOf(
                    player?.audioSessionId() ?: 0,
                    vocalPlayer?.audioSessionId ?: 0,
                )
            )
            main.postDelayed(this, 500)
        }
    }
    /** lyricTicker 生命周期开关：onResume 开启、onStop 关闭，避免后台空转。 */
    @Volatile
    private var lyricTickerActive = true
    private var search: EditText? = null
    private var songList: ListView? = null
    private var modernSongAdapter: SongListAdapter? = null
    private var queueList: ListView? = null
    private var songAdapter: ArrayAdapter<String?>? = null
    private var queueAdapter: ArrayAdapter<String?>? = null
    private var audioManager: AudioManager? = null
    private var recorder: MediaRecorder? = null
    /**
     * 麦克风输入激活器：常驻打开麦克风通道，外接麦才会被路由到车机喇叭（否则唱不了）。
     * 录音前 pause、录完 resume。
     */
    private val micActivator = MicActivator()
    private var currentRecording: File? = null

    /**
     * 真人演唱评分引擎（v1.2.6 替换原 hash 假分）。
     * 数据源 = 手机话筒 PCM 旁路（MicStreamServer.pcmTap）。
     */
    private val vocalScorer = VocalScorer()

    /**
     * 音频焦点（v1.2.6）：来电/导航播报抢占时暂停或压低，
     * 之前完全没请求过焦点 —— 电话来了照唱，属于明确缺陷。
     */
    private var resumeAfterFocusLoss = false

    /** 躲避(duck)系数：焦点方要求 CAN_DUCK 时压到 0.2，GAIN 恢复 1。 */
    private var duckFactor = 1f

    /** v1.2.7：美式工具箱权限广播只发一次（onCreate 重入不重复发）。 */
    private var nezhaPermissionBroadcastSent = false

    /** v1.2.7：启动是否因缺存储权限被挂起（等授权回调后 recreate 续启）。 */
    private var bootDeferredForPermission = false
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // 被其它应用永久抢占（另一个播放器）：暂停并交出焦点，不自动恢复
                resumeAfterFocusLoss = false
                main.post { pauseForFocusLoss("已让位给其它应用") }
                abandonAudioFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // 来电等短暂抢占：暂停，拿回焦点后自动继续
                resumeAfterFocusLoss = playWhenPrepared
                main.post { pauseForFocusLoss(null) }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                main.post {
                    if (duckFactor != 0.2f) {
                        duckFactor = 0.2f
                        applyPlaybackMode()
                    }
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                main.post {
                    if (duckFactor != 1f) {
                        duckFactor = 1f
                        applyPlaybackMode()
                    }
                    if (resumeAfterFocusLoss && currentSong != null && !playbackPreparing) {
                        player?.start()
                        startVocalIfReady()
                        playWhenPrepared = true
                        if (textPlayPause != null) textPlayPause.setText("暂停")
                        updateBottomBar(currentSong, true)
                    }
                    resumeAfterFocusLoss = false
                }
            }
        }
    }
    private var currentPage = "首页"

    private data class FocusBookmark(
        val resourceName: String?,
        val marker: String?,
        val text: String?,
        val groupText: String?,
    )

    private data class FocusReturnPoint(
        val restorePage: () -> Unit,
        val focus: FocusBookmark?,
        val directFocus: View?,
    )

    private val focusReturnStack = ArrayDeque<FocusReturnPoint>()
    private var restoringFocusRoute = false
    private var focusRestoreSerial = 0

    /** 导航按钮列表,用于维护选中态视觉  */
    private val navButtons: MutableList<TextView> = ArrayList<TextView>()

    /** 当前选中的导航索引(-1 表示未选中,如初始主页)  */
    private var currentNavIndex = -1

    // ===== 分页浏览状态(用于数据库驱动的曲库浏览) =====
    /** 当前浏览模式的页码(从0开始)  */
    private var browsePage = 0

    /** 当前浏览模式的总页数  */
    private var browseTotalPages = 0

    /**
     * 原版一屏展示量：歌曲 2x5、歌手 4x2、排行榜 1x6。
     * 车机实测一页只有 6 首 / 歌星只有 2 排太少，车机 1920x1080 竖屏空间充足，
     * 这里按"一屏尽量填满"提高每页数量：歌曲 2 列 x 8 = 16，歌手 4 列 x 4 = 16，排行榜 8。
     */
    private fun browsePageSize(): Int = when (browseMode) {
        "rank" -> 8
        "singer_list" -> 16
        else -> 16
    }

    /** 当前浏览类型:"hot"/"language"/"wordcount"/"singer"/"search"  */
    private var browseMode = "hot"

    /** 当前浏览的筛选参数(如语种名、歌手ID、搜索关键词等)  */
    private var browseParam: String? = ""

    /** 当前浏览的总记录数  */
    private var browseTotalCount = 0
    private var browseRequestVersion = 0
    private data class BrowseCacheKey(
        val mode: String,
        val param: String,
        val language: String,
        val searchQuery: String,
        val page: Int,
        val pageSize: Int,
    )
    private data class BrowsePageResult(val songs: List<Song>, val total: Int)
    private val browsePageCache = object : LinkedHashMap<BrowseCacheKey, BrowsePageResult>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<BrowseCacheKey, BrowsePageResult>?): Boolean =
            size > 48
    }
    private val browseCountCache = object : LinkedHashMap<String, Int>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean = size > 24
    }
    private val browseRequestsInFlight = HashSet<BrowseCacheKey>()
    private val browseDisplayRequests = HashMap<BrowseCacheKey, Int>()
    private var searchScope = "song"
    private var activeSettingsEntries: List<SettingsEntry> = emptyList()
    private var activeSearchQuery = ""
    private var searchLanguage = "全部"
    private var searchContextId = ""
    private var suppressSearchWatcher = false

    /** 用户在大曲库打开前进入了曲库页面，打开完成后需要重放该次查询。 */
    private var catalogRefreshPending = false

    /** 显示分页信息的 TextView  */
    private var pageInfo: TextView? = null
    private var loopOne = false
    private var autoNext = true
    private var songRetryCount = 0 // 播放错误重试计数
    private var playbackPreparing = false
    private var playWhenPrepared = true
    private var playbackGeneration = 0L
    private val pendingQueueSongIds = LinkedHashSet<String>()
    private var suppressCompletionUntil = 0L
    private val autoFullscreenRunnable = Runnable {
        val eligible = autoFullscreenSeconds > 0 && currentSong != null && playWhenPrepared && !isFullScreen
        Log.d(TAG, "autoFullscreen fired eligible=$eligible seconds=$autoFullscreenSeconds " +
            "song=${currentSong?.let(::stableId)} playing=$playWhenPrepared full=$isFullScreen")
        if (eligible) {
            showFullScreenPlayer()
        }
    }
    private var homeBannerIndex = 0
    private val homeBanners = intArrayOf(
        R.drawable.home_banner_0,
        R.drawable.home_banner_1,
        R.drawable.home_banner_2,
        R.drawable.home_banner_3,
        R.drawable.home_banner_4,
    )
    private val homeBannerRunnable = object : Runnable {
        override fun run() {
            homeBannerIndex = (homeBannerIndex + 1) % homeBanners.size
            homePosterView?.setBackgroundResource(homeBanners[homeBannerIndex])
            textPosterPage?.text = "${homeBannerIndex + 1}/${homeBanners.size}"
            main.postDelayed(this, 5000L)
        }
    }
    private var originalVocal = false
    private var vocalChannelMode = "自动"
    private var musicVolume = 70
    private var micVolume = 70
    private var tone = 0
    private var atmosphere = "标准"
    private var singMode = "普通演唱"
    private var recordEnabled = true
    private var scoreEnabled = true
    private var marqueeEnabled = true
    private var marqueeText: String? = "欢迎使用风火轮"
    private var playWhileDownloading = false
    private var clearDownloadsOnBoot = false
    private var autoFullscreenSeconds = 0
    private var showUsbSongs = true
    private var autoDeleteSongs = true
    private var reserveStorageGb = 1.0
    private var floatingButtonEnabled = true
    private var songTitleSubtitleEnabled = true
    private var orderedSongOriginal = false
    private var orderedVolumeMode = 0
    private var orderedVocalMode = 0
    private var voiceEngineEnabled = true
    private var voiceEngineVolume = 70
    private var lightMode: String? = "自动"
    private var audioDelayMs = 0
    private var screenBrightness = 80
    private var screenMode = "适应屏幕"
    private var doubleScreenEnabled = false
    private var tableBroadcastEnabled = true
    private var tableBroadcastSeconds = 8
    private var tableBroadcastText: String? = "欢迎光临"

    private class LyricLine(val timeMs: Int, val text: String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getWindow().getDecorView().setSystemUiVisibility(
            (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        )
        // 使用新的横屏TV布局
        setContentView(R.layout.activity_main)
        applyResponsiveLayout()
        // v1.2.7 权限获取对齐风火轮音乐播放器：车载首选「美式工具箱」广播静默授权
        // （nezha.toolbox.action.REQUEST_PERMISSIONS，不弹 Android 原生权限窗）；
        // 工具箱不响应时（ADB 直装的开发/测试环境）1.5s 后走原生弹窗兜底，
        // 静默授权到账后由广播接收器 recreate() 续完启动。
        if (NezhaPermissionHelper.hasMissingPermission(this)) {
            if (!nezhaPermissionBroadcastSent) {
                nezhaPermissionBroadcastSent = true
                NezhaPermissionHelper.broadcastNezhaPermissionRequest(this)
                NezhaPermissionHelper.registerNezhaGrantReceiver(this) { granted ->
                    main.post {
                        KtvLog.i("Perm", "toolbox granted: ${granted.joinToString(",")}")
                        if (bootDeferredForPermission && hasStoragePermission()) {
                            recreate() // 启动曾因缺权限挂起，存储已到手 → 重走完整启动
                        }
                    }
                }
            }
            if (!hasStoragePermission()) {
                bootDeferredForPermission = true
                main.postDelayed({
                    if (!hasStoragePermission()) requestStorage()
                }, 1500)
                return
            }
        }
        // v1.2.7 首次启动免责与风险声明（不可绕过；体系对齐风火轮 DisclaimerManager）
        DisclaimerManager.showIfNeeded(this) {}
        AppPaths.ensureDirectories()
        store.load()
        loadStateFromStore()
        // v1.2.6：EQ 死代码正式接线（异步探测设备能力后恢复设置）
        initEqualizer()
        // v1.2.6：话筒 PCM 旁路接真人评分引擎
        initVocalScoring()
        stateDatabase = KtvStateDatabase(applicationContext)
        if (clearDownloadsOnBoot) clearDownloadedFilesOnBoot()
        val restoredState = runCatching {
            stateDatabase.restoreOrSeed(orderQueue!!, sangHistory)
        }.getOrElse { KtvStateDatabase.RestoredState(null, "idle") }
        currentSong = restoredState.currentSong
        playWhenPrepared = restoredState.playbackState != "paused"
        runCatching {
            stateDatabase.restoreDownloads().forEach { snapshot ->
                if (snapshot.status == "completed") return@forEach
                snapshot.localPath?.let { snapshot.song.path = it }
                downloads += DownloadTask(snapshot.song).apply {
                    progress = snapshot.progress
                    state = when (snapshot.status) {
                        "queued", "downloading" -> "下载中断，可重试"
                        "completed" -> "完成"
                        "failed" -> "失败：${snapshot.error.orEmpty()}"
                        else -> snapshot.status
                    }
                }
                if (snapshot.status == "queued" || snapshot.status == "downloading") {
                    stateDatabase.updateDownload(
                        snapshot.song,
                        "failed",
                        snapshot.progress,
                        snapshot.localPath,
                        "应用重启，等待重试",
                    )
                }
            }
        }
        applyScreenBrightness()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager?
        // 初始化 API 客户端 (歌曲下载链接)
        init("abe235a87118f6de", "080027deed4f")
        playbackEngine = KtvPlaybackEngine(applicationContext)
        // 初始化新布局的 UI 组件
        initTvLayout()
        val startupSong = restoredState.currentSong ?: orderQueue!!.firstOrNull()
        startupSong?.let { restoredSong ->
            play(restoredSong)
            updateBottomBar(restoredSong, true)
        }
        // Keep initialization on the loading state until the external catalog is ready.
        SongApiClient.init(this)  // 主线程初始化 JS Bridge
        initializeDatabase()
        startRemoteServer()
        // 手机话筒服务与点歌服务同地址同生命周期，IP 变化由 remoteNetworkMonitor 一起处理
        ensureMicServer()
        // HTTPS 话筒服务（自签证书）：浏览器只在安全上下文给麦克风接口，必须起
        ensureHttpsMicServer()
        // 上次崩溃提示：app 上次是"无声消失"还是真有崩溃，这里给用户一个明确信号
        KtvLog.lastCrashFile()?.let { crash ->
            main.post {
                Toast.makeText(
                    this,
                    "检测到上次崩溃，堆栈已存日志\n工具 → 运行日志 可查看（${crash.name}）",
                    Toast.LENGTH_LONG
                ).show()
            }
            KtvLog.i("MainActivity", "last crash file present: ${crash.name}")
        }
        KtvLog.i("MainActivity", "onCreate done: remote=${remoteServer.statusLine()} mic=${micServer.statusLine()} https=:${httpsMicServer.port}")
        updateMobileQrOverlay()
        main.postDelayed(remoteNetworkMonitor, 5000)
        main.post(lyricTicker)
        main.postDelayed({ checkAppUpdate(false) }, 2500L)
    }

    private fun initializeDatabase(downloadRequested: Boolean = false) {
        if (databaseBootstrapRunning) return
        databaseBootstrapRunning = true
        showDatabaseLoading(true, "正在检查本地曲库…", null)
        io.execute {
            val result = CatalogStartup.open(
                downloadRequested = downloadRequested,
                openLocal = { library.muse.open() },
                download = {
                    library.muse.close()
                    DatabaseBootstrapper.download(::showDatabaseDownloadProgress).map { Unit }
                },
            )
            if (result.available) warmRemoteBrowseCache(library.muse.songCount())
            main.post {
                if (isFinishing || isDestroyed) return@post
                databaseBootstrapRunning = false
                refreshLibrary {
                    showDatabaseLoading(false, "", null)
                    showHomePage()
                    if (result.error != null) {
                        showOptionalCatalogFailure(result.error.message.orEmpty())
                    } else if (!result.available && !downloadRequested) {
                        if (prefs()?.getBoolean("catalog_setup_shown", false) != true) {
                            prefs()?.edit()?.putBoolean("catalog_setup_shown", true)?.apply()
                            showCatalogSetupOptions()
                        }
                    }
                    if (catalogRefreshPending) refreshCurrentCatalogPage()
                }
            }
        }
    }

    private fun showCatalogSetupOptions() {
        AlertDialog.Builder(this)
            .setTitle("选择曲库方式（无需下载完整曲库即可进入）")
            .setItems(arrayOf("本地 / U盘歌曲，不下载完整曲库", "配置 NAS / 网络曲库地址", "下载完整曲库数据库（需要较多空间）")) { _, which ->
                when (which) {
                    0 -> showLocalPage()
                    1 -> showCatalogDialog()
                    2 -> confirmFullCatalogDownload()
                }
            }
            .setNegativeButton("稍后设置", null)
            .create().showForTv()
    }

    private fun confirmFullCatalogDownload() {
        AlertDialog.Builder(this)
            .setTitle("下载完整曲库数据库？")
            .setMessage("这是歌曲目录数据库，不是歌曲视频。下载和解压可能需要超过 2GB 存储空间。\n空间不足时，可以继续使用本地、U盘或自己的网络曲库，无需下载完整数据库。")
            .setPositiveButton("确认下载") { _, _ -> initializeDatabase(downloadRequested = true) }
            .setNegativeButton("取消", null)
            .create().showForTv()
    }

    private fun showOptionalCatalogFailure(message: String) {
        AlertDialog.Builder(this)
            .setTitle("完整曲库未安装，可继续使用应用")
            .setMessage(message)
            .setPositiveButton("选择其他曲库方式") { _, _ -> showCatalogSetupOptions() }
            .setNeutralButton("重试下载") { _, _ -> confirmFullCatalogDownload() }
            .setNegativeButton("继续使用", null)
            .create().showForTv()
    }

    private fun showDatabaseDownloadProgress(value: Int, detail: String?) {
        val percent = value.coerceIn(0, 100)
        val phase = when {
            percent < 80 -> "正在下载曲库"
            percent < 90 -> "正在解压曲库"
            percent < 100 -> "正在校验曲库"
            else -> "曲库就绪"
        }
        val text = detail ?: "$phase $percent%"
        main.post { showDatabaseLoading(true, text, percent) }
    }

    private fun warmRemoteBrowseCache(count: Int) {
        // 必须与 browsePageSize() 的 else 分支一致，否则预热出来的 key 与 loadBrowsePage 用的不匹配（缓存永不命中）
        val pageSize = browsePageSize()
        val songs = library.muse.hotSongs(0, pageSize * 2)
        synchronized(browseCountCache) { browseCountCache["hot\u0001\u0001全部\u0001"] = count }
        songs.chunked(pageSize).forEachIndexed { page, pageSongs ->
            val key = BrowseCacheKey("hot", "", "全部", "", page, pageSize)
            synchronized(browsePageCache) { browsePageCache[key] = BrowsePageResult(pageSongs, count) }
        }
    }

    override fun onResume() {
        super.onResume()
        // P1 修复：回前台恢复 lyricTicker（onStop 时已停）。先移除再 post，避免重复排队。
        lyricTickerActive = true
        main.removeCallbacks(lyricTicker)
        main.postDelayed(lyricTicker, 500)
    }

    override fun onStop() {
        super.onStop()
        // P1 修复：完全不可见时停 lyricTicker，避免 500ms 空转耗电。
        lyricTickerActive = false
        main.removeCallbacks(lyricTicker)
    }

    @Deprecated("Deprecated in Android")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // AAOS 车机内存紧张时 LMK 会优先杀后台应用。SongApiClient 常驻一个隐藏 WebView
        // （整套 JS/渲染上下文），在内存压力下释放掉可显著降低被杀概率；
        // 下次 getSongDownloadUrl 会自动重建，代价只是首次解析慢一点。
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            Log.w(TAG, "onTrimMemory level=$level -> release JS bridge WebView")
            SongApiClient.release()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "onLowMemory -> release JS bridge WebView")
        SongApiClient.release()
    }

    /**
     * 只更新"可见行"里正在下载的进度条，不重建列表。
     *
     * 原来 onDownloadProgress 里直接调 loadDownloadedList() → renderSongList() →
     * notifyDataSetChanged()，下载中每 250ms 就把整页 View（一页 16 首 = 上百个 View）
     * 全部重建一遍，车机上明显掉帧。现在改成按 tag 定位可见进度条原地 setProgress。
     * 状态变化（开始/完成/失败）仍走 refreshSongAdapter() 全量刷新。
     */
    private fun refreshVisibleProgressCells() {
        val list = songList ?: return
        if (list.adapter !is SongListAdapter) return
        if (modernSongAdapter == null || list.adapter !== modernSongAdapter) return
        if (browseMode != "downloads" && browseMode != "catalog" && browseMode != "rank") return
        val first = list.firstVisiblePosition
        val last = list.lastVisiblePosition
        if (last < first) return
        var updated = 0
        for (position in first..last) {
            val row = list.getChildAt(position - first) ?: continue
            updated += updateProgressInTree(row)
        }
        if (updated > 0) {
            // 下载管理页标题栏显示总进度概览
            if (browseMode == "downloads") updateDownloadsTitle()
        }
    }

    /** 递归在视图树里找带进度 tag 的 ProgressBar 并更新，返回更新了多少个。 */
    private fun updateProgressInTree(root: View): Int {
        if (root is ProgressBar) {
            val tag = root.tag as? String ?: return 0
            if (!tag.startsWith(SongListAdapter.PROGRESS_TAG_PREFIX)) return 0
            val key = tag.removePrefix(SongListAdapter.PROGRESS_TAG_PREFIX)
            val task = downloads.firstOrNull { SongListAdapter.songKey(it.song) == key } ?: return 0
            val progress = task.progress.coerceIn(0, 100)
            if (progress != root.progress) {
                root.progress = progress
                return 1
            }
            return 0
        }
        if (root !is ViewGroup) return 0
        var count = 0
        for (i in 0 until root.childCount) {
            count += updateProgressInTree(root.getChildAt(i))
        }
        return count
    }

    /** 下载管理页标题：显示"下载管理 (N) + 总体进度"。 */
    private fun updateDownloadsTitle() {
        if (browseMode != "downloads") return
        val active = downloads.filter { it.state.contains("下载") }
        if (active.isEmpty()) {
            listTitle?.text = "下载管理 (" + downloads.size + ")"
            return
        }
        val avg = active.map { it.progress }.average().toInt()
        listTitle?.text = "下载管理 (" + active.size + " 个进行中 · " + avg + "%)"
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        super.onDestroy()
        // P1 修复：销毁时停 lyricTicker 并移除回调，防止 Activity 销毁后残留 post 任务。
        lyricTickerActive = false
        main.removeCallbacks(lyricTicker)
        io.shutdownNow()
        // v1.2.7：交还音频焦点、释放 EQ 效果器、停评分引擎
        abandonAudioFocus()
        KtvEqualizer.releaseAll()
        vocalScorer.abort()
        // v1.2.7：注销美式工具箱授权广播接收器（与 onCreate 注册成对）
        NezhaPermissionHelper.unregisterNezhaGrantReceiver(this)
        // 一次性清掉 main Handler 上所有待执行任务。
        // 原来只逐个 removeCallbacks 少数几个具名 Runnable，但工程里还有大量匿名
        // postDelayed（如 checkAppUpdate / hideFullScreenChromeRunnable / hidePlaybackModeNotice /
        // hideSongIntroNotice / busyTimeoutRunnable 等）无法逐个移除 —— Handler 持有 Runnable、
        // Runnable 捕获 Activity 会造成泄漏，且 remoteNetworkMonitor 这类自续任务会在
        // Activity 销毁后再次被唤醒并重建 ServerSocket。
        main.removeCallbacksAndMessages(null)
        stopRecording(false)
        releaseVocalPlayer()
        if (::playbackEngine.isInitialized) playbackEngine.release()
        remoteServer.stop()
        micServer.stop()
        httpsMicServer.stop()
        library.muse.close()
        stateIo.shutdown()
        runCatching { stateIo.awaitTermination(2, TimeUnit.SECONDS) }
        if (::stateDatabase.isInitialized) stateDatabase.close()
    }

    private fun checkAppUpdate(manual: Boolean) {
        if (updateCheckStarted) return
        updateCheckStarted = true
        if (manual) toast("正在检查更新...")
        AppUpdateManager.check(BuildConfig.VERSION_NAME) { result ->
            main.post {
                updateCheckStarted = false
                result.onSuccess { release ->
                    if (release == null) {
                        if (manual) toast("当前已是最新版本 ${BuildConfig.VERSION_NAME}")
                    } else {
                        showAppUpdateDialog(release)
                    }
                }.onFailure { error ->
                    Log.w(TAG, "Update check failed", error)
                    if (manual) toast(error.message ?: "检查更新失败")
                }
            }
        }
    }

    private fun showAppUpdateDialog(release: AppUpdateManager.Release) {
        val notes = release.notes.trim().ifBlank { "发现新版本，建议立即更新。" }
        AlertDialog.Builder(this)
            .setTitle("发现新版本 ${release.version}")
            .setMessage("${release.title}\n\n$notes")
            .setNegativeButton("稍后", null)
            .setPositiveButton("下载并安装") { _, _ -> downloadAppUpdate(release) }
            .create().showForTv(DialogInterface.BUTTON_POSITIVE)
    }

    private fun downloadAppUpdate(release: AppUpdateManager.Release) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(12))
        }
        val value = label("正在下载 ${release.apkName}…", 16, Color.WHITE).apply { gravity = Gravity.CENTER }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        box.addView(value, LinearLayout.LayoutParams(-1, dp(44)))
        box.addView(bar, LinearLayout.LayoutParams(-1, dp(24)))
        val dialog = AlertDialog.Builder(this)
            .setTitle("应用更新")
            .setView(box)
            .setNegativeButton("后台下载", null)
            .create()
        dialog.showForTv()
        AppUpdateManager.download(this, release, { progress ->
            main.post {
                bar.progress = progress
                value.text = "正在下载 ${release.apkName}… $progress%"
            }
        }) { result ->
            main.post {
                dialog.dismiss()
                result.onSuccess { apk ->
                    AlertDialog.Builder(this)
                        .setTitle("下载完成")
                        .setMessage("版本 ${release.version} 已下载，是否现在安装？")
                        .setNegativeButton("稍后", null)
                        .setPositiveButton("安装") { _, _ -> AppUpdateManager.install(this, apk) }
                        .create().showForTv(DialogInterface.BUTTON_POSITIVE)
                }.onFailure { error ->
                    AlertDialog.Builder(this).setTitle("更新失败")
                        .setMessage(error.message ?: "下载安装包失败")
                        .setPositiveButton("确定", null).show()
                }
            }
        }
    }

    /**
     * XML 保留电视端的大触控/遥控焦点尺寸；窄屏横屏设备在运行时切换紧凑尺寸。
     * 平板及电视继续使用原始布局，手机不会再因固定 370dp 左栏和顶部按钮而溢出。
     */
    private fun applyResponsiveLayout() {
        val config = resources.configuration
        compactUi = config.screenWidthDp < 1000 || config.screenHeightDp < 600
        if (!compactUi) return
        val widthDp = config.screenWidthDp.coerceAtLeast(480)
        val barHeight = dp(52)
        findViewById<ViewGroup?>(R.id.top_bar)?.apply {
            layoutParams = layoutParams.apply { height = barHeight }
            setPadding(dp(10), 0, dp(8), 0)
        }
        findViewById<ViewGroup?>(R.id.bottom_bar)?.apply {
            layoutParams = layoutParams.apply { height = barHeight }
            setPadding(dp(10), 0, dp(10), 0)
        }
        // 顶部左侧 logo 已按老板要求去除(顶栏只保留品牌名文字)
        findViewById<TextView?>(R.id.top_brand_name)?.apply { textSize = 16f }
        if (widthDp < 760) {
            findViewById<View?>(R.id.top_brand_name)?.visibility = View.GONE
            findViewById<View?>(R.id.btn_top_search)?.visibility = View.GONE
        }
        listOf(
            R.id.btn_top_search, R.id.text_top_order_label, R.id.btn_top_vocal,
            R.id.btn_top_next, R.id.btn_top_pause, R.id.btn_top_replay, R.id.btn_top_tone,
        ).forEach { id ->
            findViewById<TextView?>(id)?.apply {
                textSize = 11f
                setPadding(dp(5), paddingTop, dp(6), paddingBottom)
            }
        }
        findViewById<FrameLayout?>(R.id.content_frame)?.let { frame ->
            (frame.layoutParams as? FrameLayout.LayoutParams)?.apply {
                topMargin = barHeight
                bottomMargin = barHeight
                frame.layoutParams = this
            }
        }
        findViewById<LinearLayout?>(R.id.main_layout)?.setPadding(dp(12), dp(10), dp(12), dp(8))
        findViewById<LinearLayout?>(R.id.home_layout)?.setPadding(dp(12), dp(10), dp(12), dp(8))
        findViewById<LinearLayout?>(R.id.left_column)?.let { left ->
            val desiredWidthDp = (widthDp * 0.34f).toInt().coerceIn(230, 340)
            left.layoutParams = left.layoutParams.apply { width = dp(desiredWidthDp) }
            findViewById<View?>(R.id.player_container)?.layoutParams =
                findViewById<View>(R.id.player_container).layoutParams.apply {
                    height = dp((desiredWidthDp * 9f / 16f).toInt())
                }
        }
        listOf(
            R.id.home_card_regular, R.id.home_card_favorite, R.id.home_card_category,
            R.id.home_card_rank, R.id.home_card_song, R.id.home_card_singer, R.id.home_card_local,
        ).forEach { id ->
            findViewById<TextView?>(id)?.apply { textSize = 15f; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        }
    }

    private fun chromeBarHeight(): Int = dp(if (compactUi) 52 else 64)

    private fun buildShell() {
        val root = FrameLayout(this)
        root.setBackgroundColor(BG)

        val shell = LinearLayout(this)
        shell.setOrientation(LinearLayout.HORIZONTAL)
        shell.setPadding(dp(16), dp(14), dp(16), dp(12))
        root.addView(shell, FrameLayout.LayoutParams(-1, -1))

        val navScroll = ScrollView(this)
        navScroll.setFillViewport(true)
        navScroll.setBackgroundColor(Color.rgb(12, 18, 25))
        shell.addView(navScroll, LinearLayout.LayoutParams(dp(168), -1))

        nav = LinearLayout(this)
        nav!!.setOrientation(LinearLayout.VERTICAL)
        nav!!.setPadding(dp(10), dp(10), dp(10), dp(10))
        navScroll.addView(nav, FrameLayout.LayoutParams(-1, -2))

        val brand = label("风火轮", 26, GOLD)
        brand.setGravity(Gravity.CENTER)
        nav!!.addView(brand, LinearLayout.LayoutParams(-1, dp(64)))
        // 导航项(图标 + 文字),选中态由 selectNav() 维护
        navButtons.clear()
        addNav("🏠 主页", View.OnClickListener { v: View? -> showHome() })
        addNav("🎵 点歌", View.OnClickListener { v: View? -> showSongs("全部") })
        addNav("🎤 歌星", View.OnClickListener { v: View? -> showSingers() })
        addNav("🏆 排行榜", View.OnClickListener { v: View? -> showRank() })
        addNav("🌐 语种", View.OnClickListener { v: View? -> showLanguages() })
        addNav("🔢 字数", View.OnClickListener { v: View? -> showWordCounts() })
        addNav("⭐ 收藏", View.OnClickListener { v: View? -> showFavorites() })
        addNav("📋 歌单", View.OnClickListener { v: View? -> showPlaylists() })
        addNav("📝 已点", View.OnClickListener { v: View? -> showQueue() })
        addNav("🎵 已唱", View.OnClickListener { v: View? -> showSang() })
        addNav("⬇️ 下载", View.OnClickListener { v: View? -> showDownloads() })
        addNav("💾 U盘", View.OnClickListener { v: View? -> showUdisk() })
        addNav("🎬 影片", View.OnClickListener { v: View? -> showLocalMovies() })
        addNav("🎉 派对", View.OnClickListener { v: View? -> showDisco() })
        addNav("📡 网络", View.OnClickListener { v: View? -> showNetworkDialog() })
        addNav("🖥️ 屏幕", View.OnClickListener { v: View? -> showScreenDialog() })
        addNav("📦 应用", View.OnClickListener { v: View? -> showAppCenter() })
        addNav("🔧 维护", View.OnClickListener { v: View? -> showLibraryMaintenance() })
        addNav("📻 广播", View.OnClickListener { v: View? -> showTableBroadcast() })
        addNav("⚙️ 设置", View.OnClickListener { v: View? -> showSettings() })

        val body = LinearLayout(this)
        body.setOrientation(LinearLayout.VERTICAL)
        body.setPadding(dp(14), 0, 0, 0)
        shell.addView(body, LinearLayout.LayoutParams(0, -1, 1f))

        val top = LinearLayout(this)
        top.setGravity(Gravity.CENTER_VERTICAL)
        top.setPadding(0, 0, 0, dp(6))
        header = label("首页", 24, TEXT_WHITE)
        top.addView(header, LinearLayout.LayoutParams(0, dp(54), 1f))
        status = label("准备就绪", 16, TEXT_DIM)
        status!!.setGravity(Gravity.RIGHT or Gravity.CENTER_VERTICAL)
        top.addView(status, LinearLayout.LayoutParams(dp(430), dp(54)))
        body.addView(top)
        // 顶栏底部分割线(原版风格)
        val topDivider = View(this)
        topDivider.setBackgroundColor(DIVIDER)
        body.addView(topDivider, LinearLayout.LayoutParams(-1, dp(1)))

        content = LinearLayout(this)
        content!!.setOrientation(LinearLayout.HORIZONTAL)
        body.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        progress = ProgressBar(this)
        progress!!.setVisibility(View.GONE)
        val pp = FrameLayout.LayoutParams(dp(42), dp(42), Gravity.BOTTOM or Gravity.RIGHT)
        pp.rightMargin = dp(24)
        pp.bottomMargin = dp(14)
        root.addView(progress, pp)

        // 将构建好的 shell 内容挂载到 main_activity 布局的 R.id.container 容器中
        // 注意:现在使用 MainFragment 加载原版布局,buildShell 的程序化 UI 不再直接添加到 container
        // 保留 buildShell 中的播放器、下载等核心组件初始化逻辑,但 UI 由 Fragment 管理
        val container = findViewById<FrameLayout?>(R.id.container)
        if (container != null) {
            // 不再添加程序化导航 UI,避免与 MainFragment 冲突
            // shell 中的播放器和下载组件仍通过 root 引用保持工作
        } else {
            setContentView(root)
        }
    }

    /**
     * 更新导航栏选中态视觉
     * @param index 要选中的导航项索引(0-based),-1 表示取消选中
     */
    private fun selectNav(index: Int) {
        currentNavIndex = index
        for (i in navButtons.indices) {
            val btn = navButtons.get(i)
            if (i == index) {
                // 选中态:半透明红背景 + 红色文字 + 左侧红色色条效果(通过 padding 模拟)
                btn.setBackgroundColor(NAV_SELECTED)
                btn.setTextColor(ACCENT_RED)
                btn.setPadding(dp(16), dp(6), dp(8), dp(6))
            } else {
                // 未选中:透明背景 + 白色文字
                btn.setBackgroundColor(Color.TRANSPARENT)
                btn.setTextColor(TEXT_WHITE)
                btn.setPadding(dp(8), dp(6), dp(8), dp(6))
            }
        }
    }

    private fun showHome() {
        if (content == null) return  // buildShell 未启用

        setPage("首页")
        selectNav(0)
        if (content == null) return
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(0, -1, 1.25f))
        left.addView(sectionTitle("快速入口"))
        val grid = GridLayout(this)
        grid.setColumnCount(3)
        left.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        addTile(
            grid,
            "新歌推荐",
            "网络/本地新增歌曲",
            View.OnClickListener { v: View? -> showSongs("全部") })
        addTile(grid, "热门排行", "按点播热度排序", View.OnClickListener { v: View? -> showRank() })
        addTile(grid, "歌星点歌", "按歌手查找", View.OnClickListener { v: View? -> showSingers() })
        addTile(
            grid,
            "语种点歌",
            "国语/粤语/外语",
            View.OnClickListener { v: View? -> showLanguages() })
        addTile(
            grid,
            "字数点歌",
            "按歌名字数查找",
            View.OnClickListener { v: View? -> showWordCounts() })
        addTile(
            grid,
            "拼音搜索",
            "支持简码/歌名/歌手",
            View.OnClickListener { v: View? -> showSearch() })
        addTile(
            grid,
            "我的收藏",
            "常唱歌曲快速点播",
            View.OnClickListener { v: View? -> showFavorites() })
        addTile(
            grid,
            "我的歌单",
            "本地自建歌单",
            View.OnClickListener { v: View? -> showPlaylists() })
        addTile(grid, "U盘导入", "扫描导入目录", View.OnClickListener { v: View? -> showUdisk() })
        addTile(
            grid,
            "下载管理",
            "网络曲库下载",
            View.OnClickListener { v: View? -> showDownloads() })

        addTile(
            grid,
            "音量音调",
            "原伴唱 / 音量 / 升降调",
            View.OnClickListener { v: View? -> showAudioControlDialog() })
        addTile(
            grid,
            "气氛模式",
            atmosphere,
            View.OnClickListener { v: View? -> showAtmosphereDialog() })
        addTile(
            grid,
            "演唱评分",
            if (scoreEnabled) scoreSummary() else "评分已关闭",
            View.OnClickListener { v: View? -> showScoreDialog() })
        addTile(
            grid,
            "本地录音",
            if (recorder == null) "录制演唱音频" else "正在录音",
            View.OnClickListener { v: View? -> showRecordings() })
        addTile(
            grid,
            "手机点歌",
            remoteUrl(),
            View.OnClickListener { v: View? -> showMobileRemoteDialog() })
        addTile(
            grid,
            "手机话筒",
            "安卓手机扫码即可当麦",
            View.OnClickListener { v: View? -> showMobileMicDialog() })
        addTile(
            grid,
            "运行日志",
            "崩溃/服务/话筒全记录",
            View.OnClickListener { v: View? -> showLogViewer() })
        addTile(
            grid,
            "跑马灯",
            if (marqueeEnabled) marqueeText else "跑马灯已关闭",
            View.OnClickListener { v: View? -> showMarqueeSettings() })
        addTile(grid, "灯光迪斯科", lightMode, View.OnClickListener { v: View? -> showDisco() })
        addTile(
            grid,
            "本地影片",
            "用户 MV / 影片播放",
            View.OnClickListener { v: View? -> showLocalMovies() })
        addTile(
            grid,
            "网络设置",
            remoteUrl(),
            View.OnClickListener { v: View? -> showNetworkDialog() })
        addTile(
            grid,
            "屏幕设置",
            screenMode,
            View.OnClickListener { v: View? -> showScreenDialog() })
        addTile(
            grid,
            "应用工具",
            "本地工具 / 维护",
            View.OnClickListener { v: View? -> showAppCenter() })
        addTile(
            grid,
            "曲库维护",
            "清理 / 重建 / 导出",
            View.OnClickListener { v: View? -> showLibraryMaintenance() })
        addTile(
            grid,
            "包房广播",
            if (tableBroadcastEnabled) tableBroadcastText else "广播已关闭",
            View.OnClickListener { v: View? -> showTableBroadcast() })

        val right = panel()
        content!!.addView(right, LinearLayout.LayoutParams(0, -1, 0.8f))
        right.addView(sectionTitle("播放预览"))
        // 用 FrameLayout 包裹共享播放 Surface,以便叠加控制层
        val playerContainer = FrameLayout(this)
        player = KtvVideoView(this).also {
            it.bind(playbackEngine)
            playbackEngine.attach(it)
        }
        player!!.setBackgroundColor(Color.BLACK)
        playerContainer.addView(player, FrameLayout.LayoutParams(-1, -1))
        // 初始化播放器控制层并叠加到视频上方
        playerController = PlayerControllerView(this)
        initPlayerControllerCallbacks()
        playerContainer.addView(playerController, FrameLayout.LayoutParams(-1, -1))
        right.addView(playerContainer, LinearLayout.LayoutParams(-1, 0, 1f))
        // 同步当前歌曲信息到控制层(若已在播放)
        if (currentSong != null) {
            playerController!!.updateSongInfo(currentSong)
            playerController!!.updatePlayState(playWhenPrepared)
            playerController!!.setVocalMode(originalVocal)
        }
        lyricCurrent = label("暂无歌词", 22, GOLD)
        lyricCurrent!!.setGravity(Gravity.CENTER)
        lyricNext = label("", 18, Color.LTGRAY)
        lyricNext!!.setGravity(Gravity.CENTER)
        right.addView(lyricCurrent, LinearLayout.LayoutParams(-1, dp(42)))
        right.addView(lyricNext, LinearLayout.LayoutParams(-1, dp(34)))
        val controls = LinearLayout(this)
        controls.setGravity(Gravity.CENTER_VERTICAL)
        controls.addView(
            button("暂停/继续", View.OnClickListener { v: View? -> togglePlay() }),
            LinearLayout.LayoutParams(0, dp(50), 1f)
        )
        controls.addView(
            button("重唱", View.OnClickListener { v: View? -> replay() }),
            LinearLayout.LayoutParams(0, dp(50), 1f)
        )
        controls.addView(
            button("切歌", View.OnClickListener { v: View? -> playNext() }),
            LinearLayout.LayoutParams(0, dp(50), 1f)
        )
        controls.addView(button("单曲循环", View.OnClickListener { v: View? ->
            loopOne = !loopOne
            toast(if (loopOne) "单曲循环" else "顺序播放")
        }), LinearLayout.LayoutParams(0, dp(50), 1f))
        controls.addView(
            button(
                vocalActionText(),
                View.OnClickListener { v: View? -> toggleOriginalVocal() }),
            LinearLayout.LayoutParams(0, dp(50), 1f)
        )
        controls.addView(
            button(
                "音控",
                View.OnClickListener { v: View? -> showAudioControlDialog() }),
            LinearLayout.LayoutParams(0, dp(50), 1f)
        )
        controls.addView(
            button(
                if (recorder == null) "录音" else "停止录音",
                View.OnClickListener { v: View? -> toggleRecording() }),
            LinearLayout.LayoutParams(0, dp(50), 1f)
        )
        controls.addView(
            button(
                "状态",
                View.OnClickListener { v: View? -> showPlayerStatusDialog() }),
            LinearLayout.LayoutParams(0, dp(50), 1f)
        )
        right.addView(controls)
        right.addView(sectionTitle("已点 " + orderQueue!!.size))
        queueList = listView()
        queueAdapter = tvAdapter()
        queueList!!.setAdapter(queueAdapter)
        queueList!!.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            play(
                orderQueue.get(pos)
            )
        })
        right.addView(queueList, LinearLayout.LayoutParams(-1, 0, 1f))
        renderQueue()
        // v1.2.7：首页常驻风险提示（对齐风火轮免责体系；点击可随时查看完整声明）
        val riskFooter = label(DisclaimerText.HOME_FOOTER, 14, Color.rgb(150, 160, 175))
        riskFooter.gravity = Gravity.CENTER
        riskFooter.isClickable = true
        riskFooter.isFocusable = true
        riskFooter.isFocusableInTouchMode = true
        riskFooter.setOnClickListener { DisclaimerManager.showAlways(this) }
        riskFooter.setOnFocusChangeListener { v: View?, hasFocus: Boolean ->
            v?.setBackgroundColor(if (hasFocus) Color.argb(60, 245, 190, 89) else Color.TRANSPARENT)
        }
        right.addView(riskFooter, LinearLayout.LayoutParams(-1, dp(40)))
    }

    private fun showSearch() {
        showSongs("全部")
    }

    fun showSongs(category: String?) {
        setPage("点歌")
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(dp(230), -1))
        left.addView(sectionTitle("分类"))
        // 分类按钮:优先使用数据库语种,回退到本地分类
        val cats: MutableList<String?> = ArrayList<String?>()
        if (library.muse.isAvailable()) {
            cats.add("全部")
            cats.addAll(library.muse.languages())
            cats.add("本地")
            cats.add("网络")
        } else {
            cats.addAll(library.categories())
        }
        for (item in cats) {
            left.addView(button(item, View.OnClickListener { v: View? ->
                val cat = (v as TextView).getText().toString()
                if (library.muse.isAvailable() && ("本地" != cat) && ("网络" != cat)) {
                    browseMode = "language"
                    browseParam = cat
                    browsePage = 0
                    loadBrowsePage()
                } else {
                    filterSongs(cat, "")
                }
            }))
        }
        left.addView(button("网络同步", View.OnClickListener { v: View? -> fetchCatalog() }))

        val center = panel()
        content!!.addView(center, LinearLayout.LayoutParams(0, -1, 1f))
        val searchRow = LinearLayout(this)
        searchRow.setGravity(Gravity.CENTER_VERTICAL)
        search = EditText(this)
        search!!.setSingleLine(true)
        search!!.setHint("输入歌名 / 歌手 / 拼音简码")
        search!!.setTextColor(Color.WHITE)
        search!!.setHintTextColor(Color.GRAY)
        search!!.setInputType(InputType.TYPE_CLASS_TEXT)
        // 点歌台搜索同样改用系统软键盘（打字栏），不再弹自定义拼音键盘
        search!!.setTag(SYSTEM_KEYBOARD_TAG)
        search!!.isFocusable = true
        search!!.isFocusableInTouchMode = true
        search!!.setOnEditorActionListener(OnEditorActionListener { v: TextView?, actionId: Int, event: KeyEvent? ->
            filterSongs(category, search!!.getText().toString())
            true
        })
        search!!.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) showSystemKeyboard(search)
        }
        searchRow.addView(search, LinearLayout.LayoutParams(0, dp(54), 1f))
        searchRow.addView(
            button("键盘", View.OnClickListener { v: View? -> showSystemKeyboard(search) }),
            LinearLayout.LayoutParams(dp(110), dp(54))
        )
        searchRow.addView(
            button(
                "搜索",
                View.OnClickListener { v: View? ->
                    filterSongs(
                        category,
                        search!!.getText().toString()
                    )
                }), LinearLayout.LayoutParams(dp(110), dp(54))
        )
        center.addView(searchRow)
        // 分页控制栏
        val pageBar = LinearLayout(this)
        pageBar.setGravity(Gravity.CENTER_VERTICAL)
        pageBar.addView(
            button("上一页", View.OnClickListener { v: View? -> prevPage() }),
            LinearLayout.LayoutParams(dp(110), dp(44))
        )
        pageInfo = label("", 14, GOLD)
        pageInfo!!.setGravity(Gravity.CENTER)
        pageBar.addView(pageInfo, LinearLayout.LayoutParams(0, dp(44), 1f))
        pageBar.addView(
            button("下一页", View.OnClickListener { v: View? -> nextPage() }),
            LinearLayout.LayoutParams(dp(110), dp(44))
        )
        center.addView(pageBar)
        songList = listView()
        songAdapter = tvAdapter()
        songList!!.setAdapter(songAdapter)
        songList!!.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            handleSong(
                visibleSongs.get(pos)
            )
        })
        songList!!.setOnItemLongClickListener(OnItemLongClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            showSongActions(visibleSongs.get(pos))
            true
        })
        center.addView(songList, LinearLayout.LayoutParams(-1, 0, 1f))
        // 默认加载热门歌曲第一页
        if (library.muse.isAvailable()) {
            browseMode = "hot"
            browseParam = ""
            browsePage = 0
            loadBrowsePage()
        } else {
            filterSongs(category, "")
        }
    }

    private fun showSingers() {
        setPage("歌星")
        content!!.removeAllViews()
        val groupPanel = panel()
        content!!.addView(groupPanel, LinearLayout.LayoutParams(dp(190), -1))
        groupPanel.addView(sectionTitle("歌手分类"))

        val singerPanel = panel()
        content!!.addView(singerPanel, LinearLayout.LayoutParams(dp(340), -1))
        singerPanel.addView(sectionTitle("歌星列表"))
        val singers = listView()
        val adapter = tvAdapter()
        singers.setAdapter(adapter)
        singerPanel.addView(singers, LinearLayout.LayoutParams(-1, 0, 1f))

        val songs = panel()
        content!!.addView(songs, LinearLayout.LayoutParams(0, -1, 1f))
        songs.addView(sectionTitle("歌星歌曲"))
        // 分页控制栏
        val pageBar = LinearLayout(this)
        pageBar.setGravity(Gravity.CENTER_VERTICAL)
        pageBar.addView(
            button("上一页", View.OnClickListener { v: View? -> prevPage() }),
            LinearLayout.LayoutParams(dp(100), dp(44))
        )
        pageInfo = label("", 13, GOLD)
        pageInfo!!.setGravity(Gravity.CENTER)
        pageBar.addView(pageInfo, LinearLayout.LayoutParams(0, dp(44), 1f))
        pageBar.addView(
            button("下一页", View.OnClickListener { v: View? -> nextPage() }),
            LinearLayout.LayoutParams(dp(100), dp(44))
        )
        songs.addView(pageBar)
        songList = listView()
        songAdapter = tvAdapter()
        songList!!.setAdapter(songAdapter)
        songList!!.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            handleSong(
                visibleSongs.get(pos)
            )
        })
        songList!!.setOnItemLongClickListener(OnItemLongClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            showSongActions(visibleSongs.get(pos))
            true
        })
        songs.addView(songList, LinearLayout.LayoutParams(-1, 0, 1f))
        singers.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            val singer = adapter.getItem(pos)
            if (library.muse.isAvailable()) {
                browseMode = "singer"
                browseParam = singer
                browsePage = 0
                loadBrowsePage()
            } else {
                visibleSongs.clear()
                for (song in library.allSongs()) {
                    if (isVisibleSong(song) && singerName(song) == singer) visibleSongs.add(song)
                }
                renderSongs()
            }
        })
        // 歌手分类:优先使用数据库区域
        if (library.muse.isAvailable()) {
            val groups =
                arrayOf<String?>("全部歌手", "大陆", "港台", "外国", "中国", "男", "女", "组合")
            for (group in groups) {
                groupPanel.addView(
                    button(
                        group,
                        View.OnClickListener { v: View? ->
                            populateSingerListFromDb(
                                adapter,
                                (v as TextView).getText().toString()
                            )
                        })
                )
            }
            populateSingerListFromDb(adapter, "全部歌手")
        } else {
            val groups = arrayOf<String?>(
                "全部歌手",
                "热门歌手",
                "大陆/内地",
                "港台",
                "日韩",
                "欧美",
                "组合/乐队",
                "其他"
            )
            for (group in groups) {
                groupPanel.addView(
                    button(
                        group,
                        View.OnClickListener { v: View? ->
                            populateSingerList(
                                adapter,
                                (v as TextView).getText().toString()
                            )
                        })
                )
            }
            populateSingerList(adapter, "全部歌手")
        }
    }

    /**
     * 从数据库分页加载歌手列表。
     * 
     * @param adapter 歌手列表适配器
     * @param group   分类组名(全部歌手/大陆/港台/外国/中国/男/女/组合)
     */
    private fun populateSingerListFromDb(adapter: ArrayAdapter<String?>, group: String?) {
        if (!library.muse.isAvailable()) return
        adapter.clear()
        busy(true, "正在加载歌手...")
        io.execute(Runnable {
            var area: String? = null
            var type: String? = null
            if ("大陆" == group) area = "大陆"
            else if ("港台" == group) area = "港台"
            else if ("外国" == group) area = "外国"
            else if ("中国" == group) area = "中国"
            else if ("男" == group) type = "男"
            else if ("女" == group) type = "女"
            else if ("组合" == group) type = "组合"
            val result: MutableList<Array<String?>> = library.muse.singers(area, type, 0, 200)
            main.post(Runnable {
                for (s in result) adapter.add(s[1]) // s[1] 是歌手名
                busy(false, "加载了 " + result.size + " 位歌手")
            })
        })
    }

    private fun showLanguages() {
        setPage("语种")
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(dp(230), -1))
        left.addView(sectionTitle("语种"))
        val langs: MutableList<String?> = ArrayList<String?>()
        if (library.muse.isAvailable()) {
            langs.add("全部")
            langs.addAll(library.muse.languages())
        } else {
            langs.addAll(library.languages())
        }
        for (language in langs) {
            left.addView(button(language, View.OnClickListener { v: View? ->
                val lang = (v as TextView).getText().toString()
                if (library.muse.isAvailable()) {
                    browseMode = "language"
                    browseParam = lang
                    browsePage = 0
                    loadBrowsePage()
                } else {
                    filterLanguage(lang)
                }
            }))
        }

        val right = panel()
        content!!.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        right.addView(sectionTitle("语种点歌"))
        // 分页控制栏
        val pageBar = LinearLayout(this)
        pageBar.setGravity(Gravity.CENTER_VERTICAL)
        pageBar.addView(
            button("上一页", View.OnClickListener { v: View? -> prevPage() }),
            LinearLayout.LayoutParams(dp(110), dp(44))
        )
        pageInfo = label("", 14, GOLD)
        pageInfo!!.setGravity(Gravity.CENTER)
        pageBar.addView(pageInfo, LinearLayout.LayoutParams(0, dp(44), 1f))
        pageBar.addView(
            button("下一页", View.OnClickListener { v: View? -> nextPage() }),
            LinearLayout.LayoutParams(dp(110), dp(44))
        )
        right.addView(pageBar)
        songList = listView()
        songAdapter = tvAdapter()
        songList!!.setAdapter(songAdapter)
        songList!!.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            handleSong(
                visibleSongs.get(pos)
            )
        })
        songList!!.setOnItemLongClickListener(OnItemLongClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            showSongActions(visibleSongs.get(pos))
            true
        })
        right.addView(songList, LinearLayout.LayoutParams(-1, 0, 1f))
        if (library.muse.isAvailable()) {
            browseMode = "language"
            browseParam = "全部"
            browsePage = 0
            loadBrowsePage()
        } else {
            filterLanguage("全部")
        }
    }

    private fun showWordCounts() {
        setPage("字数")
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(dp(230), -1))
        left.addView(sectionTitle("歌名字数"))
        val groups = arrayOf<String?>("全部", "一字", "二字", "三字", "四字", "五字", "六字以上")
        for (group in groups) {
            left.addView(button(group, View.OnClickListener { v: View? ->
                val g = (v as TextView).getText().toString()
                if (library.muse.isAvailable()) {
                    val wc = if ("一字" == g) 1 else if ("二字" == g) 2 else if ("三字" == g)
                        3
                    else
                        if ("四字" == g) 4 else if ("五字" == g) 5 else if ("六字以上" == g) 6 else 0
                    browseMode = "wordcount"
                    browseParam = wc.toString()
                    browsePage = 0
                    loadBrowsePage()
                } else {
                    filterWordCount(g)
                }
            }))
        }

        val right = panel()
        content!!.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        right.addView(sectionTitle("字数点歌"))
        // 分页控制栏
        val pageBar = LinearLayout(this)
        pageBar.setGravity(Gravity.CENTER_VERTICAL)
        pageBar.addView(
            button("上一页", View.OnClickListener { v: View? -> prevPage() }),
            LinearLayout.LayoutParams(dp(110), dp(44))
        )
        pageInfo = label("", 14, GOLD)
        pageInfo!!.setGravity(Gravity.CENTER)
        pageBar.addView(pageInfo, LinearLayout.LayoutParams(0, dp(44), 1f))
        pageBar.addView(
            button("下一页", View.OnClickListener { v: View? -> nextPage() }),
            LinearLayout.LayoutParams(dp(110), dp(44))
        )
        right.addView(pageBar)
        songList = listView()
        songAdapter = tvAdapter()
        songList!!.setAdapter(songAdapter)
        songList!!.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            handleSong(
                visibleSongs.get(pos)
            )
        })
        songList!!.setOnItemLongClickListener(OnItemLongClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            showSongActions(visibleSongs.get(pos))
            true
        })
        right.addView(songList, LinearLayout.LayoutParams(-1, 0, 1f))
        if (library.muse.isAvailable()) {
            browseMode = "wordcount"
            browseParam = "0"
            browsePage = 0
            loadBrowsePage()
        } else {
            filterWordCount("全部")
        }
    }

    private fun showRank() {
        setPage("排行")
        content!!.removeAllViews()
        val panel = panel()
        content!!.addView(panel, LinearLayout.LayoutParams(-1, -1))
        panel.addView(sectionTitle("热门排行"))
        // 分页控制栏
        val pageBar = LinearLayout(this)
        pageBar.setGravity(Gravity.CENTER_VERTICAL)
        pageBar.addView(
            button("上一页", View.OnClickListener { v: View? -> prevPage() }),
            LinearLayout.LayoutParams(dp(110), dp(44))
        )
        pageInfo = label("", 14, GOLD)
        pageInfo!!.setGravity(Gravity.CENTER)
        pageBar.addView(pageInfo, LinearLayout.LayoutParams(0, dp(44), 1f))
        pageBar.addView(
            button("下一页", View.OnClickListener { v: View? -> nextPage() }),
            LinearLayout.LayoutParams(dp(110), dp(44))
        )
        panel.addView(pageBar)
        songList = listView()
        songAdapter = tvAdapter()
        songList!!.setAdapter(songAdapter)
        songList!!.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            handleSong(
                visibleSongs.get(pos)
            )
        })
        songList!!.setOnItemLongClickListener(OnItemLongClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            showSongActions(visibleSongs.get(pos))
            true
        })
        panel.addView(songList, LinearLayout.LayoutParams(-1, 0, 1f))
        if (library.muse.isAvailable()) {
            browseMode = "hot"
            browseParam = ""
            browsePage = 0
            loadBrowsePage()
        } else {
            visibleSongs.clear()
            for (song in library.allSongs()) {
                if (isVisibleSong(song)) visibleSongs.add(song)
            }
            Collections.sort<Song?>(
                visibleSongs,
                Comparator { a: Song?, b: Song? -> Integer.compare(b!!.playCount, a!!.playCount) })
            renderSongs()
        }
    }

    private fun showQueue() {
        setPage("已点")
        content!!.removeAllViews()
        val panel = panel()
        content!!.addView(panel, LinearLayout.LayoutParams(-1, -1))
        val actions = LinearLayout(this)
        actions.addView(
            button(
                "播放第一首",
                View.OnClickListener { v: View? ->
                    if (!orderQueue!!.isEmpty()) play(
                        orderQueue.get(
                            0
                        )
                    )
                }), LinearLayout.LayoutParams(dp(150), dp(54))
        )
        actions.addView(
            button("切歌", View.OnClickListener { v: View? -> playNext() }),
            LinearLayout.LayoutParams(dp(110), dp(54))
        )
        actions.addView(
            button(
                "置顶",
                View.OnClickListener { v: View? -> moveSelectedQueueToTop() }),
            LinearLayout.LayoutParams(dp(110), dp(54))
        )
        actions.addView(
            button("删除", View.OnClickListener { v: View? -> removeSelectedQueue() }),
            LinearLayout.LayoutParams(dp(110), dp(54))
        )
        actions.addView(button("清空", View.OnClickListener { v: View? ->
            orderQueue!!.clear()
            renderQueue()
            saveState()
        }), LinearLayout.LayoutParams(dp(110), dp(54)))
        panel.addView(actions)
        queueList = listView()
        queueAdapter = tvAdapter()
        queueList!!.setAdapter(queueAdapter)
        queueList!!.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            play(
                orderQueue!!.get(pos)
            )
        })
        panel.addView(queueList, LinearLayout.LayoutParams(-1, 0, 1f))
        renderQueue()
    }

    private fun showSang() {
        setPage("已唱")
        content!!.removeAllViews()
        val panel = panel()
        content!!.addView(panel, LinearLayout.LayoutParams(-1, -1))
        panel.addView(sectionTitle("已唱记录"))
        val list = listView()
        val adapter = tvAdapter()
        for (song in sangHistory) adapter.add(song.displayTitle())
        list.setAdapter(adapter)
        panel.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun showDownloads() {
        setPage("下载")
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(dp(280), -1))
        left.addView(sectionTitle("下载操作"))
        left.addView(button("同步网络曲库", View.OnClickListener { v: View? -> fetchCatalog() }))
        left.addView(button("设置网络源", View.OnClickListener { v: View? -> showCatalogDialog() }))
        left.addView(
            button(
                "重扫本地歌曲",
                View.OnClickListener { v: View? -> refreshLibrary(null) })
        )
        left.addView(
            button(
                "取消全部下载",
                View.OnClickListener { v: View? -> cancelAllDownloads() })
        )
        left.addView(button("清理已完成", View.OnClickListener { v: View? ->
            for (i in downloads.indices.reversed()) {
                if ("完成" == downloads.get(i).state) downloads.removeAt(i)
            }
            showDownloads()
        }))
        left.addView(button("清理失败任务", View.OnClickListener { v: View? ->
            for (i in downloads.indices.reversed()) {
                if (downloads.get(i).state.startsWith("失败") || "已取消" == downloads.get(i).state) downloads.removeAt(
                    i
                )
            }
            showDownloads()
        }))

        val right = panel()
        content!!.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        right.addView(sectionTitle("下载队列"))
        val list = listView()
        val adapter = tvAdapter()
        for (task in downloads) adapter.add(task.display())
        list.setAdapter(adapter)
        list.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            if (pos >= 0 && pos < downloads.size) {
                downloads.get(pos).cancelled = true
                downloads.get(pos).state = "正在取消"
                showDownloads()
            }
        })
        right.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun showUdisk() {
        setPage("U盘")
        content!!.removeAllViews()
        val panel = panel()
        content!!.addView(panel, LinearLayout.LayoutParams(-1, -1))
        panel.addView(sectionTitle("本地导入"))
        panel.addView(
            label(
                "请把歌曲放入 /sdcard/MDKTV_Native/import、/sdcard/MDKTV_Native/songs、U盘/KTV 或 U盘/songs，支持 mp4/mkv/avi/mp3/flac/wav。",
                20,
                Color.LTGRAY
            )
        )
        panel.addView(button("扫描导入目录", View.OnClickListener { v: View? ->
            refreshLibrary(
                Runnable { showSongs("本地") })
        }), LinearLayout.LayoutParams(dp(180), dp(58)))
        songList = listView()
        songAdapter = tvAdapter()
        songList!!.setAdapter(songAdapter)
        songList!!.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            handleSong(
                visibleSongs.get(pos)
            )
        })
        panel.addView(songList, LinearLayout.LayoutParams(-1, 0, 1f))
        filterSongs("本地", "")
    }

    fun showSettings() {
        currentTabIndex = 8
        setupTabs()
        showSettingsPage()
    }

    /**
     * 退出确认弹窗(对齐原app风格)
     */
    private fun AlertDialog.showForTv(
        preferredButton: Int = DialogInterface.BUTTON_NEGATIVE,
        afterShow: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null,
    ): AlertDialog {
        val sourcePage = currentPage
        val sourceView = this@MainActivity.window.decorView.findFocus()
        val sourceFocus = captureFocusBookmark(sourceView)
        setOnShowListener {
            listView?.apply {
                setSelector(R.drawable.bg_tv_dialog_choice_focus)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    defaultFocusHighlightEnabled = false
                }
            }
            window?.decorView?.let(TvFocusStyler::installTree)
            listOf(
                DialogInterface.BUTTON_NEGATIVE,
                DialogInterface.BUTTON_POSITIVE,
                DialogInterface.BUTTON_NEUTRAL,
            ).mapNotNull(::getButton).forEach(TvFocusStyler::installAction)
            val preferred = getButton(preferredButton)
                ?: getButton(DialogInterface.BUTTON_NEGATIVE)
                ?: getButton(DialogInterface.BUTTON_POSITIVE)
            preferred?.let {
                if (it.requestFocus() || it.requestFocusFromTouch()) {
                    it.refreshDrawableState()
                    it.jumpDrawablesToCurrentState()
                    it.invalidate()
                }
            }
            afterShow?.invoke()
        }
        setOnDismissListener {
            if (currentPage == sourcePage) restoreFocusBookmark(sourceFocus, sourceView)
            // 挂在同一个 listener 里追加，避免外部再 setOnDismissListener 把焦点恢复覆盖掉
            onDismiss?.invoke()
        }
        show()
        return this
    }

    private fun AlertDialog.Builder.showForTv(
        preferredButton: Int = DialogInterface.BUTTON_NEGATIVE,
        afterShow: (() -> Unit)? = null,
    ): AlertDialog = create().showForTv(preferredButton, afterShow)

    /**
     * 扫码类弹窗：显示后 [autoCloseMs] 毫秒自动收起。
     *
     * 复用 [showForTv] 的 TV 焦点逻辑（遥控/触屏高亮一致），额外挂一个自动关闭计时。
     * 计时用独立消息 token，弹窗关闭时精确撤掉自己那一条；撤回逻辑通过
     * showForTv 的 onDismiss 参数挂进同一个 listener，不会覆盖焦点恢复。
     */
    private fun AlertDialog.showAutoDismissForTv(
        preferredButton: Int = DialogInterface.BUTTON_NEGATIVE,
        autoCloseMs: Long = QR_AUTO_CLOSE_MS,
    ): AlertDialog {
        val token = Any()
        val task = Runnable { if (!isFinishing && isShowing) dismiss() }
        showForTv(
            preferredButton,
            onDismiss = { main.removeCallbacksAndMessages(token) }
        )
        main.postAtTime(task, token, android.os.SystemClock.uptimeMillis() + autoCloseMs)
        return this
    }

    private fun AlertDialog.restoreSourceFocusOnDismiss(): AlertDialog {
        val sourcePage = currentPage
        val sourceView = this@MainActivity.window.decorView.findFocus()
        val sourceFocus = captureFocusBookmark(sourceView)
        setOnDismissListener {
            if (currentPage == sourcePage) restoreFocusBookmark(sourceFocus, sourceView)
        }
        return this
    }

    private fun showExitDialog() {
        AlertDialog.Builder(this)
            .setTitle("退出确认")
            .setMessage("确定要退出风火轮吗?")
            .setPositiveButton(
                "确定退出",
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int -> finish() })
            .setNegativeButton("取消", null)
            .create()
            .showForTv(DialogInterface.BUTTON_POSITIVE)
    }

    /** 顶栏/键盘相关 占位字段  */
    private val key_abc: TextView? = null
    private val key_123: TextView? = null
    private val key_hand: TextView? = null

    /** 设置弹窗(对齐原app: 左侧菜单 + 右侧内容)  */
    private fun showSettingsDialog() {
        val root = getLayoutInflater().inflate(R.layout.dialog_settings, null)
        val btnClose = root.findViewById<TextView>(R.id.btn_settings_close)
        val menu = root.findViewById<ListView>(R.id.settings_menu)
        val content = root.findViewById<LinearLayout>(R.id.settings_content)

        val menuItems = arrayOf<String?>(
            "🎤  演唱模式", "🔊  音频控制", "🌐  声道设置", "🎚  音调控制",
            "📺  画面设置", "📃  跑马灯", "📶  网络设置", "ℹ  关于"
        )
        val adapter: ArrayAdapter<String?> =
            object : ArrayAdapter<String?>(this, R.layout.settings_menu_item, menuItems) {
                override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                    val v = super.getView(pos, cv, parent) as TextView
                    v.setTextSize(15f)
                    v.setMinHeight(dp(44))
                    v.setGravity(Gravity.CENTER_VERTICAL)
                    v.setPadding(dp(16), dp(10), dp(16), dp(10))
                    v.setTextColor(Color.rgb(225, 225, 245))
                    return v
                }
            }
        menu.setAdapter(adapter)
        // 点击左侧菜单渲染右侧内容
        menu.setOnItemClickListener(OnItemClickListener { parent: AdapterView<*>?, view: View?, position: Int, id: Long ->
            renderSettingsContent(content, position)
        })
        // 默认显示第一个
        renderSettingsContent(content, 0)

        val dialog = AlertDialog.Builder(this)
            .setView(root)
            .create()
        if (dialog.getWindow() != null) {
            dialog.getWindow()!!.setBackgroundDrawableResource(android.R.color.transparent)
        }
        btnClose.setOnClickListener(View.OnClickListener { v: View? -> dialog.dismiss() })
        dialog.restoreSourceFocusOnDismiss()
        dialog.setOnShowListener {
            menu.post {
                if (menu.childCount > 0) menu.getChildAt(0)?.requestFocus()
            }
        }
        dialog.show()
    }

    /** 渲染设置弹窗右侧内容  */
    private fun renderSettingsContent(content: LinearLayout, index: Int) {
        content.removeAllViews()
        when (index) {
            0 -> {
                // 演唱模式
                addSettingsTitle(content, "演唱模式")
                addSettingsOptions(
                    content,
                    "演唱模式",
                    arrayOf<String>(
                        "普通演唱", "练唱模式", "评分演唱", "静音练唱"
                    ),
                    arrayOf<String?>(null, null, "评分演唱", "静音练唱"),
                    singMode,
                    OnValueClickListener { v: View?, `val`: Any? ->
                        setSingMode((`val` as kotlin.String?)!!)
                    })
            }

            1 -> {
                // 音频控制(链接到调音弹窗)
                addSettingsTitle(content, "音频控制")
                addSettingsItem(
                    content,
                    "麦克风音量",
                    micVolume.toString() + "%",
                    View.OnClickListener { v: View? -> showAudioControlDialog() })
                addSettingsItem(
                    content,
                    "音乐音量",
                    musicVolume.toString() + "%",
                    View.OnClickListener { v: View? -> showAudioControlDialog() })
                addSettingsItem(
                    content,
                    "音调",
                    (if (tone > 0) "+" else "") + tone + " 调",
                    View.OnClickListener { v: View? -> showAudioControlDialog() })
                addSettingsItem(
                    content,
                    "原唱模式",
                    if (originalVocal) "开" else "关",
                    View.OnClickListener { v: View? -> toggleOriginalVocal() })
            }

            2 -> {
                // 声道设置
                addSettingsTitle(content, "声道模式")
                addSettingsOptions(
                    content,
                    "声道",
                    arrayOf<String>(
                        "自动", "左伴右原", "右伴左原", "全声道"
                    ),
                    arrayOf<String>("自动", "左伴右原", "右伴左原", "全声道"),
                    vocalChannelMode,
                    OnValueClickListener { v: View?, `val`: Any? ->
                        setVocalChannelMode((`val` as kotlin.String?)!!)
                    })
            }

            3 -> {
                // 音调控制
                addSettingsTitle(content, "音调控制")
                addSettingsItem(content, "音调 -", "降调", View.OnClickListener { v: View? ->
                    tone = clamp(tone - 1, -5, 5)
                    player?.setTone(tone)
                    saveState()
                })
                addSettingsItem(content, "音调 +", "升调", View.OnClickListener { v: View? ->
                    tone = clamp(tone + 1, -5, 5)
                    player?.setTone(tone)
                    saveState()
                })
            }

            4 -> {
                // 画面设置
                addSettingsTitle(content, "画面设置")
                addSettingsItem(content, "画面比例", "16:9", null)
                addSettingsItem(content, "画面质量", "高", null)
                addSettingsItem(content, "全屏模式", "是", null)
            }

            5 -> {
                // 跑马灯
                addSettingsTitle(content, "跑马灯")
                addSettingsItem(content, "跑马灯状态", "关", null)
                addSettingsItem(content, "跑马灯内容", "风火轮 欢迎光临", null)
                addSettingsItem(content, "滚动速度", "正常", null)
            }

            6 -> {
                // 网络设置
                addSettingsTitle(content, "网络设置")
                addSettingsItem(content, "当前网络", "WIFI 已连接", null)
                addSettingsItem(content, "CDN 主地址", "pub.cdn.cherryonline.cn", null)
                addSettingsItem(content, "CDN 备地址", "pub.mcdn.cherryonline.cn", null)
            }

            7 -> {
                // 关于
                addSettingsTitle(content, "关于")
                addSettingsItem(content, "应用名称", "风火轮 K歌版", null)
                addSettingsItem(content, "版本号", "1.0.0", null)
                addSettingsItem(
                    content,
                    "曲库",
                    if (library.muse.isAvailable()) "已加载 (" + library.muse.songCount() + " 首)" else "未加载",
                    null
                )
            }
        }
    }

    /** 设置项标题  */
    private fun addSettingsTitle(parent: LinearLayout, title: String?) {
        val tv = TextView(this)
        tv.setText(title)
        tv.setTextSize(17f)
        tv.setTextColor(Color.rgb(0x27, 0xCC, 0xA4))
        tv.setTextSize(17f)
        tv.setPadding(dp(8), dp(8), dp(8), dp(12))
        tv.setTypeface(null, Typeface.BOLD)
        parent.addView(
            tv,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    /** 设置项 - 单行(label + value, 点击有 action)  */
    private fun addSettingsItem(
        parent: LinearLayout,
        label: String?,
        value: String?,
        onClick: View.OnClickListener?
    ) {
        val row = LinearLayout(this)
        row.setOrientation(LinearLayout.HORIZONTAL)
        row.setGravity(Gravity.CENTER_VERTICAL)
        row.setBackgroundResource(R.drawable.bg_settings_item)
        row.setPadding(dp(14), dp(12), dp(14), dp(12))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(8)
        parent.addView(row, lp)
        val l = TextView(this)
        l.setText(label)
        l.setTextSize(15f)
        l.setTextColor(Color.rgb(225, 225, 245))
        row.addView(l, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val r = TextView(this)
        r.setText(value)
        r.setTextSize(15f)
        r.setTextColor(Color.rgb(0xFF, 0xD5, 0x6B))
        row.addView(
            r,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        if (onClick != null) {
            row.setClickable(true)
            row.setOnClickListener(onClick)
        }
    }

    /** 选项值点击回调 (传 value)  */
    private fun interface OnValueClickListener {
        fun onClick(v: View?, value: Any?)
    }

    /** 设置项 - 选项组(单选)  */
    private fun addSettingsOptions(
        parent: LinearLayout,
        title: String?,
        options: Array<out String?>,
        values: Array<out Any?>,
        current: Any?,
        onClick: OnValueClickListener
    ) {
        val row = LinearLayout(this)
        row.setOrientation(LinearLayout.VERTICAL)
        row.setBackgroundResource(R.drawable.bg_settings_item)
        row.setPadding(dp(14), dp(10), dp(14), dp(10))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(8)
        parent.addView(row, lp)
        val l = TextView(this)
        l.setText(title)
        l.setTextSize(15f)
        l.setTextColor(Color.rgb(225, 225, 245))
        row.addView(
            l,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        // 选项按钮横向布局
        val btnRow = LinearLayout(this)
        btnRow.setOrientation(LinearLayout.HORIZONTAL)
        btnRow.setPadding(0, dp(8), 0, 0)
        row.addView(
            btnRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        for (i in options.indices) {
            val opt = options[i]
            val `val` = values[i]
            val btn = TextView(this)
            btn.setText(opt)
            val isOn = `val` != null && `val` == current
            btn.setBackgroundResource(if (isOn) R.drawable.bg_btn_toggle_on else R.drawable.bg_btn_toggle_off)
            btn.setTextColor(if (isOn) Color.WHITE else Color.rgb(204, 204, 204))
            btn.setTextSize(13f)
            btn.setGravity(Gravity.CENTER)
            btn.setPadding(dp(10), dp(8), dp(10), dp(8))
            val bLp = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            bLp.leftMargin = if (i == 0) 0 else dp(4)
            bLp.rightMargin = if (i == options.size - 1) 0 else dp(4)
            btnRow.addView(btn, bLp)
            val fv = `val`
            val fi = i
            btn.setOnClickListener(View.OnClickListener { v: View? ->
                if (fv != null) onClick.onClick(v, fv)
                // 更新按钮状态
                for (j in 0 until btnRow.getChildCount()) {
                    val sib = btnRow.getChildAt(j) as TextView
                    val isOn2 = j == fi
                    sib.setBackgroundResource(if (isOn2) R.drawable.bg_btn_toggle_on else R.drawable.bg_btn_toggle_off)
                    sib.setTextColor(if (isOn2) Color.WHITE else Color.rgb(204, 204, 204))
                }
            })
        }
    }

    private fun toggleOriginalVocal() {
        setOriginalVocalMode(!originalVocal)
    }

    /** 按按钮表达的目标模式切换，提示框和实际声道使用同一个目标值。 */
    private fun setOriginalVocalMode(targetOriginal: Boolean) {
        val shouldPlay = playWhenPrepared
        originalVocal = targetOriginal
        applyPlaybackMode()
        saveState()
        showPlaybackModeNotice(if (targetOriginal) "原唱" else "伴唱")
        // 同步状态到控制层
        if (playerController != null) {
            playerController!!.setVocalMode(originalVocal)
        }
        // 更新 TV 布局底部控制栏
        if (textVocalMode != null) {
            textVocalMode.setText(vocalActionText())
        }
        updateBottomBar(currentSong, shouldPlay)
    }

    /** 控制按钮显示下一次动作；播放框提示显示本次切换后实际生效的模式。 */
    private fun vocalActionText(): String = if (originalVocal) "伴唱" else "原唱"

    private fun vocalActionIcon(): Int = if (originalVocal) {
        R.drawable.ott_ic_ctrl_music_accomp
    } else {
        R.drawable.ott_ic_ctrl_music_origin
    }

    /**
     * 初始化播放器控制层的回调。
     * 
     * 
     * 将 [PlayerControllerView] 的按钮事件路由到 MainActivity 的现有播放逻辑:
     * 
     *  * `onPlayPause` → [.togglePlay]
     *  * `onPrev` / `onNext` → [.playPrevSong] / [.playNext]
     *  * `onVocalSwitch` → 使用 [VocalSwitchHelper] 切换原唱/伴唱声道
     *  * `onVolume` → [.showAudioControlDialog]
     *  * `onFullScreen` → [.showFullScreenPlayer]
     * 
     */
    private fun initPlayerControllerCallbacks() {
        if (playerController == null) return
        playerController!!.setOnControlListener(object : OnControlListener {
            override fun onPlayPause() {
                togglePlay()
            }

            override fun onPrev() {
                playPrevSong()
            }

            override fun onNext() {
                playNext()
            }

            override fun onVocalSwitch(original: Boolean) {
                setOriginalVocalMode(original)
            }

            override fun onVolume() {
                showAudioControlDialog()
            }

            override fun onFullScreen() {
                showFullScreenPlayer()
            }
        })
    }

    /**
     * 进入全屏播放界面。
     * 
     * 
     * 加载 [FullScreenPlayerFragment] 到 `R.id.container` 容器,
     * 替换当前 MainFragment,并加入回退栈以便返回。
     */
    fun showFullScreenPlayer() {
        if (player == null) player = videoBackground
        if (isFullScreen || player == null || fullScreenContainer == null) return
        main.removeCallbacks(autoFullscreenRunnable)
        playerReturnSurface = player
        playerReturnHost = activePlayerHost
        playerTouchOverlay?.visibility = View.GONE

        fullScreenContainer!!.removeAllViews()
        fullScreenContainer!!.setBackgroundColor(Color.TRANSPARENT)
        fullScreenContainer!!.setOnClickListener {
            // 点视频区 = 暂停/继续（成熟车载KTV标配交互，雷石/Stingray 都是这样）。
            togglePlay()
            // 暂停时把控制条显示出来，方便直接点"继续"；继续播放时沿用原有的自动隐藏。
            if (!playWhenPrepared) setFullScreenChromeVisible(true)
        }

        val header = LinearLayout(this)
        header.setGravity(Gravity.CENTER_VERTICAL)
        header.setPadding(dp(12), dp(4), dp(12), dp(4))
        header.setBackgroundColor(Color.TRANSPARENT)
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.ktv_logo)
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER)
        header.addView(logo, LinearLayout.LayoutParams(dp(42), dp(42)))
        val song = label(currentAndNextText(), 18, Color.WHITE)
        fullScreenSongInfo = song
        header.addView(song, LinearLayout.LayoutParams(0, dp(48), 1f))
        val back = label("返回", 15, Color.WHITE)
        back.setGravity(Gravity.CENTER)
        back.setBackgroundResource(R.drawable.bg_btn_glass)
        back.setOnClickListener(View.OnClickListener { v: View? -> exitFullScreenPlayer() })
        back.visibility = View.GONE
        header.addView(back, LinearLayout.LayoutParams(dp(76), dp(36)))
        val headerParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52), Gravity.TOP
        )
        fullScreenContainer!!.addView(header, headerParams)

        val controls = LinearLayout(this)
        controls.orientation = LinearLayout.HORIZONTAL
        controls.gravity = Gravity.CENTER
        controls.clipChildren = false
        controls.clipToPadding = false
        controls.setPadding(dp(8), dp(4), dp(8), dp(4))
        controls.setBackgroundColor(Color.TRANSPARENT)
        val controlItems = ArrayList<View>(7)

        fun addControl(iconRes: Int, text: String, action: () -> Unit): Pair<TextView, ImageView> {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isFocusable = true
                isFocusableInTouchMode = true
                setBackgroundColor(Color.TRANSPARENT)
                id = View.generateViewId()
                clipChildren = false
                clipToPadding = false
            }
            val icon = ImageView(this).apply {
                setImageResource(iconRes)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                alpha = 0.76f
            }
            val caption = label(text, 12, Color.rgb(184, 184, 184)).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                includeFontPadding = false
                setPadding(0, 0, 0, 0)
            }
            item.addView(icon, LinearLayout.LayoutParams(dp(28), dp(30)))
            item.addView(caption, LinearLayout.LayoutParams(-1, dp(24)))
            item.setOnClickListener {
                action()
                if (isFullScreen) setFullScreenChromeVisible(true)
            }
            installPressFeedback(item)
            controlItems.add(item)
            val params = LinearLayout.LayoutParams(dp(78), dp(68))
            params.setMargins(dp(2), 0, dp(2), 0)
            controls.addView(item, params)
            return caption to icon
        }

        addControl(R.drawable.ic_return_order, "返回点歌") { exitFullScreenPlayer() }
        addControl(R.drawable.ott_ic_ctrl_orderlist, "已点") {
            exitFullScreenPlayer()
            showOrderListPage()
        }
        addControl(
            vocalActionIcon(),
            vocalActionText()
        ) {
            toggleOriginalVocal()
        }.also { (text, icon) -> fullScreenVocalText = text; fullScreenVocalIcon = icon }
        addControl(R.drawable.ott_ic_ctrl_switch_song, "切歌") { playNext() }
        addControl(
            if (playWhenPrepared) R.drawable.ott_ic_ctrl_pause else R.drawable.ott_ic_ctrl_play,
            if (playWhenPrepared) "暂停" else "播放"
        ) { togglePlay() }.also { (text, icon) -> fullScreenPauseText = text; fullScreenPauseIcon = icon }
        addControl(R.drawable.ott_ic_ctrl_replay, "重唱") { replay() }
        addControl(R.drawable.ott_ic_ctrl_volume, "调音") { showFullScreenVolumeDialog() }
        fullScreenToneControl = controlItems.lastOrNull()

        controlItems.forEachIndexed { index, item ->
            item.nextFocusLeftId = controlItems.getOrNull(index - 1)?.id ?: item.id
            item.nextFocusRightId = controlItems.getOrNull(index + 1)?.id ?: item.id
            item.nextFocusUpId = item.id
            item.nextFocusDownId = item.id
        }

        val controlsParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(74), Gravity.TOP or Gravity.CENTER_HORIZONTAL
        )
        controlsParams.topMargin = dp(48)
        fullScreenContainer!!.addView(controls, controlsParams)
        fullScreenControls = controls

        val progressRow = LinearLayout(this)
        progressRow.orientation = LinearLayout.HORIZONTAL
        progressRow.gravity = Gravity.CENTER_VERTICAL
        progressRow.setPadding(dp(18), dp(4), dp(18), dp(4))
        progressRow.setBackgroundColor(Color.argb(155, 0, 0, 0))

        fullScreenSeekBar = SeekBar(this).apply {
            max = 1
            isFocusable = true
            setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        fullScreenTime?.text = formatPlaybackTime(progress) + " / " +
                            formatPlaybackTime(seekBar?.max ?: 0)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    fullScreenSeeking = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val position = seekBar?.progress ?: 0
                    runCatching {
                        // 车机实测：只 seek 不重新定位播放，声音还停在原进度。
                        // 关键：IJK seekTo 后若处于暂停态，必须显式 start()/pause() 让音频跟随新位置，
                        // 且要用 playWhenPrepared 保持拖动前的播放/暂停状态，不能一律 start（那是行为倒退）。
                        player?.seekTo(position)
                        if (playWhenPrepared) player?.start() else player?.pause()
                        prepareExternalVocal(currentSong, position, forceSeek = true)
                        // 原唱/伴唱副引擎同步跳转（forceSeek 绕过 300ms 漂移门槛），再按当前播放态起停
                        if (playWhenPrepared) startVocalIfReady() else pauseVocalIfReady()
                    }
                    fullScreenSeeking = false
                    updateLyricView()
                    updateFullScreenProgress()
                    persistRuntimeState()
                }
            })
        }
        progressRow.addView(fullScreenSeekBar, LinearLayout.LayoutParams(0, dp(48), 1f))
        fullScreenTime = label("00:00 / 00:00", 14, Color.WHITE).apply {
            gravity = Gravity.CENTER
        }
        progressRow.addView(fullScreenTime, LinearLayout.LayoutParams(dp(132), dp(48)))
        fullScreenContainer!!.addView(
            progressRow,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58), Gravity.BOTTOM
            )
        )
        fullScreenProgressRow = progressRow

        isFullScreen = true
        fullScreenContainer!!.setVisibility(View.VISIBLE)
        expandPlayerToFullScreen()
        updateFullScreenQrOverlay()
        setFullScreenChromeVisible(true)
        updateFullScreenProgress()
    }

    private fun exitFullScreenPlayer() {
        if (!isFullScreen || player == null || fullScreenContainer == null) return
        // P0-13：全屏宿主 Surface 即将随容器移除/隐藏被回收，先让 engine 暂停解码防黑屏，
        // 切回宿主后 surfaceCreated 会自动 setDisplay + 恢复播放。
        playbackEngine.detachForHostChange()
        fullScreenContainer!!.removeAllViews()
        fullScreenContainer!!.setOnClickListener(null)
        fullScreenContainer!!.setVisibility(View.GONE)
        isFullScreen = false
        playerReturnHost?.let {
            activePlayerHost = it
            applyPlayerBounds(it)
        }
        playerReturnSurface = null
        playerReturnHost = null
        fullScreenPauseText = null
        fullScreenVocalText = null
        fullScreenPauseIcon = null
        fullScreenVocalIcon = null
        fullScreenSongInfo = null
        fullScreenSeekBar = null
        fullScreenTime = null
        fullScreenSeeking = false
        fullScreenControls = null
        fullScreenProgressRow = null
        fullScreenVolumePanel = null
        fullScreenVolumeIndicator = null
        fullScreenVolumeMinusButton = null
        fullScreenVolumePlusButton = null
        fullScreenVolumeMuteButton = null
        fullScreenToneControl = null
        fullScreenQrOverlay = null
        fullScreenChromeVisible = false
        main.removeCallbacks(hideFullScreenChromeRunnable)
        main.removeCallbacks(hideFullScreenVolumeIndicatorRunnable)
        scheduleAutoFullscreen()
    }

    private fun setFullScreenChromeVisible(visible: Boolean) {
        if (!isFullScreen && !visible) return
        fullScreenChromeVisible = visible
        fullScreenControls?.visibility = if (visible) View.VISIBLE else View.GONE
        fullScreenProgressRow?.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) fullScreenVolumePanel?.visibility = View.GONE
        main.removeCallbacks(hideFullScreenChromeRunnable)
        if (visible && isFullScreen) {
            val controls = fullScreenControls
            if (controls != null && !controls.hasFocus() && fullScreenVolumePanel?.hasFocus() != true) {
                controls.getChildAt(0)?.requestFocus()
            }
            main.postDelayed(hideFullScreenChromeRunnable, 5000L)
        }
    }

    private fun updateFullScreenProgress() {
        if (!isFullScreen || player == null || fullScreenSeekBar == null) return
        runCatching {
            val duration = max(0, player!!.duration)
            val position = max(0, player!!.currentPosition)
            if (duration > 0) fullScreenSeekBar!!.max = duration
            if (!fullScreenSeeking) fullScreenSeekBar!!.progress = min(position, max(1, duration))
            fullScreenTime?.text = formatPlaybackTime(position) + " / " + formatPlaybackTime(duration)
        }
    }

    private fun formatPlaybackTime(milliseconds: Int): String {
        val totalSeconds = max(0, milliseconds) / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return (if (minutes < 10) "0" else "") + minutes + ":" +
            (if (seconds < 10) "0" else "") + seconds
    }

    /**
     * 供 [FullScreenPlayerFragment] 调用打开音量控制面板。
     */
    fun showAudioControlFromFragment() {
        showAudioControlDialog()
    }

    private fun showFullScreenVolumeDialog() {
        if (!isFullScreen || fullScreenContainer == null) return
        val existing = fullScreenVolumePanel
        if (existing != null) {
            existing.visibility = if (existing.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (existing.visibility == View.VISIBLE) {
                fullScreenVolumeMinusButton?.requestFocus()
            } else {
                fullScreenToneControl?.requestFocus()
            }
            return
        }

        if (musicVolume > 0) fullScreenPreviousVolume = musicVolume
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
        }
        val indicator = CircularVolumeView(this).apply {
            setVolume(musicVolume)
            visibility = View.GONE
            alpha = 1f
        }
        panel.addView(indicator, LinearLayout.LayoutParams(dp(154), dp(154)).apply {
            bottomMargin = dp(22)
        })
        panel.addView(label("调音", 26, Color.WHITE).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(420), dp(54)))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun volumeButton(text: String): TextView = TextView(this).apply {
            this.text = text
            id = View.generateViewId()
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.ott_bg_volume_ctrl_action_button)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            includeFontPadding = false
            setPadding(dp(14), 0, dp(14), 0)
        }
        val minus = volumeButton("音量 －")
        val plus = volumeButton("音量 ＋")
        val mute = volumeButton("静音").apply {
            compoundDrawablePadding = dp(8)
        }
        fun renderMuteButton() {
            mute.text = if (musicVolume == 0) "放音" else "静音"
            val drawable = getDrawable(
                if (musicVolume == 0) R.drawable.ic_ctrl_sound_enable
                else R.drawable.ic_ctrl_sound_disable
            )
            drawable?.setBounds(0, 0, dp(23), dp(23))
            mute.setCompoundDrawables(null, null, drawable, null)
        }
        fun showVolumeResult() {
            indicator.animate().cancel()
            indicator.setVolume(musicVolume)
            indicator.alpha = 1f
            indicator.visibility = View.VISIBLE
            renderMuteButton()
            applySystemMusicVolume()
            applyPlaybackMode()
            saveState()
            main.removeCallbacks(hideFullScreenVolumeIndicatorRunnable)
            main.postDelayed(hideFullScreenVolumeIndicatorRunnable, 2200L)
            setFullScreenChromeVisible(true)
        }
        minus.setOnClickListener {
            musicVolume = (musicVolume - 5).coerceAtLeast(0)
            if (musicVolume > 0) fullScreenPreviousVolume = musicVolume
            showVolumeResult()
        }
        plus.setOnClickListener {
            musicVolume = (musicVolume + 5).coerceAtMost(100)
            if (musicVolume > 0) fullScreenPreviousVolume = musicVolume
            showVolumeResult()
        }
        mute.setOnClickListener {
            if (musicVolume > 0) {
                fullScreenPreviousVolume = musicVolume
                musicVolume = 0
            } else {
                musicVolume = fullScreenPreviousVolume.coerceAtLeast(5)
            }
            showVolumeResult()
        }
        listOf(minus, plus, mute).forEachIndexed { index, button ->
            button.nextFocusLeftId = listOf(minus, plus, mute).getOrNull(index - 1)?.id ?: button.id
            button.nextFocusRightId = listOf(minus, plus, mute).getOrNull(index + 1)?.id ?: button.id
            button.nextFocusUpId = fullScreenToneControl?.id ?: button.id
            button.nextFocusDownId = button.id
            buttons.addView(button, LinearLayout.LayoutParams(dp(136), dp(58)).apply {
                leftMargin = dp(6)
                rightMargin = dp(6)
            })
        }
        panel.addView(buttons, LinearLayout.LayoutParams(-2, dp(70)).apply { topMargin = dp(12) })
        renderMuteButton()

        fullScreenContainer!!.addView(
            panel,
            FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply {
                bottomMargin = dp(86)
            },
        )
        fullScreenVolumePanel = panel
        fullScreenVolumeIndicator = indicator
        fullScreenVolumeMinusButton = minus
        fullScreenVolumePlusButton = plus
        fullScreenVolumeMuteButton = mute
        minus.requestFocus()
    }

    /**
     * 切换到上一首歌曲(供控制层 onPrev 回调使用)。
     */
    fun playPrevSong() {
        if (orderQueue!!.isEmpty() || currentSong == null) return
        val index = orderQueue.indexOf(currentSong!!)
        if (index <= 0) {
            // 当前歌曲不在队列或已是首曲,从已唱历史取上一首
            if (!sangHistory.isEmpty()) {
                val prev = sangHistory.get(0)
                orderQueue.add(0, prev)
                sangHistory.removeAt(0)
                saveState()
                play(prev)
            }
            return
        }
        val prev = orderQueue.get(index - 1)
        // 将当前歌曲放回队首
        orderQueue.removeAt(index)
        orderQueue.add(index, prev)
        play(prev)
    }

    private fun setSingMode(mode: String) {
        singMode = mode
        if ("练唱模式" == mode) {
            recordEnabled = true
            scoreEnabled = false
        } else if ("评分演唱" == mode) {
            recordEnabled = true
            scoreEnabled = true
        } else if ("静音练唱" == mode) {
            recordEnabled = true
            scoreEnabled = false
            atmosphere = "静音练唱"
        }
        applyPlaybackMode()
        saveState()
        toast("演唱模式：" + singMode)
    }

    private fun setVocalChannelMode(mode: String) {
        vocalChannelMode = mode
        applyPlaybackMode()
        saveState()
        toast("声道模式：" + vocalChannelMode)
    }

    /**
     * 显示调音/音频控制弹窗(对应"弹窗-调音.jpg")
     * 使用 dialog_audio_control.xml 实现
     */
    private fun showAudioControlDialog() {
        // 默认路径：对齐调音弹窗（轻快入口，含升降调/麦克风步进器 + 4 氛围预设 + 「更多音频设置」）。
        // 完整弹窗（3 个 SeekBar + 4 切换）走 showFullAudioControlDialog，由对齐弹窗内的
        // 「更多音频设置」按钮触发，从而保持默认 UX 不变。
        if (showAlignedTuningDialog()) return
    }

    private fun showAlignedTuningDialog(): Boolean {
        val root = getLayoutInflater().inflate(R.layout.dialog_tuning, null)
        val toneValue = root.findViewById<TextView>(R.id.text_tone_counter)
        val micValue = root.findViewById<TextView>(R.id.text_mic_counter)
        toneValue.setText(tone.toString())
        micValue.setText(micVolume.toString())

        val dialog = AlertDialog.Builder(this).setView(root).create()
        root.findViewById<View?>(R.id.btn_tuning_close)
            .setOnClickListener(View.OnClickListener { v: View? -> dialog.dismiss() })
        root.findViewById<View?>(R.id.btn_tone_down)
            .setOnClickListener(View.OnClickListener { v: View? ->
                tone = max(-5, tone - 1)
                toneValue.setText(tone.toString())
                player?.setTone(tone)
                applyPlaybackMode()
                saveState()
            })
        root.findViewById<View?>(R.id.btn_tone_up)
            .setOnClickListener(View.OnClickListener { v: View? ->
                tone = min(5, tone + 1)
                toneValue.setText(tone.toString())
                player?.setTone(tone)
                applyPlaybackMode()
                saveState()
            })
        root.findViewById<View?>(R.id.btn_mic_down)
            .setOnClickListener(View.OnClickListener { v: View? ->
                micVolume = max(0, micVolume - 5)
                micValue.setText(micVolume.toString())
                micServer.setVolume(micVolume)
                saveState()
            })
        root.findViewById<View?>(R.id.btn_mic_up)
            .setOnClickListener(View.OnClickListener { v: View? ->
                micVolume = min(100, micVolume + 5)
                micValue.setText(micVolume.toString())
                micServer.setVolume(micVolume)
                saveState()
            })
        bindAtmospherePreset(root, R.id.btn_preset_pop, "流行")
        bindAtmospherePreset(root, R.id.btn_preset_lyric, "抒情")
        bindAtmospherePreset(root, R.id.btn_preset_rock, "摇滚")
        bindAtmospherePreset(root, R.id.btn_preset_singer, "唱将")
        refreshAtmosphereButtons(root)

        // 完整音频控制弹窗入口（3 个 SeekBar + 伴唱/原唱/立体声/静音 4 切换）：
        // 此前 showAlignedTuningDialog 无条件 return true，导致 showAudioControlDialog
        // 的完整弹窗永远不可达。现在点「更多音频设置」先关掉本对齐弹窗，再开完整弹窗。
        root.findViewById<View?>(R.id.btn_tuning_more)
            ?.setOnClickListener(View.OnClickListener { v: View? ->
                dialog.dismiss()
                showFullAudioControlDialog()
            })

        dialog.showForTv(afterShow = {
            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                attributes = attributes.apply { dimAmount = 0.72f }
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
            root.findViewById<View>(R.id.btn_tone_down).requestFocus()
        })
        return true
    }

    /**
     * 完整音频控制弹窗（3 个 SeekBar + 伴唱/原唱/立体声/静音 4 切换）。
     *
     * 此前 [showAudioControlDialog] 因 `if (showAlignedTuningDialog()) return` 永远走不到弹窗体
     * （showAlignedTuningDialog 无条件 return true），导致完整弹窗为死代码。现在由对齐调音
     * 弹窗内「更多音频设置」按钮调用本方法；showAudioControlDialog 仍默认打开对齐弹窗保持原 UX。
     */
    private fun showFullAudioControlDialog() {
        val root = getLayoutInflater().inflate(R.layout.dialog_audio_control, null)
        val btnClose = root.findViewById<TextView>(R.id.btn_audio_close)
        val btnStereo = root.findViewById<TextView>(R.id.btn_tone_stereo)
        val btnAccomp = root.findViewById<TextView>(R.id.btn_tone_accomp)
        val btnOrigin = root.findViewById<TextView>(R.id.btn_tone_origin)
        val btnMute = root.findViewById<TextView>(R.id.btn_tone_mute)
        val seekMicVol = root.findViewById<SeekBar>(R.id.seek_mic_volume)
        val seekMicEffect = root.findViewById<SeekBar>(R.id.seek_mic_effect)
        val seekMusicVol = root.findViewById<SeekBar>(R.id.seek_music_volume)
        val tvMicVol = root.findViewById<TextView>(R.id.text_mic_volume_value)
        val tvMicEffect = root.findViewById<TextView>(R.id.text_mic_effect_value)
        val tvMusicVol = root.findViewById<TextView>(R.id.text_music_volume_value)
        val btnDefault = root.findViewById<TextView>(R.id.btn_audio_default)
        val btnSave = root.findViewById<TextView>(R.id.btn_audio_save)

        // 初始值
        seekMicVol.setProgress(micVolume)
        seekMicEffect.setProgress(50)
        seekMusicVol.setProgress(musicVolume)
        tvMicVol.setText(micVolume.toString())
        tvMicEffect.setText("50")
        tvMusicVol.setText(musicVolume.toString())

        // 4 个切换按钮:伴唱 / 原唱 / 立体声 / 静音
        updateToggleState(btnAccomp, !originalVocal)
        updateToggleState(btnOrigin, originalVocal)
        updateToggleState(btnStereo, "自动" == vocalChannelMode)
        updateToggleState(btnMute, false)
        btnAccomp.setOnClickListener(View.OnClickListener { v: View? ->
            if (originalVocal) toggleOriginalVocal()
            updateToggleState(btnAccomp, true)
            updateToggleState(btnOrigin, false)
        })
        btnOrigin.setOnClickListener(View.OnClickListener { v: View? ->
            if (!originalVocal) toggleOriginalVocal()
            updateToggleState(btnAccomp, false)
            updateToggleState(btnOrigin, true)
        })
        btnStereo.setOnClickListener(View.OnClickListener { v: View? ->
            setVocalChannelMode("自动")
            updateToggleState(btnStereo, true)
        })
        btnMute.setOnClickListener(View.OnClickListener { v: View? ->
            if (musicVolume > 0) {
                muteBeforeVol = musicVolume
                musicVolume = 0
            } else {
                musicVolume = muteBeforeVol
            }
            applySystemMusicVolume()
            applyPlaybackMode()
            seekMusicVol.setProgress(musicVolume)
            tvMusicVol.setText(musicVolume.toString())
            updateToggleState(btnMute, musicVolume == 0)
            saveState()
        })

        // 3 个滑块
        seekMicVol.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                micVolume = p
                tvMicVol.setText(p.toString())
                // 实时同步给手机话筒：人声音量必须立刻可调
                micServer.setVolume(p)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                saveState()
            }
        })
        seekMicEffect.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                tvMicEffect.setText(p.toString())
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        seekMusicVol.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                musicVolume = p
                tvMusicVol.setText(p.toString())
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                applySystemMusicVolume()
                applyPlaybackMode()
                saveState()
            }
        })

        // 默认 / 保存
        btnDefault.setOnClickListener(View.OnClickListener { v: View? ->
            seekMicVol.setProgress(60)
            seekMicEffect.setProgress(50)
            seekMusicVol.setProgress(80)
            micVolume = 60
            musicVolume = 80
            applySystemMusicVolume()
            applyPlaybackMode()
            saveState()
            tvMicVol.setText("60")
            tvMicEffect.setText("50")
            tvMusicVol.setText("80")
            updateToggleState(btnMute, false)
        })

        // 创建并显示弹窗
        val dialog = AlertDialog.Builder(this)
            .setView(root)
            .create()
        // 透明背景显示圆角
        if (dialog.getWindow() != null) {
            dialog.getWindow()!!.setBackgroundDrawableResource(android.R.color.transparent)
        }
        btnClose.setOnClickListener(View.OnClickListener { v: View? -> dialog.dismiss() })
        btnSave.setOnClickListener(View.OnClickListener { v: View? ->
            saveState()
            dialog.dismiss()
        })
        dialog.restoreSourceFocusOnDismiss()
        dialog.show()
    }

    /**
     * 调音弹窗的音效按钮（v1.2.6 起接真实 EQ）。
     *
     * 旧实现只把 [atmosphere] 字符串改掉再 toast"音效: XX"，**没有任何音频
     * 效果**——纯摆设。现在点击应用 KtvEqualizer 预设曲线；再次点击已生效的
     * 同一个按钮 = 关闭音效（TV 上不便做二级菜单，用再点一次当开关）。
     */
    private fun bindAtmospherePreset(root: View, id: Int, preset: String) {
        val btn = root.findViewById<View?>(id) ?: return
        btn.setOnClickListener(View.OnClickListener { v: View? ->
            val turningOff = atmosphere == preset && KtvEqualizer.enabled
            atmosphere = preset
            if (turningOff) {
                KtvEqualizer.setEnabled(false)
                toast("音效已关闭")
            } else {
                applyAtmosphereEq(preset)
                toast("音效: " + preset)
            }
            refreshAtmosphereButtons(root)
            saveState()
        })
    }

    /** 4 个切换按钮的状态切换  */
    private var muteBeforeVol = 80
    private fun updateToggleState(btn: TextView?, on: Boolean) {
        if (btn == null) return
        btn.setBackgroundResource(if (on) R.drawable.bg_btn_toggle_on else R.drawable.bg_btn_toggle_off)
        btn.setTextColor(if (on) Color.WHITE else Color.rgb(204, 204, 204))
    }

    private fun controlRow(
        left: String?,
        right: String?,
        leftAction: Runnable,
        rightAction: Runnable
    ): LinearLayout {
        val row = LinearLayout(this)
        row.setGravity(Gravity.CENTER_VERTICAL)
        row.addView(
            button(left, View.OnClickListener { v: View? -> leftAction.run() }),
            LinearLayout.LayoutParams(0, dp(54), 1f)
        )
        row.addView(
            button(right, View.OnClickListener { v: View? -> rightAction.run() }),
            LinearLayout.LayoutParams(0, dp(54), 1f)
        )
        return row
    }

    private fun showAtmosphereDialog() {
        val items = arrayOf<String>("标准", "热烈", "柔和", "派对", "静音练唱")
        AlertDialog.Builder(this)
            .setTitle("气氛模式")
            .setItems(
                items,
                DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                    atmosphere = items[which]
                    saveState()
                    toast("气氛模式：" + atmosphere)
                })
            .create().showForTv()
    }

    private fun showPlayerStatusDialog() {
        val now = if (orderQueue!!.isEmpty()) null else orderQueue.get(0)
        val message = ("当前歌曲：" + (if (now == null) "无" else now.displayTitle())
                + "\n已点：" + orderQueue.size
                + "\n已唱：" + sangHistory.size
                + "\n模式：" + (if (loopOne) "单曲循环" else (if (autoNext) "顺序播放" else "手动切歌"))
                + "\n声道：" + (if (originalVocal) "原唱" else "伴唱")
                + "\n声道模式：" + vocalChannelMode
                + "\n演唱模式：" + singMode
                + "\n音乐音量：" + musicVolume
                + "\n麦克风音量：" + micVolume
                + "\n音调：" + tone
                + "\n气氛：" + atmosphere
                + "\n评分：" + scoreSummary()
                + "\n录音：" + (if (recorder == null) "未录音" else "录音中")
                + "\n灯光：" + lightMode
                + "\n跑马灯：" + (if (marqueeEnabled) marqueeText else "关闭")
                + "\n音画同步：" + audioDelayMs + "ms"
                + "\n包房广播：" + (if (tableBroadcastEnabled) tableBroadcastText else "关闭"))
        AlertDialog.Builder(this).setTitle("播放状态").setMessage(message)
            .setPositiveButton("确定", null).create().showForTv(DialogInterface.BUTTON_POSITIVE)
    }

    private fun showStorageDialog() {
        val root = library.rootDir
        val importRoot = library.importDir
        val message = ("歌曲目录：" + root.getAbsolutePath()
                + "\n可用空间：" + formatSize(root.getFreeSpace())
                + "\n导入目录：" + importRoot.getAbsolutePath()
                + "\n录音目录：" + recordDir().getAbsolutePath()
                + "\n曲库总数：" + library.allSongs().size
                + "\n下载任务：" + downloads.size)
        AlertDialog.Builder(this).setTitle("存储信息").setMessage(message)
            .setPositiveButton("确定", null).create().showForTv(DialogInterface.BUTTON_POSITIVE)
    }

    private fun showNetworkDialog() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager?
        val info = if (cm == null) null else cm.getActiveNetworkInfo()
        val message =
            ("网络状态：" + (if (info != null && info.isConnected()) "已连接" else "未连接")
                    + "\n网络类型：" + (if (info == null) "未知" else info.getTypeName())
                    + "\n曲库源：" + prefs()!!.getString(KEY_CATALOG_URL, "未设置")
                    + "\n本地曲库：" + library.allSongs().size + " 首")
        AlertDialog.Builder(this)
            .setTitle("网络状态")
            .setMessage(message)
            .setPositiveButton(
                "同步曲库",
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int -> fetchCatalog() })
            .setNegativeButton("关闭", null)
            .create().showForTv()
    }


    private fun showAppCenter() {
        setPage("应用")
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(dp(320), -1))
        left.addView(sectionTitle("应用工具"))
        left.addView(button("网络设置", View.OnClickListener { v: View? -> showNetworkDialog() }))
        left.addView(button("屏幕设置", View.OnClickListener { v: View? -> showScreenDialog() }))
        left.addView(
            button(
                "曲库维护",
                View.OnClickListener { v: View? -> showLibraryMaintenance() })
        )
        left.addView(button("存储信息", View.OnClickListener { v: View? -> showStorageDialog() }))
        left.addView(button("配置备份", View.OnClickListener { v: View? -> showBackupTools() }))

        val right = panel()
        content!!.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        right.addView(sectionTitle("本地工具"))
        val tools = arrayOf<String?>(
            "点歌台",
            "语种点歌",
            "字数点歌",
            "歌星分类",
            "下载管理",
            "U盘加歌",
            "用户影片",
            "灯光迪斯科",
            "手机点歌",
            "手机话筒",
            "运行日志",
            "录音评分"
        )
        val grid = GridLayout(this)
        grid.setColumnCount(4)
        right.addView(grid)
        for (tool in tools) {
            addTile(
                grid,
                tool,
                "打开",
                View.OnClickListener { v: View? ->
                    openTool(
                        (v as TextView).getText().toString().split("\n".toRegex())
                            .dropLastWhile { it.isEmpty() }.toTypedArray()[0])
                })
        }
        right.addView(sectionTitle("本地 APK / 升级包"))
        val list = listView()
        val adapter = tvAdapter()
        val apks = localApks()
        for (apk in apks) adapter.add(apk.getName() + "    " + formatSize(apk.length()))
        if (adapter.isEmpty()) adapter.add("暂无 APK，请放入 /sdcard/MDKTV_Native/apps 或 U盘/apps")
        list.setAdapter(adapter)
        list.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            if (pos >= 0 && pos < apks.size) installApk(apks.get(pos))
        })
        right.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun showTableBroadcast() {
        setPage("广播")
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(dp(340), -1))
        left.addView(sectionTitle("包房广播"))
        left.addView(
            button(
                if (tableBroadcastEnabled) "广播功能：开" else "广播功能：关",
                View.OnClickListener { v: View? ->
                    tableBroadcastEnabled = !tableBroadcastEnabled
                    saveState()
                    showTableBroadcast()
                })
        )
        left.addView(
            button(
                "发送当前广播",
                View.OnClickListener { v: View? -> sendTableBroadcast(tableBroadcastText) })
        )
        left.addView(controlRow("时长 -", "时长 +", Runnable {
            tableBroadcastSeconds = clamp(tableBroadcastSeconds - 1, 2, 60)
            saveState()
            showTableBroadcast()
        }, Runnable {
            tableBroadcastSeconds = clamp(tableBroadcastSeconds + 1, 2, 60)
            saveState()
            showTableBroadcast()
        }))

        val right = panel()
        content!!.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        right.addView(sectionTitle("广播内容"))
        val input = EditText(this)
        input.setSingleLine(true)
        input.setText(tableBroadcastText)
        input.setHint("输入包房广播内容")
        right.addView(input, LinearLayout.LayoutParams(-1, dp(58)))
        right.addView(button("保存并发送", View.OnClickListener { v: View? ->
            tableBroadcastText = input.getText().toString().trim { it <= ' ' }
            if (tableBroadcastText!!.isEmpty()) tableBroadcastText = "欢迎光临"
            saveState()
            sendTableBroadcast(tableBroadcastText)
            showTableBroadcast()
        }), LinearLayout.LayoutParams(dp(180), dp(58)))
        right.addView(label("显示时长：" + tableBroadcastSeconds + " 秒", 20, Color.WHITE))
        right.addView(sectionTitle("广播历史"))
        val list = listView()
        val adapter = tvAdapter()
        if (store.broadcastHistory.isEmpty()) adapter.add("暂无广播记录")
        else adapter.addAll(store.broadcastHistory)
        list.setAdapter(adapter)
        right.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun showBackupTools() {
        setPage("备份")
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(dp(340), -1))
        left.addView(sectionTitle("配置备份"))
        left.addView(button("备份当前配置", View.OnClickListener { v: View? -> backupConfig() }))
        left.addView(button("从备份恢复", View.OnClickListener { v: View? -> restoreConfig() }))
        left.addView(
            button(
                "打开曲库维护",
                View.OnClickListener { v: View? -> showLibraryMaintenance() })
        )

        val right = panel()
        content!!.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        right.addView(sectionTitle("备份信息"))
        val backupDir = backupDir()
        right.addView(label("备份目录：" + backupDir.getAbsolutePath(), 20, Color.LTGRAY))
        val list = listView()
        val adapter = tvAdapter()
        val files = backupDir.listFiles()
        if (files != null) {
            val values: MutableList<File> = ArrayList<File>()
            for (file in files) if (file.isFile()) values.add(file)
            Collections.sort<File?>(
                values,
                Comparator { a: File?, b: File? ->
                    b!!.lastModified().compareTo(a!!.lastModified())
                })
            for (file in values) adapter.add(file.getName() + "    " + formatSize(file.length()))
        }
        if (adapter.isEmpty()) adapter.add("暂无备份")
        list.setAdapter(adapter)
        right.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun showLibraryMaintenance() {
        setPage("维护")
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(dp(340), -1))
        left.addView(sectionTitle("曲库维护"))
        left.addView(button("重扫本地曲库", View.OnClickListener { v: View? ->
            refreshLibrary(
                Runnable { showLibraryMaintenance() })
        }))
        left.addView(button("同步网络曲库", View.OnClickListener { v: View? -> fetchCatalog() }))
        left.addView(button("清除网络缓存", View.OnClickListener { v: View? ->
            val deleted = library.clearRemoteCache()
            refreshLibrary(Runnable {
                toast(if (deleted) "网络曲库缓存已清除" else "没有可清除的网络缓存")
                showLibraryMaintenance()
            })
        }))
        left.addView(button("清空下载任务", View.OnClickListener { v: View? ->
            downloads.clear()
            showLibraryMaintenance()
        }))
        left.addView(button("清理失效已点", View.OnClickListener { v: View? ->
            removeMissingQueueItems()
            showLibraryMaintenance()
        }))
        left.addView(button("恢复隐藏歌曲", View.OnClickListener { v: View? ->
            store.hiddenSongIds.clear()
            saveState()
            refreshLibrary(Runnable {
                toast("已恢复隐藏歌曲")
                showLibraryMaintenance()
            })
        }))

        val right = panel()
        content!!.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        right.addView(sectionTitle("曲库概览"))
        right.addView(label("总曲库：" + library.allSongs().size + " 首", 22, Color.WHITE))
        right.addView(label("已点：" + orderQueue!!.size + " 首", 20, Color.LTGRAY))
        right.addView(label("已唱：" + sangHistory.size + " 首", 20, Color.LTGRAY))
        right.addView(label("下载任务：" + downloads.size, 20, Color.LTGRAY))
        right.addView(label("隐藏歌曲：" + store.hiddenSongIds.size + " 首", 20, Color.LTGRAY))
        right.addView(label("本地目录：" + library.rootDir.getAbsolutePath(), 18, Color.LTGRAY))
        right.addView(label("导入目录：" + library.importDir.getAbsolutePath(), 18, Color.LTGRAY))
    }

    /**
     * 扫码点歌弹窗。
     *
     * 行业做法调研结论（雷石/问界/岚图/Stingray 全部走"云端账号绑定+公网服务器"），
     * 车上无外网时这条路走不通，只能局域网。此时有两条都能用的路径：
     *  ① 车机开 Wi-Fi 热点，手机连车机热点；
     *  ② 手机开热点、车机连手机热点（手机没流量也能开 AP，车机连上后同网段）。
     * 两条都不需要外网流量，所以引导必须写清楚 —— 之前只提示"连车机热点"，用户不知道还有②，
     * 而很多车机热点需要开通流量服务才能开，车上没有流量时②才是唯一可行路径。
     *
     * 同时给"网络诊断"入口：车机上无法从手机侧确认连通性，缺诊断就只能盲猜哪里错了。
     */
    /**
     * 【v1.2.8 已移除蓝牙麦克风弹窗】老板真车验证"行不通"（2026-10-12 拍板）：
     * HFP/SCO 链路在哪吒L 车机上无法把手机人声稳定送到车机喇叭。正路是
     * 手机话筒（HTTPS 网页 PCM，v1.2.6 已加抖动缓冲 + 8 人上限 + 评分旁路）
     * 与有线麦（MicActivator 占位）。原 showBluetoothMicDialog /
     * openBluetoothSettings / BluetoothMicManager 一并移除，权限声明同步清理。
     */

    /**
     * 手机话筒入口。
     *
     * 扫码后打开的就是点歌页（同���个二维码！），底部「话筒」页签即可开麦 ——
     * 老板要"扫一下就能点歌和当麦都出来"，所以这里与 [showMobileRemoteDialog]
     * 用同一个 URL，只在文案上强调这是话筒入口。
     */
    private fun showMobileMicDialog() {
        // 优先给 https 地址：只有安全上下文浏览器才提供麦克风接口，http 页面开不了麦。
        // 自签证书首次访问会提示"不安全"，点一次"继续前往"即信任（业界通行做法，
        // QuicMic 等成熟项目同样如此）。
        val httpsUrl = ensureHttpsMicServer().let { if (it.isBlank()) "" else "$it/mic" }
        val httpUrl = remoteUrl()
        val url = if (httpsUrl.isNotBlank()) httpsUrl else httpUrl
        if (url.isBlank()) {
            AlertDialog.Builder(this)
                .setTitle("手机话筒")
                .setMessage(
                    "未检测到可用局域网地址\n\n" +
                        "请先让手机与车机连到同一个网络：\n" +
                        "① 车机开启 Wi-Fi 热点，手机连该热点\n" +
                        "② 手机开热点，车机设置里连上该热点\n" +
                        "（第② 种不需要任何流量，车机没流量时也能用）"
                )
                .setNeutralButton("网络诊断") { _, _ -> showNetworkDiagnostics() }
                .setNegativeButton("关闭", null)
                .setPositiveButton("重新检测") { _, _ -> restartRemoteServer() }
                .create().showForTv(DialogInterface.BUTTON_POSITIVE)
            return
        }
        val box = LinearLayout(this)
        box.setOrientation(LinearLayout.VERTICAL)
        box.setGravity(Gravity.CENTER_HORIZONTAL)
        box.setPadding(dp(24), dp(18), dp(24), dp(12))
        box.addView(ImageView(this).apply {
            setBackgroundColor(Color.WHITE)
            setImageBitmap(qrBitmap(url, dp(240)))
            contentDescription = "手机话筒二维码"
        }, LinearLayout.LayoutParams(dp(260), dp(260)))
        val logsUrl = if (httpsUrl.isNotBlank()) httpsUrl.removeSuffix("/mic") + "/logs" else ""
        val tip = label(
            if (httpsUrl.isNotBlank()) {
                "① 让手机与车机连到同一个网络（车机开热点 / 手机开热点都行）\n" +
                    "② 用手机自带浏览器扫码（不要用微信）\n" +
                    "③ 提示\"证书不安全\"时点「高级 → 继续前往」\n" +
                    "④ 进页面后点底部「话筒」页签即可开麦\n" +
                    "※ 出问题时手机浏览器开 $logsUrl 看车机日志"
            } else {
                "① 让手机与车机连到同一个网络（二选一）\n" +
                    "· 车机开 Wi-Fi 热点，手机连车机热点\n" +
                    "· 手机开热点，车机连上（不需要流量）\n" +
                    "② 用安卓手机扫码 → 点底部「话筒」页签 → 开麦"
            },
            16, Color.WHITE,
        )
        tip.gravity = Gravity.CENTER
        box.addView(tip, LinearLayout.LayoutParams(dp(440), dp(170)))
        val status = label(
            (if (micServer.clientCount() > 0) "已有 ${micServer.clientCount()} 个手机在当话筒"
            else "当前没有手机接入话筒") + "\n" +
                (if (httpsUrl.isNotBlank()) "地址：$httpsUrl" else "地址：$httpUrl"),
            13, Color.LTGRAY,
        )
        status.gravity = Gravity.CENTER
        box.addView(status, LinearLayout.LayoutParams(dp(440), dp(52)))
        AlertDialog.Builder(this)
            .setTitle("手机话筒")
            .setView(box)
            .setNeutralButton("网络诊断") { _, _ -> showNetworkDiagnostics() }
            .setNegativeButton("关闭", null)
            .setPositiveButton("去开车机热点") { _, _ -> openHotspotSettings() }
            .create()
            .showAutoDismissForTv(DialogInterface.BUTTON_POSITIVE, QR_AUTO_CLOSE_MS)
    }

    /** 话筒客户端接入数变化时更新播放页提示（有手机接上/掉线）。 */
    private fun onMicClientChanged(count: Int) {
        if (isFinishing) return
        KtvLog.i("MicUI", "client count -> $count")
        if (count > 0) toast("$count 个手机已接入话筒")
        updateMicStatusLabel()
    }

    /** 播放页的话筒接入状态提示文案（无手机时返回 null 不显示）。 */
    private fun updateMicStatusLabel() {
        val count = micServer.clientCount()
        if (!isFullScreen) return
        val container = fullScreenContainer ?: return
        var label = container.findViewById<android.widget.TextView?>(MIC_STATUS_ID)
        if (count == 0) {
            label?.let { container.removeView(it) }
            return
        }
        if (label == null) {
            label = android.widget.TextView(this).apply {
                id = MIC_STATUS_ID
                textSize = if (compactUi) 11f else 12f
                setTextColor(Color.rgb(120, 240, 160))
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setBackgroundColor(Color.argb(170, 20, 60, 40))
                includeFontPadding = false
            }
            container.addView(
                label,
                FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.TOP).apply {
                    topMargin = dp(8)
                    leftMargin = dp(10)
                }
            )
        }
        label.text = "🎤 $count 个手机话筒"
    }

    private fun showMobileRemoteDialog() {
        val url = remoteUrl()
        if (url.isBlank()) {
            AlertDialog.Builder(this)
                .setTitle("手机点歌")
                .setMessage(
                    remoteServer.error() ?: "未检测到可用局域网地址\n\n" +
                        "请用下面任一方式让手机与车机在同一网络：\n" +
                        "① 车机开启 Wi-Fi 热点，手机连该热点\n" +
                        "② 手机开热点，车机设置里连上该热点\n" +
                        "（第② 种不需要任何流量，车机没流量时也能用）"
                )
                .setNeutralButton("网络诊断") { _, _ -> showNetworkDiagnostics() }
                .setNegativeButton("关闭", null)
                .setPositiveButton("重新检测") { _, _ -> restartRemoteServer() }
                .create().showForTv(DialogInterface.BUTTON_POSITIVE)
            return
        }
        val box = LinearLayout(this)
        box.setOrientation(LinearLayout.VERTICAL)
        box.setGravity(Gravity.CENTER_HORIZONTAL)
        box.setPadding(dp(24), dp(18), dp(24), dp(12))
        val qr = ImageView(this)
        qr.setBackgroundColor(Color.WHITE)
        qr.setImageBitmap(qrBitmap(url, dp(260)))
        box.addView(qr, LinearLayout.LayoutParams(dp(280), dp(280)))
        val tip = label(
            "① 让手机与车机连到同一个网络（二选一）\n" +
                "· 车机开 Wi-Fi 热点，手机连车机热点\n" +
                "· 手机开热点，车机连上（不需要流量）\n" +
                "② 用微信扫下方二维码（iPhone 别用相机直扫 http）\n" +
                "③ 连上热点后，也可在手机浏览器手动输入上方地址",
            17, Color.WHITE,
        )
        tip.gravity = Gravity.CENTER
        box.addView(tip, LinearLayout.LayoutParams(dp(400), dp(150)))
        val addr = label(
            "二维码地址：" + url + "\n本机其它地址：" +
                localIpsInOrder().joinToString("  /  "),
            14, Color.LTGRAY,
        )
        addr.gravity = Gravity.CENTER
        box.addView(addr, LinearLayout.LayoutParams(dp(400), dp(52)))
        hotspotInfoText()?.let { info ->
            val hot = label(info, 14, Color.rgb(120, 220, 140))
            hot.gravity = Gravity.CENTER
            box.addView(hot, LinearLayout.LayoutParams(dp(400), dp(26)))
        }
        AlertDialog.Builder(this)
            .setTitle("扫码点歌")
            .setView(box)
            .setNeutralButton("网络诊断") { _, _ -> showNetworkDiagnostics() }
            .setNegativeButton("关闭", null)
            .setPositiveButton("去开车机热点") { _, _ -> openHotspotSettings() }
            .create()
            .showAutoDismissForTv(DialogInterface.BUTTON_POSITIVE, QR_AUTO_CLOSE_MS)
    }

    /**
     * 网络诊断：把"服务有没有起来、绑在哪个 IP、每张网卡是什么类型、车机热点开没开、
     * 二维码地址自己能不能访问"全列出来。车机侧无法确认手机能否连通，缺这个面板时
     * 只能反复盲试，是扫码点歌一直搞不定的关键原因。
     *
     * @param selfCheck 车机自访二维码地址的结果；null=尚未检测（打开时自动跑一次）
     */
    private fun showNetworkDiagnostics(selfCheck: String? = null) {
        val lines = ArrayList<String>()
        lines.add("服务状态：" + if (remoteServer.isRunning()) "已启动" else "未启动")
        lines.add("监听地址：" + (remoteServer.address() ?: "无") + ":" + remoteServer.port())
        lines.add("二维码：" + (remoteUrl().ifBlank { "无（无可用局域网地址）" }))
        remoteServer.error()?.let { lines.add("错误：" + it) }
        lines.add("车机热点：" + hotspotStateText())
        lines.add("页面自检：" + (selfCheck ?: "检测中…"))
        lines.add("")
        lines.add("本机网卡（按手机可连性排序）：")
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            val rows = ArrayList<String>()
            while (interfaces.hasMoreElements()) {
                val network = interfaces.nextElement()
                val name = (network.name ?: "").lowercase(Locale.ROOT)
                val kind = when {
                    name.startsWith("lo") -> "回环(不可用)"
                    easyTierIfaceHints.any { name.contains(it) } -> "EasyTier虚拟网卡"
                    hotspotIfaceHints.any { name.contains(it) } -> "热点/Wi-Fi"
                    cellularIfaceHints.any { name.contains(it) } -> "4G数据(手机连不上)"
                    else -> "其它"
                }
                val addresses = network.getInetAddresses()
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    val ip = address.hostAddress ?: continue
                    if (ip.indexOf(':') >= 0) continue
                    rows.add("  $name  $ip   [$kind]")
                }
            }
            if (rows.isEmpty()) lines.add("  未发现可用 IPv4 网卡") else lines.addAll(rows)
        } catch (e: Exception) {
            lines.add("  读取网卡失败：" + e.message)
        }
        lines.add("")
        lines.add("手机连不上时按顺序排查：")
        lines.add("1. 车机热点是否已开（上方\"车机热点\"一行）")
        lines.add("2. 手机和车机是否在同一网络（热点/同一 Wi-Fi）")
        lines.add("3. 上方\"二维码地址\"能否在手机浏览器直接打开")
        lines.add("4. iPhone 不要用相机直扫（会拦 http），请用微信扫")
        lines.add("5. 地址不对时点\"去开热点\"或\"重新检测\"；仍不对可手动指定")
        AlertDialog.Builder(this)
            .setTitle("扫码点歌 · 网络诊断")
            .setMessage(lines.joinToString("\n"))
            .setNeutralButton("手动指定地址") { _, _ -> showSetRemoteHostDialog() }
            .setNegativeButton("去开热点") { _, _ -> openHotspotSettings() }
            .setPositiveButton("确定", null)
            .create().showForTv(DialogInterface.BUTTON_POSITIVE)
        if (selfCheck == null) runSelfCheck()
    }

    /** 反射检测车机热点（SoftAP）是否已开；部分车机屏蔽该接口，拿不到就明说。 */
    private fun hotspotStateText(): String = runCatching {
        val wifi = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE)
            as? android.net.wifi.WifiManager ?: error("no wifi service")
        val method = wifi.javaClass.getMethod("isWifiApEnabled")
        (method.invoke(wifi) as? Boolean) ?: false
    }.fold(
        onSuccess = { on -> if (on) "已开（手机可连）" else "未开（先点\"去开热点\"）" },
        onFailure = { "无法检测（车机屏蔽了该接口）" }
    )

    /**
     * 车机自己访问一次二维码地址：能打开 = 服务正常，问题在手机侧网络；
     * 打不开 = 服务/端口问题，先修车机。把"谁的问题"一次分清。
     */
    private fun runSelfCheck() {
        val url = remoteUrl()
        if (url.isBlank()) return
        io.execute {
            val result = runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                val code = conn.responseCode
                conn.disconnect()
                if (code == 200) "正常（手机应能打开同一地址）" else "HTTP $code"
            }.getOrElse { "不可达：" + (it.message ?: "连接失败") }
            main.post { if (!isFinishing) showNetworkDiagnostics(result) }
        }
    }

    /**
     * 运行日志查看器。
     *
     * 存在的意义：以前出问题只有一张手机截图，"车机侧到底发生了什么"全靠猜。
     * 现在话筒服务、HTTPS 握手、AudioTrack、崩溃堆栈全在 [KtvLog] 里，
     * 车机上直接看，也能分享出去（写文件 → FileProvider → 系统分享）。
     */
    private fun showLogViewer() {
        val crashes = KtvLog.crashFiles()
        val text = buildString {
            appendLine("服务：${remoteServer.statusLine()}")
            appendLine("话筒：${micServer.statusLine()}")
            appendLine("HTTPS：port=${httpsMicServer.port} running=${httpsMicServer.running} error=${httpsMicServer.lastError ?: "无"}")
            appendLine("日志文件：${KtvLog.logFiles().joinToString { it.name }.ifEmpty { "无" }}")
            if (crashes.isNotEmpty()) {
                appendLine("崩溃记录：${crashes.joinToString { it.name }}")
            }
            appendLine("──── 最近 300 条 ────")
            append(KtvLog.recentText(300))
        }
        val scroll = ScrollView(this)
        val tv = TextView(this).apply {
            setText(text)
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setTextIsSelectable(true)
        }
        scroll.addView(tv)
        val dialog = AlertDialog.Builder(this)
            .setTitle("运行日志")
            .setView(scroll)
            .setNeutralButton("分享") { _, _ -> shareLogs() }
            .setNegativeButton("清空") { _, _ ->
                KtvLog.clearAll()
                toast("日志已清空")
            }
            .setPositiveButton("刷新", null)
            .create()
        // 刷新 = 关掉旧弹窗再开新的（TV 上对话框叠层很难退）
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
                dialog.dismiss()
                showLogViewer()
            }
        }
        dialog.showForTv(DialogInterface.BUTTON_POSITIVE)
    }

    /** 把最近日志写成文件并经 FileProvider 弹出系统分享（微信/邮件都行）。 */
    private fun shareLogs() {
        io.execute {
            val file = runCatching {
                val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val f = java.io.File(dir, "ktv_log_share.txt")
                f.writeText(
                    buildString {
                        appendLine("${remoteServer.statusLine()}")
                        appendLine("${micServer.statusLine()}")
                        appendLine("https port=${httpsMicServer.port} running=${httpsMicServer.running}")
                        KtvLog.crashFiles().forEach { crash ->
                            appendLine("==== crash: ${crash.name} ====")
                            appendLine(crash.readText())
                        }
                        appendLine("==== recent logs ====")
                        append(KtvLog.recentText(500))
                    }
                )
                f
            }.getOrNull()
            main.post {
                if (file == null) {
                    toast("日志导出失败")
                    return@post
                }
                val uri = runCatching {
                    androidx.core.content.FileProvider.getUriForFile(
                        this,
                        applicationContext.packageName + ".fileprovider",
                        file
                    )
                }.getOrNull()
                if (uri == null) {
                    toast("日志已存：${file.absolutePath}")
                    return@post
                }
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    putExtra(android.content.Intent.EXTRA_SUBJECT, "风火轮K歌版 运行日志")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(android.content.Intent.createChooser(intent, "分享日志"))
            }
        }
    }

    /**
     * 一键跳系统设置页。先给两条路让老板选：
     * ① 开车机热点（手机连车机）——哪吒L 若裁了热点功能或要流量套餐，走不通；
     * ② 车机去连手机热点（手机先开热点，零流量）——车机没流量时的主路径。
     */
    private fun openHotspotSettings() {
        AlertDialog.Builder(this)
            .setTitle("让手机与车机同网")
            .setItems(arrayOf("① 开车机热点（手机来连车机）", "② 车机去连手机热点（手机先开热点）")) { _, which ->
                val action = if (which == 0) "android.settings.WIFI_AP_SETTINGS" else "android.settings.WIFI_SETTINGS"
                if (!tryOpenSettings(action)) {
                    toast("未找到该设置页，请手动进 设置→网络")
                }
            }
            .setNegativeButton("取消", null)
            .create().showForTv(DialogInterface.BUTTON_POSITIVE)
    }

    /** 依次尝试多个 settings action，返回是否成功打开了一个。 */
    private fun tryOpenSettings(action: String): Boolean {
        val intent = Intent(action)
        if (intent.resolveActivity(packageManager) == null) return false
        return runCatching { startActivity(intent) }.isSuccess
    }

    /** 读车机热点的 SSID/密码直接显示在弹窗里（反射，拿不到就返回 null 不显示）。 */
    private fun hotspotInfoText(): String? = runCatching {
        val wifi = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE)
            as? android.net.wifi.WifiManager ?: return null
        val method = wifi.javaClass.getMethod("getWifiApConfiguration")
        val config = method.invoke(wifi) as? android.net.wifi.WifiConfiguration ?: return null
        val ssid = config.SSID?.trim('"')
        if (ssid.isNullOrBlank()) return null
        val key = config.preSharedKey?.trim('"')
        "车机热点：$ssid   密码：${if (key.isNullOrBlank()) "无" else key}"
    }.getOrNull()

    /** 手动指定二维码地址（网卡判断失灵时的逃生口，写 global settings 持久化）。 */
    private fun showSetRemoteHostDialog() {
        val input = android.widget.EditText(this).apply {
            setText(Settings.Global.getString(contentResolver, "maidong_remote_host") ?: "")
            setHint("例如 192.168.43.1")
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("手动指定车机地址")
            .setMessage(
                "车机无法自动选准时，把手机能访问到的车机地址填这里。\n" +
                    "在手机浏览器里试哪个地址能打开车机网页，就填哪个。\n" +
                    "（不填＝自动检测）"
            )
            .setView(input)
            .setNeutralButton("清除") { _, _ ->
                Settings.Global.putString(contentResolver, "maidong_remote_host", null)
                restartRemoteServer()
            }
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val value = input.text?.toString()?.trim().orEmpty()
                if (value.isBlank() || LocalRemoteServer.isPrivateIpv4(value)) {
                    Settings.Global.putString(contentResolver, "maidong_remote_host", value.ifBlank { null })
                    restartRemoteServer()
                    toast(if (value.isBlank()) "已恢复自动检测" else "已指定地址：$value")
                } else {
                    toast("地址格式不对，应为内网 IP，例如 192.168.43.1")
                }
            }
            .create().showForTv(DialogInterface.BUTTON_POSITIVE)
    }

    private fun restartRemoteServer() {
        remoteServer.stop()
        startRemoteServer()
        updateMobileQrOverlay()
        updateFullScreenQrOverlay()
        main.postDelayed({ showMobileRemoteDialog() }, 180)
    }

    private fun updateMobileQrOverlay() {
        val root = findViewById<FrameLayout?>(R.id.layout_root) ?: return
        mobileQrOverlay?.let { root.removeView(it) }
        mobileQrOverlay = null
        if (prefs()?.getBoolean(KEY_SHOW_MOBILE_QR, false) != true) return
        val url = remoteUrl()
        if (url.isBlank()) return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(4))
            setBackgroundColor(Color.argb(205, 255, 255, 255))
            addView(ImageView(this@MainActivity).apply {
                setImageBitmap(qrBitmap(url, dp(112)))
                contentDescription = "手机点歌二维码"
            }, LinearLayout.LayoutParams(dp(116), dp(116)))
            addView(TextView(this@MainActivity).apply {
                text = "手机点歌"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(59, 18, 92))
            }, LinearLayout.LayoutParams(-1, dp(24)))
            setOnClickListener { showMobileRemoteDialog() }
        }
        val params = FrameLayout.LayoutParams(dp(128), dp(152), Gravity.END or Gravity.BOTTOM).apply {
            rightMargin = dp(20)
            bottomMargin = dp(76)
        }
        root.addView(box, params)
        mobileQrOverlay = box
    }

    /** 全屏播放时固定显示在右上角的小型手机点歌二维码。 */
    private fun updateFullScreenQrOverlay() {
        val container = fullScreenContainer ?: return
        fullScreenQrOverlay?.let { container.removeView(it) }
        fullScreenQrOverlay = null
        if (!isFullScreen) return
        val url = remoteUrl()
        if (url.isBlank()) return
        val qrSize = dp(if (compactUi) 68 else 84)
        val totalWidth = dp(if (compactUi) 78 else 96)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(3))
            setBackgroundColor(Color.argb(218, 255, 255, 255))
            elevation = dp(12).toFloat()
            addView(ImageView(this@MainActivity).apply {
                setImageBitmap(qrBitmap(url, qrSize))
                contentDescription = "扫码手机点歌"
            }, LinearLayout.LayoutParams(qrSize, qrSize))
            addView(TextView(this@MainActivity).apply {
                text = "扫码点歌"
                textSize = if (compactUi) 9f else 10f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(59, 18, 92))
                includeFontPadding = false
            }, LinearLayout.LayoutParams(-1, dp(if (compactUi) 16 else 18)))
            setOnClickListener { showMobileRemoteDialog() }
        }
        val params = FrameLayout.LayoutParams(totalWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.TOP).apply {
            topMargin = dp(8)
            rightMargin = dp(10)
        }
        container.addView(box, params)
        box.bringToFront()
        fullScreenQrOverlay = box
    }

    private fun showMobileRemoteSettings() {
        val enabled = prefs()?.getBoolean(KEY_SHOW_MOBILE_QR, false) == true
        AlertDialog.Builder(this)
            .setTitle("手机点歌")
            .setSingleChoiceItems(arrayOf("仅在菜单中显示二维码", "播放页右下角显示二维码"), if (enabled) 1 else 0) { dialog, which ->
                prefs()?.edit()?.putBoolean(KEY_SHOW_MOBILE_QR, which == 1)?.apply()
                updateMobileQrOverlay()
                dialog.dismiss()
            }
            .setNeutralButton("查看二维码") { _, _ -> showMobileRemoteDialog() }
            .setNegativeButton("关闭", null)
            .create().showForTv()
    }

    private fun startRemoteServer() {
        val address = localIp().takeIf { LocalRemoteServer.isPrivateIpv4(it) }
        remoteServer.start(address, object : LocalRemoteServer.Callback {
            override fun handle(
                method: String,
                path: String,
                query: Map<String, String>,
                body: String
            ): LocalRemoteServer.ApiResponse = remoteApi(method, path, query, body)

            override fun statusJson(): String {
                try {
                    val json = JSONObject()
                    val now = if (orderQueue!!.isEmpty()) null else orderQueue.get(0)
                    json.put("ok", true)
                    json.put("current", if (now == null) "" else now.displayTitle())
                    json.put("queueSize", orderQueue.size)
                    json.put("historySize", sangHistory.size)
                    json.put("playing", player?.isPlaying == true)
                    json.put("score", scoreSummary())
                    json.put("vocal", if (originalVocal) "原唱" else "伴唱")
                    json.put("vocalChannelMode", vocalChannelMode)
                    json.put("singMode", singMode)
                    json.put("remoteUrl", remoteUrl())
                    return json.toString()
                } catch (e: Exception) {
                    return errorJson(e)
                }
            }

            override fun catalogJson(): String {
                try {
                    val array = JSONArray()
                    for (song in library.allSongs()) {
                        if (isVisibleSong(song)) array.put(songJson(song))
                    }
                    val json = JSONObject()
                    json.put("ok", true)
                    json.put("songs", array)
                    return json.toString()
                } catch (e: Exception) {
                    return errorJson(e)
                }
            }

            override fun queueJson(): String {
                try {
                    val queue = JSONArray()
                    for (song in orderQueue!!) queue.put(songJson(song))
                    val history = JSONArray()
                    for (song in sangHistory) history.put(songJson(song))
                    val json = JSONObject()
                    json.put("ok", true)
                    json.put("queue", queue)
                    json.put("history", history)
                    return json.toString()
                } catch (e: Exception) {
                    return errorJson(e)
                }
            }

            override fun rankJson(): String {
                try {
                    val ranked: MutableList<Song?> = ArrayList<Song?>()
                    for (song in library.allSongs()) {
                        if (isVisibleSong(song)) ranked.add(song)
                    }
                    Collections.sort<Song?>(
                        ranked,
                        Comparator { a: Song?, b: Song? ->
                            Integer.compare(
                                b!!.playCount,
                                a!!.playCount
                            )
                        })
                    val array = JSONArray()
                    var i = 0
                    while (i < ranked.size && i < 80) {
                        array.put(songJson(ranked.get(i)!!))
                        i++
                    }
                    val json = JSONObject()
                    json.put("ok", true)
                    json.put("songs", array)
                    return json.toString()
                } catch (e: Exception) {
                    return errorJson(e)
                }
            }

            override fun filterJson(type: String?, value: String?): String {
                try {
                    val t = if (type == null) "" else type.trim { it <= ' ' }
                    val v = if (value == null) "" else value.trim { it <= ' ' }
                    val array = JSONArray()
                    for (song in library.allSongs()) {
                        if (!isVisibleSong(song)) continue
                        val compare = if ("language" == t) song.language else song.category
                        if (v.isEmpty() || v == compare) array.put(songJson(song))
                        if (array.length() >= 120) break
                    }
                    val json = JSONObject()
                    json.put("ok", true)
                    json.put("songs", array)
                    return json.toString()
                } catch (e: Exception) {
                    return errorJson(e)
                }
            }

            override fun favoritesJson(): String {
                try {
                    val array = JSONArray()
                    for (song in library.allSongs()) {
                        if (isVisibleSong(song) && store.isFavorite(song)) array.put(songJson(song))
                    }
                    val json = JSONObject()
                    json.put("ok", true)
                    json.put("songs", array)
                    return json.toString()
                } catch (e: Exception) {
                    return errorJson(e)
                }
            }

            override fun search(query: String?): String {
                try {
                    val array = JSONArray()
                    val q = if (query == null) "" else query.lowercase().trim { it <= ' ' }
                    var count = 0
                    for (song in library.allSongs()) {
                        if (!isVisibleSong(song)) continue
                        val text =
                            (song.title + " " + song.singer + " " + song.category + " " + song.language + " " + song.pinyin).lowercase()
                        if (q.isEmpty() || text.contains(q)) {
                            array.put(songJson(song))
                            if (++count >= 80) break
                        }
                    }
                    val json = JSONObject()
                    json.put("ok", true)
                    json.put("songs", array)
                    return json.toString()
                } catch (e: Exception) {
                    return errorJson(e)
                }
            }

            override fun order(query: String?): String {
                val q = if (query == null) "" else query.lowercase().trim { it <= ' ' }
                if (q.isEmpty()) return "{\"ok\":false,\"error\":\"missing q\"}"
                for (song in library.allSongs()) {
                    if (store.hiddenSongIds.contains(stableId(song))) continue
                    val text = (song.title + " " + song.singer + " " + song.pinyin).lowercase()
                    if (text.contains(q)) {
                        main.post(Runnable { handleSong(song) })
                        return "{\"ok\":true,\"message\":\"ordered\"}"
                    }
                }
                return "{\"ok\":false,\"error\":\"song not found\"}"
            }

            override fun command(action: String?, params: Map<String, String>): String {
                val a = if (action == null) "" else action
                if ("next" == a) main.post(Runnable { playNext() })
                else if ("pause" == a) main.post(Runnable { togglePlay() })
                else if ("replay" == a) main.post(Runnable { replay() })
                else if ("vocal" == a) {
                    val mode = params.get("mode")
                    main.post(Runnable {
                        originalVocal = "original" == mode || "原唱" == mode
                        applyPlaybackMode()
                        saveState()
                        status!!.setText(if (originalVocal) "远程切换原唱" else "远程切换伴唱")
                    })
                } else if ("singmode" == a) {
                    val mode = params.get("mode")
                    if (mode == null || mode.trim { it <= ' ' }
                            .isEmpty()) return "{\"ok\":false,\"error\":\"missing mode\"}"
                    main.post(Runnable { setSingMode(mode.trim { it <= ' ' }) })
                } else if ("channel" == a) {
                    val mode = params.get("mode")
                    if (mode == null || mode.trim { it <= ' ' }
                            .isEmpty()) return "{\"ok\":false,\"error\":\"missing mode\"}"
                    main.post(Runnable { setVocalChannelMode(mode.trim { it <= ' ' }) })
                } else if ("light" == a) {
                    val mode = params.get("mode")
                    if (mode == null || mode.trim { it <= ' ' }
                            .isEmpty()) return "{\"ok\":false,\"error\":\"missing mode\"}"
                    main.post(Runnable {
                        lightMode = mode
                        saveState()
                        status!!.setText("远程灯光：" + mode)
                    })
                } else if ("marquee" == a) {
                    val text = params.get("text")
                    main.post(Runnable {
                        if (text != null && !text.trim { it <= ' ' }.isEmpty()) marqueeText =
                            text.trim { it <= ' ' }
                        marqueeEnabled = true
                        saveState()
                        status!!.setText("远程跑马灯：" + marqueeText)
                    })
                } else if ("broadcast" == a) {
                    val text = params.get("text")
                    if (text == null || text.trim { it <= ' ' }
                            .isEmpty()) return "{\"ok\":false,\"error\":\"missing text\"}"
                    main.post(Runnable { sendTableBroadcast(text.trim { it <= ' ' }) })
                } else if ("status" == a) {
                    return statusJson()
                } else if ("cancel_downloads" == a) {
                    main.post(Runnable { cancelAllDownloads() })
                } else if ("clear_queue" == a) {
                    main.post(Runnable {
                        orderQueue!!.clear()
                        saveState()
                        renderQueue()
                        status!!.setText("远程清空已点")
                    })
                } else if ("remove_queue" == a) {
                    val index = params.get("index")
                    try {
                        val pos = index!!.toInt()
                        main.post(Runnable {
                            if (pos >= 0 && pos < orderQueue!!.size) {
                                val removed = orderQueue.removeAt(pos)
                                saveState()
                                renderQueue()
                                status!!.setText("远程删除已点：" + removed.title)
                            }
                        })
                    } catch (e: Exception) {
                        return "{\"ok\":false,\"error\":\"bad index\"}"
                    }
                } else if ("favorite" == a) {
                    val q = params.get("q")
                    if (q == null || q.trim { it <= ' ' }
                            .isEmpty()) return "{\"ok\":false,\"error\":\"missing q\"}"
                    for (song in library.allSongs()) {
                        if (!isVisibleSong(song)) continue
                        val text = (song.title + " " + song.singer + " " + song.pinyin).lowercase()
                        if (text.contains(q.lowercase())) {
                            main.post(Runnable {
                                store.toggleFavorite(song)
                                saveState()
                                status!!.setText("远程收藏：" + song.title)
                            })
                            return "{\"ok\":true,\"message\":\"favorite toggled\"}"
                        }
                    }
                    return "{\"ok\":false,\"error\":\"song not found\"}"
                } else if ("hide" == a) {
                    val q = params.get("q")
                    if (q == null || q.trim { it <= ' ' }
                            .isEmpty()) return "{\"ok\":false,\"error\":\"missing q\"}"
                    for (song in library.allSongs()) {
                        val text = (song.title + " " + song.singer + " " + song.pinyin).lowercase()
                        if (text.contains(q.lowercase())) {
                            main.post(Runnable { removeSong(song) })
                            return "{\"ok\":true,\"message\":\"hidden\"}"
                        }
                    }
                    return "{\"ok\":false,\"error\":\"song not found\"}"
                } else return "{\"ok\":false,\"error\":\"unknown command\"}"
                return "{\"ok\":true}"
            }
        })
    }

    private fun remoteApi(
        method: String,
        path: String,
        query: Map<String, String>,
        body: String
    ): LocalRemoteServer.ApiResponse {
        return try {
            when {
                method == "GET" && path == "/api/v1/state" -> remoteOk(remoteStateJson())
                method == "GET" && path == "/api/v1/mic" -> remoteOk(remoteMicJson())
                // 运行日志：手机端排查"车机侧到底发生了什么"的唯一入口
                // （崩溃堆栈、服务起没起、话筒客户端接入/掉线全在里面）
                method == "GET" && path == "/api/v1/logs" ->
                    remoteOkRaw(KtvLog.recentJson((query["n"]?.toIntOrNull() ?: 300).coerceIn(10, 1000)))
                method == "GET" && path == "/api/v1/songs" -> remoteOk(remoteSearchJson(query))
                method == "GET" && path == "/api/v1/hot" -> remoteOk(remoteHotJson(query))
                method == "GET" && path == "/api/v1/queue" -> remoteOk(remoteQueueJson())
                method == "POST" && path == "/api/v1/queue" -> remoteOrder(JSONObject(body))
                method == "POST" && path == "/api/v1/player/actions" -> remotePlayerAction(JSONObject(body))
                method == "PATCH" && path == "/api/v1/player/audio" -> remoteAudio(JSONObject(body))
                path.startsWith("/api/v1/queue/") && method in arrayOf("PATCH", "DELETE") -> {
                    remoteQueueMutation(method, URLDecoder.decode(path.substringAfterLast('/'), "UTF-8"))
                }
                else -> remoteError(404, "NOT_FOUND", "接口不存在")
            }
        } catch (e: Exception) {
            remoteError(400, "BAD_REQUEST", e.message ?: "请求参数错误")
        }
    }

    private fun remoteStateJson(): JSONObject {
        val now = currentSong ?: orderQueue?.firstOrNull()
        val next = orderQueue?.firstOrNull { now == null || stableId(it) != stableId(now) }
        return JSONObject().apply {
            put("current", now?.let { songJson(it) } ?: JSONObject.NULL)
            put("next", next?.let { songJson(it) } ?: JSONObject.NULL)
            put("queueSize", orderQueue?.size ?: 0)
            put("playing", player?.isPlaying == true)
            put("vocalMode", if (originalVocal) "original" else "accompany")
            put("musicVolume", musicVolume)
            put("micVolume", micVolume)
        }
    }

    private fun remoteSearchJson(query: Map<String, String>): JSONObject {
        val keyword = query["q"].orEmpty().trim()
        val type = query["type"].orEmpty().lowercase(Locale.ROOT).let { if (it == "song") "song" else "singer" }
        val page = (query["page"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val pageSize = (query["pageSize"]?.toIntOrNull() ?: 20).coerceIn(1, 50)
        val offset = (page - 1) * pageSize
        val songs = if (library.muse.isAvailable() && keyword.isNotBlank()) {
            if (type == "singer") library.muse.searchSongsBySingerKeyword(keyword, offset, pageSize + 1)
            else library.muse.searchSongs(keyword, offset, pageSize + 1)
        } else {
            library.allSongs().asSequence().filter { song ->
                isVisibleSong(song) && keyword.isNotBlank() && if (type == "singer") {
                    song.singer.orEmpty().contains(keyword, true)
                } else {
                    (song.title + " " + song.pinyin).contains(keyword, true)
                }
            }.drop(offset).take(pageSize + 1).toMutableList()
        }
        val hasMore = songs.size > pageSize
        val pageSongs = songs.take(pageSize)
        val array = JSONArray()
        pageSongs.forEach { song ->
            remoteSongCache[stableId(song)] = song
            array.put(songJson(song))
        }
        while (remoteSongCache.size > 500) remoteSongCache.remove(remoteSongCache.keys.first())
        return JSONObject().put("songs", array).put("page", page).put("pageSize", pageSize)
            .put("type", type).put("hasMore", hasMore)
    }

    private fun remoteHotJson(query: Map<String, String>): JSONObject {
        val page = (query["page"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val pageSize = (query["pageSize"]?.toIntOrNull() ?: 10).coerceIn(1, 30)
        val offset = (page - 1) * pageSize
        val songs = if (library.muse.isAvailable()) {
            library.muse.hotSongs(offset, pageSize + 1)
        } else {
            library.allSongs().asSequence().filter(::isVisibleSong)
                .sortedByDescending { it.playCount }.drop(offset).take(pageSize + 1).toMutableList()
        }
        val hasMore = songs.size > pageSize
        val array = JSONArray()
        songs.take(pageSize).forEach { song ->
            remoteSongCache[stableId(song)] = song
            array.put(songJson(song))
        }
        while (remoteSongCache.size > 500) remoteSongCache.remove(remoteSongCache.keys.first())
        return JSONObject().put("songs", array).put("page", page).put("pageSize", pageSize)
            .put("hasPrevious", page > 1).put("hasMore", hasMore)
    }

    private fun remoteQueueJson(): JSONObject {
        val array = JSONArray()
        orderQueue?.toList()?.forEach { array.put(songJson(it)) }
        return JSONObject().put("queue", array)
    }

    private fun remoteOrder(json: JSONObject): LocalRemoteServer.ApiResponse {
        val id = json.optString("songId").trim()
        if (id.isEmpty()) return remoteError(400, "MISSING_SONG_ID", "缺少歌曲 ID")
        val song = remoteSongCache[id] ?: library.allSongs().firstOrNull { stableId(it) == id }
            ?: return remoteError(404, "SONG_NOT_FOUND", "歌曲不存在或搜索结果已过期")
        if (orderQueue?.any { stableId(it) == id } == true) {
            return remoteError(409, "ALREADY_QUEUED", "歌曲已在已点列表中")
        }
        main.post {
            addToQueue(song)
            remoteStateVersion++
            saveState()
        }
        return remoteOk(JSONObject().put("message", "点歌成功"), 201)
    }

    private fun remoteQueueMutation(method: String, id: String): LocalRemoteServer.ApiResponse {
        val song = orderQueue?.firstOrNull { stableId(it) == id }
            ?: return remoteError(404, "QUEUE_ITEM_NOT_FOUND", "已点歌曲不存在")
        if (stableId(currentSong ?: song) == id && currentSong != null) {
            return remoteError(409, "CURRENT_SONG", "正在播放的歌曲不能修改")
        }
        main.post {
            if (method == "PATCH") moveQueuedSongToNext(song) else deleteQueuedSong(song)
            remoteStateVersion++
        }
        return remoteOk(JSONObject().put("message", if (method == "PATCH") "已置顶" else "已删除"))
    }

    private fun remotePlayerAction(json: JSONObject): LocalRemoteServer.ApiResponse {
        val action = json.optString("action")
        if (action !in arrayOf("pause", "resume", "next", "replay")) {
            return remoteError(400, "BAD_ACTION", "不支持的播放操作")
        }
        main.post {
            when (action) {
                "pause" -> if (player?.isPlaying == true) togglePlay()
                "resume" -> if (player?.isPlaying != true) togglePlay()
                "next" -> playNext()
                "replay" -> replay()
            }
            remoteStateVersion++
        }
        return remoteOk(JSONObject().put("message", "操作已执行"))
    }

    private fun remoteAudio(json: JSONObject): LocalRemoteServer.ApiResponse {
        if (!json.has("vocalMode") && !json.has("musicVolume") && !json.has("micVolume")) {
            return remoteError(400, "MISSING_AUDIO_VALUE", "缺少音频参数")
        }
        val vocal = json.optString("vocalMode", "")
        if (vocal.isNotEmpty() && vocal !in arrayOf("original", "accompany")) {
            return remoteError(400, "BAD_VOCAL_MODE", "原伴唱参数无效")
        }
        val music = if (json.has("musicVolume")) json.optInt("musicVolume", -1) else null
        val mic = if (json.has("micVolume")) json.optInt("micVolume", -1) else null
        if ((music != null && music !in 0..100) || (mic != null && mic !in 0..100)) {
            return remoteError(400, "BAD_VOLUME", "音量必须在 0 到 100 之间")
        }
        main.post {
            if (vocal.isNotEmpty()) originalVocal = vocal == "original"
            music?.let { musicVolume = it }
            mic?.let {
                micVolume = it
                // 同一个"麦克风音量"同时管车机麦与手机话筒 —— K 歌里人声必须能
                // 单独调：伴奏压太大听不清唱的，人声太大又盖过伴奏。
                micServer.setVolume(it)
            }
            applyPlaybackMode()
            playerController?.setVocalMode(originalVocal)
            saveState()
            remoteStateVersion++
        }
        return remoteOk(JSONObject().put("message", "音频设置已更新"))
    }

    private fun remoteOk(data: JSONObject, statusCode: Int = 200): LocalRemoteServer.ApiResponse {
        data.put("ok", true).put("stateVersion", remoteStateVersion)
        return LocalRemoteServer.ApiResponse(statusCode, data.toString())
    }

    /** 已经是 JSON 文本的响应（如日志接口），补上统一字段后原样返回。 */
    private fun remoteOkRaw(body: String, statusCode: Int = 200): LocalRemoteServer.ApiResponse {
        val json = runCatching { JSONObject(body) }
            .getOrElse { JSONObject().put("raw", body) }
        json.put("ok", true).put("stateVersion", remoteStateVersion)
        return LocalRemoteServer.ApiResponse(statusCode, json.toString())
    }

    private fun remoteError(statusCode: Int, code: String, message: String): LocalRemoteServer.ApiResponse =
        LocalRemoteServer.ApiResponse(statusCode, JSONObject().put("ok", false).put("code", code)
            .put("error", message).put("stateVersion", remoteStateVersion).toString())

    private fun errorJson(e: Exception): String {
        return "{\"ok\":false,\"error\":\"" + e.message.toString().replace("\"", "'") + "\"}"
    }

    @Throws(Exception::class)
    private fun songJson(song: Song): JSONObject {
        val item = JSONObject()
        item.put("id", stableId(song))
        item.put("title", song.title)
        item.put("singer", song.singer)
        item.put("category", song.category)
        item.put("language", song.language)
        item.put("remote", song.remote)
        item.put("favorite", store.isFavorite(song))
        item.put("playCount", song.playCount)
        item.put("hasOriginal", hasUrl(song.originalUrl) || hasUrl(song.originalPath))
        item.put("hasAccompany", hasUrl(song.accompanyUrl) || hasUrl(song.accompanyPath))
        item.put("hasLyric", hasUrl(song.lyricUrl) || hasUrl(song.lyricPath))
        return item
    }

    private fun qrBitmap(text: String, size: Int): Bitmap {
        try {
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            return bitmap
        } catch (e: Exception) {
            val fallback = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            fallback.eraseColor(Color.WHITE)
            return fallback
        }
    }

    private fun remoteUrl(): String {
        if (!remoteServer.isRunning() || remoteServer.port() <= 0) return ""
        val overrideHost = Settings.Global.getString(contentResolver, "maidong_remote_host")
            ?.takeIf { LocalRemoteServer.isPrivateIpv4(it) }
        val host = overrideHost ?: remoteServer.address() ?: return ""
        return "http://$host:${remoteServer.port()}/mobile"
    }

    /** EasyTier 虚拟网卡名特征：PC/车机/手机都装 EasyTier 后走虚拟局域网，这是扫码点歌最稳的通道，优先级最高。 */
    private val easyTierIfaceHints = listOf("et_", "tun", "easytier")

    /** 热点/SoftAP 网卡名特征：手机连上车机热点后能访问的就是它（含蓝牙网络共享 bt0）。 */
    private val hotspotIfaceHints = listOf("wlan1", "ap0", "ap1", "ap2", "softap", "swlan", "wl1", "rndis", "usb", "eth", "bt0", "bt-pan", "bluetooth")

    /** 蜂窝数据网卡名特征：手机扫码连不上，优先级最低。 */
    private val cellularIfaceHints = listOf("rmnet", "ccmni", "ccm", "pdp", "wwan")

    /**
     * 取"手机连上车机热点后能访问"的本机 IP。
     * 车上同时存在 4G(rmnet,10.x) 与热点 AP(192.168.x.x) 两张网卡，
     * 必须优先热点那张，否则二维码里的地址手机根本连不上（扫码点歌在车上的关键）。
     * 优先级：热点AP网卡 > 192.168.x.x 局域网 > 其它内网IP。
     */
    private fun localIp(): String = localIpsInOrder().firstOrNull() ?: "127.0.0.1"

    /**
     * 列出本机所有可供手机访问的内网 IP，按"手机最可能连得上"排序：
     * 热点AP网卡 > 192.168.x.x > 其它内网IP(4G等)。
     * 二维码会把整串地址带给手机，手机端按序自动试探，连"选错 IP"也不再是问题。
     */
    private fun localIpsInOrder(): List<String> {
        val easyTierIps = ArrayList<String>()
        val hotspotIps = ArrayList<String>()
        val lanIps = ArrayList<String>()
        val otherIps = ArrayList<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val network = interfaces.nextElement()
                val name = (network.name ?: "").lowercase(Locale.ROOT)
                val isEasyTier = easyTierIfaceHints.any { name.contains(it) }
                val isHotspot = !isEasyTier && hotspotIfaceHints.any { name.contains(it) }
                val isCellular = !isEasyTier && !isHotspot && cellularIfaceHints.any { name.contains(it) }
                val addresses = network.getInetAddresses()
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    val ip = address.hostAddress ?: continue
                    if (address.isLoopbackAddress() || ip.indexOf(':') >= 0) continue
                    if (!LocalRemoteServer.isPrivateIpv4(ip)) continue
                    val bucket = when {
                        isEasyTier -> easyTierIps
                        isHotspot -> hotspotIps
                        isCellular -> otherIps
                        ip.startsWith("192.168.") -> lanIps
                        else -> otherIps
                    }
                    if (!bucket.contains(ip)) bucket.add(ip)
                }
            }
        } catch (ignored: Exception) {
        }
        // EasyTier 虚拟网卡优先：装了 EasyTier 的手机即使不在同一物理网络也能访问这个IP。
        return easyTierIps + hotspotIps + lanIps + otherIps
    }

    private fun showScoreDialog() {
        val box = LinearLayout(this)
        box.setOrientation(LinearLayout.VERTICAL)
        box.addView(label("当前：" + (if (scoreEnabled) "已开启" else "已关闭"), 20, Color.WHITE))
        box.addView(label("最近：" + scoreSummary(), 20, Color.LTGRAY))
        box.addView(sectionTitle("评分记录"))
        val list = listView()
        val adapter = tvAdapter()
        if (store.scoreHistory.isEmpty()) adapter.add("暂无评分记录")
        else adapter.addAll(store.scoreHistory)
        list.setAdapter(adapter)
        box.addView(list, LinearLayout.LayoutParams(-1, dp(260)))
        AlertDialog.Builder(this)
            .setTitle("演唱评分")
            .setView(box)
            .setPositiveButton(
                if (scoreEnabled) "关闭评分" else "开启评分",
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    scoreEnabled = !scoreEnabled
                    saveState()
                })
            .setNegativeButton("确定", null)
            .create().showForTv()
    }

    private fun showRecordings() {
        setPage("录音")
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(dp(280), -1))
        left.addView(sectionTitle("录音操作"))
        left.addView(
            button(
                if (recorder == null) "开始录音" else "停止录音",
                View.OnClickListener { v: View? -> toggleRecording() })
        )
        left.addView(button("刷新列表", View.OnClickListener { v: View? -> showRecordings() }))
        left.addView(
            button(
                if (recordEnabled) "录音功能：开" else "录音功能：关",
                View.OnClickListener { v: View? ->
                    recordEnabled = !recordEnabled
                    if (!recordEnabled) stopRecording(true)
                    saveState()
                    showRecordings()
                })
        )
        val right = panel()
        content!!.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        right.addView(sectionTitle("录音文件"))
        val list = listView()
        val adapter = tvAdapter()
        val files = recordDir().listFiles()
        if (files != null) {
            val values: MutableList<File> = ArrayList<File>()
            for (file in files) if (file.isFile()) values.add(file)
            Collections.sort<File?>(
                values,
                Comparator { a: File?, b: File? ->
                    b!!.lastModified().compareTo(a!!.lastModified())
                })
            for (file in values) adapter.add(file.getName() + "    " + formatSize(file.length()))
        }
        if (adapter.isEmpty()) adapter.add("暂无录音")
        list.setAdapter(adapter)
        right.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun showMarqueeSettings() {
        val box = LinearLayout(this)
        box.setOrientation(LinearLayout.VERTICAL)
        val input = EditText(this)
        input.setSingleLine(true)
        input.setText(marqueeText)
        input.setHint("跑马灯文字")
        box.addView(label("当前：" + (if (marqueeEnabled) "已开启" else "已关闭"), 20, Color.WHITE))
        box.addView(input, LinearLayout.LayoutParams(-1, dp(58)))
        AlertDialog.Builder(this)
            .setTitle("跑马灯设置")
            .setView(box)
            .setPositiveButton(
                "保存",
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    marqueeText = input.getText().toString().trim { it <= ' ' }
                    if (marqueeText!!.isEmpty()) marqueeText = "欢迎使用风火轮"
                    saveState()
                    toast("跑马灯已保存")
                })
            .setNegativeButton(
                if (marqueeEnabled) "关闭" else "开启",
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    marqueeEnabled = !marqueeEnabled
                    saveState()
                })
            .create().showForTv()
    }

    private fun showDisco() {
        setPage("迪斯科")
        content!!.removeAllViews()
        val panel = panel()
        content!!.addView(panel, LinearLayout.LayoutParams(-1, -1))
        panel.addView(sectionTitle("灯光 / 迪斯科"))
        val modes =
            arrayOf<String?>("自动", "开房灯", "关房灯", "暂停灯", "静音灯", "热舞", "柔和", "全关")
        val grid = GridLayout(this)
        grid.setColumnCount(4)
        panel.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        for (mode in modes) {
            addTile(
                grid,
                mode,
                if (mode == lightMode) "当前模式" else "切换灯效",
                View.OnClickListener { v: View? ->
                    lightMode = (v as TextView).getText().toString().split("\n".toRegex())
                        .dropLastWhile { it.isEmpty() }.toTypedArray()[0]
                    if ("热舞" == lightMode) atmosphere = "派对"
                    if ("柔和" == lightMode) atmosphere = "柔和"
                    saveState()
                    showDisco()
                })
        }
        panel.addView(
            label(
                "本地版记录灯效状态；接入实际灯控盒时可把这些模式映射到串口、HTTP 或红外命令。",
                18,
                Color.LTGRAY
            )
        )
    }

    private fun showLocalMovies() {
        setPage("本地影片")
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(dp(300), -1))
        left.addView(sectionTitle("影片操作"))
        left.addView(button("扫描影片目录", View.OnClickListener { v: View? -> showLocalMovies() }))
        left.addView(label("目录：/sdcard/MDKTV_Native/movies 或 U盘/movies", 18, Color.LTGRAY))

        val right = panel()
        content!!.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        right.addView(sectionTitle("用户 MV / 本地影片"))
        val list = listView()
        val adapter = tvAdapter()
        val movies = localMovies()
        for (file in movies) adapter.add(file.getName() + "    " + formatSize(file.length()))
        if (adapter.isEmpty()) adapter.add("暂无影片")
        list.setAdapter(adapter)
        list.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: kotlin.Long ->
            if (pos >= 0 && pos < movies.size) playMovie(movies.get(pos))
        })
        right.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun networkSummary(): String {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager?
        val info = if (cm == null) null else cm.getActiveNetworkInfo()
        val wifi = getApplicationContext().getSystemService(WIFI_SERVICE) as WifiManager?
        val wifiState =
            if (wifi == null) "未知" else (if (wifi.isWifiEnabled()) "已开启" else "已关闭")
        return ("连接：" + (if (info != null && info.isConnected()) "已连接" else "未连接")
                + "\n类型：" + (if (info == null) "未知" else info.getTypeName())
                + "\nWiFi：" + wifiState
                + "\nIP：" + localIp())
    }

    private fun testCatalogSource() {
        val url: String = prefs()!!.getString(KEY_CATALOG_URL, "")!!
        if (url.trim { it <= ' ' }.isEmpty()) {
            showCatalogDialog()
            return
        }
        busy(true, "正在测试曲库源...")
        io.execute(Runnable {
            try {
                val array = CatalogClient().fetch(url)
                main.post(Runnable { busy(false, "曲库源可用：" + array.length() + " 首") })
            } catch (e: Exception) {
                main.post(Runnable { busy(false, "曲库源不可用：" + e.message) })
            }
        })
    }

    private fun openTool(tool: String?) {
        if ("点歌台" == tool) showSongs("全部")
        else if ("语种点歌" == tool) showLanguages()
        else if ("字数点歌" == tool) showWordCounts()
        else if ("歌星分类" == tool) showSingers()
        else if ("下载管理" == tool) showDownloads()
        else if ("U盘加歌" == tool) showUdisk()
        else if ("用户影片" == tool) showLocalMovies()
        else if ("灯光迪斯科" == tool) showDisco()
        else if ("手机点歌" == tool) showMobileRemoteDialog()
        else if ("手机话筒" == tool) showMobileMicDialog()
        else if ("运行日志" == tool) showLogViewer()
        else if ("录音评分" == tool) showScoreDialog()
    }

    private fun sendTableBroadcast(text: String?) {
        if (!tableBroadcastEnabled) {
            toast("包房广播已关闭")
            return
        }
        var value: String? = if (text == null) "" else text.trim { it <= ' ' }
        if (value!!.isEmpty()) value = tableBroadcastText
        if (value == null || value.trim { it <= ' ' }.isEmpty()) value = "欢迎光临"
        tableBroadcastText = value
        marqueeEnabled = true
        marqueeText = value
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        store.broadcastHistory.add(0, stamp + "  " + value)
        while (store.broadcastHistory.size > 100) store.broadcastHistory.removeAt(store.broadcastHistory.size - 1)
        saveState()
        status!!.setText("包房广播：" + value + "（" + tableBroadcastSeconds + "秒）")
        toast("广播已发送")
    }

    private fun applyScreenBrightness() {
        val attrs = getWindow().getAttributes()
        attrs.screenBrightness = screenBrightness / 100f
        getWindow().setAttributes(attrs)
    }

    private fun removeMissingQueueItems() {
        val before = orderQueue!!.size
        for (i in orderQueue.indices.reversed()) {
            val song = orderQueue.get(i)
            val localPath = song.path
            if (!song.remote && (localPath.isNullOrEmpty() || !File(localPath).exists())) {
                orderQueue.removeAt(i)
            }
        }
        saveState()
        toast("已清理：" + (before - orderQueue.size) + " 首")
    }

    private fun toggleRecording() {
        if (!recordEnabled) {
            toast("录音功能已关闭")
            return
        }
        if (recorder == null) startRecording()
        else stopRecording(true)
    }

    private var lastMicRestartAt = 0L

    /**
     * 确保麦克风输入通道被本应用真正占用（外接麦才能被路由到车机喇叭）。
     * 车机上通道常被雷石(ThunderMicSdk)等后台服务抢占，MicActivator 内部会持续重试；
     * 这里在每首歌准备完成时再主动重建一次，加快抢占速度。
     * 用 3 秒节流防止连续多首歌 prepare 时反复重建线程。
     */
    private fun ensureMicActivated() {
        if (Build.VERSION.SDK_INT >= 23
            && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            return // 未授权麦克风，激活器无法工作
        }
        // 录音中绝不能抢麦克风：recorder 已独占通道，此时 restart() 会把激活器拉起来直接搞挂录音
        if (recorder != null) return
        val now = System.currentTimeMillis()
        if (now - lastMicRestartAt < 3000L) return
        if (micActivator.isActive) return // 已真实占用
        lastMicRestartAt = now
        // 注意：MicActivator.start() 内部是 if(running) return，录音置 paused 后调它不会恢复；
        // 抢占失败时 record 为 null 但线程还活着，也调不动 start()。统一走 restart()。
        runCatching { micActivator.restart() }
        Log.i(TAG, "mic ensure: isActive=${micActivator.isActive} -> restart()")
    }

    private fun startRecording() {
        if (Build.VERSION.SDK_INT >= 23
            && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf<String>(Manifest.permission.RECORD_AUDIO), 11)
            return
        }
        try {
            val dir = recordDir()
            if (!dir.exists()) dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
            val now = if (orderQueue!!.isEmpty()) null else orderQueue.get(0)
            val name =
                (if (now == null) "ktv" else safeFileName(now.title + "-" + now.singer)) + "-" + stamp + ".m4a"
            currentRecording = File(dir, name)
            recorder = MediaRecorder()
            // VOICE_COMMUNICATION：平台自动挂 AEC(回声消除)+降噪+自动增益，
            // 消除喇叭伴奏串回麦克风造成的啸叫/反馈，录出干净人声（类似打电话的语音处理）。
            recorder!!.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            recorder!!.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder!!.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder!!.setOutputFile(currentRecording!!.getAbsolutePath())
            recorder!!.prepare()
            // MediaRecorder 与激活器共用麦克风通道，录音前先暂停激活器
            micActivator.pause()
            recorder!!.start()
            toast("开始录音")
            status!!.setText("正在录音：" + currentRecording!!.getName())
            if ("首页" == currentPage) showHome()
        } catch (e: Exception) {
            stopRecording(false)
            toast("录音启动失败：" + e.message)
        }
    }

    private fun stopRecording(notify: Boolean) {
        if (recorder == null) return
        try {
            recorder!!.stop()
        } catch (ignored: Exception) {
        }
        try {
            recorder!!.release()
        } catch (ignored: Exception) {
        }
        recorder = null
        micActivator.resume() // 录完恢复麦克风通道占用，外接麦继续可用
        if (notify && currentRecording != null) toast("录音已保存：" + currentRecording!!.getName())
        currentRecording = null
        if ("录音" == currentPage) showRecordings()
        else if ("首页" == currentPage) showHome()
    }

    /**
     * 记录演唱评分（v1.2.6 起为真人评分）。
     *
     * 旧实现是 `60 + (stableId + 分钟数).hashCode() % 40` —— 纯随机数，
     * 跟唱成什么样没有任何关系，属于自欺欺人。现在由 [VocalScorer] 按
     * 歌词时间轴 + 话筒 PCM 真实统计；**没有检测到人声时记录"未检测到演唱"，
     * 不再编造分数**。
     */
    private fun recordScore(song: Song?) {
        if (!scoreEnabled || song == null) {
            vocalScorer.abort()
            return
        }
        val result = vocalScorer.finish()
        if (result == null) {
            store.scoreHistory.add(0, "未检测到演唱    " + song.displayTitle())
            KtvLog.i("Score", "no vocal data: ${song.displayTitle()}")
        } else {
            store.lastScore = result.score
            store.lastScoreSong = song.displayTitle()
            store.scoreHistory.add(0, result.score.toString() + " 分    " + song.displayTitle())
            KtvLog.i(
                "Score",
                "song=${song.displayTitle()} score=${result.score} " +
                    "lines=${result.linesSung}/${result.linesTotal} " +
                    "coverage=${"%.2f".format(result.coverage)} " +
                    "stability=${"%.2f".format(result.stability)} singer=${result.singerId}",
            )
        }
        while (store.scoreHistory.size > 100) store.scoreHistory.removeAt(store.scoreHistory.size - 1)
    }

    private fun scoreSummary(): String {
        if (store.lastScore <= 0 || store.lastScoreSong == null || store.lastScoreSong.isEmpty()) return "暂无评分"
        return store.lastScore.toString() + " 分  " + store.lastScoreSong
    }

    // ===== 音频焦点（v1.2.6）=====

    /** 因焦点丢失暂停播放（来电 / 被其它播放器抢占）。 */
    private fun pauseForFocusLoss(notice: String?) {
        if (!playWhenPrepared || playbackPreparing) return
        player?.pause()
        pauseVocalIfReady()
        playWhenPrepared = false
        if (textPlayPause != null) textPlayPause.setText("播放")
        updateBottomBar(currentSong, false)
        if (notice != null) showPlaybackModeNotice(notice, true)
    }

    private fun requestAudioFocusIfNeeded() {
        val am = audioManager ?: return
        runCatching {
            // targetSdk 25：legacy API 在所有版本行为一致；deprecation 仅编译期提示
            am.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        runCatching { am.abandonAudioFocus(audioFocusListener) }
    }

    // ===== 均衡器接线（v1.2.6）=====

    /**
     * 启动时恢复 EQ 设置并对齐设备频段。
     *
     * KtvEqualizer 自项目初期就是死代码（316 行完整实现，从未被调用）——
     * 这里把它正式接进播放链路：探测设备能力 → 恢复用户设置 → 之后由
     * lyricTicker 周期性 syncSessions 挂到真实播放会话上。
     */
    private fun initEqualizer() {
        io.execute {
            runCatching {
                val caps = KtvEqualizer.capabilities(this)
                KtvEqualizer.restore(
                    store.eqEnabled,
                    store.eqPreset,
                    parseEqLevels(store.eqLevels),
                    store.eqGlobalMix,
                )
                KtvEqualizer.alignToDevice(caps)
                KtvLog.i(
                    "EQ",
                    "init enabled=${KtvEqualizer.enabled} preset=${KtvEqualizer.preset} " +
                        "bands=${caps.bandCount} sessions=${KtvEqualizer.attachedSessionCount()}",
                )
            }.onFailure { KtvLog.w("EQ", "init failed: ${it.message}") }
        }
    }

    private fun parseEqLevels(csv: String): FloatArray {
        if (csv.isBlank()) return FloatArray(0)
        return runCatching {
            csv.split(',').mapNotNull { it.trim().toFloatOrNull() }.toFloatArray()
        }.getOrDefault(FloatArray(0))
    }

    /** 话筒 PCM 旁路接评分引擎（micServer 懒初始化，这里触发并挂 tap）。 */
    private fun initVocalScoring() {
        micServer.pcmTap = { id, pcm, length -> vocalScorer.feed(id, pcm, length) }
    }

    /** 把当前歌词时间轴转成评分引擎的窗口列表；无歌词 → 整首歌一个窗口。 */
    private fun buildLyricTimeline(): List<Pair<Int, Int>> {
        val valid = lyricLines.filterNotNull()
        if (valid.isEmpty()) return listOf(0 to Int.MAX_VALUE)
        val out = ArrayList<Pair<Int, Int>>(valid.size)
        for (i in valid.indices) {
            val start = valid[i].timeMs
            val end = if (i + 1 < valid.size) valid[i + 1].timeMs else Int.MAX_VALUE
            if (end > start) out.add(start to end)
        }
        return out.ifEmpty { listOf(0 to Int.MAX_VALUE) }
    }

    /**
     * 氛围预设 → 真实 EQ 曲线。
     *
     * v1.2.6 之前"音效"按钮只改一个字符串再 toast，没有任何音频效果；
     * 现在落到 KtvEqualizer 的真实预设曲线上。
     */
    private fun applyAtmosphereEq(atmospherePreset: String) {
        val eqPreset = when (atmospherePreset) {
            "流行" -> "流行"
            "摇滚" -> "摇滚"
            "唱将" -> "舞曲"
            else -> "标准"
        }
        KtvEqualizer.setEnabled(true)
        KtvEqualizer.applyPreset(eqPreset)
        KtvLog.i("EQ", "atmosphere=$atmospherePreset -> preset=$eqPreset enabled=true")
    }

    /** 刷新调音弹窗 4 个音效按钮的选中态（白色 = 生效中）。 */
    private fun refreshAtmosphereButtons(root: View) {
        val ids = intArrayOf(R.id.btn_preset_pop, R.id.btn_preset_lyric, R.id.btn_preset_rock, R.id.btn_preset_singer)
        val presets = arrayOf("流行", "抒情", "摇滚", "唱将")
        for (i in ids.indices) {
            val b = root.findViewById<TextView?>(ids[i]) ?: continue
            val on = atmosphere == presets[i] && KtvEqualizer.enabled
            b.setTextColor(if (on) Color.WHITE else Color.parseColor("#99FFFFFF"))
        }
    }

    private fun recordDir(): File {
        return AppPaths.recordsDir
    }

    private fun localMovies(): MutableList<File> {
        val out: MutableList<File> = ArrayList<File>()
        scanMovieDir(
            AppPaths.moviesDir,
            out
        )
        val storage = File("/storage")
        val roots = storage.listFiles()
        if (roots != null) {
            for (root in roots) {
                val name = root.getName()
                if ("emulated" == name || "self" == name) continue
                scanMovieDir(File(root, "movies"), out)
                scanMovieDir(File(root, "Movies"), out)
            }
        }
        Collections.sort<File?>(
            out,
            Comparator { a: File?, b: File? ->
                a!!.getName().compareTo(b!!.getName(), ignoreCase = true)
            })
        return out
    }

    private fun localApks(): MutableList<File> {
        val out: MutableList<File> = ArrayList<File>()
        scanApkDir(AppPaths.appsDir, out)
        val storage = File("/storage")
        val roots = storage.listFiles()
        if (roots != null) {
            for (root in roots) {
                val name = root.getName()
                if ("emulated" == name || "self" == name) continue
                scanApkDir(File(root, "apps"), out)
                scanApkDir(File(root, "apk"), out)
            }
        }
        Collections.sort<File?>(
            out,
            Comparator { a: File?, b: File? ->
                b!!.getName().compareTo(a!!.getName(), ignoreCase = true)
            })
        return out
    }

    private fun scanApkDir(dir: File, out: MutableList<File>) {
        val files = dir.listFiles()
        if (files == null) return
        for (file in files) {
            if (file.isDirectory()) scanApkDir(file, out)
            else if (file.getName().lowercase().endsWith(".apk")) out.add(file)
        }
    }

    private fun installApk(apk: File) {
        try {
            val uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, "application/vnd.android.package-archive")
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (e: Exception) {
            toast("无法打开安装器：" + e.message)
        }
    }

    private fun scanMovieDir(dir: File, out: MutableList<File>) {
        val files = dir.listFiles()
        if (files == null) return
        for (file in files) {
            if (file.isDirectory()) scanMovieDir(file, out)
            else if (isMovie(file.getName())) out.add(file)
        }
    }

    private fun isMovie(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi")
                || lower.endsWith(".mov") || lower.endsWith(".ts") || lower.endsWith(".flv")
    }

    private fun playMovie(file: File) {
        if (player == null) showHome()
        try {
            releaseVocalPlayer()
            currentSong = null
            clearLyrics()
            player!!.setOnPreparedListener(OnPreparedListener { mp: MediaPlayer? ->
                player!!.start()
                status!!.setText("正在播放影片：" + file.getName())
            })
            player!!.setOnCompletionListener(OnCompletionListener { mp: MediaPlayer? ->
                status!!.setText("影片播放完成：" + file.getName())
                if (autoNext && !orderQueue!!.isEmpty()) play(orderQueue.get(0))
            })
            player!!.setVideoURI(Uri.fromFile(file))
        } catch (e: Exception) {
            toast("影片播放失败：" + e.message)
        }
    }

    private fun safeFileName(value: String?): String {
        return if (value == null) "ktv" else value.replace("[\\\\/:*?\"<>|]".toRegex(), "_")
    }

    private fun backupDir(): File {
        return AppPaths.backupDir
    }

    private fun backupConfig() {
        try {
            val dir = backupDir()
            if (!dir.exists()) dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
            copyFile(store.stateFile(), File(dir, "state-" + stamp + ".json"))
            if (library.catalogFile().exists()) copyFile(
                library.catalogFile(),
                File(dir, "catalog-" + stamp + ".json")
            )
            toast("配置已备份")
            showBackupTools()
        } catch (e: Exception) {
            toast("备份失败：" + e.message)
        }
    }

    private fun restoreConfig() {
        val dir = backupDir()
        val files = dir.listFiles()
        if (files == null) {
            toast("没有备份文件")
            return
        }
        var newestState: File? = null
        var newestCatalog: File? = null
        for (file in files) {
            if (file.getName().startsWith("state-") && file.getName().endsWith(".json")
                && (newestState == null || file.lastModified() > newestState.lastModified())
            ) newestState = file
            if (file.getName().startsWith("catalog-") && file.getName().endsWith(".json")
                && (newestCatalog == null || file.lastModified() > newestCatalog.lastModified())
            ) newestCatalog = file
        }
        val state = newestState
        val catalog = newestCatalog
        if (state == null && catalog == null) {
            toast("没有可恢复的备份")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("从备份恢复")
            .setMessage("将恢复最近的 state/catalog 备份，应用内状态会重新加载。")
            .setPositiveButton(
                "恢复",
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    try {
                        if (state != null) copyFile(state, store.stateFile())
                        if (catalog != null) copyFile(catalog, library.catalogFile())
                        store.load()
                        loadStateFromStore()
                        applyScreenBrightness()
                        refreshLibrary(Runnable {
                            toast("恢复完成")
                            showHome()
                        })
                    } catch (e: Exception) {
                        toast("恢复失败：" + e.message)
                    }
                })
            .setNegativeButton("取消", null)
            .create().showForTv()
    }

    @Throws(Exception::class)
    private fun copyFile(source: File?, target: File) {
        if (source == null || !source.exists()) return
        val parent = target.getParentFile()
        if (parent != null && !parent.exists()) parent.mkdirs()
        BufferedInputStream(FileInputStream(source)).use { `in` ->
            FileOutputStream(target, false).use { out ->
                val buffer = ByteArray(64 * 1024)
                var read: Int
                while ((`in`.read(buffer).also { read = it }) > 0) out.write(buffer, 0, read)
            }
        }
    }

    private fun applySystemMusicVolume() {
        if (audioManager == null) return
        val max = audioManager!!.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val value = max(0, min(max, (musicVolume * max) / 100))
        audioManager!!.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
    }

    private fun clamp(value: Int, min: Int, max: Int): Int {
        return max(min, min(max, value))
    }

    private fun formatSize(bytes: kotlin.Long): String {
        if (bytes > 1024L * 1024L * 1024L) return String.format(
            Locale.ROOT,
            "%.1f GB",
            bytes / 1024f / 1024f / 1024f
        )
        if (bytes > 1024L * 1024L) return String.format(
            Locale.ROOT,
            "%.1f MB",
            bytes / 1024f / 1024f
        )
        return bytes.toString() + " B"
    }

    private fun populateSingerList(adapter: ArrayAdapter<String?>, group: String?) {
        adapter.clear()
        val names: MutableList<String?> = ArrayList<String?>()
        for (song in library.allSongs()) {
            if (!isVisibleSong(song) || !singerMatchesGroup(song, group)) continue
            val name = singerName(song)
            if (!names.contains(name)) names.add(name)
        }
        Collections.sort<String?>(
            names,
            Comparator { obj: String?, str: String? -> obj!!.compareTo(str!!, ignoreCase = true) })
        if ("热门歌手" == group) {
            Collections.sort<String?>(
                names,
                Comparator { a: String?, b: String? ->
                    Integer.compare(
                        singerPlayCount(b),
                        singerPlayCount(a)
                    )
                })
        }
        adapter.addAll(names)
        adapter.notifyDataSetChanged()
        visibleSongs.clear()
        renderSongs()
        header!!.setText("歌星  " + group + "  " + names.size)
    }

    private fun singerPlayCount(singer: String?): Int {
        var count = 0
        for (song in library.allSongs()) {
            if (isVisibleSong(song) && singerName(song) == singer) count += song.playCount
        }
        return count
    }

    private fun singerMatchesGroup(song: Song, group: String?): Boolean {
        if ("全部歌手" == group) return true
        val text = (((if (song.singer == null) "" else song.singer) + " "
                + (if (song.category == null) "" else song.category) + " "
                + (if (song.language == null) "" else song.language))).lowercase()
        if ("热门歌手" == group) return song.playCount > 0 || !song.remote
        if ("大陆/内地" == group) return text.contains("大陆") || text.contains("内地") || text.contains(
            "国语"
        )
        if ("港台" == group) return text.contains("香港") || text.contains("台湾") || text.contains(
            "港台"
        ) || text.contains("粤语") || text.contains("闽南")
        if ("日韩" == group) return text.contains("日本") || text.contains("韩国") || text.contains(
            "日韩"
        ) || text.contains("日语") || text.contains("韩语")
        if ("欧美" == group) return text.contains("欧美") || text.contains("英语") || text.contains(
            "英文"
        ) || text.contains("外语")
        if ("组合/乐队" == group) return text.contains("组合") || text.contains("乐队") || text.contains(
            "band"
        ) || text.contains("group")
        return !singerMatchesGroup(song, "大陆/内地") && !singerMatchesGroup(
            song,
            "港台"
        ) && !singerMatchesGroup(song, "日韩") && !singerMatchesGroup(
            song,
            "欧美"
        ) && !singerMatchesGroup(song, "组合/乐队")
    }

    private fun filterLanguage(language: String?) {
        visibleSongs.clear()
        for (song in library.allSongs()) {
            val value = song.language?.takeIf(String::isNotEmpty) ?: "其他"
            if (isVisibleSong(song) && ("全部" == language || value == language)) visibleSongs.add(
                song
            )
        }
        Collections.sort<Song?>(
            visibleSongs,
            Comparator.comparing<Song?, String?>(Function { song: Song? -> song!!.title!!.lowercase() })
        )
        renderSongs()
    }

    private fun filterWordCount(group: String?) {
        visibleSongs.clear()
        for (song in library.allSongs()) {
            val count = songWordCount(song.title)
            if (isVisibleSong(song) && ("全部" == group || wordGroupMatches(
                    group,
                    count
                ))
            ) visibleSongs.add(song)
        }
        Collections.sort<Song?>(
            visibleSongs,
            Comparator.comparing<Song?, String?>(Function { song: Song? -> song!!.title!!.lowercase() })
        )
        renderSongs()
    }

    private fun wordGroupMatches(group: String?, count: Int): Boolean {
        if ("一字" == group) return count == 1
        if ("二字" == group) return count == 2
        if ("三字" == group) return count == 3
        if ("四字" == group) return count == 4
        if ("五字" == group) return count == 5
        return "六字以上" == group && count >= 6
    }

    private fun songWordCount(title: String?): Int {
        var count = 0
        val value = if (title == null) "" else title
        for (i in 0 until value.length) {
            val ch = value.get(i)
            if (Character.isLetterOrDigit(ch) || (ch >= '\u4e00' && ch <= '\u9fa5')) count++
        }
        return count
    }

    private fun singerName(song: Song): String {
        return song.singer?.takeIf(String::isNotEmpty) ?: "未知歌手"
    }

    private fun isVisibleSong(song: Song): Boolean {
        return !store.hiddenSongIds.contains(stableId(song))
    }

    private fun filterSongs(category: String?, query: String?) {
        val q = if (query == null) "" else query.trim { it <= ' ' }
        // 优先使用数据库搜索(64 万+歌曲)
        if (library.muse.isAvailable() && !q.isEmpty()) {
            browseMode = "search"
            browseParam = q
            browsePage = 0
            loadBrowsePage()
            return
        }
        // 数据库不可用或空查询时,回退到本地文件扫描
        val ql = q.lowercase()
        visibleSongs.clear()
        for (song in library.allSongs()) {
            val categoryOk = "全部" == category
                    || (song.category != null && song.category == category)
                    || ("本地" == category && !song.remote)
                    || ("网络" == category && song.remote)
            val text =
                (song.title + " " + song.singer + " " + song.category + " " + song.language + " " + song.pinyin).lowercase()
            if (isVisibleSong(song) && categoryOk && (ql.isEmpty() || text.contains(ql))) visibleSongs.add(
                song
            )
        }
        Collections.sort<Song?>(
            visibleSongs,
            Comparator.comparing<Song?, String?>(Function { song: Song? -> song!!.title!!.lowercase() })
        )
        renderSongs()
        if (pageInfo != null) pageInfo!!.setText("本地 " + visibleSongs.size + " 首")
    }

    /**
     * 加载当前分页浏览模式的歌曲列表。
     * 根据 browseMode 和 browseParam 从数据库查询对应页的数据。
     */
    /**
     * 安全解析整数。
     * 
     * @param value        字符串
     * @param defaultValue 默认值
     * @return 解析结果
     */
    private fun parseIntSafe(value: String?, defaultValue: Int): Int {
        if (value == null || value.isEmpty()) return defaultValue
        try {
            return value.trim { it <= ' ' }.toInt()
        } catch (e: Exception) {
            return defaultValue
        }
    }

    /**
     * 公共点歌方法:将歌曲加入已点队列并开始播放。
     * 
     * 
     * 供 [SongSearchFragment] 等外部组件调用,实现点歌功能。
     * 
     * @param song 要点播的歌曲
     */
    fun orderSong(song: Song) {
        handleSong(song)
    }

    val muse: MuseDatabase
        /**
         * 获取 Muse 曲库数据库访问层(供 Fragment 查询歌曲/歌手/排行)。
         * 
         * @return 全局 MuseDatabase 实例
         */
        get() = library.muse

    val playbackPosition: Int
        get() {
            try {
                return player?.currentPosition ?: 0
            } catch (ignored: Exception) {
                return 0
            }
        }

    fun resumeMainPlaybackAt(positionMs: Int) {
        try {
            if (player != null && currentSong != null) {
                player!!.seekTo(max(0, positionMs))
                player!!.start()
                startVocalIfReady()
                updateBottomBar(currentSong, true)
            }
        } catch (ignored: Exception) {
        }
    }

    /**
     * 播放指定歌曲(供已点列表点击播放)。
     * 
     * @param song 要播放的歌曲
     */
    fun playSong(song: Song?) {
        play(song)
    }

    /**
     * 已点列表(主页/已点 tab)点击立即播放。
     * 遥控器选中即播: 单击直接播放该歌(当前播放的歌再次点击只提示, 不重播)。
     */
    private fun onOrderedSongClicked(song: Song, position: Int) {
        if (currentSong != null && currentSong!!.equals(song)) {
            toast("正在播放: " + song.title)
            return
        }
        playSong(song)
        toast("立即播放: " + song.title)
    }

    /**
     * 切换到下一首歌曲(供已点列表切歌操作)。
     */
    fun playNextSong() {
        playNext()
    }

    private fun handleSong(song: Song) {
        addToQueue(song)
        if (currentPage == "已点" || currentPage == "首页") renderQueue()
    }

    private fun download(song: Song) {
        val mainUrl = if (song.downloadUrl == null || song.downloadUrl!!.trim { it <= ' ' }
                .isEmpty()) song.videoUrl else song.downloadUrl
        if (mainUrl == null || mainUrl.trim { it <= ' ' }.isEmpty()) {
            toast("该条目没有下载地址")
            return
        }
        val task = DownloadTask(song)
        downloads.add(task)
        busy(true, "正在下载：" + song.title)
        io.execute(Runnable {
            val target = library.targetFor(song)
            try {
                downloadToFile(mainUrl, target, task, 0, 75)
                val originalFile = sidecarFile(target, ".original", song.originalUrl)
                val accompanyFile = sidecarFile(target, ".accompany", song.accompanyUrl)
                val lyricFile = sidecarFile(target, "", song.lyricUrl)
                if (hasUrl(song.originalUrl)) downloadToFile(
                    song.originalUrl,
                    originalFile,
                    task,
                    75,
                    85
                )
                if (hasUrl(song.accompanyUrl)) downloadToFile(
                    song.accompanyUrl,
                    accompanyFile,
                    task,
                    85,
                    95
                )
                if (hasUrl(song.lyricUrl)) downloadToFile(song.lyricUrl, lyricFile, task, 95, 99)
                if (task.cancelled) throw InterruptedException("cancelled")
                task.state = "完成"
                task.progress = 100
                val local = local(target.getAbsolutePath(), target.getName())
                local.originalPath =
                    if (originalFile.exists()) originalFile.getAbsolutePath() else ""
                local.accompanyPath =
                    if (accompanyFile.exists()) accompanyFile.getAbsolutePath() else ""
                local.lyricPath = if (lyricFile.exists()) lyricFile.getAbsolutePath() else ""
                main.post(Runnable {
                    orderQueue!!.add(local)
                    saveState()
                    refreshLibrary(null)
                    busy(false, "下载完成并已加入已点：" + local.title)
                    if (currentTabIndex == 6) loadDownloadedList()
                })
            } catch (e: InterruptedException) {
                task.state = "已取消"
                target.delete()
                cleanupSidecars(target)
                main.post(Runnable {
                    busy(false, "下载已取消：" + song.title)
                    if (currentTabIndex == 6) loadDownloadedList()
                })
            } catch (e: Exception) {
                task.state = "失败：" + e.message
                main.post(Runnable {
                    busy(false, "下载失败：" + e.message)
                    if (currentTabIndex == 6) loadDownloadedList()
                })
            }
        })
    }

    @Throws(Exception::class)
    private fun downloadToFile(
        url: String?,
        target: File?,
        task: DownloadTask,
        base: Int,
        span: Int
    ) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setConnectTimeout(10000)
        conn.setReadTimeout(30000)
        val code = conn.getResponseCode()
        check(!(code < 200 || code >= 300)) { "HTTP " + code }
        val total = conn.getContentLength()
        var done = 0
        task.state = "下载中"
        try {
            BufferedInputStream(conn.getInputStream()).use { `in` ->
                FileOutputStream(target).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while ((`in`.read(buffer).also { read = it }) > 0) {
                        if (task.cancelled) throw InterruptedException("cancelled")
                        out.write(buffer, 0, read)
                        done += read
                        if (total > 0) task.progress = min(99, base + (done * span) / total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun hasUrl(value: String?): Boolean {
        return value != null && !value.trim { it <= ' ' }.isEmpty()
    }

    private fun sidecarFile(media: File, suffix: String?, url: String?): File {
        var ext = ".mp3"
        if (url != null) {
            val dot = url.lastIndexOf('.')
            val q = url.indexOf('?', dot)
            if (dot >= 0) ext = url.substring(dot, if (q > dot) q else url.length)
            if (ext.length > 8 || ext.contains("/")) ext = ".mp3"
        }
        val path = media.getAbsolutePath()
        val dot = path.lastIndexOf('.')
        val base = if (dot > 0) path.substring(0, dot) else path
        return File(base + suffix + ext)
    }

    private fun cleanupSidecars(media: File) {
        val dir = media.getParentFile()
        if (dir == null) return
        val path = media.getAbsolutePath()
        val dot = path.lastIndexOf('.')
        val base = if (dot > 0) path.substring(0, dot) else path
        val files = dir.listFiles()
        if (files == null) return
        for (file in files) {
            val value = file.getAbsolutePath()
            if (value.startsWith(base + ".original") || value.startsWith(base + ".accompany") || value.startsWith(
                    base + ".lrc"
                )
            ) {
                file.delete()
            }
        }
    }

    private fun cancelAllDownloads() {
        for (task in downloads) {
            if ("完成" != task.state) {
                task.cancelled = true
                task.state = "正在取消"
            }
        }
        showDownloads()
    }

    private fun play(song: Song?) {
        if (player == null) player = videoBackground
        if (song == null) return
        try {
            // 取消仍在后台扫描的“已点播完后随机本地歌曲”请求，避免抢占新点歌曲。
            randomLocalRequestSerial++
            playbackGeneration++
            songRetryCount = 0 // 重置重试计数
            playWhenPrepared = true
            hidePlaybackNoticeNow()
            releaseVocalPlayer()
            currentSong = song
            loadLyrics(song)
            // v1.2.6：启动真人评分（歌词时间轴 + 话筒旁路），并请求音频焦点
            if (store.scoreEnabled) {
                vocalScorer.startSong()
                vocalScorer.setTimeline(buildLyricTimeline())
            } else {
                vocalScorer.abort()
            }
            requestAudioFocusIfNeeded()
            persistRuntimeState()

            if (playerController != null) {
                playerController!!.updateSongInfo(song)
                playerController!!.setVocalMode(originalVocal)
            }

            updateBottomBar(song, true)
            scheduleAutoFullscreen()

            if (song.hasLocalFile()) {
                playLocalFile(song)
            } else if (CatalogPlayback.directStreamUrl(song.remote, song.videoUrl) != null) {
                playbackPreparing = true
                hideDownloadProgress()
                player!!.stopPlayback()
                setupPlayerListeners(song, 0, playbackGeneration)
                player!!.setVideoURI(Uri.parse(CatalogPlayback.directStreamUrl(song.remote, song.videoUrl)!!))
                if (playWhenPrepared) player!!.start()
            } else {
                playbackPreparing = true
                // 统一使用支持断点续传和下载地址重试的下载管理器。
                if (isDownloading(song)) {
                    showDownloadProgressFor(song)
                    return
                }
                toast("加载中: " + song.title + "...")
                showDownloadProgressFor(song)
                startDownloadAndPlay(song)
            }
        } catch (e: Exception) {
            toast("播放失败：" + e.message)
        }
    }

    private fun playLocalFile(song: Song, startPositionMs: Int = 0) {
        try {
            playbackPreparing = true
            val localPath = song.path
            if (localPath.isNullOrEmpty()) {
                playbackPreparing = false
                startDownloadAndPlay(song)
                return
            }
            val f = File(localPath)
            val inspection = SongFileValidator.inspect(f, SongFileValidator.requiresTransportStream(f))
            if (!inspection.valid) {
                Log.w(TAG, "Invalid local file: ${song.path}, ${inspection.reason}")
                if (f.exists()) runCatching { f.delete() }
                song.path = null
                toast("本地文件丢失，重新下载...")
                startDownloadAndPlay(song)
                return
            }
            Log.i(
                TAG,
                "playLocalFile song=${stableId(song)} generation=$playbackGeneration " +
                    "startPositionMs=$startPositionMs path=${f.absolutePath} size=${f.length()}",
            )
            // 自动清理以文件时间作为最近使用时间；每次实际播放都刷新，避免常唱歌曲被误删。
            runCatching { f.setLastModified(System.currentTimeMillis()) }
            player!!.stopPlayback()
            setupPlayerListeners(song, startPositionMs, playbackGeneration)
            player!!.setVideoURI(Uri.fromFile(f))
            if (playWhenPrepared) player!!.start()
        } catch (e: Exception) {
            playbackPreparing = false
            Log.e(TAG, "playLocalFile error: " + e.message, e)
            toast("播放失败")
        }
    }

    /**
     * 完整下载后播放。VideoView不支持流式TS，必须下载完再播。
     * 使用256KB大缓冲区加速下载，实时显示进度。
     */
    private fun streamDownloadAndPlay(song: Song) {
        io.execute(Runnable {
            var tmpFile: File? = null
            try {
                var url = song.downloadUrl
                if (url == null || url.isEmpty()) {
                    url = getSongDownloadUrl(extractTid(song))
                    if (url != null) song.downloadUrl = url
                }
                if (url == null || url.isEmpty()) {
                    main.post(Runnable {
                        hideDownloadProgress()
                        toast("无法获取播放地址")
                    })
                    return@Runnable
                }

                val finalFile = getLocalFile(song)
                finalFile.getParentFile().mkdirs()
                tmpFile = File(finalFile.getParent(), finalFile.getName() + ".tmp")

                val conn = URL(url).openConnection() as HttpURLConnection
                conn.setConnectTimeout(10000)
                conn.setReadTimeout(30000)
                conn.setRequestProperty("User-Agent", "ThunderSDK/4.1.3")
                conn.connect()

                val totalSize = conn.getContentLength().toLong()
                val `in`: InputStream = BufferedInputStream(conn.getInputStream(), 262144)
                val out = FileOutputStream(tmpFile)

                val buf = ByteArray(262144) // 256KB buffer for speed
                var downloaded: kotlin.Long = 0
                var lastPct = 0
                var lastTime = System.currentTimeMillis()
                var lastBytes: kotlin.Long = 0

                while (true) {
                    val read = `in`.read(buf)
                    if (read < 0) break
                    out.write(buf, 0, read)
                    downloaded += read.toLong()

                    // 每秒更新一次进度和速度
                    val now = System.currentTimeMillis()
                    if (now - lastTime > 1000) {
                        val pct = if (totalSize > 0) (downloaded * 100 / totalSize).toInt() else 0
                        val speed = downloaded - lastBytes
                        lastPct = pct
                        lastTime = now
                        lastBytes = downloaded
                        val p = pct
                        val speedStr = formatSize(speed) + "/s"
                        main.post(Runnable {
                            updateDownloadProgress(song, p)
                            if (textDownloadSpeed != null) textDownloadSpeed!!.setText(speedStr)
                        })
                    }
                }
                out.close()
                `in`.close()
                conn.disconnect()

                // 移到正式位置
                tmpFile.renameTo(finalFile)
                song.path = finalFile.getAbsolutePath()

                main.post(Runnable {
                    hideDownloadProgress()
                    if (currentSong != null && currentSong!!.equals(song)) {
                        playLocalFile(song)
                    }
                    toast(song.title + " 就绪")
                })
            } catch (e: Exception) {
                Log.e(TAG, "download error: " + e.message)
                if (tmpFile != null) tmpFile.delete()
                main.post(Runnable {
                    hideDownloadProgress()
                    toast("下载失败: " + e.message)
                })
            }
        })
    }

    /**
     * 下载歌曲并在完成后自动播放 (旧版，备用)。
     */
    private fun startDownloadAndPlay(song: Song) {
        downloadSong(song, object : DownloadCallback {
            override fun onDownloadStart(s: Song) {
                main.post(Runnable {
                    updateDownloadProgress(s, 0)
                })
            }

            override fun onDownloadProgress(s: Song, progress: Int) {
                main.post(Runnable { updateDownloadProgress(s, progress) })
            }

            override fun onDownloadReadyToPlay(s: Song) {}
            override fun onDownloadComplete(s: Song, localPath: String) {
                main.post { hideDownloadProgress() }
            }

            override fun onDownloadFailed(s: Song, error: String) {
                main.post(Runnable {
                    hideDownloadProgress()
                    toast("下载失败: " + error)
                })
            }
        })
    }

    private fun showDownloadProgressFor(song: Song) {
        speedSampleBytes = SongOkDownloadManager.getDownloadedBytes(song)
        speedSampleAt = System.currentTimeMillis()
        if (downloadProgressContainer != null) downloadProgressContainer!!.setVisibility(View.VISIBLE)
        if (textDownloadStatus != null) textDownloadStatus!!.setText("正在下载：" + song.title)
        if (textDownloadSpeed != null) textDownloadSpeed!!.setText("准备中…")
        if (downloadProgressBar != null) downloadProgressBar!!.setProgress(0)
    }

    private fun updateDownloadProgress(song: Song?, progress: Int) {
        if (downloadProgressContainer != null) downloadProgressContainer!!.setVisibility(View.VISIBLE)
        val target = song ?: currentSong
        val total = target?.let { SongOkDownloadManager.getTotalBytes(it) } ?: 0L
        val done = target?.let { SongOkDownloadManager.getDownloadedBytes(it) } ?: 0L

        if (textDownloadStatus != null) {
            textDownloadStatus!!.text = "正在下载：" + (target?.title ?: "")
        }

        if (textDownloadSpeed != null) {
            textDownloadSpeed!!.text = if (total > 0) {
                val speed = estimateDownloadSpeed(done)
                "${formatMb(done)} / ${formatMb(total)}   ${progress}%   ${formatSpeed(speed)}"
            } else {
                // 服务器未返回总长度时退化为不确定进度条动画 + 百分比
                "$progress%"
            }
        }
        if (downloadProgressBar != null) {
            if (total > 0) {
                downloadProgressBar!!.isIndeterminate = false
                downloadProgressBar!!.setProgress(progress)
            } else {
                downloadProgressBar!!.isIndeterminate = true
            }
        }
    }

    /** 依据两次采样估算瞬时下载速度（字节/秒）。 */
    private fun estimateDownloadSpeed(doneBytes: Long): Long {
        val now = System.currentTimeMillis()
        val elapsed = now - speedSampleAt
        if (elapsed < 400L) return -1L
        val delta = doneBytes - speedSampleBytes
        speedSampleBytes = doneBytes
        speedSampleAt = now
        return if (delta < 0) -1L else delta * 1000L / elapsed
    }

    private fun formatMb(bytes: Long): String =
        String.format(Locale.ROOT, "%.1fMB", bytes / 1024.0 / 1024.0)

    private fun formatSpeed(bytesPerSecond: Long): String =
        if (bytesPerSecond < 0) "" else String.format(Locale.ROOT, "%.1fMB/s", bytesPerSecond / 1024.0 / 1024.0)

    private fun hideDownloadProgress() {
        if (downloadProgressContainer != null) downloadProgressContainer!!.setVisibility(View.GONE)
    }

    private fun setupPlayerListeners(song: Song?, startPositionMs: Int = 0, generation: Long = playbackGeneration) {
        player!!.setOnPreparedListener(OnPreparedListener { mp: MediaPlayer? ->
            if (generation != playbackGeneration || currentSong?.equals(song) != true) return@OnPreparedListener
            Log.i(TAG, "onPrepared song=${song?.let(::stableId)} generation=$generation play=$playWhenPrepared")
            playbackPreparing = false
            currentMediaPlayer = mp
            applyPlaybackMode()
            ensureMicActivated() // 占住麦克风通道，外接麦才能被路由到车机喇叭
            if (startPositionMs > 0) player!!.seekTo(startPositionMs)
            prepareExternalVocal(song, startPositionMs)
            if (playWhenPrepared) {
                player!!.start()
                startVocalIfReady()
            }
            updateBottomBar(song, playWhenPrepared)
            if (startPositionMs <= 0) showSongIntro(song)
            persistRuntimeState()
        })
        player!!.setOnCompletionListener(OnCompletionListener { mp: MediaPlayer? ->
            if (generation != playbackGeneration || currentSong?.equals(song) != true) return@OnCompletionListener
            if (System.currentTimeMillis() >= suppressCompletionUntil) {
                main.post(Runnable { playNext(true) })
            }
        })
        player!!.setOnErrorListener(MediaPlayer.OnErrorListener { mp: MediaPlayer?, what: Int, extra: Int ->
            if (generation != playbackGeneration || currentSong?.equals(song) != true) return@OnErrorListener true
            playbackPreparing = false
            Log.e(TAG, "MediaPlayer error what=" + what + " extra=" + extra)
            if (songRetryCount < 2 && song != null && song.hasLocalFile()) {
                songRetryCount++
                val retry = songRetryCount
                main.postDelayed(Runnable {
                    toast("播放重试 " + retry + "/2")
                    playLocalFile(song)
                }, 700L * retry)
            } else {
                songRetryCount = 0
                if (song != null && CatalogPlayback.directStreamUrl(song.remote, song.videoUrl) != null) {
                    toast("网络播放失败，请检查曲库地址或网络连接；未自动下载歌曲")
                }
                main.post(Runnable { this.playNext() })
            }
            true
        })
    }

    private fun switchToLocalPlayback(song: Song?) {
        if (currentSong == null || !currentSong!!.equals(song)) return
        if (!song!!.hasLocalFile()) return
        var pos = 0
        try {
            pos = player!!.currentPosition
        } catch (ignored: Exception) {
        }
        player!!.stopPlayback()
        playLocalFile(song, pos)
    }

    /**
     * 更新底部控制栏显示
     */
    private fun updateBottomBar(song: Song?, playing: Boolean) {
        if (playing && playbackModeNotice?.text?.contains("暂停") == true) hidePlaybackNoticeNow()
        if (playerSongTitle != null) playerSongTitle!!.setText(if (song != null) song.title else "未播放")
        if (playerSongSinger != null) playerSongSinger!!.setText(if (song != null) (if (song.singer != null) song.singer else "") else "")
        if (textVocalMode != null) textVocalMode.setText(vocalActionText())
        if (textPlayPause != null) textPlayPause.setText(if (playing) "暂停" else "播放")
        if (btnTopPause != null) btnTopPause!!.setText(if (playing) "暂停" else "播放")
        if (btnTopVocal != null) btnTopVocal!!.setText(vocalActionText())
        setTopControlIcon(btnTopPause, if (playing) R.drawable.ott_ic_ctrl_pause else R.drawable.ott_ic_ctrl_play)
        setTopControlIcon(
            btnTopVocal,
            vocalActionIcon(),
        )
        fullScreenPauseText?.text = if (playing) "暂停" else "播放"
        fullScreenPauseIcon?.setImageResource(if (playing) R.drawable.ott_ic_ctrl_pause else R.drawable.ott_ic_ctrl_play)
        fullScreenVocalText?.text = vocalActionText()
        fullScreenVocalIcon?.setImageResource(vocalActionIcon())
        playerController?.updatePlayState(playing)
        playerController?.setVocalMode(originalVocal)
        syncPlayerInfo()
        updateOrderBadge()
    }

    /**
     * 从 CDN 下载歌曲文件。
     * 使用 global_confs 表中的 cdn_path 作为基础 URL。
     * 
     * @param song 要下载的歌曲
     */
    private fun downloadFromCdn(song: Song) {
        if (song.filename == null || song.filename!!.isEmpty()) {
            toast("无法下载:缺少文件名")
            return
        }
        val cdnBase = "https://pub.mcdn.cherryonline.cn/"
        val url = cdnBase + "cloud-song/" + song.filename
        val task = DownloadTask(song)
        downloads.add(task)
        busy(true, "正在从云端下载:" + song.title)
        io.execute(Runnable {
            val target =
                File(MuseDatabase.VIDEO_ROOT + "/" + MuseDatabase.CLOUD_SONG_DIR, song.filename)
            try {
                File(target.getParent()).mkdirs()
                downloadToFile(url, target, task, 0, 100)
                task.state = "完成"
                task.progress = 100
                song.path = target.getAbsolutePath()
                main.post(Runnable {
                    busy(false, "下载完成")
                    if (currentTabIndex == 6) loadDownloadedList()
                    if (currentSong?.equals(song) == true && playbackPreparing) {
                        switchToLocalPlayback(song)
                    }
                })
            } catch (e: Exception) {
                task.state = "失败:" + e.message
                main.post(Runnable {
                    busy(false, "下载失败:" + e.message)
                    if (currentTabIndex == 6) loadDownloadedList()
                })
            }
        })
    }

    /**
     * 使用 SongOkDownloadManager 下载歌曲到本地。
     */
    private fun downloadSong(song: Song, extraCallback: DownloadCallback? = null, storageChecked: Boolean = false) {
        if (SongOkDownloadManager.isDownloading(song)) {
            if (extraCallback != null) extraCallback.onDownloadStart(song)
            return
        }
        if (!storageChecked && autoDeleteSongs && isBelowReservedStorage()) {
            io.execute {
                purgeColdDownloadedFiles()
                main.post { downloadSong(song, extraCallback, storageChecked = true) }
            }
            return
        }
        if (::stateDatabase.isInitialized) runCatching {
            stateIo.execute { stateDatabase.updateDownload(song, "queued", 0) }
        }
        download(song, object : DownloadCallback {
            override fun onDownloadStart(song: Song) {
                if (extraCallback != null) extraCallback.onDownloadStart(song)
                // P1 修复：下载线程的 SQLite 写必须走 stateIo 串行化，避免与 syncQueue/saveState
                // 并发直写触发 SQLITE_BUSY。onDownloadStart/Progress/Complete/Failed 全部统一。
                if (::stateDatabase.isInitialized) runCatching {
                    stateIo.execute { stateDatabase.updateDownload(song, "downloading", 0) }
                }
                main.post(Runnable {
                    val task = downloads.firstOrNull { stableId(it.song) == stableId(song) }
                        ?: DownloadTask(song).also { downloads.add(it) }
                    task.state = "下载中"
                    task.progress = 0
                    refreshSongAdapter()
                    if (currentTabIndex == 6) loadDownloadedList()
                    if (currentSong?.equals(song) == true && playbackPreparing) updateDownloadProgress(song, 0)
                })
            }

            override fun onDownloadProgress(song: Song, progress: Int) {
                if (extraCallback != null) extraCallback.onDownloadProgress(song, progress)
                // P1 修复：每 250ms 的下载进度写 SQLite 绕过 stateIo 串行化，与 syncQueue/saveState
                // 并发直写会触发 SQLITE_BUSY。改走 stateIo.execute，与 10369 行 paused 写保持一致。
                if (::stateDatabase.isInitialized) runCatching {
                    stateIo.execute { stateDatabase.updateDownload(song, "downloading", progress) }
                }
                main.post(Runnable {
                    for (task in downloads) {
                        if (task.song === song || task.song.id != null && task.song.id == song.id) {
                            task.progress = progress
                            break
                        }
                    }
                    // 原来是 loadDownloadedList()（整页重建 + 切页状态复位），
                    // 下载中每 250ms 触发一次，车机上掉帧明显。改为只原地更新可见进度条。
                    refreshVisibleProgressCells()
                    if (currentSong?.equals(song) == true && playbackPreparing) updateDownloadProgress(song, progress)
                })
            }

            override fun onDownloadReadyToPlay(song: Song) {
                if (extraCallback != null) extraCallback.onDownloadReadyToPlay(song)
                // 后台预下载不能抢占当前播放；当前点播在完整文件落盘后切换。
            }

            override fun onDownloadComplete(song: Song, localPath: String) {
                if (extraCallback != null) extraCallback.onDownloadComplete(song, localPath)
                if (::stateDatabase.isInitialized) runCatching {
                    stateIo.execute { stateDatabase.removeDownload(song) }
                }
                main.post(Runnable {
                    downloads.removeAll { stableId(it.song) == stableId(song) }
                    song.path = localPath
                    // 文件刚落盘，本地标记要从 false 变 true，清掉进程内缓存强制重算
                    invalidateLocalFileCache()
                    SongOkDownloadManager.getLocalFile(song)?.let { SongFileValidator.forget(it) }
                    refreshSongAdapter()
                    val shouldAddToQueue = pendingQueueSongIds.remove(stableId(song))
                    Log.i(
                        TAG,
                        "downloadComplete song=${stableId(song)} pendingQueue=$shouldAddToQueue " +
                            "current=${currentSong?.let(::stableId)} preparing=$playbackPreparing path=$localPath",
                    )
                    if (shouldAddToQueue) addReadySongToQueue(song)
                    if (currentTabIndex == 6) loadDownloadedList()
                    if (currentSong?.equals(song) == true && playbackPreparing) hideDownloadProgress()
                    if (!shouldAddToQueue && currentSong?.equals(song) == true && playbackPreparing) {
                        switchToLocalPlayback(song)
                    }
                })
                enforceStorageReserve()
            }

            override fun onDownloadFailed(song: Song, error: String) {
                if (extraCallback != null) extraCallback.onDownloadFailed(song, error)
                if (::stateDatabase.isInitialized) runCatching {
                    stateIo.execute { stateDatabase.updateDownload(song, "failed", 0, error = error) }
                }
                main.post(Runnable {
                    val wasPendingQueue = pendingQueueSongIds.remove(stableId(song))
                    if (currentSong != null && currentSong!!.equals(song)) playbackPreparing = false
                    for (task in downloads) {
                        if (task.song === song || task.song.id != null && task.song.id == song.id) {
                            task.state = "失败:" + error
                            break
                        }
                    }
                    refreshSongAdapter()
                    if (currentTabIndex == 6) loadDownloadedList()
                    if (currentSong?.equals(song) == true) hideDownloadProgress()
                    if (wasPendingQueue) toast("下载失败，未加入已点：${song.title}")
                })
            }
        })
    }

    /**
     * 检查指定歌曲是否正在下载中。
     * 
     * 
     * 使用 SongOkDownloadManager 检查真实的下载状态。
     * 
     * @param song 要检查的歌曲
     * @return true 表示正在下载
     */
    /** 从 Song 中提取云端歌曲 TID (filename 格式: "7678785.ts" / "7586669.ls" → "7678785" / "7586669")  */
    private fun extractTid(song: Song): String? {
        if (song.filename != null) {
            // 去掉 .ts 或 .ls 扩展名
            if (song.filename!!.endsWith(".ts")) return song.filename!!.substring(
                0,
                song.filename!!.length - 3
            )
            if (song.filename!!.endsWith(".ls")) return song.filename!!.substring(
                0,
                song.filename!!.length - 3
            )
            return song.filename
        }
        return song.id
    }

    private fun isDownloading(song: Song): Boolean {
        if (SongOkDownloadManager.isDownloading(song)) {
            return true
        }
        for (task in downloads) {
            val sameSong = task.song === song || stableId(task.song) == stableId(song)
            val active = task.state == "等待" || task.state == "等待下载" ||
                task.state == "下载中" || task.state == "正在取消"
            if (sameSong && active) return true
        }
        return false
    }

    private fun playNext(fromCompletion: Boolean = false) {
        // 单曲循环只影响自然播完；用户主动切歌始终进入下一首。
        if (fromCompletion && loopOne && player != null && currentSong != null) {
            playWhenPrepared = true
            player!!.seekTo(0)
            prepareExternalVocal(currentSong, 0)
            player!!.start()
            startVocalIfReady()
            return
        }
        // 队列为空
        if (orderQueue!!.isEmpty()) {
            if (fromCompletion && autoNext) {
                playRandomLocalSong()
                return
            }
            playbackPreparing = false
            playWhenPrepared = false
            releaseVocalPlayer()
            currentSong = null
            clearLyrics()
            // v1.2.6：播放全部结束，交还音频焦点
            abandonAudioFocus()
            updateBottomBar(null, false)
            if (status != null) status!!.setText("播放完成，请点歌")
            return
        }
        // 移除当前歌曲，放入已唱列表
        if (currentSong != null) {
            sangHistory.add(0, currentSong!!)
            recordScore(currentSong)
            orderQueue.remove(currentSong!!)
        }
        saveState()
        renderQueue()
        // 播放下一首
        if ((!fromCompletion || autoNext) && !orderQueue.isEmpty()) {
            play(orderQueue.get(0))
        } else if (fromCompletion && autoNext && orderQueue.isEmpty()) {
            playRandomLocalSong()
        } else {
            playbackPreparing = false
            playWhenPrepared = false
            releaseVocalPlayer()
            currentSong = null
            clearLyrics()
            updateBottomBar(null, false)
            if (status != null) status!!.setText("播放完成" + (if (orderQueue.isEmpty()) "" else "，点击播放继续"))
        }
    }

    /**
     * 已点队列自然播放完毕后，从本机所有有效歌曲中随机续播。
     * 随机歌曲不写入已点列表；用户此时点歌会立即取消本次随机请求并优先播放已点歌曲。
     */
    private fun playRandomLocalSong() {
        val previousSongId = currentSong?.let(::stableId) ?: lastRandomLocalSongId
        val requestSerial = ++randomLocalRequestSerial
        playbackPreparing = true
        playWhenPrepared = true
        releaseVocalPlayer()
        currentSong = null
        clearLyrics()
        updateBottomBar(null, false)
        status?.text = "已点播放完成，正在随机本地歌曲..."
        persistRuntimeState()

        io.execute {
            val localSongs = localSongInventory()
            library.scanLocal().forEach { song ->
                val file = song.path?.let(::File) ?: return@forEach
                if (SongFileValidator.inspect(
                        file,
                        SongFileValidator.requiresTransportStream(file),
                    ).valid
                ) {
                    localSongs.putIfAbsent(stableId(song), song)
                }
            }
            val allLocalSongs = localSongs.values.toList()
            val alternatives = allLocalSongs.filter { stableId(it) != previousSongId }
            val selected = (if (alternatives.isNotEmpty()) alternatives else allLocalSongs).randomOrNull()
            main.post {
                if (requestSerial != randomLocalRequestSerial || orderQueue!!.isNotEmpty() || currentSong != null) {
                    return@post
                }
                if (selected != null) {
                    lastRandomLocalSongId = stableId(selected)
                    status?.text = "随机播放：${selected.title}"
                    play(selected)
                } else {
                    playbackPreparing = false
                    playWhenPrepared = false
                    updateBottomBar(null, false)
                    status?.text = "播放完成，本地暂无可播放歌曲"
                    persistRuntimeState()
                }
            }
        }
    }

    private fun togglePlay() {
        if (player == null) return
        val targetPlaying = !playWhenPrepared
        playWhenPrepared = targetPlaying
        if (playbackPreparing) {
            updateBottomBar(currentSong, targetPlaying)
            if (targetPlaying) hidePlaybackNoticeNow() else showPlaybackModeNotice("暂停", true)
            scheduleAutoFullscreen()
            persistRuntimeState()
            return
        }
        if (!targetPlaying) {
            player!!.pause()
            pauseVocalIfReady()
            if (textPlayPause != null) textPlayPause.setText("播放")
            showPlaybackModeNotice("暂停", true)
        } else {
            // v1.2.6：恢复播放前重新请求音频焦点（暂停期间可能已被其它应用拿走）
            requestAudioFocusIfNeeded()
            player!!.start()
            startVocalIfReady()
            if (textPlayPause != null) textPlayPause.setText("暂停")
            hidePlaybackNoticeNow()
        }
        updateBottomBar(currentSong, targetPlaying)
        scheduleAutoFullscreen()
        persistRuntimeState()
    }

    private fun replay() {
        if (player == null) return
        playWhenPrepared = true
        suppressCompletionUntil = System.currentTimeMillis() + 1500L
        player!!.seekTo(0)
        updateLyricView()
        prepareExternalVocal(currentSong, 0)
        player!!.start()
        startVocalIfReady()
        hidePlaybackNoticeNow()
        updateBottomBar(currentSong, true)
        showSongIntro(currentSong)
        persistRuntimeState()
    }

    private fun applyPlaybackMode() {
        val videoPlayer = player ?: return
        videoPlayer.setTone(tone)
        val external = prepareExternalVocal(
            currentSong,
            player?.currentPosition ?: 0
        )
        val embedded = !external && "自动" == vocalChannelMode &&
            prepareEmbeddedDualTrackVocal(currentSong, player?.currentPosition ?: 0)
        var trackSelected = false
        var channelApplied = false
        if (!external && !embedded && "自动" == vocalChannelMode) {
            trackSelected = applySelectedAudioTrack()
            if (!trackSelected && currentSong != null && currentSong!!.accomp > 0) {
                channelApplied = applyChannelByAccomp()
            }
        }
        val alternateTrackActive = embedded && originalVocal != embeddedMainTrackOriginal
        val volume = if (external || alternateTrackActive || "静音练唱" == singMode) {
            0f
        } else {
            // duckFactor：音频焦点 CAN_DUCK 时压到 0.2，GAIN 恢复 1（v1.2.6）
            max(0f, min(1f, musicVolume / 100f)) * duckFactor
        }
        try {
            when {
                external || "静音练唱" == singMode -> videoPlayer.setPlaybackVolume(0f, 0f)
                embedded -> videoPlayer.setPlaybackVolume(volume, volume)
                trackSelected -> videoPlayer.selectAudioChannel(IjkMediaPlayer.AUDIO_CHANNEL_STEREO, volume)
                "自动" != vocalChannelMode -> {
                    videoPlayer.selectAudioChannel(selectedChannelForMode(), volume)
                    status?.text = "声道模式：$vocalChannelMode / ${if (originalVocal) "原唱" else "伴唱"}"
                }
                channelApplied -> Unit
                else -> videoPlayer.selectAudioChannel(IjkMediaPlayer.AUDIO_CHANNEL_STEREO, volume)
            }
        } catch (ignored: Exception) {
        }
        applyVocalVolume()
        if (embedded && vocalPlayerPrepared) {
            val mainPosition = videoPlayer.currentPosition
            val alternatePosition = embeddedVocalEngine?.currentPosition ?: mainPosition
            Log.i(
                TAG,
                "Embedded seamless switch original=$originalVocal main=$mainPosition " +
                    "alternate=$alternatePosition drift=${alternatePosition - mainPosition}",
            )
        }
    }

    /**
     * 基于 songs.accomp 字段自动切换原唱/伴奏声道。
     * 
     * 
     * accomp=1:左声道=伴奏,右声道=原唱
     * accomp=2:左声道=原唱,右声道=伴奏
     * 
     * 
     * 当 originalVocal=true 时,播放原唱声道,静音伴奏声道;
     * 当 originalVocal=false 时,播放伴奏声道,静音原唱声道。
     * 
     * @return true 表示成功应用声道切换
     */
    private fun applyChannelByAccomp(): Boolean {
        val videoPlayer = player ?: return false
        if (currentSong == null || currentSong!!.accomp <= 0) return false
        try {
            val volume = max(0f, min(1f, musicVolume / 100f))
            val channel = if (currentSong!!.accomp == 1) {
                if (originalVocal) IjkMediaPlayer.AUDIO_CHANNEL_RIGHT else IjkMediaPlayer.AUDIO_CHANNEL_LEFT
            } else {
                if (originalVocal) IjkMediaPlayer.AUDIO_CHANNEL_LEFT else IjkMediaPlayer.AUDIO_CHANNEL_RIGHT
            }
            videoPlayer.selectAudioChannel(channel, volume)
            status!!.setText((if (originalVocal) "原唱" else "伴唱") + "(声道" + currentSong!!.accomp + ")")
            return true
        } catch (e: Exception) {
            return false
        }
    }

    private fun applySelectedAudioTrack(): Boolean {
        val selected = player?.selectAudioTrack(originalVocal) == true
        if (selected) status?.text = if (originalVocal) "已选择原唱音轨" else "已选择伴唱音轨"
        return selected
    }

    private fun selectedChannelForMode(): Int = when (vocalChannelMode) {
        "左伴右原" -> if (originalVocal) IjkMediaPlayer.AUDIO_CHANNEL_RIGHT else IjkMediaPlayer.AUDIO_CHANNEL_LEFT
        "右伴左原" -> if (originalVocal) IjkMediaPlayer.AUDIO_CHANNEL_LEFT else IjkMediaPlayer.AUDIO_CHANNEL_RIGHT
        else -> IjkMediaPlayer.AUDIO_CHANNEL_STEREO
    }

    private fun channelVolumes(volume: Float): FloatArray {
        if ("左伴右原" == vocalChannelMode) {
            return if (originalVocal) floatArrayOf(0f, volume) else floatArrayOf(volume, 0f)
        }
        if ("右伴左原" == vocalChannelMode) {
            return if (originalVocal) floatArrayOf(volume, 0f) else floatArrayOf(0f, volume)
        }
        return floatArrayOf(volume, volume)
    }

    private fun prepareExternalVocal(song: Song?, positionMs: Int, forceSeek: Boolean = false): Boolean {
        val path = selectedVocalPath(song)
        if (!hasUrl(path)) {
            if (embeddedDualTrackMode && embeddedVocalEngine != null) {
                pendingVocalPosition = adjustedAudioPosition(positionMs)
                if (vocalPlayerPrepared) runCatching {
                    val drift = kotlin.math.abs(embeddedVocalEngine!!.currentPosition - pendingVocalPosition)
                    // 进度条拖动(forceSeek)必须无条件对齐，否则声音留在原进度；平时保持 300ms 门槛防抖动
                    if (forceSeek || (drift > 300 && originalVocal == embeddedMainTrackOriginal)) {
                        embeddedVocalEngine!!.seekTo(pendingVocalPosition)
                    }
                }
                return false
            }
            releaseVocalPlayer()
            return false
        }
        if (path == currentVocalPath && vocalPlayer != null) {
            pendingVocalPosition = adjustedAudioPosition(positionMs)
            if (vocalPlayerPrepared) {
                runCatching {
                    vocalPlayer!!.seekTo(pendingVocalPosition)
                    applyVocalVolume()
                    if (playWhenPrepared) startVocalIfReady() else pauseVocalIfReady()
                }
            }
            return true
        }
        releaseVocalPlayer()
        try {
            embeddedDualTrackMode = false
            val candidate = MediaPlayer()
            vocalPlayer = candidate
            vocalPlayerPrepared = false
            pendingVocalPosition = adjustedAudioPosition(positionMs)
            currentVocalPath = path
            candidate.setDataSource(path)
            candidate.setAudioStreamType(AudioManager.STREAM_MUSIC)
            candidate.setOnCompletionListener(OnCompletionListener { _: MediaPlayer? -> })
            candidate.setOnPreparedListener { preparedPlayer ->
                if (vocalPlayer !== preparedPlayer || currentVocalPath != path) {
                    runCatching { preparedPlayer.release() }
                    return@setOnPreparedListener
                }
                vocalPlayerPrepared = true
                if (pendingVocalPosition > 0) runCatching { preparedPlayer.seekTo(pendingVocalPosition) }
                applyVocalVolume()
                if (playWhenPrepared) startVocalIfReady() else pauseVocalIfReady()
            }
            candidate.setOnErrorListener { failedPlayer, _, _ ->
                if (vocalPlayer === failedPlayer) releaseVocalPlayer()
                true
            }
            candidate.prepareAsync()
            return true
        } catch (e: Exception) {
            releaseVocalPlayer()
            toast("外置原伴唱加载失败：" + e.message)
            return false
        }
    }

    /**
     * 双音轨 TS 使用一个常驻的静音副播放器预解码另一条音轨。切换时只交叉调整两个
     * 播放器的音量，不再对主 IJKPlayer 调用 selectTrack，避免 AudioTrack 被销毁重建。
     */
    private fun prepareEmbeddedDualTrackVocal(song: Song?, positionMs: Int, forceSeek: Boolean = false): Boolean {
        val videoPlayer = player ?: return false
        val path = song?.path?.takeIf { it.isNotBlank() } ?: return false
        if (videoPlayer.audioTrackCount() < 2 || !File(path).isFile) {
            if (embeddedDualTrackMode) releaseVocalPlayer()
            return false
        }
        if (embeddedDualTrackMode && currentVocalPath == path && embeddedVocalEngine != null) {
            if (!vocalPlayerPrepared && embeddedMainTrackOriginal != originalVocal) {
                releaseVocalPlayer()
            } else {
                // 进度条拖动(forceSeek)必须把副解码器一起拉过去，否则声音留在原进度
                if (forceSeek && vocalPlayerPrepared) {
                    runCatching {
                        val target = adjustedAudioPosition(positionMs)
                        pendingVocalPosition = target
                        embeddedVocalEngine!!.seekTo(target)
                        // 保持拖动前的播放态：暂停时拖动不能强制起播
                        if (playWhenPrepared) startVocalIfReady() else pauseVocalIfReady()
                    }
                }
                applyVocalVolume()
                return vocalPlayerPrepared
            }
        }

        releaseVocalPlayer()
        return try {
            embeddedDualTrackMode = true
            embeddedMainTrackOriginal = originalVocal
            pendingVocalPosition = adjustedAudioPosition(positionMs)
            currentVocalPath = path
            val candidate = KtvPlaybackEngine(this)
            embeddedVocalEngine = candidate
            vocalPlayerPrepared = false
            candidate.setVolume(0f, 0f)
            candidate.setOnCompletionListener(null)
            candidate.setOnPreparedListener {
                if (embeddedVocalEngine !== candidate || currentVocalPath != path || !embeddedDualTrackMode) {
                    candidate.release()
                    return@setOnPreparedListener
                }
                if (candidate.audioTrackCount() < 2 ||
                    !candidate.selectAudioTrack(!embeddedMainTrackOriginal)
                ) {
                    Log.w(
                        TAG,
                        "Embedded seamless vocal unavailable: tracks=${candidate.audioTrackCount()} path=$path",
                    )
                    releaseVocalPlayer()
                    return@setOnPreparedListener
                }
                vocalPlayerPrepared = true
                // The secondary decoder starts roughly one audio buffer behind the visible player.
                // Give it a small head start once, while it is still muted, so later volume-only
                // switches land on the same presentation timestamp.
                val synchronizedPosition = adjustedAudioPosition(
                    (player?.currentPosition ?: pendingVocalPosition) + 120,
                )
                if (synchronizedPosition > 0) candidate.seekTo(synchronizedPosition)
                applyVocalVolume()
                if (playWhenPrepared) startVocalIfReady() else pauseVocalIfReady()
                Log.i(
                    TAG,
                    "Embedded seamless vocal ready tracks=${candidate.audioTrackCount()} " +
                        "mainOriginal=$embeddedMainTrackOriginal path=$path",
                )
            }
            candidate.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Embedded seamless vocal failed what=$what extra=$extra path=$path")
                if (embeddedVocalEngine === candidate) releaseVocalPlayer()
                true
            }
            candidate.setVideoUri(Uri.fromFile(File(path)))
            false
        } catch (e: Exception) {
            Log.w(TAG, "Embedded seamless vocal prepare failed path=$path", e)
            releaseVocalPlayer()
            false
        }
    }

    private fun selectedVocalPath(song: Song?): String {
        if (song == null) return ""
        val direct = if (originalVocal) song.originalPath else song.accompanyPath
        val databasePath = if (originalVocal) song.pOrigin else song.pAccomp
        return direct?.takeIf { it.isNotBlank() && it !in setOf("0", "1", "2") }
            ?: databasePath?.takeIf { it.isNotBlank() && it !in setOf("0", "1", "2") }
            ?: ""
    }

    private fun applyVocalVolume() {
        if (vocalPlayer == null && embeddedVocalEngine == null) return
        val alternateTrackActive = embeddedDualTrackMode && originalVocal != embeddedMainTrackOriginal
        val volume = if ("静音练唱" == singMode || embeddedDualTrackMode && !alternateTrackActive) {
            0f
        } else {
            max(0f, min(1f, musicVolume / 100f))
        }
        try {
            if (embeddedDualTrackMode) {
                embeddedVocalEngine?.setVolume(volume, volume)
            } else {
                vocalPlayer?.setVolume(volume, volume)
            }
        } catch (ignored: Exception) {
        }
    }

    private fun startVocalIfReady() {
        if (!vocalPlayerPrepared) return
        try {
            if (embeddedDualTrackMode) {
                if (embeddedVocalEngine?.isPlaying != true) embeddedVocalEngine?.start()
            } else if (vocalPlayer?.isPlaying != true) {
                vocalPlayer?.start()
            }
        } catch (ignored: Exception) {
        }
    }

    private fun pauseVocalIfReady() {
        if (!vocalPlayerPrepared) return
        try {
            if (embeddedDualTrackMode) {
                if (embeddedVocalEngine?.isPlaying == true) embeddedVocalEngine?.pause()
            } else if (vocalPlayer?.isPlaying == true) {
                vocalPlayer?.pause()
            }
        } catch (ignored: Exception) {
        }
    }

    private fun releaseVocalPlayer() {
        embeddedVocalEngine?.release()
        embeddedVocalEngine = null
        if (vocalPlayer != null) {
            try {
                vocalPlayer!!.release()
            } catch (ignored: Exception) {
            }
        }
        vocalPlayer = null
        vocalPlayerPrepared = false
        embeddedDualTrackMode = false
        embeddedMainTrackOriginal = true
        pendingVocalPosition = 0
        currentVocalPath = ""
    }

    private fun adjustedAudioPosition(videoPositionMs: Int): Int {
        return max(0, videoPositionMs - audioDelayMs)
    }

    private fun loadLyrics(song: Song?) {
        lyricLines.clear()
        if (song == null || !hasUrl(song.lyricPath)) {
            showLyricText("暂无歌词", "")
            return
        }
        val lyricPath = song.lyricPath.orEmpty()
        val file = File(lyricPath)
        if (!file.exists() || !file.getName().lowercase().endsWith(".lrc")) {
            showLyricText("歌词文件：" + file.getName(), "暂仅同步 LRC 歌词")
            return
        }
        try {
            BufferedReader(InputStreamReader(FileInputStream(file), "UTF-8")).use { reader ->
                var line: String?
                while ((reader.readLine().also { line = it }) != null) parseLyricLine(line)
                Collections.sort<LyricLine?>(
                    lyricLines, Comparator.comparingInt<LyricLine?>(
                        ToIntFunction { item: LyricLine? -> item!!.timeMs })
                )
                updateLyricView()
            }
        } catch (e: Exception) {
            showLyricText("歌词读取失败", e.message)
        }
    }

    private fun parseLyricLine(line: String?) {
        val value = if (line == null) "" else line.trim { it <= ' ' }
        if (value.isEmpty()) return
        val lastBracket = value.lastIndexOf(']')
        if (!value.startsWith("[") || lastBracket < 0 || lastBracket >= value.length - 1) return
        val text = value.substring(lastBracket + 1).trim { it <= ' ' }
        if (text.isEmpty()) return
        var index = 0
        while (index < lastBracket) {
            val open = value.indexOf('[', index)
            val close = value.indexOf(']', open + 1)
            if (open < 0 || close < 0 || close > lastBracket) break
            val time = parseLyricTime(value.substring(open + 1, close))
            if (time >= 0) lyricLines.add(LyricLine(time, text))
            index = close + 1
        }
    }

    private fun parseLyricTime(value: String): Int {
        try {
            val parts = value.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            if (parts.size != 2) return -1
            val minutes = parts[0].toInt()
            val sec = parts[1].split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            val seconds = sec[0].toInt()
            var millis = 0
            if (sec.size > 1) {
                val fraction = (sec[1] + "00").substring(0, 2)
                millis = fraction.toInt() * 10
            }
            return minutes * 60000 + seconds * 1000 + millis
        } catch (e: Exception) {
            return -1
        }
    }

    private fun updateLyricView() {
        if (lyricCurrent == null || lyricNext == null || lyricLines.isEmpty() || player == null) return
        val pos = player!!.currentPosition
        var current = 0
        for (i in lyricLines.indices) {
            if (lyricLines.get(i)!!.timeMs <= pos) current = i
            else break
        }
        val next = if (current + 1 < lyricLines.size) lyricLines.get(current + 1)!!.text else ""
        showLyricText(lyricLines.get(current)!!.text, next)
    }

    private fun clearLyrics() {
        lyricLines.clear()
        showLyricText("暂无歌词", "")
    }

    private fun showLyricText(current: String?, next: String?) {
        if (lyricCurrent != null) lyricCurrent!!.setText(if (current == null) "" else current)
        if (lyricNext != null) lyricNext!!.setText(if (next == null) "" else next)
    }

    private fun moveSelectedQueueToTop() {
        if (queueList == null || orderQueue!!.isEmpty()) return
        var pos = queueList!!.getCheckedItemPosition()
        if (pos < 0) pos = queueList!!.getSelectedItemPosition()
        if (pos > 0 && pos < orderQueue.size) {
            val song = orderQueue.removeAt(pos)
            orderQueue.add(0, song)
            saveState()
            renderQueue()
        }
    }

    private fun removeSelectedQueue() {
        if (queueList == null || orderQueue!!.isEmpty()) return
        var pos = queueList!!.getCheckedItemPosition()
        if (pos < 0) pos = queueList!!.getSelectedItemPosition()
        if (pos >= 0 && pos < orderQueue.size) {
            orderQueue.removeAt(pos)
            saveState()
            renderQueue()
        }
    }

    private fun showSongActions(song: Song) {
        val hasLocalFile = hasValidLocalSongFile(song)
        val isFavorite = store.isFavorite(song)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(8), dp(28), dp(22))
        }
        val title = TextView(this).apply {
            text = song.title.orEmpty()
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(20))
        }
        panel.addView(title, LinearLayout.LayoutParams(-1, -2))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        lateinit var dialog: AlertDialog
        fun action(iconRes: Int, labelText: String, iconTint: Int? = null, block: () -> Unit): View = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            setBackgroundResource(R.drawable.bg_song_action_focus)
            addView(ImageView(this@MainActivity).apply {
                setImageResource(iconRes)
                iconTint?.let { imageTintList = ColorStateList.valueOf(it) }
                setPadding(dp(18), dp(18), dp(18), dp(18))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.rgb(92, 92, 97))
                }
            }, LinearLayout.LayoutParams(dp(76), dp(76)))
            addView(TextView(this@MainActivity).apply {
                text = labelText; textSize = 17f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                setPadding(0, dp(8), 0, 0)
            }, LinearLayout.LayoutParams(-1, -2))
            setOnClickListener { dialog.dismiss(); block() }
        }
        actions.addView(action(R.drawable.ott_ic_top, "置顶") {
            if (!orderQueue!!.contains(song)) addToQueue(song)
            moveQueuedSongToNext(song)
            toast("已置顶：${song.title}")
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(action(
            if (isFavorite) R.drawable.ott_ic_favorite_cancel else R.drawable.ott_ic_favorite,
            if (isFavorite) "取消收藏" else "收藏",
            if (isFavorite) Color.rgb(233, 30, 99) else null,
        ) {
            val added = store.toggleFavorite(song)
            saveState()
            toast(if (added) "已收藏" else "已取消收藏")
            if (browseMode == "favorites") {
                visibleSongs.removeAll { stableId(it) == stableId(song) }
                renderSongList()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (hasLocalFile) {
            actions.addView(action(R.drawable.ott_ic_delete, "删除文件") {
                confirmDeleteLocalFile(song)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        panel.addView(actions, LinearLayout.LayoutParams(-1, -2))
        panel.addView(TextView(this).apply {
            text = song.singer.orEmpty()
            textSize = 20f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.WHITE)
            setPadding(dp(24), 0, dp(24), 0)
            setBackgroundResource(R.drawable.ott_bg_item)
            isFocusable = false
            isClickable = false
        }, LinearLayout.LayoutParams(-1, dp(70)).apply { topMargin = dp(22) })
        dialog = AlertDialog.Builder(this).setView(panel).create()
        dialog.showForTv(afterShow = { actions.getChildAt(0)?.requestFocus() })
    }

    private fun hasValidLocalSongFile(song: Song): Boolean {
        val direct = song.path?.let(::File)
        if (direct != null && SongFileValidator.inspect(
                direct,
                SongFileValidator.requiresTransportStream(direct),
            ).valid
        ) return true
        return isDownloaded(song)
    }

    private fun confirmDeleteLocalFile(song: Song) {
        AlertDialog.Builder(this)
            .setTitle("删除本地文件")
            .setMessage("确定删除 ${song.title} 的本地文件吗？歌曲仍保留在曲库中。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ -> deleteDownloadedSong(song) }
            .create().showForTv()
    }

    private fun showSongInfo(song: Song) {
        val message = ("歌名：" + song.title
                + "\n歌手：" + song.singer
                + "\n分类：" + song.category
                + "\n语种：" + song.language
                + "\n拼音：" + song.pinyin
                + "\n原唱资源：" + resourceState(song.originalUrl, song.originalPath)
                + "\n伴唱资源：" + resourceState(song.accompanyUrl, song.accompanyPath)
                + "\n歌词资源：" + resourceState(song.lyricUrl, song.lyricPath)
                + "\n来源：" + (if (song.remote) "网络曲库" else song.path))
        AlertDialog.Builder(this).setTitle("歌曲信息").setMessage(message)
            .setPositiveButton("确定", null).create().showForTv(DialogInterface.BUTTON_POSITIVE)
    }

    private fun resourceState(remoteUrl: String?, localPath: String?): String {
        if (hasUrl(localPath)) return "本地已下载"
        if (hasUrl(remoteUrl)) return "网络可下载"
        return "无"
    }

    private fun confirmRemoveSong(song: Song) {
        val title = if (song.remote) "从网络曲库移除" else "删除本地文件"
        val message = if (song.remote)
            "该操作会在本机隐藏这首网络曲库歌曲。"
        else
            "该操作会删除本地文件，并从已点、收藏和歌单中移除。"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message + "\n\n" + song.displayTitle())
            .setPositiveButton(
                "确定",
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int -> removeSong(song) })
            .setNegativeButton("取消", null)
            .create().showForTv()
    }

    private fun removeSong(song: Song) {
        val id = stableId(song)
        if (!song.remote && song.path != null && !song.path!!.isEmpty()) {
            val file = File(song.path.orEmpty())
            if (file.exists() && !file.delete()) {
                toast("文件删除失败")
                return
            }
        }
        store.hiddenSongIds.add(id)
        store.favoriteIds.remove(id)
        for (ids in store.playlistSongs.values) ids.remove(id)
        removeSongFromList(orderQueue!!, id)
        removeSongFromList(sangHistory, id)
        saveState()
        refreshLibrary(Runnable {
            toast("已移除：" + song.title)
            if ("收藏" == currentPage) showFavorites()
            else if ("已点" == currentPage) showQueue()
            else showSongs("全部")
        })
    }

    private fun removeSongFromList(songs: MutableList<Song>, id: String?) {
        for (i in songs.indices.reversed()) {
            if (stableId(songs.get(i)) == id) songs.removeAt(i)
        }
    }

    private fun showCreatePlaylistDialog() {
        val input = EditText(this)
        input.setSingleLine(true)
        input.setHint("歌单名称")
        AlertDialog.Builder(this)
            .setTitle("新建歌单")
            .setView(input)
            .setPositiveButton(
                "创建",
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    val name = input.getText().toString().trim { it <= ' ' }
                    if (!name.isEmpty() && !store.playlists.contains(name)) {
                        store.playlists.add(name)
                        saveState()
                    }
                    showPlaylists()
                })
            .setNegativeButton("取消", null)
            .create().showForTv()
    }

    private fun fetchCatalog() {
        val url: String = prefs()!!.getString(KEY_CATALOG_URL, "")!!
        if (url.trim { it <= ' ' }.isEmpty()) {
            showCatalogDialog()
            return
        }
        busy(true, "正在同步网络曲库...")
        io.execute(Runnable {
            try {
                val array = CatalogClient().fetch(url)
                library.saveRemote(array)
                library.scanLocal()
                main.post(Runnable {
                    busy(false, "网络曲库已同步：" + array.length() + " 首")
                    if (currentTabIndex == 6) loadDownloadedList()
                    else showNetworkCatalogPage()
                })
            } catch (e: Exception) {
                main.post(Runnable { busy(false, "网络曲库同步失败：" + e.message) })
            }
        })
    }

    private fun refreshLibrary(after: Runnable?) {
        busy(true, "正在扫描曲库...")
        io.execute(Runnable {
            val locals = library.scanLocal().size
            library.loadCachedRemote()
            main.post(Runnable {
                busy(
                    false,
                    "曲库已更新：本地 " + locals + " 首，总计 " + library.allSongs().size + " 首"
                )
                if (after != null) after.run()
            })
        })
    }

    private fun showNetworkCatalogPage() {
        currentTabIndex = -1
        showSubPageShell("主页 / 网络曲库")
        setupTabs()
        browseMode = "direct_catalog"
        browsePage = 0
        currentCategories.clear()
        currentCategoryIndex = -1
        updateCategories()
        keyboardArea?.visibility = View.GONE
        paginationBar?.visibility = View.GONE
        browsePagination?.visibility = View.GONE
        listTitle?.text = "网络曲库 · 在线播放"
        visibleSongs.clear()
        visibleSongs.addAll(library.allSongs().filter { it.remote && isVisibleSong(it) })
        renderSongList()
        if (visibleSongs.isEmpty()) toast("尚未配置网络歌曲，可在设置中的曲库方式填写自己的曲库地址")
    }

    private fun showCatalogDialog() {
        val input = EditText(this)
        input.setSingleLine(true)
        input.setText(prefs()!!.getString(KEY_CATALOG_URL, ""))
        input.setHint("http://你的服务器/catalog.json")
        AlertDialog.Builder(this)
            .setTitle("网络曲库源")
            .setMessage("填写自己的 NAS / 媒体服务器曲库 JSON 地址。只同步歌曲目录，点播 HTTP(S) 媒体时在线播放，不先下载整首歌曲。\nJSON: [{\"title\":\"歌名\",\"singer\":\"歌手\",\"url\":\"http://.../song.mp4\"}]")
            .setView(input)
            .setPositiveButton(
                "保存并同步",
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    prefs()!!.edit()
                        .putString(KEY_CATALOG_URL, input.getText().toString().trim { it <= ' ' })
                        .apply()
                    fetchCatalog()
                })
            .setNegativeButton("取消", null)
            .create().showForTv()
    }

    private fun renderSongs() {
        if (songAdapter == null) return
        songAdapter!!.clear()
        for (i in visibleSongs.indices) {
            val song = visibleSongs.get(i)
            songAdapter!!.add(
                String.format(
                    Locale.ROOT,
                    "%02d  %s%s  -  %s    [%s/%s]%s",
                    i + 1,
                    if (store.isFavorite(song)) "★ " else "",
                    song.title,
                    song.singer,
                    song.category,
                    song.language,
                    if (song.remote) "  下载" else "  点歌"
                )
            )
        }
        songAdapter!!.notifyDataSetChanged()
        header!!.setText(currentPage + "  " + visibleSongs.size)
    }

    private fun renderQueue() {
        if (queueAdapter == null) return
        queueAdapter!!.clear()
        for (i in orderQueue!!.indices) queueAdapter!!.add(
            (i + 1).toString() + ". " + orderQueue.get(
                i
            ).displayTitle()
        )
        queueAdapter!!.notifyDataSetChanged()
    }

    private fun addNav(text: String?, listener: View.OnClickListener) {
        val index = navButtons.size
        val btn = button(text, listener)
        // 注册到列表以便维护选中态
        navButtons.add(btn)
        // 点击时更新选中态
        btn.setOnClickListener(View.OnClickListener { v: View? ->
            selectNav(index)
            listener.onClick(v)
        })
        nav!!.addView(btn, LinearLayout.LayoutParams(-1, dp(54)))
    }

    private fun addTile(
        grid: GridLayout,
        title: String?,
        sub: String?,
        listener: View.OnClickListener?
    ) {
        // 卡片式磁贴:圆角背景 + 描边 + 焦点高亮
        val tile = label(title + "\n" + sub, 20, TEXT_WHITE)
        tile.setFocusable(true)
        tile.setClickable(true)
        tile.setGravity(Gravity.CENTER)
        tile.setMinHeight(dp(110))
        // 默认背景:深色圆角 + 浅描边
        tile.setBackground(roundedBg(PANEL, 8, DIVIDER, 1))
        tile.setOnClickListener(listener)
        tile.setOnFocusChangeListener(OnFocusChangeListener { v: View?, hasFocus: Boolean ->
            if (hasFocus) {
                // 焦点态:青绿色圆角 + 白色描边
                v!!.setBackground(roundedBg(FOCUS, 8, Color.argb(180, 255, 255, 255), 2))
                tile.setTextColor(Color.rgb(8, 12, 18))
            } else {
                v!!.setBackground(roundedBg(PANEL, 8, DIVIDER, 1))
                tile.setTextColor(TEXT_WHITE)
            }
        })
        val lp = GridLayout.LayoutParams()
        lp.width = dp(210)
        lp.height = dp(126)
        lp.setMargins(dp(8), dp(8), dp(8), dp(8))
        grid.addView(tile, lp)
    }

    private fun setPage(page: String) {
        currentPage = page
        if (header != null) header!!.setText(page)
        if (listTitle != null) listTitle!!.setText(page)
    }

    private fun panel(): LinearLayout {
        val layout = LinearLayout(this)
        layout.setOrientation(LinearLayout.VERTICAL)
        layout.setPadding(dp(12), dp(12), dp(12), dp(12))
        // 圆角面板背景
        layout.setBackground(roundedBg(PANEL, 6, 0, 0))
        return layout
    }

    private fun sectionTitle(text: String?): TextView {
        val view = label(text, 22, GOLD)
        view.setMinHeight(dp(46))
        // 标题底部加一条浅色分割线
        view.setPadding(dp(8), dp(6), dp(8), dp(10))
        return view
    }

    // ===== TV 布局视图字段 =====
    /** 视频背景  */
    private var videoBackground: KtvVideoView? = null
    private var playerLayerRoot: FrameLayout? = null
    private var playerFocusBorder: View? = null
    private var playerTouchOverlay: View? = null
    private var homePlayerHost: View? = null
    private var subPagePlayerHost: View? = null
    private var activePlayerHost: View? = null
    private var lastHomeFocusSource: View? = null
    private var lastHomeFocusTarget: View? = null
    private var lastHomeFocusReverseKey: Int = KeyEvent.KEYCODE_UNKNOWN

    /** 顶部状态栏区域  */
    private val topStatusBar: LinearLayout? = null
    /** 当前播放提示  */
    /** 下载速度提示  */
    private var textDownloadSpeed: TextView? = null

    /** 下载进度容器  */
    private var downloadProgressContainer: LinearLayout? = null

    /** 下载状态文字  */
    private var textDownloadStatus: TextView? = null

    /** 下载进度条  */
    private var downloadProgressBar: ProgressBar? = null

    /** 下载速度估算：上一次采样时的已下载字节与时间戳  */
    private var speedSampleBytes = 0L
    private var speedSampleAt = 0L

    /** Tab 容器  */
    private var tabContainer: LinearLayout? = null

    /** 已点列表按钮  */

    /** 已点数量徽标  */
    private var textOrderBadge: TextView? = null
    /** 搜索按钮  */
    /** 搜索输入框  */
    private var searchInput: EditText? = null

    /** 搜索执行按钮  */
    private var btnDoSearch: TextView? = null

    /** 分类列表容器  */
    private var categoryList: LinearLayout? = null

    /** 列表标题  */
    private var listTitle: TextView? = null

    /** 分页信息  */
    private var textPageInfo: TextView? = null

    /** 上一页按钮  */
    private var btnPrevPage: TextView? = null

    /** 下一页按钮  */
    private var btnNextPage: TextView? = null

    /** 当前播放歌曲标题  */
    private var playerSongTitle: TextView? = null

    /** 当前播放歌手  */
    private var playerSongSinger: TextView? = null
    /** 原唱/伴唱切换按钮  */
    /** 原唱/伴唱文本  */
    private val textVocalMode: TextView? = null
    private var breadcrumbCurrent: TextView? = null

    /** 播放/暂停文本  */
    private val textPlayPause: TextView? = null

    /** 切歌按钮  */
    private val btnNextSong: TextView? = null

    /** 重唱按钮  */
    private val btnReplay: TextView? = null

    /** 音量按钮  */
    private val btnVolume: TextView? = null
    /** ===== 新版UI字段(顶栏/底栏/首页3列) =====  */
    /** 顶部状态栏  */
    private var topBar: View? = null

    /** 底部控制栏  */
    private var bottomBar: View? = null

    /** 顶栏品牌名 */
    private var topBrandName: TextView? = null
    private var contentFrame: FrameLayout? = null
    private var browsePagination: View? = null

    /** 顶栏搜索按钮  */
    private var btnTopSearch: TextView? = null

    /** 顶栏已点按钮(整条)  */
    private var btnTopOrder: LinearLayout? = null
    private var textTopOrderLabel: TextView? = null

    /** 已点数量徽章  */
    private var textTopOrderBadge: TextView? = null

    /** 顶栏伴唱按钮  */
    private var btnTopVocal: TextView? = null

    /** 顶栏切歌按钮  */
    private var btnTopNext: TextView? = null

    /** 顶栏暂停按钮  */
    private var btnTopPause: TextView? = null

    /** 顶栏重唱按钮  */
    private var btnTopReplay: TextView? = null

    /** 顶栏调音按钮  */
    private var btnTopTone: TextView? = null

    /** 底栏猜你想唱  */
    private var btnBottomGuess: TextView? = null

    /** 底栏设置  */
    private var btnBottomSettings: TextView? = null

    /** 底栏扫码点歌 */
    private var btnBottomMobileOrder: TextView? = null

    /** 底栏退出  */
    private var btnBottomExit: TextView? = null

    /** 首页 3 列布局根  */
    private var homeLayout: LinearLayout? = null

    /** 首页视频窗口  */
    private var homeVideo: KtvVideoView? = null

    /** 首页视频标题  */
    private var homePlayerTitle: TextView? = null

    /** 首页视频歌手  */
    private var homePlayerSinger: TextView? = null

    /** 首页4个分类卡片  */
    private var homeCardRank: TextView? = null
    private var homeCardSong: TextView? = null
    private var homeCardSinger: TextView? = null
    private var homeCardLocal: TextView? = null
    private var homeCardRegular: TextView? = null
    private var homeCardFavorite: TextView? = null
    private var homeCardCategory: TextView? = null

    /** 新歌榜海报分页  */
    private var textPosterPage: TextView? = null
    private var homePosterView: View? = null

    /** 当前歌曲顶部提示(用于子页面左列)  */
    private var textCurrentSongTip: TextView? = null

    /** 子页面返回按钮(右栏顶部)  */
    private var btnBack: TextView? = null

    /** 品牌标识  */
    private var textBrandRight: TextView? = null

    /** 左侧栏布局引用  */
    private var leftColumn: LinearLayout? = null
    private var rightColumn: LinearLayout? = null

    /** 子页面二级分类滚动容器(语种筛选)  */
    private var subCategoryScroll: HorizontalScrollView? = null
    private var keyboardArea: View? = null
    private var rankCategoryGrid: GridLayout? = null
    private var paginationBar: View? = null
    private var fullScreenContainer: FrameLayout? = null
    private var isFullScreen = false
    private var playerReturnSurface: KtvVideoView? = null
    private var playerReturnHost: View? = null
    private var fullScreenPauseText: TextView? = null
    private var fullScreenVocalText: TextView? = null
    private var fullScreenPauseIcon: ImageView? = null
    private var fullScreenVocalIcon: ImageView? = null
    private var fullScreenSongInfo: TextView? = null
    private var fullScreenSeekBar: SeekBar? = null
    private var fullScreenTime: TextView? = null
    private var fullScreenSeeking = false
    private var fullScreenControls: LinearLayout? = null
    private var fullScreenProgressRow: View? = null
    private var fullScreenToneControl: View? = null
    private var fullScreenVolumePanel: View? = null
    private var fullScreenVolumeIndicator: CircularVolumeView? = null
    private var fullScreenVolumeMinusButton: TextView? = null
    private var fullScreenVolumePlusButton: TextView? = null
    private var fullScreenVolumeMuteButton: TextView? = null
    private var fullScreenPreviousVolume = 70
    private var randomLocalRequestSerial = 0L
    private var lastRandomLocalSongId: String? = null
    private var fullScreenChromeVisible = false
    private val hideFullScreenVolumeIndicatorRunnable = Runnable {
        fullScreenVolumeIndicator?.animate()?.alpha(0f)?.setDuration(220L)?.withEndAction {
            fullScreenVolumeIndicator?.visibility = View.GONE
            fullScreenVolumeIndicator?.alpha = 1f
        }?.start()
    }
    private val hideFullScreenChromeRunnable = Runnable {
        if (isFullScreen && fullScreenVolumePanel?.visibility != View.VISIBLE) {
            setFullScreenChromeVisible(false)
        }
    }
    private var playbackModeNotice: TextView? = null
    private var songIntroNotice: TextView? = null
    private var songIntroIcon: TextView? = null
    private val hidePlaybackModeNotice = Runnable {
        playbackModeNotice?.animate()?.alpha(0f)?.setDuration(180L)?.withEndAction {
            playbackModeNotice?.visibility = View.GONE
        }?.start()
    }
    private val hideSongIntroNotice = Runnable {
        songIntroNotice?.animate()?.alpha(0f)?.setDuration(250L)?.withEndAction {
            songIntroNotice?.visibility = View.GONE
            songIntroIcon?.visibility = View.GONE
        }?.start()
    }

    /** 当前选中的 Tab 索引  */
    private var currentTabIndex = -1 // -1 = 首页

    /** 当前分类列表  */
    private val currentCategories: MutableList<String> = ArrayList<String>()

    /** 排行榜显示名对应的数据库 playlist id，索引与 currentCategories 一致。 */
    private val rankPlaylistIds: MutableList<String> = ArrayList<String>()

    /** 本机实时热歌的虚拟 playlist id（不对应服务器表，仅用于在排行榜里标识本地实时分类）。 */
    private val LOCAL_HOT_PLAYLIST_ID = "__local_hot__"

    /** 当前分类索引  */
    private var currentCategoryIndex = 0

    private var categoryParentPage = 0
    private var categoryParentQuery = ""

    private fun label(text: String?, sp: Int, color: Int): TextView {
        val view = TextView(this)
        view.setText(text)
        view.setTextSize(sp.toFloat())
        view.setTextColor(color)
        view.setGravity(Gravity.CENTER_VERTICAL)
        view.setPadding(dp(8), dp(6), dp(8), dp(6))
        return view
    }

    private fun setTopControlIcon(view: TextView?, drawableRes: Int) {
        if (view == null) return
        val icon = androidx.appcompat.content.res.AppCompatResources.getDrawable(this, drawableRes)?.mutate()
        icon?.setBounds(0, 0, dp(18), dp(18))
        view.setCompoundDrawablesRelative(icon, null, null, null)
        view.compoundDrawablePadding = dp(5)
    }

    private fun button(text: String?, listener: View.OnClickListener?): TextView {
        val view = label(text, 18, TEXT_WHITE)
        view.setFocusable(true)
        view.setClickable(true)
        view.setMinHeight(dp(50))
        view.setOnClickListener(listener)
        // 焦点态:圆角青绿色背景;失去焦点:透明
        view.setOnFocusChangeListener(OnFocusChangeListener { v: View?, hasFocus: Boolean ->
            if (hasFocus) {
                val gd = GradientDrawable()
                gd.setColor(FOCUS)
                gd.setCornerRadius(dp(6).toFloat())
                gd.setStroke(dp(1), Color.argb(100, 255, 255, 255))
                v!!.setBackground(gd)
                view.setTextColor(Color.rgb(8, 12, 18))
            } else {
                v!!.setBackgroundColor(Color.TRANSPARENT)
                view.setTextColor(TEXT_WHITE)
            }
        })
        return view
    }

    /**
     * 创建圆角面板背景 drawable
     * @param bgColor 背景色
     * @param cornerRadiusDp 圆角半径(dp)
     * @param strokeColor 描边色(0 表示无描边)
     * @param strokeWidthDp 描边宽度(dp)
     * @return GradientDrawable 圆角背景
     */
    private fun roundedBg(
        bgColor: Int,
        cornerRadiusDp: Int,
        strokeColor: Int,
        strokeWidthDp: Int
    ): GradientDrawable {
        val gd = GradientDrawable()
        gd.setColor(bgColor)
        gd.setCornerRadius(dp(cornerRadiusDp).toFloat())
        if (strokeColor != 0 && strokeWidthDp > 0) {
            gd.setStroke(dp(strokeWidthDp), strokeColor)
        }
        return gd
    }

    private fun listView(): ListView {
        val view = ListView(this)
        view.setBackgroundColor(PANEL_2)
        view.setCacheColorHint(Color.TRANSPARENT)
        // 分割线:半透明白色,1px 高
        view.setDivider(ColorDrawable(DIVIDER))
        view.setDividerHeight(dp(1))
        view.setFocusable(true)
        return view
    }

    private fun tvAdapter(): ArrayAdapter<String?> {
        return object :
            ArrayAdapter<String?>(this, android.R.layout.simple_list_item_1, ArrayList<String?>()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.setTextColor(TEXT_WHITE)
                view.setTextSize(18f)
                view.setMinHeight(dp(48))
                view.setGravity(Gravity.CENTER_VERTICAL)
                view.setPadding(dp(14), dp(4), dp(14), dp(4))
                return view
            }
        }
    }

    /**
     * 初始化 TV 布局的视图组件和事件监听
     */
    private fun initTvLayout() {
        // 绑定主布局视图组件
        val subPageVideoPlaceholder = findViewById<KtvVideoView?>(R.id.video_background)
        playerLayerRoot = findViewById(R.id.layout_root)
        homePlayerHost = findViewById(R.id.home_player_container)
        subPagePlayerHost = findViewById(R.id.player_container)
        textDownloadSpeed = findViewById<TextView?>(R.id.text_download_speed)
        downloadProgressContainer = findViewById<LinearLayout?>(R.id.download_progress_container)
        textDownloadStatus = findViewById<TextView?>(R.id.text_download_status)
        downloadProgressBar = findViewById<ProgressBar?>(R.id.download_progress_bar)
        tabContainer = findViewById<LinearLayout?>(R.id.tab_container)
        // 子页面沿用顶部已点数字徽章。
        textOrderBadge = null
        searchInput = findViewById<EditText?>(R.id.search_input)
        btnDoSearch = findViewById<TextView?>(R.id.btn_do_search)
        categoryList = findViewById<LinearLayout>(R.id.category_list)
        textPageInfo = findViewById<TextView>(R.id.text_page_info)
        btnPrevPage = findViewById<TextView?>(R.id.btn_prev_page)
        btnNextPage = findViewById<TextView?>(R.id.btn_next_page)
        songList = findViewById<ListView?>(R.id.song_list)
        playerSongTitle = findViewById<TextView?>(R.id.player_song_title)
        playerSongSinger = findViewById<TextView?>(R.id.player_song_singer)
        breadcrumbCurrent = findViewById<TextView?>(R.id.breadcrumb_current)
        // 新布局不显示独立列表标题，不能让业务标题覆盖面包屑。
        listTitle = TextView(this)
        val breadcrumbHome = findViewById<TextView?>(R.id.breadcrumb_home)
        if (breadcrumbHome != null) breadcrumbHome.setOnClickListener { navigateBackToHome() }

        // ===== 顶栏/底栏控件 =====
        topBar = findViewById<View?>(R.id.top_bar)
        bottomBar = findViewById<View?>(R.id.bottom_bar)
        topBrandName = findViewById<TextView?>(R.id.top_brand_name)
        contentFrame = findViewById<FrameLayout?>(R.id.content_frame)
        browsePagination = findViewById<View?>(R.id.browse_pagination)
        btnTopSearch = findViewById<TextView?>(R.id.btn_top_search)
        btnTopOrder = findViewById<LinearLayout?>(R.id.btn_top_order)
        textTopOrderLabel = findViewById<TextView?>(R.id.text_top_order_label)
        textTopOrderBadge = findViewById<TextView?>(R.id.text_top_order_badge)
        btnTopVocal = findViewById<TextView?>(R.id.btn_top_vocal)
        btnTopNext = findViewById<TextView?>(R.id.btn_top_next)
        btnTopPause = findViewById<TextView?>(R.id.btn_top_pause)
        btnTopReplay = findViewById<TextView?>(R.id.btn_top_replay)
        btnTopTone = findViewById<TextView?>(R.id.btn_top_tone)
        btnTopVocal?.text = vocalActionText()
        btnTopPause?.text = if (playWhenPrepared) "暂停" else "播放"
        setTopControlIcon(textTopOrderLabel, R.drawable.ott_ic_ctrl_orderlist)
        setTopControlIcon(btnTopVocal, vocalActionIcon())
        setTopControlIcon(btnTopNext, R.drawable.ott_ic_ctrl_switch_song)
        setTopControlIcon(
            btnTopPause,
            if (playWhenPrepared) R.drawable.ott_ic_ctrl_pause else R.drawable.ott_ic_ctrl_play,
        )
        setTopControlIcon(btnTopReplay, R.drawable.ott_ic_ctrl_replay)
        setTopControlIcon(btnTopTone, R.drawable.ott_ic_ctrl_volume)
        btnBottomGuess = findViewById<TextView?>(R.id.btn_bottom_guess)
        btnBottomMobileOrder = findViewById<TextView?>(R.id.btn_bottom_mobile_order)
        btnBottomSettings = findViewById<TextView?>(R.id.btn_bottom_settings)
        btnBottomExit = findViewById<TextView?>(R.id.btn_bottom_exit)

        // ===== 首页 3 列布局 =====
        homeLayout = findViewById<LinearLayout?>(R.id.home_layout)
        val homeVideoPlaceholder = findViewById<KtvVideoView?>(R.id.home_video_background)
        homePlayerTitle = findViewById<TextView?>(R.id.home_player_song_title)
        homePlayerSinger = findViewById<TextView?>(R.id.home_player_song_singer)
        homeCardRank = findViewById<TextView?>(R.id.home_card_rank)
        homeCardSong = findViewById<TextView?>(R.id.home_card_song)
        homeCardSinger = findViewById<TextView?>(R.id.home_card_singer)
        homeCardLocal = findViewById<TextView?>(R.id.home_card_local)
        homeCardRegular = findViewById<TextView?>(R.id.home_card_regular)
        homeCardFavorite = findViewById<TextView?>(R.id.home_card_favorite)
        homeCardCategory = findViewById<TextView?>(R.id.home_card_category)
        textPosterPage = findViewById<TextView?>(R.id.text_poster_page)

        // ===== 子页面外壳 =====
        textCurrentSongTip = findViewById<TextView?>(R.id.text_current_song_tip)
        btnBack = findViewById<TextView?>(R.id.btn_back)
        textBrandRight = findViewById<TextView?>(R.id.text_brand_right)
        leftColumn = findViewById<LinearLayout?>(R.id.left_column)
        rightColumn = findViewById<LinearLayout?>(R.id.right_column)
        subCategoryScroll = findViewById<HorizontalScrollView?>(R.id.sub_category_scroll)
        keyboardArea = findViewById<View?>(R.id.keyboard_area)
        rankCategoryGrid = findViewById(R.id.rank_category_grid)
        paginationBar = findViewById<View?>(R.id.pagination_bar)
        fullScreenContainer = findViewById<FrameLayout?>(R.id.container)

        // 设置顶栏按钮事件
        if (btnTopSearch != null) btnTopSearch!!.setOnClickListener(View.OnClickListener { v: View? -> showQuickSearchPage() })
        if (btnTopOrder != null) btnTopOrder!!.setOnClickListener(View.OnClickListener { v: View? -> showOrderListPage() })
        if (btnTopVocal != null) btnTopVocal!!.setOnClickListener(View.OnClickListener { v: View? -> toggleOriginalVocal() })
        if (btnTopNext != null) btnTopNext!!.setOnClickListener(View.OnClickListener { v: View? -> playNext() })
        if (btnTopPause != null) btnTopPause!!.setOnClickListener(View.OnClickListener { v: View? -> togglePlay() })
        if (btnTopReplay != null) btnTopReplay!!.setOnClickListener(View.OnClickListener { v: View? -> replay() })
        if (btnTopTone != null) btnTopTone!!.setOnClickListener(View.OnClickListener { v: View? -> showAudioControlDialog() })

        // 设置底栏按钮事件
        if (btnBottomGuess != null) btnBottomGuess!!.setOnClickListener(View.OnClickListener { v: View? -> showGuessPage() })
        if (btnBottomMobileOrder != null) btnBottomMobileOrder!!.setOnClickListener(View.OnClickListener { showMobileRemoteDialog() })
        if (btnBottomSettings != null) btnBottomSettings!!.setOnClickListener(View.OnClickListener { v: View? -> showSettingsPage() })
        if (btnBottomExit != null) btnBottomExit!!.setOnClickListener(View.OnClickListener { v: View? -> showExitDialog() })

        // 设置首页4个分类卡片点击事件
        if (homeCardRank != null) homeCardRank!!.setOnClickListener(View.OnClickListener { v: View? -> loadHotSongs() })
        if (homeCardSong != null) homeCardSong!!.setOnClickListener(View.OnClickListener { v: View? -> showSearchPage() })
        if (homeCardSinger != null) homeCardSinger!!.setOnClickListener(View.OnClickListener { v: View? -> loadSingers() })
        if (homeCardLocal != null) homeCardLocal!!.setOnClickListener(View.OnClickListener { v: View? -> showLocalPage() })
        if (homeCardRegular != null) homeCardRegular!!.setOnClickListener(View.OnClickListener { v: View? -> loadSangHistory() })
        if (homeCardFavorite != null) homeCardFavorite!!.setOnClickListener(View.OnClickListener { v: View? -> loadFavorites() })
        if (homeCardCategory != null) homeCardCategory!!.setOnClickListener(View.OnClickListener { v: View? -> loadCategoryPlaylists() })
        val homePoster = findViewById<View?>(R.id.home_poster_container)
        homePosterView = homePoster
        textPosterPage?.text = "1/${homeBanners.size}"
        main.removeCallbacks(homeBannerRunnable)
        main.postDelayed(homeBannerRunnable, 5000L)
        if (homePoster != null) homePoster.setOnClickListener(View.OnClickListener { v: View? -> loadHotSongs() })
        // 原版始终保留同一个视频窗口，仅在首页、子页和全屏间调整边界。
        // 两个 XML View 只用于提供精确的布局锚点，避免页面操作重建解码 Surface。
        homeVideoPlaceholder?.visibility = View.GONE
        subPageVideoPlaceholder?.visibility = View.GONE
        val persistentVideo = KtvVideoView(this).apply {
            setOnClickListener {
                if (isFullScreen) exitFullScreenPlayer() else showFullScreenPlayer()
            }
        }
        val root = playerLayerRoot
        if (root != null) {
            val maskIndex = root.indexOfChild(findViewById<View>(R.id.mask))
            val insertionIndex = if (maskIndex >= 0) maskIndex else root.childCount
            root.addView(persistentVideo, insertionIndex, FrameLayout.LayoutParams(1, 1))
            playerFocusBorder = View(this).apply {
                setBackgroundResource(R.drawable.bg_player_focus)
                isClickable = false
                isFocusable = false
                visibility = View.GONE
                elevation = dp(6).toFloat()
            }.also { root.addView(it, insertionIndex + 1, FrameLayout.LayoutParams(1, 1)) }
            playbackModeNotice = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 18f
                setTextColor(Color.WHITE)
                visibility = View.GONE
                alpha = 0f
                elevation = dp(8).toFloat()
            }.also { root.addView(it, insertionIndex + 1, FrameLayout.LayoutParams(dp(104), dp(104))) }
            songIntroNotice = TextView(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(94), 0, dp(20), 0)
                textSize = 18f
                setTextColor(Color.WHITE)
                maxLines = 2
                visibility = View.GONE
                alpha = 0f
                elevation = dp(7).toFloat()
            }.also { root.addView(it, insertionIndex + 1, FrameLayout.LayoutParams(dp(330), dp(92))) }
            songIntroIcon = TextView(this).apply {
                text = "♫"
                textSize = 34f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.rgb(254, 78, 184), Color.rgb(87, 52, 230))
                ).apply { shape = GradientDrawable.OVAL; setStroke(dp(3), Color.argb(210, 255, 255, 255)) }
                visibility = View.GONE
                alpha = 0f
                elevation = dp(9).toFloat()
            }.also { root.addView(it, insertionIndex + 2, FrameLayout.LayoutParams(dp(78), dp(78))) }
            playerTouchOverlay = View(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                isClickable = true
                isFocusable = false
                setOnClickListener { showFullScreenPlayer() }
                elevation = dp(10).toFloat()
            }.also { root.addView(it, insertionIndex + 1, FrameLayout.LayoutParams(1, 1)) }
        }
        homeVideo = persistentVideo
        videoBackground = persistentVideo
        player = persistentVideo
        persistentVideo.bind(playbackEngine)
        playbackEngine.attach(persistentVideo)
        movePlayerToHost(homePlayerHost)
        listOfNotNull(homePlayerHost, subPagePlayerHost).forEach(::bindPlayerHostFocus)
        // The persistent video surface sits above its placeholder; forward touch entry to full screen too.
        persistentVideo.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) showFullScreenPlayer()
            true
        }

        // 设置子页面返回按钮
        if (btnBack != null) btnBack!!.setOnClickListener { navigateBack() }

        // 设置 Tab 导航
        setupTabs()
        // 构建虚拟键盘
        buildKeyboard()

        // 设置搜索/翻页事件
        if (btnDoSearch != null) btnDoSearch!!.setOnClickListener(View.OnClickListener { v: View? -> performSearch() })
        if (searchInput != null) {
            searchInput!!.setOnEditorActionListener(OnEditorActionListener { v: TextView?, actionId: Int, event: KeyEvent? ->
                performSearch()
                true
            })
            // 聚焦时调起车机系统软键盘（打字栏），与风火轮音乐播放器一致，不再强制内嵌键盘
            searchInput!!.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                if (hasFocus) showSystemKeyboard()
            }
            searchInput!!.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(text: Editable?) {
                    if (!suppressSearchWatcher) scheduleKeyboardSearch()
                }
            })
        }
        if (btnPrevPage != null) btnPrevPage!!.setOnClickListener(View.OnClickListener { v: View? -> prevPage() })
        if (btnNextPage != null) btnNextPage!!.setOnClickListener(View.OnClickListener { v: View? -> nextPage() })
        listOfNotNull(
            btnTopSearch, btnTopOrder, btnTopVocal, btnTopNext, btnTopPause, btnTopReplay, btnTopTone,
            btnBottomGuess, btnBottomMobileOrder, btnBottomSettings, btnBottomExit, btnBack, btnPrevPage, btnNextPage,
            homeCardRank, homeCardSong, homeCardSinger, homeCardLocal, homeCardRegular,
            homeCardFavorite, homeCardCategory,
        ).forEach(::installPressFeedback)
        listOfNotNull(homePlayerHost, subPagePlayerHost, homePoster).forEach(TvFocusStyler::preserveBackground)
        (homePoster as? FrameLayout)?.foreground = getDrawable(R.drawable.bg_player_focus)
        configureTvFocusNavigation()
        prepareTvFocusableTree(window.decorView)

        // 设置歌曲列表点击事件
        songList!!.setOnItemClickListener(OnItemClickListener { parent: AdapterView<*>?, view: View?, position: Int, id: kotlin.Long ->
            if (browseMode == "settings" || browseMode == "settings_section") {
                activeSettingsEntries.getOrNull(position)?.takeIf { it.checked == null }?.action?.invoke()
                return@OnItemClickListener
            }
            if (browseMode == "quick" || browseMode == "category_list") return@OnItemClickListener
            if ("singer_list" == browseMode) {
                if (position < visibleSingers.size) {
                    val singer = visibleSingers.get(position)!!
                    loadSingerSongs(singer[0], singer[1])
                }
                return@OnItemClickListener
            }
            if (position < visibleSongs.size) {
                val song = visibleSongs.get(position)
                if (currentTabIndex == -1) {
                    // 首页: 点击卡片导航
                    showHomePage()
                } else if (currentTabIndex == 4) {
                    // 已点列表: 单击排成下一首; 双击(快速再点同一行)立即播放。
                    onOrderedSongClicked(song, position)
                } else if (currentTabIndex == 5) {
                    // 已唱历史: 重新点歌
                    addToQueue(song)
                    renderSongList()
                } else if (currentTabIndex == 6) {
                    // 下载列表中的歌曲同样按点播入队，不能抢占当前播放。
                    addToQueue(song)
                } else if (currentTabIndex == 7) {
                    // 收藏: 点歌
                    addToQueue(song)
                    renderSongList()
                } else {
                    addToQueue(song)
                    renderSongList()
                }
            }
        })
        songList!!.setOnItemLongClickListener(OnItemLongClickListener { parent: AdapterView<*>?, view: View?, position: Int, id: kotlin.Long ->
            if ("settings" == browseMode || "singer_list" == browseMode) return@OnItemLongClickListener false
            if (position < 0 || position >= visibleSongs.size) return@OnItemLongClickListener false
            val song = visibleSongs.get(position)
            if (currentTabIndex == 4) {
                if (currentSong != null && currentSong!!.equals(song)) {
                    toast("正在播放的歌曲不能删除")
                    return@OnItemLongClickListener true
                }
                orderQueue!!.remove(song)
                saveState()
                loadOrderedList()
                toast("已删除: " + song.title)
            } else {
                showSongActions(song)
            }
            true
        })
    }

    /**
     * 首页 - 仿原版3列布局(视频+4卡片+新歌榜海报)
     */
    private fun showHomePage() {
        if (!restoringFocusRoute) focusReturnStack.clear()
        browseRequestVersion++
        main.removeCallbacks(keyboardSearchRunnable)
        currentPage = "首页"
        currentTabIndex = -1
        currentCategories.clear()
        currentCategoryIndex = -1
        clearHomeFocusTransition()
        updateCategories()
        // 隐藏子页面外壳,显示首页 3 列
        val shell = findViewById<View?>(R.id.main_layout)
        if (shell != null) shell.setVisibility(View.GONE)
        topBar?.apply {
            visibility = View.VISIBLE
            translationY = 0f
            setBackgroundResource(R.drawable.bg_top_bar)
        }
        topBrandName?.visibility = View.VISIBLE
        btnTopSearch?.visibility = View.VISIBLE
        bottomBar?.visibility = View.VISIBLE
        bottomBar?.visibility = View.VISIBLE
        updateContentFrameMargins(chromeBarHeight(), chromeBarHeight())
        if (homeLayout != null) homeLayout!!.setVisibility(View.VISIBLE)
        configureTvFocusNavigation()
        movePlayerToHost(homePlayerHost)
        // 同步当前播放信息到首页
        syncPlayerInfo()
        // 隐藏已点的子页面辅助控件
        if (subCategoryScroll != null) subCategoryScroll!!.setVisibility(View.GONE)
        window.decorView.post {
            prepareTvFocusableTree(window.decorView)
        }
    }

    /**
     * 切换到子页面外壳(左视频+键盘 / 右内容)
     * @param breadcrumb 面包屑显示文字(主页 / xxx)
     */
    private fun showSubPageShell(breadcrumb: String) {
        rememberReturnPointIfNeeded(breadcrumb)
        browseRequestVersion++
        // 进入任意子页面先收起系统软键盘；搜索页会在聚焦输入框时按需重新调起
        hideSystemKeyboard()
        visibleSongs.clear()
        visibleSingers.clear()
        songList?.adapter = tvAdapter()
        currentPage = breadcrumb
        if (homeLayout != null) homeLayout!!.setVisibility(View.GONE)
        clearHomeTopFocusNavigation()
        topBar?.apply {
            visibility = View.VISIBLE
            // 紧凑横屏的内容区顶部内边距只有 10dp；继续下移 22dp 会让顶部
            // 控制按钮压到面包屑/返回栏上。窄屏只做轻微下移，保留两栏间距。
            translationY = dp(if (compactUi) 8 else 22).toFloat()
            setBackgroundColor(Color.TRANSPARENT)
        }
        topBrandName?.visibility = View.GONE
        btnTopSearch?.visibility = View.GONE
        bottomBar?.visibility = View.GONE
        updateContentFrameMargins(0, 0)
        val shell = findViewById<View?>(R.id.main_layout)
        if (shell != null) shell.setVisibility(View.VISIBLE)
        if (breadcrumbCurrent != null) {
            breadcrumbCurrent!!.text = breadcrumb.removePrefix("主页").ifEmpty { " / 首页" }
        }
        if (keyboardArea != null) keyboardArea!!.setVisibility(View.VISIBLE)
        rankCategoryGrid?.visibility = View.GONE
        if (tabContainer != null) tabContainer!!.setVisibility(View.VISIBLE)
        if (paginationBar != null) paginationBar!!.setVisibility(View.GONE)
        browsePagination?.visibility = View.VISIBLE
        if (songList != null) {
            songList!!.setBackgroundResource(R.drawable.bg_song_list)
            songList!!.setDivider(ColorDrawable(Color.argb(34, 255, 255, 255)))
            songList!!.setDividerHeight(dp(1))
            songList!!.selector = ColorDrawable(Color.TRANSPARENT)
            songList!!.itemsCanFocus = true
            songList!!.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        movePlayerToHost(subPagePlayerHost)
        window.decorView.post {
            prepareTvFocusableTree(window.decorView)
        }
    }

    private fun updateContentFrameMargins(top: Int, bottom: Int) {
        val frame = contentFrame ?: return
        val params = frame.layoutParams as? FrameLayout.LayoutParams ?: return
        if (params.topMargin == top && params.bottomMargin == bottom) return
        params.topMargin = top
        params.bottomMargin = bottom
        frame.layoutParams = params
    }

    private fun movePlayerToHost(host: View?) {
        if (host == null || isFullScreen) return
        activePlayerHost = host
        host.post { applyPlayerBounds(host) }
    }

    private fun bindPlayerHostFocus(host: View) {
        host.isClickable = true
        host.isFocusable = true
        host.isFocusableInTouchMode = false
        host.setOnClickListener { showFullScreenPlayer() }
        host.setOnFocusChangeListener { _, hasFocus ->
            playerFocusBorder?.apply {
                isActivated = hasFocus
                visibility = if (hasFocus && !isFullScreen) View.VISIBLE else View.GONE
            }
            if (hasFocus && !isFullScreen) {
                activePlayerHost = host
                applyPlayerBounds(host)
            }
        }
    }

    private fun applyPlayerBounds(host: View) {
        val root = playerLayerRoot ?: return
        val video = player ?: return
        if (host.width <= 0 || host.height <= 0 || host.visibility != View.VISIBLE) return
        val rootLocation = IntArray(2)
        val hostLocation = IntArray(2)
        root.getLocationInWindow(rootLocation)
        host.getLocationInWindow(hostLocation)
        val params = (video.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(host.width, host.height)
        params.width = host.width
        params.height = host.height
        params.leftMargin = hostLocation[0] - rootLocation[0]
        params.topMargin = hostLocation[1] - rootLocation[1]
        video.layoutParams = params
        video.visibility = View.VISIBLE
        playerFocusBorder?.let { border ->
            val borderParams = (border.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(host.width, host.height)
            borderParams.width = host.width
            borderParams.height = host.height
            borderParams.leftMargin = params.leftMargin
            borderParams.topMargin = params.topMargin
            border.layoutParams = borderParams
            border.isActivated = host.hasFocus()
            border.visibility = if (host.hasFocus() && !isFullScreen) View.VISIBLE else View.GONE
        }
        playerTouchOverlay?.let { overlay ->
            val overlayParams = (overlay.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(host.width, host.height)
            overlayParams.width = host.width
            overlayParams.height = host.height
            overlayParams.leftMargin = params.leftMargin
            overlayParams.topMargin = params.topMargin
            overlay.layoutParams = overlayParams
            overlay.visibility = if (isFullScreen) View.GONE else View.VISIBLE
        }
        updatePlaybackNoticeBounds()
    }

    private fun expandPlayerToFullScreen() {
        val root = playerLayerRoot ?: return
        val video = player ?: return
        val params = (video.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        params.height = ViewGroup.LayoutParams.MATCH_PARENT
        params.leftMargin = 0
        params.topMargin = 0
        video.layoutParams = params
        playerFocusBorder?.visibility = View.GONE
        video.bringToFront()
        songIntroNotice?.bringToFront()
        songIntroIcon?.bringToFront()
        playbackModeNotice?.bringToFront()
        updatePlaybackNoticeBounds()
        fullScreenContainer?.bringToFront()
    }

    private fun updatePlaybackNoticeBounds() {
        val root = playerLayerRoot ?: return
        val video = player ?: return
        val videoParams = video.layoutParams as? FrameLayout.LayoutParams ?: return
        val width = if (videoParams.width > 0) videoParams.width else root.width
        val height = if (videoParams.height > 0) videoParams.height else root.height
        playbackModeNotice?.let { notice ->
            val size = dp(104)
            (notice.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.width = size; params.height = size
                params.leftMargin = videoParams.leftMargin + (width - size) / 2
                params.topMargin = videoParams.topMargin + (height - size) / 2
                notice.layoutParams = params
            }
        }
        songIntroNotice?.let { notice ->
            val noticeWidth = min(dp(330), (width * 0.72f).toInt())
            val noticeHeight = dp(92)
            (notice.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.width = noticeWidth; params.height = noticeHeight
                params.leftMargin = videoParams.leftMargin + (width - noticeWidth) / 2
                params.topMargin = videoParams.topMargin + (height - noticeHeight) / 2
                notice.layoutParams = params
                songIntroIcon?.let { icon ->
                    (icon.layoutParams as? FrameLayout.LayoutParams)?.let { iconParams ->
                        iconParams.width = dp(78); iconParams.height = dp(78)
                        iconParams.leftMargin = params.leftMargin + dp(8)
                        iconParams.topMargin = params.topMargin + dp(7)
                        icon.layoutParams = iconParams
                    }
                }
            }
        }
    }

    private fun showPlaybackModeNotice(mode: String, persistent: Boolean = false) {
        val notice = playbackModeNotice ?: return
        main.removeCallbacks(hidePlaybackModeNotice)
        notice.text = when (mode) {
            "暂停" -> "Ⅱ\n暂停"
            "原唱" -> "♟\n原唱"
            else -> "♛\n伴唱"
        }
        notice.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(151, 126, 255), Color.rgb(74, 39, 220))
        ).apply { shape = GradientDrawable.OVAL }
        updatePlaybackNoticeBounds()
        notice.visibility = View.VISIBLE
        notice.alpha = 0f
        notice.animate().alpha(1f).setDuration(140L).start()
        if (!persistent) main.postDelayed(hidePlaybackModeNotice, 1400L)
    }

    private fun hidePlaybackNoticeNow() {
        main.removeCallbacks(hidePlaybackModeNotice)
        playbackModeNotice?.visibility = View.GONE
        playbackModeNotice?.alpha = 0f
    }

    private fun showSongIntro(song: Song?) {
        if (!songTitleSubtitleEnabled || song == null) return
        val notice = songIntroNotice ?: return
        main.removeCallbacks(hideSongIntroNotice)
        notice.text = "${song.title}\n${song.singer.orEmpty()}"
        notice.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.rgb(163, 67, 225), Color.rgb(242, 48, 174), Color.argb(20, 242, 48, 174))
        ).apply { cornerRadius = dp(3).toFloat() }
        updatePlaybackNoticeBounds()
        notice.visibility = View.VISIBLE
        songIntroIcon?.visibility = View.VISIBLE
        notice.alpha = 0f
        songIntroIcon?.alpha = 0f
        notice.animate().alpha(1f).setDuration(180L).start()
        songIntroIcon?.animate()?.alpha(1f)?.setDuration(180L)?.start()
        main.postDelayed(hideSongIntroNotice, 3800L)
    }

    /**
     * 同步当前播放信息到首页和子页面的播放器覆盖层
     */
    private fun syncPlayerInfo() {
        val title = if (currentSong == null) "未播放" else currentSong!!.title
        val singer = if (currentSong == null) "" else currentSong!!.singer
        if (homePlayerTitle != null) homePlayerTitle!!.text = currentAndNextText()
        if (homePlayerSinger != null) homePlayerSinger!!.setText(singer)
        if (playerSongTitle != null) playerSongTitle!!.setText(title)
        if (playerSongSinger != null) playerSongSinger!!.setText(singer)
        // 顶部状态栏 - 当前/下首
        if (textCurrentSongTip != null) {
            val cur =
                "当前: " + title + (if (singer == null || singer.isEmpty()) "" else " - " + singer)
            val nxt = if (orderQueue!!.size >= 2)
                orderQueue.get(1).title + (if (orderQueue.get(1).singer == null || orderQueue.get(
                        1
                    ).singer!!.isEmpty()
                ) "" else " - " + orderQueue.get(1).singer)
            else
                "无"
            textCurrentSongTip!!.setText(cur + "  下首: " + nxt)
        }
        fullScreenSongInfo?.text = currentAndNextText()
        updateOrderBadge()
        if (browseMode == "ordered" || browseMode == "sang" || browseMode == "downloads") {
            renderSongList()
        }
    }

    private fun currentAndNextText(): String {
        val current = currentSong?.let { song ->
            song.title.orEmpty() + song.singer?.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()
        } ?: "未播放"
        val currentIndex = currentSong?.let { orderQueue?.indexOf(it) } ?: -1
        val next = when {
            currentIndex >= 0 -> orderQueue?.getOrNull(currentIndex + 1)
            currentSong == null -> orderQueue?.firstOrNull()
            else -> orderQueue?.firstOrNull { currentSong?.equals(it) != true }
        }?.let { song -> song.title.orEmpty() + song.singer?.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty() }
            ?: "无"
        return "当前：$current    下一首：$next"
    }

    /** 顶栏搜索按钮 - 跳到搜索页  */
    private fun showSearchPage() {
        if (!library.muse.isAvailable()) {
            if (library.allSongs().any { it.remote }) showNetworkCatalogPage()
            else showCatalogSetupOptions()
            return
        }
        searchScope = "song"
        activeSearchQuery = ""
        setSearchText("")
        showSubPageShell("主页 / 歌名")
        currentTabIndex = 0
        setupTabs()
        currentCategories.clear()
        currentCategories.add("全部")
        currentCategoryIndex = 0
        searchLanguage = "全部"
        updateCategories()
        // 搜索使用车机系统软键盘（打字栏）输入，隐藏内嵌自定义键盘，与风火轮音乐播放器一致
        hideInlineKeyboard()
        searchInput?.requestFocus()
        showSystemKeyboard()
        performSearch()
        io.execute {
            val languages = if (library.muse.isAvailable()) library.muse.languages() else emptyList()
            main.post {
                if (currentTabIndex != 0 || searchScope != "song") return@post
                currentCategories.clear()
                currentCategories.add("全部")
                currentCategories.addAll(languages)
                currentCategoryIndex = currentCategories.indexOf(searchLanguage).coerceAtLeast(0)
                updateCategories()
            }
        }
    }

    private fun showQuickSearchPage() {
        searchScope = "quick"
        activeSearchQuery = ""
        setSearchText("")
        showSubPageShell("主页 / 快搜")
        currentTabIndex = 8
        setupTabs()
        currentCategories.clear()
        currentCategoryIndex = -1
        updateCategories()
        // 快搜同样使用车机系统软键盘输入，隐藏内嵌自定义键盘
        hideInlineKeyboard()
        searchInput?.requestFocus()
        showSystemKeyboard()
        browseMode = "quick"
        browsePage = 0
        loadQuickSearchPage("")
    }

    /** 顶栏已点按钮  */
    private fun showOrderListPage() {
        showSubPageShell("主页 / 已点")
        currentTabIndex = 4
        setupTabs()
        loadOrderedList()
    }

    /** 首页"本地"卡片 - 显示已下载歌曲  */
    private fun showLocalPage() {
        searchScope = "local"
        activeSearchQuery = ""
        setSearchText("")
        currentTabIndex = 10
        showSubPageShell("主页 / 本地")
        setupTabs()
        keyboardArea?.visibility = View.VISIBLE
        browseMode = "local"
        browseParam = "全部"
        browsePage = 0
        currentCategories.clear()
        currentCategories.add("全部")
        currentCategoryIndex = 0
        searchLanguage = "全部"
        updateCategories()
        loadLocalCatalogPage()
        io.execute {
            val languages = if (library.muse.isAvailable()) library.muse.languages() else emptyList()
            main.post {
                if (currentTabIndex != 10 || searchScope != "local") return@post
                currentCategories.clear()
                currentCategories.add("全部")
                currentCategories.addAll(languages)
                currentCategoryIndex = currentCategories.indexOf(searchLanguage).coerceAtLeast(0)
                updateCategories()
            }
        }
    }

    private fun showAllSongsPage() {
        showSearchPage()
    }

    /** 底栏"猜你想唱" - 加载推荐歌曲  */
    private fun showGuessPage() {
        val root = layoutInflater.inflate(R.layout.dialog_song_recommend, null)
        val grid = root.findViewById<GridLayout>(R.id.recommend_song_grid)
        val confirm = root.findViewById<TextView>(R.id.button_recommend_confirm)
        val dialog = AlertDialog.Builder(this).setView(root).create()
        root.findViewById<View>(R.id.image_recommend_close).setOnClickListener { dialog.dismiss() }
        confirm.isEnabled = false
        confirm.alpha = 0.55f
        val selected = ArrayList<Song>()
        confirm.setOnClickListener {
            selected.forEach(::addToQueue)
            dialog.dismiss()
        }
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(
                    (resources.displayMetrics.widthPixels * 0.87f).toInt(),
                    (resources.displayMetrics.heightPixels * 0.90f).toInt(),
                )
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attributes = attributes.apply { dimAmount = 0.56f }
            }
            TvFocusStyler.disablePlatformHighlightTree(root)
            root.alpha = 0f
            root.scaleX = 0.96f
            root.scaleY = 0.96f
            root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160L).start()
            root.findViewById<View>(R.id.image_recommend_close).apply {
                isFocusable = true
                requestFocus()
            }
        }
        dialog.restoreSourceFocusOnDismiss()
        dialog.show()

        val historySnapshot = sangHistory.toList()
        val queueSnapshot = orderQueue.orEmpty().toList()
        val immediateRecommendations = (historySnapshot.asSequence() + queueSnapshot.asSequence() +
            library.allSongs().asSequence())
            .distinctBy(::stableId)
            .take(10)
            .toList()
        selected.addAll(immediateRecommendations)
        populateRecommendationGrid(grid, immediateRecommendations, historySnapshot)
        confirm.text = "一键点歌(${immediateRecommendations.size}首)"
        confirm.isEnabled = immediateRecommendations.isNotEmpty()
        confirm.alpha = if (immediateRecommendations.isEmpty()) 0.55f else 1f
        io.execute {
            val quickSongs = library.muse.quickSongs(30)
            val quickRecommendations = (historySnapshot.asSequence() + queueSnapshot.asSequence() +
                quickSongs.asSequence() + immediateRecommendations.asSequence())
                .distinctBy(::stableId)
                .take(10)
                .toList()
            main.post {
                if (!dialog.isShowing) return@post
                selected.clear()
                selected.addAll(quickRecommendations)
                populateRecommendationGrid(grid, quickRecommendations, historySnapshot)
                confirm.text = "一键点歌(${quickRecommendations.size}首)"
                confirm.isEnabled = quickRecommendations.isNotEmpty()
                confirm.alpha = if (quickRecommendations.isEmpty()) 0.55f else 1f
            }
            val hotSongs = library.muse.hotSongs(0, 30)
            val recommendations = (historySnapshot.asSequence() + queueSnapshot.asSequence() +
                hotSongs.asSequence() + immediateRecommendations.asSequence())
                .distinctBy(::stableId)
                .take(10)
                .toList()
            main.post {
                if (!dialog.isShowing) return@post
                selected.clear()
                selected.addAll(recommendations)
                populateRecommendationGrid(grid, recommendations, historySnapshot)
                confirm.text = "一键点歌(${recommendations.size}首)"
                confirm.isEnabled = recommendations.isNotEmpty()
                confirm.alpha = if (recommendations.isEmpty()) 0.55f else 1f
            }
        }
    }

    private fun populateRecommendationGrid(
        grid: GridLayout,
        songs: List<Song>,
        history: List<Song>,
    ) {
        grid.removeAllViews()
        songs.take(10).forEachIndexed { index, song ->
            val item = FrameLayout(this).apply {
                setBackgroundResource(R.drawable.ott_bg_item_not_selected)
                isClickable = true
                isFocusable = true
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(5), dp(104), dp(5))
                    addView(label(song.title, 16, Color.WHITE).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, 0, 0, 0)
                    }, LinearLayout.LayoutParams(-1, dp(25)))
                    addView(label(song.singer, 13, Color.rgb(193, 193, 193)).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, 0, 0, 0)
                    }, LinearLayout.LayoutParams(-1, dp(22)))
                }, FrameLayout.LayoutParams(-1, -1))
                val historyCount = history.count { stableId(it) == stableId(song) }
                val recommendationLabel = when {
                    historyCount > 0 -> "您共唱过${historyCount}次"
                    index == 3 -> "飙升榜TOP1"
                    index == 5 -> "新歌榜TOP5"
                    index == 7 -> "抖音榜TOP1"
                    index >= 8 -> "人气热歌"
                    else -> "热门TOP${index + 1}"
                }
                addView(label(
                    recommendationLabel,
                    12,
                    if (historyCount > 0) Color.rgb(193, 193, 193) else Color.rgb(253, 51, 89),
                ).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setPadding(0, 0, dp(8), 0)
                }, FrameLayout.LayoutParams(dp(104), -1, Gravity.END))
                setOnClickListener {
                    addToQueue(song)
                    toast("已点歌：${song.title}")
                }
            }
            installPressFeedback(item)
            grid.addView(item, GridLayout.LayoutParams().apply {
                rowSpec = GridLayout.spec(index / 2)
                columnSpec = GridLayout.spec(index % 2)
                width = (((resources.displayMetrics.widthPixels * 0.87f).toInt() - dp(28)) / 2)
                height = max(dp(48), (((resources.displayMetrics.heightPixels * 0.90f).toInt() - dp(163)) / 5) - dp(6))
                setMargins(dp(2), dp(3), dp(2), dp(3))
            })
        }
    }

    /**
     * 设置顶部 Tab 导航栏
     */
    private fun setupTabs() {
        if (tabContainer == null) return
        val tabs: Array<out String?>
        val targets: IntArray
        if (currentTabIndex >= 4 && currentTabIndex <= 6) {
            tabs = arrayOf<String>("已点", "已唱", "下载")
            targets = intArrayOf(4, 5, 6)
        } else if (currentTabIndex == 1 || currentTabIndex == 2 || currentTabIndex == 3 ||
            currentTabIndex == 7 || currentTabIndex == 8 || currentTabIndex == 9
        ) {
            tabContainer!!.setVisibility(View.GONE)
            return
        } else {
            tabs = arrayOf<String>("全网", "本地")
            targets = intArrayOf(0, 10)
        }
        tabContainer!!.setVisibility(View.VISIBLE)

        fun bindTab(tab: TextView, label: String, index: Int) {
            tab.text = label
            tab.textSize = 14f
            tab.minWidth = 0
            tab.setPadding(0, 0, 0, 0)
            tab.gravity = Gravity.CENTER
            tab.setAccessibleFocus("focus:tab:$label", "分类，$label")
            tab.isFocusable = true
            tab.isClickable = true
            updateTabStyle(tab, index == currentTabIndex)
            tab.setOnClickListener {
                currentTabIndex = index
                when (index) {
                    0 -> showAllSongsPage()
                    1 -> loadSingers()
                    2 -> loadLanguages()
                    3 -> loadWordCounts()
                    4 -> loadOrderedList()
                    5 -> loadSangHistory()
                    6 -> loadDownloadedList()
                    7 -> loadFavorites()
                    8 -> showSettingsPage()
                    10 -> showLocalPage()
                }
            }
        }

        val canReuse = tabContainer!!.childCount == tabs.size && tabs.indices.all { position ->
            (tabContainer!!.getChildAt(position) as? TextView)?.text?.toString() == tabs[position]
        }
        if (canReuse) {
            tabs.indices.forEach { position ->
                bindTab(
                    tabContainer!!.getChildAt(position) as TextView,
                    tabs[position].orEmpty(),
                    targets[position],
                )
            }
            return
        }

        val focusedTab = window.decorView.findFocus()?.takeIf { isDescendantOf(it, tabContainer) }
            ?.let(::captureFocusBookmark)
        tabContainer!!.removeAllViews()
        for (i in tabs.indices) {
            val index = targets[i]
            val tab = TextView(this)
            bindTab(tab, tabs[i].orEmpty(), index)
            installPressFeedback(tab)
            tabContainer!!.addView(tab, LinearLayout.LayoutParams(dp(76), -1))
        }
        restoreFocusBookmark(focusedTab)
    }

    private fun installPressFeedback(view: View) {
        TvFocusStyler.install(view)
        if (view.isClickable) {
            view.isFocusable = true
            view.isFocusableInTouchMode = false
        }
        view.setOnTouchListener { target, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN ->
                    target.animate().scaleX(0.94f).scaleY(0.94f).alpha(0.84f).setDuration(80L).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    target.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120L).start()
            }
            false
        }
    }

    private fun configureTvFocusNavigation() {
        btnTopSearch?.nextFocusDownId = R.id.home_player_container
        btnTopOrder?.nextFocusDownId = R.id.home_card_rank
        btnTopVocal?.nextFocusDownId = R.id.home_card_rank
        btnTopNext?.nextFocusDownId = R.id.home_card_rank
        btnTopPause?.nextFocusDownId = R.id.home_card_rank
        btnTopReplay?.nextFocusDownId = R.id.home_poster_container
        btnTopTone?.nextFocusDownId = R.id.home_poster_container

        homePlayerHost?.nextFocusUpId = R.id.btn_top_search
        homePlayerHost?.nextFocusDownId = R.id.home_card_regular
        homePlayerHost?.nextFocusRightId = R.id.home_card_rank

        homeCardRank?.nextFocusUpId = R.id.btn_top_pause
        homeCardRank?.nextFocusLeftId = R.id.home_player_container
        homeCardRank?.nextFocusDownId = R.id.home_card_song
        homeCardRank?.nextFocusRightId = R.id.home_poster_container
        homeCardSong?.nextFocusUpId = R.id.home_card_rank
        homeCardSong?.nextFocusDownId = R.id.home_card_singer
        homeCardSong?.nextFocusRightId = R.id.home_poster_container
        homeCardSinger?.nextFocusUpId = R.id.home_card_song
        homeCardSinger?.nextFocusDownId = R.id.home_card_local
        homeCardSinger?.nextFocusRightId = R.id.home_poster_container
        homeCardLocal?.nextFocusUpId = R.id.home_card_singer
        homeCardLocal?.nextFocusLeftId = R.id.home_card_category
        homeCardLocal?.nextFocusDownId = R.id.btn_bottom_settings
        homeCardLocal?.nextFocusRightId = R.id.home_poster_container
        homeCardRegular?.nextFocusUpId = R.id.home_player_container
        homeCardRegular?.nextFocusRightId = R.id.home_card_favorite
        homeCardRegular?.nextFocusDownId = R.id.btn_bottom_guess
        homeCardFavorite?.nextFocusUpId = R.id.btn_top_search
        homeCardFavorite?.nextFocusLeftId = R.id.home_card_regular
        homeCardFavorite?.nextFocusRightId = R.id.home_card_category
        homeCardFavorite?.nextFocusDownId = R.id.btn_bottom_guess
        homeCardCategory?.nextFocusUpId = R.id.home_card_rank
        homeCardCategory?.nextFocusLeftId = R.id.home_card_favorite
        homeCardCategory?.nextFocusRightId = R.id.home_card_local
        homeCardCategory?.nextFocusDownId = R.id.btn_bottom_settings
        homePosterView?.nextFocusUpId = R.id.btn_top_tone
        homePosterView?.nextFocusLeftId = R.id.home_card_song
        homePosterView?.nextFocusDownId = R.id.btn_bottom_exit

        btnBottomGuess?.nextFocusUpId = R.id.home_card_regular
        btnBottomGuess?.nextFocusRightId = R.id.btn_bottom_mobile_order
        btnBottomMobileOrder?.nextFocusUpId = R.id.home_card_local
        btnBottomMobileOrder?.nextFocusLeftId = R.id.btn_bottom_guess
        btnBottomMobileOrder?.nextFocusRightId = R.id.btn_bottom_settings
        btnBottomSettings?.nextFocusUpId = R.id.home_card_local
        btnBottomSettings?.nextFocusLeftId = R.id.btn_bottom_mobile_order
        btnBottomSettings?.nextFocusRightId = R.id.btn_bottom_exit
        btnBottomExit?.nextFocusUpId = R.id.home_poster_container
        btnBottomExit?.nextFocusLeftId = R.id.btn_bottom_settings
    }

    private fun clearHomeFocusTransition() {
        lastHomeFocusSource = null
        lastHomeFocusTarget = null
        lastHomeFocusReverseKey = KeyEvent.KEYCODE_UNKNOWN
    }

    private fun oppositeDpadKey(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_DPAD_LEFT -> KeyEvent.KEYCODE_DPAD_RIGHT
        KeyEvent.KEYCODE_DPAD_RIGHT -> KeyEvent.KEYCODE_DPAD_LEFT
        KeyEvent.KEYCODE_DPAD_UP -> KeyEvent.KEYCODE_DPAD_DOWN
        KeyEvent.KEYCODE_DPAD_DOWN -> KeyEvent.KEYCODE_DPAD_UP
        else -> KeyEvent.KEYCODE_UNKNOWN
    }

    private fun clearHomeTopFocusNavigation() {
        listOfNotNull(
            btnTopSearch, btnTopOrder, btnTopVocal, btnTopNext, btnTopPause, btnTopReplay, btnTopTone,
        ).forEach { it.nextFocusDownId = View.NO_ID }
    }

    /**
     * 更新 Tab 样式(选中/未选中)
     */
    private fun updateTabStyle(tab: TextView, selected: Boolean) {
        val normalText = if (selected) Color.rgb(253, 51, 89) else Color.argb(180, 241, 241, 241)
        tab.setTextColor(ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_focused),
                intArrayOf(android.R.attr.state_pressed),
                intArrayOf(),
            ),
            intArrayOf(Color.WHITE, Color.WHITE, normalText),
        ))
        val normal = GradientDrawable().apply {
            setColor(if (selected) Color.argb(30, 253, 51, 89) else Color.TRANSPARENT)
            cornerRadius = dp(4).toFloat()
        }
        val focused = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.rgb(232, 49, 111), Color.rgb(255, 154, 48)),
        ).apply {
            cornerRadius = dp(4).toFloat()
            setStroke(dp(1), Color.argb(150, 255, 255, 255))
        }
        tab.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_pressed), focused)
            addState(intArrayOf(), normal)
        }
    }

    /**
     * 构建虚拟键盘 (拼音首字母 + 数字 + 功能键)
     * 4 行字母 + 1 行数字 = 完整 26 字母键盘
     */
    private fun buildKeyboard() {
        val grid = findViewById<LinearLayout?>(R.id.keyboard_grid)
        // 每次重建子页面时复位内嵌键盘可见性，搜索页会在其后按需隐藏
        grid?.visibility = View.VISIBLE
        findViewById<View?>(R.id.key_abc)?.visibility = View.VISIBLE
        findViewById<View?>(R.id.key_123)?.visibility = View.VISIBLE
        findViewById<View?>(R.id.key_space)?.visibility = View.VISIBLE
        if (grid == null) return
        renderKeyboard("abc")
        // 绑定输入模式切换
        val keyAbc = findViewById<TextView?>(R.id.key_abc)
        val key123 = findViewById<TextView?>(R.id.key_123)
        val keySpace = findViewById<TextView?>(R.id.key_space)
        val keyBackspace = findViewById<TextView?>(R.id.key_backspace)
        val keyClear = findViewById<TextView?>(R.id.key_clear)
        if (keyAbc != null) keyAbc.setOnClickListener(View.OnClickListener { v: View? ->
            hideSystemKeyboard()
            keyAbc.setBackgroundResource(R.drawable.bg_key_mode)
            keyAbc.setTextColor(Color.rgb(0x27, 0xCC, 0xA4))
            if (key123 != null) {
                key123.setBackgroundResource(R.drawable.bg_key_mode_dim)
                key123.setTextColor(Color.argb(153, 255, 255, 255))
            }
            renderKeyboard("abc")
        })
        if (key123 != null) key123.setOnClickListener(View.OnClickListener { v: View? ->
            showSystemKeyboard()
        })
        if (keySpace != null) keySpace.setOnClickListener(View.OnClickListener { v: View? ->
            showSystemKeyboard()
        })
        if (keyBackspace != null) keyBackspace.setOnClickListener(View.OnClickListener { v: View? ->
            if (searchInput == null) return@OnClickListener
            val s = searchInput!!.getText().toString()
            if (!s.isEmpty()) {
                searchInput!!.setText(s.substring(0, s.length - 1))
                searchInput!!.setSelection(searchInput!!.getText().length)
                scheduleKeyboardSearch()
            }
        })
        if (keyClear != null) keyClear.setOnClickListener(View.OnClickListener { v: View? ->
            if (searchInput != null) {
                searchInput!!.setText("")
                scheduleKeyboardSearch()
            }
        })
    }

    /**
     * 切换键盘模式(ABC/123)并重建键盘
     * @param mode "abc" 或 "123"
     */
    private var keyboardMode: String? = "abc"
    private fun renderKeyboard(mode: String?) {
        keyboardMode = mode
        val grid = findViewById<LinearLayout?>(R.id.keyboard_grid)
        if (grid == null) return
        grid.removeAllViews()
        val rows: Array<Array<String?>?>?
        if ("123" == mode) {
            rows = arrayOf<Array<String?>?>(
                arrayOf<String?>("1", "2", "3", "4", "5", "6", "7"),
                arrayOf<String?>("8", "9", "0", "", "", "", ""),
                arrayOf<String?>("", "", "", "", "", "", ""),
                arrayOf<String?>("ABC", "", "", "", "", "", ""),
            )
        } else {
            rows = arrayOf<Array<String?>?>(
                arrayOf<String?>("A", "B", "C", "D", "E", "F", "G"),
                arrayOf<String?>("H", "I", "J", "K", "L", "M", "N"),
                arrayOf<String?>("O", "P", "Q", "R", "S", "T", "U"),
                arrayOf<String?>("V", "W", "X", "Y", "Z", "123", ""),
            )
        }
        for (row in rows) {
            val rowLayout = LinearLayout(this)
            rowLayout.setOrientation(LinearLayout.HORIZONTAL)
            rowLayout.setGravity(Gravity.CENTER)
            rowLayout.setWeightSum(row!!.size.toFloat())
            val rowLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            rowLp.bottomMargin = dp(2)
            grid.addView(rowLayout, rowLp)
            for (key in row) {
                val btn = TextView(this)
                btn.setText(key)
                btn.setTextSize(15f)
                btn.setTextColor(Color.rgb(241, 241, 241))
                btn.setGravity(Gravity.CENTER)
                btn.setBackgroundColor(Color.TRANSPARENT)
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                lp.leftMargin = 2
                lp.rightMargin = 2
                btn.setLayoutParams(lp)
                btn.visibility = if (key.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE
                btn.setOnClickListener {
                    when (key) {
                        "123" -> renderKeyboard("123")
                        "ABC" -> renderKeyboard("abc")
                        else -> appendToSearch(key)
                    }
                }
                btn.setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> btn.setBackgroundResource(R.drawable.bg_key_char_pressed)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> btn.setBackgroundColor(Color.TRANSPARENT)
                    }
                    false
                }
                rowLayout.addView(btn)
            }
        }
    }

    /**
     * 向搜索框追加文本
     */
    private fun appendToSearch(key: String?) {
        if (searchInput == null) return
        val s = searchInput!!.getText().toString()
        searchInput!!.setText(s + key)
        searchInput!!.setSelection(searchInput!!.getText().length)
        scheduleKeyboardSearch()
    }

    private fun scheduleKeyboardSearch() {
        main.removeCallbacks(keyboardSearchRunnable)
        main.postDelayed(keyboardSearchRunnable, 350L)
    }

    private fun setSearchText(value: String) {
        val input = searchInput ?: return
        main.removeCallbacks(keyboardSearchRunnable)
        suppressSearchWatcher = true
        input.setText(value)
        input.setSelection(input.text.length)
        suppressSearchWatcher = false
    }

    private fun clearSearchForFilterChange() {
        activeSearchQuery = ""
        setSearchText("")
    }

    private fun showSystemKeyboard(target: EditText? = searchInput) {
        val input = target ?: return
        // 确保搜索框在触摸模式下可聚焦（TV 焦点样式会关闭它），否则无法唤起系统软键盘
        input.isFocusable = true
        input.isFocusableInTouchMode = true
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.showSoftInput(input, InputMethodManager.SHOW_FORCED)
    }

    private fun hideSystemKeyboard() {
        val input = searchInput ?: return
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    /** 隐藏内嵌自定义键盘（字母网格与模式键），让搜索改用车机系统软键盘输入。 */
    private fun hideInlineKeyboard() {
        findViewById<View?>(R.id.keyboard_grid)?.visibility = View.GONE
        findViewById<View?>(R.id.key_abc)?.visibility = View.GONE
        findViewById<View?>(R.id.key_123)?.visibility = View.GONE
        findViewById<View?>(R.id.key_space)?.visibility = View.GONE
    }

    /**
     * 加载热门歌曲列表
     */
    private fun loadHotSongs() {
        searchScope = "none"
        activeSearchQuery = ""
        setSearchText("")
        currentTabIndex = 9
        showSubPageShell("主页 / 排行榜")
        setupTabs()
        if (keyboardArea != null) keyboardArea!!.setVisibility(View.GONE)
        rankCategoryGrid?.visibility = View.VISIBLE
        listTitle!!.setText("排行榜")
        browseMode = "rank"
        browseParam = ""
        browsePage = 0
        currentCategories.clear()
        rankPlaylistIds.clear()
        currentCategoryIndex = 0
        updateCategories()
        if (!library.muse.isAvailable()) {
            catalogRefreshPending = false
            busy(false, "此页面需要完整曲库，可选择本地或网络曲库")
            showCatalogSetupOptions()
            return
        }
        io.execute {
            val playlists = library.muse.rankPlaylists()
            main.post {
                if (currentTabIndex != 9 || browseMode != "rank") return@post
                currentCategories.clear()
                rankPlaylistIds.clear()
                // 本机实时热歌：放在最前并默认选中（按本机点播次数实时排序，跨重启持久）
                rankPlaylistIds.add(LOCAL_HOT_PLAYLIST_ID)
                currentCategories.add("本机热歌")
                playlists.forEach { playlist ->
                    rankPlaylistIds.add(playlist[0].orEmpty())
                    currentCategories.add(playlist[1].orEmpty())
                }
                currentCategoryIndex = 0
                browseParam = rankPlaylistIds.getOrNull(currentCategoryIndex).orEmpty()
                listTitle!!.text = currentCategories.getOrNull(currentCategoryIndex) ?: "排行榜"
                updateRankCategoryGrid()
                subCategoryScroll?.visibility = View.GONE
                loadBrowsePage()
            }
        }
    }

    private fun updateRankCategoryGrid() {
        val grid = rankCategoryGrid ?: return
        grid.removeAllViews()
        currentCategories.take(9).forEachIndexed { index, title ->
            val tile = TextView(this).apply {
                text = title
                textSize = 14f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setBackgroundResource(
                    if (index == currentCategoryIndex) R.drawable.ott_bg_rank_item_selected
                    else R.drawable.ott_bg_rank_item_not_selected
                )
                val trophy = getDrawable(R.drawable.ic_default_rank_avatar)?.mutate()
                trophy?.setBounds(0, 0, dp(42), dp(42))
                setCompoundDrawables(null, trophy, null, null)
                compoundDrawablePadding = dp(1)
                setOnClickListener {
                    if (index == currentCategoryIndex) return@setOnClickListener
                    currentCategoryIndex = index
                    browseMode = "rank"
                    browseParam = rankPlaylistIds.getOrNull(index).orEmpty()
                    browsePage = 0
                    listTitle?.text = title
                    updateRankCategoryGrid()
                    loadBrowsePage()
                }
            }
            installPressFeedback(tile)
            grid.addView(tile, GridLayout.LayoutParams().apply {
                rowSpec = GridLayout.spec(index / 3, 1f)
                columnSpec = GridLayout.spec(index % 3, 1f)
                width = 0
                height = 0
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
    }

    private fun loadCategoryPlaylists() {
        searchScope = "playlist"
        activeSearchQuery = ""
        setSearchText("")
        currentTabIndex = 2
        showSubPageShell("主页 / 分类")
        setupTabs()
        currentCategories.clear()
        currentCategoryIndex = -1
        updateCategories()
        browseMode = "category_list"
        browseParam = ""
        browsePage = 0
        loadCategoryPlaylistPage("")
    }

    private fun returnToCategoryPlaylists() {
        searchScope = "playlist"
        activeSearchQuery = categoryParentQuery
        setSearchText(categoryParentQuery)
        currentTabIndex = 2
        showSubPageShell("主页 / 分类")
        setupTabs()
        currentCategories.clear()
        currentCategoryIndex = -1
        updateCategories()
        browseMode = "category_list"
        browseParam = categoryParentQuery
        browsePage = categoryParentPage
        loadCategoryPlaylistPage(categoryParentQuery)
    }

    private fun loadCategoryPlaylistPage(query: String = activeSearchQuery) {
        browseMode = "category_list"
        browseParam = query
        val requestVersion = ++browseRequestVersion
        val requestedPage = browsePage
        val requestedQuery = query.trim()
        val pageSize = 12
        io.execute {
            val playlists = library.muse.categoryPlaylists(requestedQuery, requestedPage * pageSize, pageSize)
            val total = library.muse.categoryPlaylistCount(requestedQuery)
            main.post {
                if (requestVersion != browseRequestVersion || browseMode != "category_list" ||
                    browsePage != requestedPage || activeSearchQuery != requestedQuery
                ) return@post
                browseTotalCount = total
                browseTotalPages = (total + pageSize - 1) / pageSize
                visiblePlaylists.clear()
                visiblePlaylists.addAll(playlists)
                songList?.apply {
                    dividerHeight = 0
                    adapter = PlaylistGridAdapter(this@MainActivity, playlists) { playlist ->
                        openCategoryPlaylist(playlist)
                    }
                }
                updatePageInfo()
            }
        }
    }

    private fun openCategoryPlaylist(playlist: Array<String?>) {
        categoryParentPage = browsePage
        categoryParentQuery = activeSearchQuery
        clearSearchForFilterChange()
        showSubPageShell("主页 / 分类 / ${playlist.getOrNull(1).orEmpty()}")
        currentTabIndex = 2
        setupTabs()
        searchScope = "playlist_songs"
        searchContextId = playlist.getOrNull(0).orEmpty()
        currentCategories.clear()
        currentCategoryIndex = -1
        updateCategories()
        browseMode = "playlist_id"
        browseParam = playlist.getOrNull(0).orEmpty()
        browsePage = 0
        loadBrowsePage()
    }

    private fun loadQuickSearchPage(query: String = activeSearchQuery) {
        browseMode = "quick"
        browseParam = query
        val requestVersion = ++browseRequestVersion
        val requestedPage = browsePage
        val requestedQuery = query.trim()
        io.execute {
            // 车机实测"一页只有六首歌、歌星只有两排"：快搜页原来硬编码 6 歌 + 4 歌手。
            // 快搜是 2 列布局，提到 12 首(6 行) + 8 歌手(2 行满)，与其它页密度一致。
            val quickSongPageSize = 12
            val quickSingerPageSize = 8
            val songs = if (requestedQuery.isEmpty()) {
                library.muse.hotSongs(requestedPage * quickSongPageSize, quickSongPageSize)
            } else {
                library.muse.searchSongs(requestedQuery, requestedPage * quickSongPageSize, quickSongPageSize)
            }
            val singers = if (requestedQuery.isEmpty()) {
                library.muse.singers("", "", requestedPage * quickSingerPageSize, quickSingerPageSize)
            } else {
                library.muse.searchSingers(
                    requestedQuery, "", "", requestedPage * quickSingerPageSize, quickSingerPageSize,
                )
            }
            val total = if (requestedQuery.isEmpty()) {
                library.muse.songCount()
            } else {
                library.muse.searchSongCount(requestedQuery)
            }
            main.post {
                if (requestVersion != browseRequestVersion || browseMode != "quick" ||
                    browsePage != requestedPage || activeSearchQuery != requestedQuery
                ) return@post
                browseTotalCount = total
                browseTotalPages = (total + quickSongPageSize - 1) / quickSongPageSize
                visibleSongs.clear()
                visibleSongs.addAll(songs)
                visibleSingers.clear()
                visibleSingers.addAll(singers)
                songList?.apply {
                    dividerHeight = 0
                    adapter = QuickSearchAdapter(
                        this@MainActivity,
                        singers,
                        songs,
                        onSingerClick = { singer -> loadSingerSongs(singer.getOrNull(0), singer.getOrNull(1)) },
                        onSongClick = { song -> addToQueue(song) },
                        onSongMore = { song -> showSongActions(song) },
                    )
                }
                updatePageInfo()
            }
        }
    }

    /**
     * 加载语种列表
     */
    private fun loadLanguages() {
        currentTabIndex = 2
        showSubPageShell("主页 / 语种")
        setupTabs()
        listTitle!!.setText("语种点歌")
        if (library.muse.isAvailable()) {
            currentCategories.clear()
            currentCategories.addAll(library.muse.languages())
            currentCategoryIndex = 0
            updateCategories()
            if (!currentCategories.isEmpty()) {
                browseMode = "language"
                browseParam = currentCategories.get(0)
                browsePage = 0
                loadBrowsePage()
            }
        }
    }

    /**
     * 加载字数分类
     */
    private fun loadWordCounts() {
        currentTabIndex = 3
        showSubPageShell("主页 / 字数")
        setupTabs()
        listTitle!!.setText("字数点歌")
        currentCategories.clear()
        currentCategories.add("2字")
        currentCategories.add("3字")
        currentCategories.add("4字")
        currentCategories.add("5字")
        currentCategories.add("6字")
        currentCategories.add("7字以上")
        currentCategoryIndex = 0
        updateCategories()
        browseMode = "wordcount"
        browseParam = "4" // 默认显示4字歌曲
        browsePage = 0
        loadBrowsePage()
    }

    /**
     * 加载歌手列表
     */
    private fun loadSingers() {
        searchScope = "singer"
        activeSearchQuery = ""
        setSearchText("")
        currentTabIndex = 1
        showSubPageShell("主页 / 歌星")
        setupTabs()
        listTitle!!.setText("歌星点歌")
        currentCategories.clear()
        currentCategories.add("全部")
        currentCategories.add("大陆男")
        currentCategories.add("大陆女")
        currentCategories.add("港台男")
        currentCategories.add("港台女")
        currentCategories.add("中国组合")
        currentCategories.add("外国组合")
        currentCategoryIndex = 0
        updateCategories()
        browsePage = 0
        loadSingerList("全部")
    }

    private fun loadSingerList(type: String?, query: String = activeSearchQuery) {
        browseMode = "singer_list"
        browseParam = type
        if (!library.muse.isAvailable()) {
            catalogRefreshPending = false
            busy(false, "此页面需要完整曲库，可选择本地或网络曲库")
            showCatalogSetupOptions()
            return
        }
        val requestVersion = ++browseRequestVersion
        val requestedPage = browsePage
        val requestedType = type.orEmpty()
        val requestedQuery = query.trim()
        // 4 列 x 4 行 = 16，车机一屏 4 排填满（原来 8 只 2 排，太少）
        val pageSize = 16
        val offset = requestedPage * pageSize
        val (areaFilter, typeFilter) = singerFilter(type)
        io.execute(Runnable {
            val singers: MutableList<Array<String?>> = if (requestedQuery.isEmpty()) {
                library.muse.singers(areaFilter, typeFilter, offset, pageSize)
            } else {
                library.muse.searchSingers(requestedQuery, areaFilter, typeFilter, offset, pageSize)
            }
            val total = if (requestedQuery.isEmpty()) {
                library.muse.singerCount(areaFilter, typeFilter)
            } else {
                library.muse.searchSingerCount(requestedQuery, areaFilter, typeFilter)
            }
            main.post(Runnable {
                if (requestVersion != browseRequestVersion || browseMode != "singer_list" ||
                    browseParam.orEmpty() != requestedType || browsePage != requestedPage ||
                    activeSearchQuery != requestedQuery
                ) return@Runnable
                browseTotalCount = total
                browseTotalPages = (total + pageSize - 1) / pageSize
                visibleSingers.clear()
                visibleSingers.addAll(singers)
                songList!!.dividerHeight = 0
                songList!!.adapter = SingerGridAdapter(this, singers) { singer ->
                    loadSingerSongs(singer.getOrNull(0), singer.getOrNull(1))
                }
                updatePageInfo()
            })
        })
    }

    private fun singerFilter(category: String?): Pair<String, String> = when (category) {
        "大陆男" -> "大陆" to "男"
        "大陆女" -> "大陆" to "女"
        "港台男" -> "港台" to "男"
        "港台女" -> "港台" to "女"
        "中国组合" -> "中国" to "组合"
        "外国组合" -> "外国" to "组合"
        else -> "" to ""
    }

    private fun loadSingerSongs(singerId: String?, singerName: String?) {
        searchScope = "singer_songs"
        searchContextId = singerId.orEmpty()
        activeSearchQuery = ""
        setSearchText("")
        showSubPageShell("主页 / 歌星 / " + singerName)
        currentTabIndex = 1
        setupTabs()
        browseMode = "singer_id"
        browseParam = singerId
        browsePage = 0
        listTitle!!.setText(singerName)
        loadBrowsePage()
    }

    /**
     * 加载收藏歌曲
     */
    /**
     * 加载歌单
     */
    private fun loadPlaylists() {
        showSubPageShell("主页 / 歌单")
        listTitle!!.setText("我的歌单")
        currentCategories.clear()
        // 获取歌单列表(从数据库 playlists 表)
        val playlistData: MutableList<Array<String?>> = library.muse.playlists(0, 100)
        if (playlistData.isEmpty()) {
            currentCategories.add("暂无歌单")
        } else {
            for (p in playlistData) {
                currentCategories.add(p[1].orEmpty()) // p[1] 是歌单名称
            }
        }
        currentCategoryIndex = 0
        updateCategories()
        if (!currentCategories.isEmpty() && currentCategories.get(0) != "暂无歌单") {
            browseMode = "playlist"
            browseParam = currentCategories.get(0)
            browsePage = 0
            loadBrowsePage()
        }
    }

    /**
     * 加载已点歌曲列表 (Tab 4)
     */
    private fun loadOrderedList() {
        currentTabIndex = 4
        showSubPageShell("主页 / 已点")
        setupTabs()
        if (keyboardArea != null) keyboardArea!!.setVisibility(View.GONE)
        listTitle!!.setText("已点歌曲 (" + orderQueue!!.size + ")")
        currentCategories.clear()
        currentCategoryIndex = 0
        browseMode = "ordered"
        browseParam = ""
        browsePage = 0
        // 直接更新歌曲列表
        visibleSongs.clear()
        visibleSongs.addAll(orderQueue)
        updateCategories()
        renderSongList()
        updateOrderBadge()
    }

    /**
     * 加载下载列表 (Tab 5)
     */
    private fun loadDownloadedList() {
        currentTabIndex = 6
        showSubPageShell("主页 / 下载")
        setupTabs()
        listTitle!!.setText("下载管理")
        currentCategories.clear()
        currentCategoryIndex = 0
        browseMode = "downloads"
        browseParam = ""
        browsePage = 0
        // The original download page is a task queue. Completed files move to the local-song page.
        visibleSongs.clear()
        for (task in downloads) {
            visibleSongs.add(task.song)
        }
        if (visibleSongs.isEmpty()) {
            toast("暂无下载任务")
        }
        updateCategories()
        renderSongList()
    }

    private fun loadLocalCatalogPage() {
        browseMode = "local"
        val language = searchLanguage.ifEmpty { browseParam.orEmpty() }
        val query = activeSearchQuery.trim().uppercase(Locale.ROOT)
        val requestedPage = browsePage
        val requestVersion = ++browseRequestVersion
        val pageSize = browsePageSize()
        io.execute {
            val localSongs = localSongInventory()
            downloads.asSequence().map { it.song }
                .plus(library.allSongs().asSequence())
                .forEach { song ->
                if (song.hasLocalFile() || isDownloaded(song)) {
                    localSongs.putIfAbsent(stableId(song), song)
                }
            }
            val filtered = localSongs.values.filter {
                val languageMatches = language.isEmpty() || language == "全部" || it.language == language
                val text = listOf(it.title, it.singer, it.pinyin, it.pinyinFull)
                    .joinToString(" ").uppercase(Locale.ROOT)
                languageMatches && (query.isEmpty() || query in text)
            }
            val from = (requestedPage * pageSize).coerceAtMost(filtered.size)
            val page = filtered.drop(from).take(pageSize)
            main.post {
                if (requestVersion != browseRequestVersion || browseMode != "local" ||
                    browsePage != requestedPage || activeSearchQuery.uppercase(Locale.ROOT) != query ||
                    searchLanguage != language
                ) return@post
                browseTotalCount = filtered.size
                browseTotalPages = (filtered.size + pageSize - 1) / pageSize
                visibleSongs.clear()
                visibleSongs.addAll(page)
                renderSongList()
                updatePageInfo()
            }
        }
    }

    /**
     * 加载已唱历史 (Tab 5)
     */
    private fun loadSangHistory() {
        currentTabIndex = 5
        showSubPageShell("主页 / 已唱")
        setupTabs()
        if (keyboardArea != null) keyboardArea!!.setVisibility(View.GONE)
        listTitle!!.setText("已唱历史 (" + sangHistory.size + ")")
        currentCategories.clear()
        currentCategoryIndex = 0
        browseMode = "sang"
        browseParam = ""
        browsePage = 0
        visibleSongs.clear()
        visibleSongs.addAll(sangHistory)
        updateCategories()
        renderSongList()
    }

    /**
     * 加载收藏列表 (Tab 7) - 从 KtvStore 收藏和数据库查询
     */
    private fun loadFavorites() {
        currentTabIndex = 7
        showSubPageShell("主页 / 收藏")
        setupTabs()
        listTitle!!.setText("我的收藏")
        currentCategories.clear()
        currentCategoryIndex = 0
        browseMode = "favorites"
        browseParam = ""
        browsePage = 0
        visibleSongs.clear()

        // 从数据库查询收藏歌曲
        io.execute(Runnable {
            val result: MutableList<Song> = ArrayList<Song>()
            for (songId in store.favoriteIds) {
                val song = library.muse.songById(songId)
                    ?: library.allSongs().firstOrNull { stableId(it) == songId }
                if (song != null) result.add(song)
            }
            main.post(Runnable {
                visibleSongs.clear()
                visibleSongs.addAll(result)
                updateCategories()
                renderSongList()
                if (visibleSongs.isEmpty()) toast("暂无收藏，长按歌曲可收藏")
            })
        })
    }

    /**
     * 显示设置对话框 (旧版 Tab 8, 现已改为弹窗)
     */
    private fun showSettingsPage(requestInitialFocus: Boolean = true) {
        currentTabIndex = 8
        showSubPageShell("主页 / 设置")
        browseMode = "settings"
        visibleSongs.clear()
        currentCategories.clear()
        updateCategories()
        if (keyboardArea != null) keyboardArea!!.setVisibility(View.GONE)
        if (tabContainer != null) tabContainer!!.setVisibility(View.GONE)
        if (subCategoryScroll != null) subCategoryScroll!!.setVisibility(View.GONE)
        if (paginationBar != null) paginationBar!!.setVisibility(View.GONE)
        browsePagination?.visibility = View.GONE

        val entries = listOf(
            SettingsEntry(R.drawable.ott_ic_data_setting, "曲库方式", "本地 / U盘 / 自有网络曲库，完整数据库按需下载", "选择") { showCatalogSetupOptions() },
            SettingsEntry(R.drawable.ott_ic_data_setting, "数据设置", "数据库、歌曲下载、歌曲文件相关设置") { showSettingsSection(1) },
            SettingsEntry(R.drawable.ott_ic_play, "播放设置", "播放器、视频窗口相关设置") { showSettingsSection(2) },
            SettingsEntry(R.drawable.ott_ic_sound, "声音设置") { showSoundSettingsDialog() },
            SettingsEntry(
                R.drawable.ott_ic_data_upgrade,
                "应用更新",
                "当前版本 ${BuildConfig.VERSION_NAME}",
                "检查更新",
            ) { toast("当前已是最新版本 ${BuildConfig.VERSION_NAME}") },
            SettingsEntry(R.drawable.ott_ic_about_us, "关于", actionText = "查看") { showAboutDialog() },
        )
        activeSettingsEntries = entries
        songList!!.setDivider(ColorDrawable(Color.TRANSPARENT))
        songList!!.setDividerHeight(dp(12))
        songList!!.setBackgroundColor(Color.TRANSPARENT)
        val settingsAdapter = SettingsListAdapter(this, entries, R.id.btn_back)
        songList!!.adapter = settingsAdapter
        btnBack?.nextFocusDownId = settingsAdapter.firstActionId
        if (requestInitialFocus) settingsAdapter.requestInitialFocus(songList!!)
    }

    private fun openSettingsItem(position: Int) {
        activeSettingsEntries.getOrNull(position)?.action?.invoke()
    }

    /**
     * 曲库统计：songCount() 是 67 万行表的 COUNT(*)，countDownloadedFiles() 会对每个已下载文件
     * 深读文件头尾（SongFileValidator 读约 4.3MiB）。两者都必须在 io 线程，主线程只显示缓存文本。
     */
    private fun refreshLibraryStatsAsync() {
        if (libraryStatsPending) return
        libraryStatsPending = true
        io.execute {
            val text = runCatching {
                "数据库 ${library.muse.songCount()} 首  本地已下载 ${countDownloadedFiles()} 首"
            }.getOrElse { "数据库统计失败" }
            main.post {
                libraryStatsPending = false
                if (libraryStatsText == text) return@post
                libraryStatsText = text
                // 只在数据页刷新，避免打断用户当前所在的其他设置页
                if (currentPage == "设置" && browseMode == "settings_section" && currentSettingsSection == 1) {
                    showSettingsSection(1, false)
                }
            }
        }
    }

    /** 曲库统计异步计算结果与在途标记（设置页"曲库"行，主线程不做 COUNT/文件深读）。 */
    private var libraryStatsText: String? = null
    private var libraryStatsPending = false

    private var currentSettingsSection = 0

    private fun showSettingsSection(section: Int, requestInitialFocus: Boolean = true) {
        currentSettingsSection = section
        if (section == 1 && libraryStatsText == null) refreshLibraryStatsAsync()
        val names = arrayOf("网络设置", "数据设置", "播放设置", "声音设置", "界面设置")
        currentTabIndex = 8
        showSubPageShell("主页 / 设置 / ${names[section]}")
        browseMode = "settings_section"
        currentCategories.clear()
        updateCategories()
        keyboardArea?.visibility = View.GONE
        tabContainer?.visibility = View.GONE
        browsePagination?.visibility = View.GONE
        val entries = when (section) {
            0 -> listOf(
                SettingsEntry(R.drawable.ott_ic_wifi_setting, "无线网络") {
                    runCatching { startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)) }
                },
            )
            1 -> listOf(
                // 曲库统计（songCount 全表 COUNT + countDownloadedFiles 逐文件深读 8MB）原来在主线程，
                // 车机上是数十秒级冻结。改成先显示"统计中…"，异步算完再回填。
                SettingsEntry(
                    R.drawable.ott_ic_data_upgrade, "曲库",
                    libraryStatsText ?: "数据库统计中…", "更新",
                ) {
                    io.execute { library.muse.close(); library.muse.open(); main.post { showSettingsSection(1, false) } }
                },
                SettingsEntry(R.drawable.ott_ic_data_setting, "U盘加歌", "扫描U盘内按规定命名的歌曲文件，并添加到曲库中", "立即加歌") { toast("未检测到U盘") },
                SettingsEntry(R.drawable.ott_ic_setting_storage_space, "预留存储空间", "当前 ${String.format(Locale.ROOT, "%.1f", reserveStorageGb)} GB") { showReserveStorageDialog() },
                SettingsEntry(R.drawable.ott_ic_data_reset, "自动删歌", "当剩余存储空间低于预留空间时，自动删除最久未使用的歌曲", action = {
                    autoDeleteSongs = !autoDeleteSongs
                    saveState()
                    if (autoDeleteSongs) enforceStorageReserve(showResult = true)
                }, checked = autoDeleteSongs),
                SettingsEntry(R.drawable.ott_ic_data_upgrade, "重置数据库", "删除本地数据库并重新下载", "重置") { confirmResetDatabase() },
                SettingsEntry(R.drawable.ott_ic_setting_storage_space, "硬盘读写权限") { toast(storageStatusText()) },
            )
            2 -> listOf(
                SettingsEntry(R.drawable.ott_ic_download_setting, "开机清空下载列表", "开机时删除本地已下载歌曲并清理数据库状态", action = { clearDownloadsOnBoot = !clearDownloadsOnBoot; saveState() }, checked = clearDownloadsOnBoot),
                SettingsEntry(R.drawable.ott_ic_tv_display_mode, "自动全屏", "设置多久未操作后自动进入全屏", "设置间隔") { showAutoFullscreenDialog() },
                SettingsEntry(R.drawable.ott_ic_play, "音画同步") { showAudioSyncDialog() },
                SettingsEntry(R.drawable.ott_ic_marquee_setting, "歌曲片头字幕", action = { songTitleSubtitleEnabled = !songTitleSubtitleEnabled; saveState() }, checked = songTitleSubtitleEnabled),
            )
            3 -> listOf(
                SettingsEntry(R.drawable.ott_ic_sound, "声音设置") { showSoundSettingsDialog() },
            )
            else -> emptyList()
        }
        activeSettingsEntries = entries
        songList!!.divider = ColorDrawable(Color.TRANSPARENT)
        songList!!.dividerHeight = dp(12)
        songList!!.setBackgroundColor(Color.TRANSPARENT)
        val settingsAdapter = SettingsListAdapter(this, entries, R.id.btn_back)
        songList!!.adapter = settingsAdapter
        btnBack?.nextFocusDownId = settingsAdapter.firstActionId
        if (requestInitialFocus) settingsAdapter.requestInitialFocus(songList!!)
    }

    private fun isNetworkConnected(): Boolean {
        val manager = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager
        return manager?.activeNetworkInfo?.isConnected == true
    }

    private fun networkStatusText(): String = if (isNetworkConnected()) "已连接" else "未连接"

    private fun storageStatusText(): String {
        val root = Environment.getExternalStorageDirectory()
        return "可用 ${formatSize(root.usableSpace)} / 总计 ${formatSize(root.totalSpace)}"
    }

    private fun downloadedSongDirectory(): File =
        File(MuseDatabase.VIDEO_ROOT, MuseDatabase.CLOUD_SONG_DIR)

    private fun countDownloadedFiles(): Int = localSongInventory().size

    private fun localSongInventory(): LinkedHashMap<String, Song> {
        val result = LinkedHashMap<String, Song>()
        library.muse.localPathSongs().forEach { song ->
            val resolved = File(MuseDatabase.resolveSongFilePath(song))
            if (SongFileValidator.inspect(
                    resolved,
                    SongFileValidator.requiresTransportStream(resolved),
                ).valid
            ) {
                song.path = resolved.absolutePath
                result.putIfAbsent(stableId(song), song)
            }
        }

        val downloadedFiles = MuseDatabase.songDirectories()
            .flatMap { it.listFiles().orEmpty().asIterable() }
            .distinctBy { it.name }
        val inspections = downloadedFiles.asSequence()
            .filter { it.isFile && !it.name.endsWith(".download") }
            .associateWith { file ->
                SongFileValidator.inspect(file, SongFileValidator.requiresTransportStream(file))
            }
        inspections.filter { (file, inspection) ->
            file.parentFile?.absolutePath == downloadedSongDirectory().absolutePath && !inspection.valid
        }.keys.forEach { invalid ->
            Log.w(TAG, "Deleting invalid downloaded song: ${invalid.absolutePath}, ${inspections[invalid]?.reason}")
            SongFileValidator.forget(invalid)
            cleanupSidecars(invalid)
            invalid.delete()
        }
        val validFiles = inspections.filter { (file, inspection) -> file.exists() && inspection.valid }.keys
        val filesByName = validFiles.associateBy { it.name }
        val databaseSongs = library.muse.songsByFilenames(filesByName.keys)
        databaseSongs.forEach { song ->
            filesByName[song.filename]?.let { song.path = it.absolutePath }
            result.putIfAbsent(stableId(song), song)
        }
        val matchedNames = databaseSongs.mapNotNullTo(HashSet()) { it.filename }
        validFiles.filter { it.name !in matchedNames }.forEach { file ->
            val song = Song.local(file.absolutePath, file.name)
            song.filename = file.name
            result.putIfAbsent(stableId(song), song)
        }
        return result
    }

    private fun clearDownloadedFilesOnBoot() {
        downloadedSongDirectory().listFiles().orEmpty().forEach { file ->
            if (file.isFile) {
                cleanupSidecars(file)
                runCatching { file.delete() }
            }
        }
        downloads.clear()
        runCatching { stateDatabase.clearDownloads() }
    }

    private fun isBelowReservedStorage(): Boolean {
        val required = (reserveStorageGb * 1024.0 * 1024.0 * 1024.0).toLong()
        return downloadedSongDirectory().usableSpace < required
    }

    private fun enforceStorageReserve(showResult: Boolean = false) {
        if (!autoDeleteSongs || !isBelowReservedStorage()) return
        io.execute {
            val deleted = purgeColdDownloadedFiles()
            val enough = !isBelowReservedStorage()
            if (showResult) main.post {
                toast(
                    if (enough) "已清理 $deleted 首歌曲，预留空间已恢复"
                    else "已清理 $deleted 首歌曲，受保护歌曲不会被删除，空间仍不足",
                )
                if (browseMode == "settings_section") showSettingsSection(1, false)
            }
        }
    }

    private fun purgeColdDownloadedFiles(): Int {
        val required = (reserveStorageGb * 1024.0 * 1024.0 * 1024.0).toLong()
        val protectedNames = buildSet {
            currentSong?.filename?.let(::add)
            orderQueue.orEmpty().mapNotNullTo(this) { it.filename }
        }
        var deletedCount = 0
        downloadedSongDirectory().listFiles().orEmpty()
            .filter { it.isFile && it.name !in protectedNames && !it.name.endsWith(".download") }
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            .forEach { file ->
                if (downloadedSongDirectory().usableSpace >= required) return deletedCount
                cleanupSidecars(file)
                if (file.delete()) {
                    deletedCount++
                    library.allSongs().filter { it.filename == file.name }.forEach { song ->
                        song.path = null
                        runCatching { stateDatabase.removeDownload(song) }
                    }
                }
            }
        return deletedCount
    }

    private fun confirmResetDatabase() {
        AlertDialog.Builder(this)
            .setTitle("重置数据库")
            .setMessage("将删除本地曲库数据库，然后从服务器重新下载。不会保留备份，是否继续？")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认") { _, _ -> resetDatabaseFromGitee() }
            .create().showForTv()
    }

    private fun resetDatabaseFromGitee() {
        showDatabaseLoading(true, "正在重置数据库...", null)
        io.execute {
            library.muse.close()
            MuseDatabase.defaultDbFile().delete()
            val result = DatabaseBootstrapper.download(::showDatabaseDownloadProgress)
            val opened = result.isSuccess && library.muse.open()
            main.post {
                if (opened) {
                    showDatabaseLoading(false, "", null)
                    toast("数据库重置完成")
                    showSettingsSection(1, false)
                } else {
                    showDatabaseLoadingFailure("数据库重置失败：${result.exceptionOrNull()?.message.orEmpty()}")
                }
            }
        }
    }

    private fun showDatabaseLoading(show: Boolean, message: String, progressValue: Int?) {
        if (!show) {
            databaseLoadingOverlay?.visibility = View.GONE
            return
        }
        if (databaseLoadingOverlay == null) {
            val root = findViewById<FrameLayout>(R.id.layout_root)
            val overlay = FrameLayout(this).apply {
                setBackgroundColor(Color.rgb(24, 17, 45))
                isClickable = true
                isFocusable = true
                elevation = dp(30).toFloat()
            }
            val center = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ktv_logo)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }, LinearLayout.LayoutParams(dp(112), dp(112)))
                addView(ProgressBar(this@MainActivity, null, 0, R.style.LoadingProgressBar), LinearLayout.LayoutParams(dp(54), dp(54)).apply {
                    topMargin = dp(22)
                })
                databaseLoadingText = TextView(this@MainActivity).apply {
                    textSize = 20f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setPadding(dp(20), dp(18), dp(20), dp(12))
                }
                addView(databaseLoadingText, LinearLayout.LayoutParams(dp(620), -2))
                databaseLoadingProgress = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progressDrawable = getDrawable(R.drawable.bg_download_progress)
                }
                addView(databaseLoadingProgress, LinearLayout.LayoutParams(dp(620), dp(10)))
                databaseRetryButton = TextView(this@MainActivity).apply {
                    text = "重试"
                    textSize = 19f
                    gravity = Gravity.CENTER
                    setTextColor(Color.rgb(45, 20, 65))
                    isClickable = true
                    isFocusable = true
                    visibility = View.GONE
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(Color.rgb(255, 204, 92))
                    }
                }
                addView(databaseRetryButton, LinearLayout.LayoutParams(dp(180), dp(54)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(24)
                })
            }
            overlay.addView(center, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
            databaseLoadingOverlay = overlay
        }
        databaseLoadingOverlay?.visibility = View.VISIBLE
        databaseLoadingText?.text = message
        databaseLoadingProgress?.apply {
            visibility = if (progressValue == null) View.INVISIBLE else View.VISIBLE
            progress = progressValue ?: 0
        }
        databaseRetryButton?.apply {
            visibility = View.GONE
            isEnabled = true
            setOnClickListener(null)
        }
        databaseLoadingOverlay?.bringToFront()
    }

    private fun showDatabaseLoadingFailure(message: String) {
        showDatabaseLoading(false, "", null)
        showHomePage()
        showOptionalCatalogFailure(message)
    }

    // ===== 设置子功能 (新Tab版) =====
    private fun dialogSingleChoiceGroup(
        labels: Array<String>,
        selected: Int,
        onSelected: (Int) -> Unit,
    ): android.widget.RadioGroup = android.widget.RadioGroup(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(6), dp(20), dp(6))
        labels.forEachIndexed { index, label ->
            addView(android.widget.RadioButton(this@MainActivity).apply {
                id = View.generateViewId()
                tag = index
                text = label
                textSize = 17f
                setTextColor(Color.WHITE)
                isChecked = index == selected
                minimumHeight = 0
                minHeight = 0
                setPadding(dp(8), 0, dp(14), 0)
                layoutParams = android.widget.RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(48),
                )
                TvFocusStyler.installAction(this)
            })
        }
        setOnCheckedChangeListener { group, checkedId ->
            val index = group.findViewById<View>(checkedId)?.tag as? Int
                ?: return@setOnCheckedChangeListener
            onSelected(index)
        }
    }

    private fun dialogMultiChoiceGroup(
        labels: Array<String>,
        checked: BooleanArray,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(6), dp(20), dp(6))
        labels.forEachIndexed { index, label ->
            addView(android.widget.CheckBox(this@MainActivity).apply {
                text = label
                textSize = 17f
                setTextColor(Color.WHITE)
                isChecked = checked.getOrElse(index) { false }
                minimumHeight = 0
                minHeight = 0
                setPadding(dp(8), 0, dp(14), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(48),
                )
                setOnCheckedChangeListener { _, value -> checked[index] = value }
                TvFocusStyler.installAction(this)
            })
        }
    }

    private fun showScreenDialog() {
        val items = arrayOf<String>("16 : 9", "4 : 3", "全屏")
        val selected = when (screenMode) {
            "16 : 9" -> 0
            "4 : 3" -> 1
            else -> 2
        }
        var pending = selected
        val choices = dialogSingleChoiceGroup(items, selected) { pending = it }
        AlertDialog.Builder(this)
            .setTitle("视频比例")
            .setView(choices)
            .setNegativeButton("关闭", null)
            .setPositiveButton("确认") { _, _ ->
                screenMode = items[pending]
                saveState()
                applyScreenMode()
            }.create().showForTv()
    }

    private fun showAutoFullscreenDialog() {
        val items = arrayOf("30秒", "45秒", "1分钟", "2分钟", "关闭（不推荐）")
        val seconds = intArrayOf(30, 45, 60, 120, 0)
        val selected = seconds.indexOf(autoFullscreenSeconds).let { if (it >= 0) it else 4 }
        var pending = selected
        val choices = dialogSingleChoiceGroup(items, selected) { pending = it }
        AlertDialog.Builder(this).setTitle("设置间隔")
            .setView(choices)
            .setNegativeButton("关闭", null)
            .setPositiveButton("确认") { _, _ ->
                autoFullscreenSeconds = seconds[pending]
                saveState()
                scheduleAutoFullscreen()
                showSettingsSection(2, false)
            }.create().showForTv()
    }

    private fun showReserveStorageDialog() {
        val totalGb = downloadedSongDirectory().totalSpace.toDouble() / 1024.0 / 1024.0 / 1024.0
        val values = listOf(0.5, 1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 64.0)
            .filter { it <= max(0.5, totalGb * 0.95) }
            .ifEmpty { listOf(0.5) }
        val labels = values.map { value ->
            "${String.format(Locale.ROOT, if (value < 1) "%.1f" else "%.0f", value)} GB"
        }.toTypedArray()
        var selected = values.indices.minByOrNull { kotlin.math.abs(values[it] - reserveStorageGb) } ?: 0
        AlertDialog.Builder(this).setTitle("预留存储空间")
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setNegativeButton("取消", null).setPositiveButton("保存") { _, _ ->
                reserveStorageGb = values[selected]
                saveState()
                if (autoDeleteSongs) enforceStorageReserve(showResult = true)
                showSettingsSection(1, false)
            }.create().showForTv()
    }

    private fun showAudioSyncDialog() {
        var pendingDelay = audioDelayMs
        val seek = SeekBar(this).apply { max = 40; progress = (pendingDelay / 50 + 20).coerceIn(0, 40) }
        val value = TextView(this).apply { gravity = Gravity.CENTER; textSize = 18f }
        fun update() { value.text = "声音${if (pendingDelay >= 0) "提前" else "延后"} ${kotlin.math.abs(pendingDelay) / 1000f} 秒" }
        update()
        seek.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { pendingDelay = (progress - 20) * 50; update() }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(32), dp(8), dp(32), dp(16)); addView(value); addView(seek) }
        AlertDialog.Builder(this).setTitle("音画同步").setView(box).setMessage("重唱或切歌后生效")
            .setNegativeButton("关闭", null)
            .setPositiveButton("确认") { _, _ -> audioDelayMs = pendingDelay; saveState() }
            .create().showForTv()
    }

    private fun showBannerConfigDialog() {
        val labels = arrayOf("新歌", "戏曲", "广场舞")
        val pending = booleanArrayOf(true, true, true)
        val choices = dialogMultiChoiceGroup(labels, pending)
        AlertDialog.Builder(this).setTitle("轮播图配置")
            .setView(choices)
            .setNegativeButton("关闭", null).setPositiveButton("确认", null)
            .create().showForTv()
    }

    private fun showSoundSettingsDialog() {
        var pendingOrderedSongOriginal = orderedSongOriginal
        var pendingOrderedVolumeMode = orderedVolumeMode
        var pendingOrderedVocalMode = orderedVocalMode
        var pendingMusicVolume = musicVolume
        fun title(text: String) = TextView(this).apply {
            this.text = text; textSize = 16f; setTextColor(Color.WHITE); setPadding(0, dp(4), 0, 0)
        }
        fun choices(labels: Array<String>, selected: Int, horizontal: Boolean = false, changed: (Int) -> Unit) =
            android.widget.RadioGroup(this).apply {
                orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                labels.forEachIndexed { index, label ->
                    addView(android.widget.RadioButton(this@MainActivity).apply {
                        text = label; textSize = 15f; setTextColor(Color.WHITE); isChecked = index == selected
                        id = View.generateViewId(); tag = index
                        minimumHeight = 0
                        minHeight = 0
                        setPadding(0, 0, dp(6), 0)
                        layoutParams = android.widget.RadioGroup.LayoutParams(-2, dp(30))
                        TvFocusStyler.installAction(this)
                    })
                }
                setOnCheckedChangeListener { group, checkedId ->
                    val index = group.findViewById<View>(checkedId)?.tag as? Int ?: return@setOnCheckedChangeListener
                    changed(index)
                }
            }
        val musicVolumeTitle = title("歌曲最大音量  ${pendingMusicVolume}%")
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title("切到点播歌曲时音量："))
            addView(choices(arrayOf("跟随上首音量", "跟随上首点播歌曲音量"), pendingOrderedVolumeMode) { pendingOrderedVolumeMode = it })
            addView(musicVolumeTitle)
            addView(SeekBar(this@MainActivity).apply {
                max = 100; progress = pendingMusicVolume
                setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                        pendingMusicVolume = value
                        musicVolumeTitle.text = "歌曲最大音量  ${pendingMusicVolume}%"
                    }
                    override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                })
            })
        }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title("点播歌曲原伴唱"))
            addView(choices(arrayOf("原唱", "伴唱"), if (pendingOrderedSongOriginal) 0 else 1, true) { pendingOrderedSongOriginal = it == 0 })
            addView(title("切到点播歌曲时原伴唱："))
            addView(choices(arrayOf("跟随上首原伴唱", "跟随上首点播原伴唱", "固定点播原伴唱"), pendingOrderedVocalMode) { pendingOrderedVocalMode = it })
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(28), dp(4), dp(28), dp(12))
            addView(left, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(28) })
            addView(right, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val scroll = android.widget.ScrollView(this).apply { addView(content) }
        val dialog = AlertDialog.Builder(this).setTitle("声音设置")
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .setPositiveButton("确认") { _, _ ->
                orderedSongOriginal = pendingOrderedSongOriginal
                orderedVolumeMode = pendingOrderedVolumeMode
                orderedVocalMode = pendingOrderedVocalMode
                musicVolume = pendingMusicVolume
                applyPlaybackMode()
                saveState()
            }.create()
        dialog.showForTv(DialogInterface.BUTTON_NEGATIVE) {
            val available = (resources.displayMetrics.widthPixels * 0.92f).toInt()
            dialog.window?.setLayout(min(dp(790), available), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun showSingModeDialog() {
        val items = arrayOf<String>("普通演唱", "评分演唱", "静音练唱")
        AlertDialog.Builder(this)
            .setTitle("演唱模式 (当前: " + singMode + ")")
            .setItems(items, DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                singMode = items[w]
                if ("评分演唱" == singMode) scoreEnabled = true
                else if ("静音练唱" == singMode) {
                    scoreEnabled = false
                    musicVolume = 0
                }
                saveState()
                applyPlaybackMode()
                toast("演唱模式: " + singMode)
            }).create().showForTv()
    }

    private fun showVocalChannelDialog() {
        val items = arrayOf("自动", "左伴右原", "右伴左原", "全声道")
        AlertDialog.Builder(this)
            .setTitle("声道模式 (当前: $vocalChannelMode)")
            .setItems(items) { _, which ->
                setVocalChannelMode(items[which])
                showSettingsSection(3)
            }
            .create().showForTv()
    }

    private fun showMarqueeDialog() {
        val status = if (marqueeEnabled) "已开启: " + marqueeText else "已关闭"
        AlertDialog.Builder(this)
            .setTitle("跑马灯设置 - " + status)
            .setItems(
                arrayOf<String>("开启", "关闭", "编辑文字"),
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    if (w == 0) {
                        marqueeEnabled = true
                        saveState()
                    } else if (w == 1) {
                        marqueeEnabled = false
                        saveState()
                    } else {
                        val input = EditText(this)
                        input.setText(marqueeText)
                        input.setHint("输入跑马灯文字")
                        AlertDialog.Builder(this)
                            .setTitle("编辑跑马灯文字")
                            .setView(input)
                            .setPositiveButton(
                                "确定",
                                DialogInterface.OnClickListener { dd: DialogInterface?, ww: Int ->
                                    marqueeText = input.getText().toString()
                                    marqueeEnabled = true
                                    saveState()
                                }).setNegativeButton("取消", null).create().showForTv()
                    }
                    toast("跑马灯: " + (if (marqueeEnabled) marqueeText else "已关闭"))
                }).create().showForTv()
    }

    private fun showNetworkDlg() {
        var ip = "未知"
        try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val ni = en.nextElement()
                val addr = ni.getInetAddresses()
                while (addr.hasMoreElements()) {
                    val ia = addr.nextElement()
                    if (!ia.isLoopbackAddress() && ia is Inet4Address) {
                        ip = ia.getHostAddress().orEmpty()
                    }
                }
            }
        } catch (ignored: Exception) {
        }
        AlertDialog.Builder(this)
            .setTitle("网络设置")
            .setMessage("IP: " + ip + "\n手机遥控端口: 8765\n扫码连接手机点歌")
            .setPositiveButton("确定", null).create().showForTv(DialogInterface.BUTTON_POSITIVE)
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("关于风火轮 K歌版")
            .setMessage(
                "power by 抖音@杨家三郎\n" +
                    "此软件免费开源，请勿付费获取\n" +
                    "版本：" + BuildConfig.VERSION_NAME
            )
            .setPositiveButton("确定", null).create().showForTv(DialogInterface.BUTTON_POSITIVE)
    }

    private fun applyScreenMode() {
        if (player != null) {
            try {
                applyPlaybackMode()
            } catch (ignored: Exception) {
            }
        }
    }

    /**
     * 更新分类列表 UI
     */
    private fun updateCategories() {
        val focusedCategory = window.decorView.findFocus()?.takeIf { isDescendantOf(it, categoryList) }
            ?.let(::captureFocusBookmark)
        categoryList!!.removeAllViews()
        subCategoryScroll?.visibility = if (currentCategories.isEmpty()) View.GONE else View.VISIBLE
        for (i in currentCategories.indices) {
            val index = i
            val cat = TextView(this)
            cat.setText(currentCategories.get(i))
            cat.setTextSize(16f)
            cat.setPadding(dp(12), 0, dp(12), 0)
            cat.gravity = Gravity.CENTER
            cat.setAccessibleFocus("focus:category:${currentCategories[i]}", "分类，${currentCategories[i]}")
            cat.setFocusable(true)
            cat.setClickable(true)
            // 设置选中状态
            if (i == currentCategoryIndex) {
                cat.setTextColor(Color.rgb(253, 51, 89))
                cat.setBackgroundColor(Color.argb(30, 253, 51, 89))
            } else {
                cat.setTextColor(Color.argb(200, 241, 241, 241))
                cat.setBackgroundColor(Color.TRANSPARENT)
            }
            cat.setOnClickListener(View.OnClickListener { v: View? ->
                currentCategoryIndex = index
                updateCategories()
                // 根据分类加载歌曲
                val category = currentCategories.get(index)
                clearSearchForFilterChange()
                when (currentTabIndex) {
                    0 -> if ("全部" == category) {
                        browseMode = "hot"
                        browseParam = ""
                        searchLanguage = "全部"
                    } else {
                        browseMode = "language"
                        browseParam = category
                        searchLanguage = category
                    }

                    1 -> {
                        browsePage = 0
                        loadSingerList(category)
                        return@OnClickListener
                    }

                    2 -> {
                        browseMode = "language"
                        browseParam = category
                    }

                    3 -> {
                        browseMode = "wordcount"
                        browseParam = getWordCountParam(category)
                    }

                    5 -> {
                        browseMode = "playlist"
                        browseParam = category
                    }

                    9 -> {
                        browseMode = "rank"
                        browseParam = rankPlaylistIds.getOrNull(index).orEmpty()
                        listTitle!!.text = category
                    }

                    10 -> {
                        browseMode = "local"
                        browseParam = category
                        searchLanguage = category
                        browsePage = 0
                        loadLocalCatalogPage()
                        return@OnClickListener
                    }
                }
                browsePage = 0
                loadBrowsePage()
            })
            installPressFeedback(cat)
            categoryList!!.addView(cat)
        }
        restoreFocusBookmark(focusedCategory)
    }

    /**
     * 将字数分类转换为查询参数
     */
    private fun getWordCountParam(category: String): String {
        when (category) {
            "2字" -> return "2"
            "3字" -> return "3"
            "4字" -> return "4"
            "5字" -> return "5"
            "6字" -> return "6"
            else -> return "7" // 7字以上
        }
    }

    /**
     * 执行搜索
     */
    private fun performSearch() {
        val query = searchInput?.text?.toString()?.trim().orEmpty()
        activeSearchQuery = query
        browsePage = 0
        when (searchScope) {
            "quick" -> loadQuickSearchPage(query)
            "singer" -> {
                val category = currentCategories.getOrNull(currentCategoryIndex) ?: "全部"
                loadSingerList(category, query)
            }
            "playlist" -> loadCategoryPlaylistPage(query)
            "local" -> loadLocalCatalogPage()
            "singer_songs" -> {
                browseMode = if (query.isEmpty()) "singer_id" else "singer_id_search"
                browseParam = searchContextId
                loadBrowsePage()
            }
            "playlist_songs" -> {
                browseMode = if (query.isEmpty()) "playlist_id" else "playlist_id_search"
                browseParam = searchContextId
                loadBrowsePage()
            }
            else -> {
                searchLanguage = currentCategories.getOrNull(currentCategoryIndex) ?: "全部"
                if (query.isEmpty()) {
                    browseMode = if (searchLanguage == "全部") "hot" else "language"
                    browseParam = if (searchLanguage == "全部") "" else searchLanguage
                    listTitle?.text = if (searchLanguage == "全部") "热门歌曲" else searchLanguage
                } else {
                    browseMode = "search"
                    browseParam = query
                    listTitle?.text = "搜索结果: $query"
                }
                loadBrowsePage()
            }
        }
    }

    /**
     * 加载浏览分页数据
     */
    private fun loadBrowsePage() {
        if (!library.muse.isAvailable()) {
            catalogRefreshPending = false
            busy(false, "此页面需要完整曲库，可选择本地或网络曲库")
            showCatalogSetupOptions()
            return
        }
        catalogRefreshPending = false
        busy(false, "")
        val requestVersion = ++browseRequestVersion
        val requestedMode = browseMode
        val requestedParam = browseParam.orEmpty()
        val requestedPage = browsePage
        val requestedLanguage = searchLanguage
        val requestedSearchQuery = activeSearchQuery
        val pageSize = browsePageSize()
        val key = BrowseCacheKey(
            requestedMode,
            requestedParam,
            requestedLanguage,
            requestedSearchQuery,
            requestedPage,
            pageSize,
        )
        val cached = synchronized(browsePageCache) { browsePageCache[key] }
        if (cached != null) {
            applyBrowsePage(key, cached, requestVersion)
            prefetchBrowseNeighbors(key, cached.total)
            return
        }
        requestBrowsePage(key, requestVersion, true)
    }

    private fun browseCountKey(key: BrowseCacheKey): String =
        listOf(key.mode, key.param, key.language, key.searchQuery).joinToString("\u0001")

    private fun requestBrowsePage(key: BrowseCacheKey, requestVersion: Int, display: Boolean) {
        if (display) synchronized(browseDisplayRequests) { browseDisplayRequests[key] = requestVersion }
        synchronized(browseRequestsInFlight) {
            if (!browseRequestsInFlight.add(key)) return
        }
        io.execute(Runnable {
            try {
                val result = queryBrowsePage(key)
                synchronized(browsePageCache) { browsePageCache[key] = result }
                val displayVersion = synchronized(browseDisplayRequests) { browseDisplayRequests.remove(key) }
                if (displayVersion != null) main.post { applyBrowsePage(key, result, displayVersion) }
                prefetchBrowseNeighbors(key, result.total)
            } finally {
                synchronized(browseRequestsInFlight) { browseRequestsInFlight.remove(key) }
            }
        })
    }

    private fun queryBrowsePage(key: BrowseCacheKey): BrowsePageResult {
            var songs: MutableList<Song>? = null
            val countKey = browseCountKey(key)
            var total = synchronized(browseCountCache) { browseCountCache[countKey] } ?: -1
            val offset = key.page * key.pageSize
            if (library.muse.isAvailable()) {
                when (key.mode) {
                    "hot" -> {
                        songs = library.muse.hotSongs(offset, key.pageSize)
                        if (total < 0) total = library.muse.songCount()
                    }

                    "guess" -> {
                        songs = library.muse.hotSongs(offset, key.pageSize)
                        if (total < 0) total = library.muse.songCount()
                    }

                    "rank" -> {
                        if (key.param == LOCAL_HOT_PLAYLIST_ID) {
                            // 本机实时热歌：读 song_clicks 按点击次数实时排序，不依赖服务器快照
                            val top = if (::stateDatabase.isInitialized) {
                                stateDatabase.topClickedSongs(500)
                            } else {
                                emptyList()
                            }
                            total = top.size
                            val counts = top.toMap()
                            val pageIds = top.drop(offset).take(key.pageSize).map { it.first }
                            val idOrder = pageIds.withIndex().associate { it.value to it.index }
                            songs = library.muse.songsByIds(pageIds)
                                .sortedBy { s -> idOrder[s.id] ?: Int.MAX_VALUE }
                                .onEach { s ->
                                    s.playCount = s.id?.let { counts[it] } ?: 0
                                }
                                .toMutableList()
                        } else {
                            songs = library.muse.rankSongs(key.param, offset, key.pageSize)
                            if (total < 0) total = library.muse.rankSongCount(key.param)
                        }
                    }

                    "language" -> {
                        songs = library.muse.songsByLanguage(key.param, offset, key.pageSize)
                        if (total < 0) total = library.muse.countByLanguage(key.param)
                    }

                    "wordcount" -> {
                        val wc = parseIntSafe(key.param, 0)
                        songs = library.muse.songsByWordCount(wc, offset, key.pageSize)
                        if (total < 0) total = library.muse.countByWordCount(wc)
                    }

                    "singer" -> {
                        songs = library.muse.songsBySingerName(key.param, offset, key.pageSize)
                        if (total < 0) total = library.muse.countSongsBySingerName(key.param)
                    }

                    "singer_id" -> {
                        songs = library.muse.songsBySinger(key.param, offset, key.pageSize)
                        if (total < 0) total = library.muse.countSongsBySinger(key.param)
                    }

                    "singer_id_search" -> {
                        songs = library.muse.searchSongsBySinger(key.param, key.searchQuery, offset, key.pageSize)
                        if (total < 0) total = library.muse.countSearchSongsBySinger(key.param, key.searchQuery)
                    }

                    "search" -> {
                        songs = library.muse.searchSongs(key.param, offset, key.pageSize, key.language)
                        if (total < 0) total = library.muse.searchSongCount(key.param, key.language)
                    }

                    "favorite" -> {
                        // 收藏功能暂未实现,返回空列表
                        songs = ArrayList<Song>()
                        total = 0
                    }

                    "playlist" -> {
                        // 歌单查询(通过歌单名称查找 ID)
                        val plist: MutableList<Array<String?>> = library.muse.playlists(0, 100)
                        var playlistId = ""
                        for (p in plist) {
                            if (key.param == p[1]) {
                                playlistId = p[0].orEmpty()
                                break
                            }
                        }
                        if (!playlistId.isEmpty()) {
                            songs = library.muse.songsInPlaylist(playlistId, offset, key.pageSize)
                            if (total < 0) total = library.muse.playlistSongCount(playlistId)
                        } else {
                            songs = ArrayList<Song>()
                            total = 0
                        }
                    }

                    "playlist_id" -> {
                        songs = library.muse.songsInPlaylist(key.param, offset, key.pageSize)
                        if (total < 0) total = library.muse.playlistSongCount(key.param)
                    }

                    "playlist_id_search" -> {
                        songs = library.muse.searchSongsInPlaylist(key.param, key.searchQuery, offset, key.pageSize)
                        if (total < 0) total = library.muse.countSearchSongsInPlaylist(key.param, key.searchQuery)
                    }
                }
            }
            if (total < 0) total = 0
            synchronized(browseCountCache) { browseCountCache[countKey] = total }
            return BrowsePageResult(songs.orEmpty(), total)
    }

    private fun applyBrowsePage(key: BrowseCacheKey, result: BrowsePageResult, requestVersion: Int) {
        if (requestVersion != browseRequestVersion || key.mode != browseMode ||
            key.param != browseParam.orEmpty() || key.page != browsePage ||
            key.language != searchLanguage || key.searchQuery != activeSearchQuery
        ) return
        browseTotalCount = result.total
        browseTotalPages = (result.total + key.pageSize - 1) / key.pageSize
        visibleSongs.clear()
        visibleSongs.addAll(result.songs)
        renderSongList()
        updatePageInfo()
    }

    private fun prefetchBrowseNeighbors(key: BrowseCacheKey, total: Int) {
        val totalPages = (total + key.pageSize - 1) / key.pageSize
        listOf(key.page - 1, key.page + 1)
            .filter { it in 0 until totalPages }
            .forEach { page ->
                val neighbor = key.copy(page = page)
                val cached = synchronized(browsePageCache) { browsePageCache.containsKey(neighbor) }
                if (!cached) requestBrowsePage(neighbor, browseRequestVersion, false)
            }
    }

    private fun refreshCurrentCatalogPage() {
        catalogRefreshPending = false
        synchronized(browsePageCache) { browsePageCache.clear() }
        synchronized(browseCountCache) { browseCountCache.clear() }
        busy(false, "曲库加载完成")
        when {
            browseMode == "singer_list" -> loadSingerList(browseParam)
            browseMode == "category_list" -> loadCategoryPlaylistPage(activeSearchQuery)
            browseMode == "quick" -> loadQuickSearchPage(activeSearchQuery)
            currentTabIndex == 3 -> loadWordCounts()
            currentTabIndex == 7 -> loadFavorites()
            else -> loadBrowsePage()
        }
    }

    /**
     * 轻量刷新当前歌曲列表 Adapter (不重建), 用于下载状态变更后即时更新 UI。
     */
    private fun refreshSongAdapter() {
        modernSongAdapter?.let { adapter ->
            if (songList?.adapter === adapter) adapter.notifyDataSetChanged()
        }
    }

    /**
     * 渲染歌曲列表
     */
    private fun renderSongList() {
        val list = songList ?: return
        val focusedView = window.decorView.findFocus()?.takeIf { isDescendantOf(it, list) }
        val focusedMarker = focusedView?.focusMarker()
            ?.takeIf { it.startsWith(SongListAdapter.FOCUS_PREFIX) }
        val oldSelectedPosition = list.selectedItemPosition
        val oldFocusedTop = focusedView?.top
        if (browseMode == "ordered") {
            visibleSongs.clear()
            visibleSongs.addAll(orderQueue.orEmpty())
        } else if (browseMode == "sang") {
            visibleSongs.clear()
            visibleSongs.addAll(sangHistory)
        }
        if (visibleSongs.isEmpty()) {
            modernSongAdapter = null
            list.adapter = tvAdapter().apply { add("暂无歌曲") }
            return
        }
        list.dividerHeight = 0
        val mode = when (browseMode) {
            "rank", "ordered", "downloads", "sang" -> browseMode
            else -> "catalog"
        }
        val pageOffset = if (mode == "rank") browsePage * browsePageSize() else 0
        val existing = modernSongAdapter?.takeIf { it.matches(mode, pageOffset) && list.adapter === it }
        val listAdapter = existing ?: SongListAdapter(
            this,
            visibleSongs,
            mode,
            pageOffset,
            btnPrevPage?.id ?: View.NO_ID,
            btnNextPage?.id ?: View.NO_ID,
            { song -> songRowState(song) },
            SongListCallbacks(
                onPrimary = { song -> handleSongPrimary(song) },
                onDownload = { song -> downloadSong(song) },
                onMore = { song -> showSongActions(song) },
                onTop = { song -> moveQueuedSongToNext(song) },
                onDelete = { song ->
                    if (browseMode == "downloads") deleteDownloadedSong(song) else deleteQueuedSong(song)
                },
                onPauseResume = { song -> toggleDownloadPause(song) },
            ),
        ).also {
            modernSongAdapter = it
            list.adapter = it
        }
        if (existing != null) listAdapter.notifyDataSetChanged()

        val targetPosition = focusedMarker?.let(listAdapter::adapterPositionFor)
            ?: oldSelectedPosition.takeIf { it >= 0 }
        if (targetPosition != null && focusedMarker != null) {
            list.setSelectionFromTop(targetPosition, oldFocusedTop ?: 0)
            list.post {
                if (list.adapter !== listAdapter) return@post
                list.setSelection(targetPosition)
                val row = list.getChildAt(targetPosition - list.firstVisiblePosition)
                val target = row?.let { findViewByFocusMarker(it, focusedMarker) }
                if (target != null) {
                    target.requestFocus()
                    target.refreshDrawableState()
                    target.jumpDrawablesToCurrentState()
                    target.invalidate()
                }
            }
        }
    }

    private fun findViewByFocusMarker(root: View, marker: String): View? {
        if (root.focusMarker() == marker) return root
        if (root is ViewGroup) repeat(root.childCount) { index ->
            findViewByFocusMarker(root.getChildAt(index), marker)?.let { return it }
        }
        return null
    }

    /**
     * "是否已有本地文件"的进程内缓存。
     *
     * Song.hasLocalFile() 会走 SongFileValidator.inspect() 读文件头尾采样（约 8.6MiB），
     * 而这个方法在列表 getView 里被逐行调用，且下载中每 250ms 就notifyDataSetChanged 一次 ——
     * 在车机 eMMC 上这是最主要的掉帧来源。
     * 这里以 (路径, 长度, 修改时间) 为 key 做一层廉价缓存：先 stat 取长度（极快），
     * 命中就不做深读；下载完成/删除时由 [invalidateLocalFileCache] 清掉对应条目。
     */
private val localFileFlagCache = HashMap<String, Boolean>()

    private fun localFileFlag(song: Song): Boolean {
        val file = song.path?.takeIf(String::isNotEmpty)?.let(::File) ?: return false
        if (!file.isFile) return false
        val length = file.length()
        if (length <= 0L) return false
        val key = file.absolutePath
        val stamp = length
        localFileFlagCache[key]?.let { return it }
        val valid = SongFileValidator.inspect(file, SongFileValidator.requiresTransportStream(file)).valid
        localFileFlagCache[key] = valid
        return valid
    }

    private fun invalidateLocalFileCache() {
        localFileFlagCache.clear()
    }

    /**
     * 列表渲染期的查表快照，避免 getView 里做 O(n) 遍历。
     * songRowState 会被每一行调用一次，下载中每 250ms 触发一轮：
     * 原来 downloads.lastOrNull{} + orderQueue.contains{} 都是线性扫描，
     * 队列/任务多时单帧就是几百次比较，改为一次建表的 O(1) 查询。
     *
     * 注意不能用 stableId 单一 key 替代：Song.equals 是「id || filename || path || title|singer」
     * 的多字段或关系（见 Song.equals），而 stableId 只按优先级取一个，直接换会让
     * 「正在播放/已点」标记在部分歌上失效。所以这里把每首歌的**全部等价 key** 都写入表，
     * 查询时任一命中即等价于原来的 equals 语义。
     */
    private var queuedIdSet: Set<String> = emptySet()
    private var downloadTaskById: Map<String, DownloadTask> = emptyMap()
    private var rowStateSnapshotAt = 0L
    private var rowStateSnapshotStamp = -1

    /** 该歌曲在 equals 意义上所有可能匹配的 key（与 Song.equals 的判定字段一一对应）。 */
    private fun identityKeys(song: Song): List<String> = buildList {
        song.id?.takeIf(String::isNotEmpty)?.let { add("id:$it") }
        song.filename?.takeIf(String::isNotEmpty)?.let { add("fn:$it") }
        song.path?.takeIf(String::isNotEmpty)?.let { add("pa:$it") }
        song.title?.takeIf(String::isNotEmpty)?.let { title ->
            song.singer?.takeIf(String::isNotEmpty)?.let { add("ts:$title|$it") }
        }
    }

    private fun matchesAny(set: Set<String>, song: Song): Boolean {
        if (set.isEmpty()) return false
        song.id?.takeIf(String::isNotEmpty)?.let { if (set.contains("id:$it")) return true }
        song.filename?.takeIf(String::isNotEmpty)?.let { if (set.contains("fn:$it")) return true }
        song.path?.takeIf(String::isNotEmpty)?.let { if (set.contains("pa:$it")) return true }
        val title = song.title?.takeIf(String::isNotEmpty) ?: return false
        val singer = song.singer?.takeIf(String::isNotEmpty) ?: return false
        return set.contains("ts:$title|$singer")
    }

    private fun ensureRowStateSnapshot() {
        val now = System.currentTimeMillis()
        // 60ms 内复用同一份快照：一次刷新里 getView 会被调用十几次，无需反复重建
        if (now - rowStateSnapshotAt < 60L && rowStateSnapshotStamp == downloads.size) return
        val queued = HashSet<String>()
        orderQueue!!.forEach { queued.addAll(identityKeys(it)) }
        queuedIdSet = queued
        val tasks = HashMap<String, DownloadTask>(downloads.size.coerceAtLeast(16) * 2)
        downloads.forEach { task -> identityKeys(task.song).forEach { tasks[it] = task } }
        downloadTaskById = tasks
        rowStateSnapshotAt = now
        rowStateSnapshotStamp = downloads.size
    }

    private fun songRowState(song: Song): SongRowState {
        ensureRowStateSnapshot()
        val keys = identityKeys(song)
        val task = keys.firstNotNullOfOrNull { downloadTaskById[it] }
        val taskState = task?.state.orEmpty()
        return SongRowState(
            current = currentSong != null && matchesAny(currentKeysSet(), song),
            queued = matchesAny(queuedIdSet, song),
            local = localFileFlag(song),
            downloading = isDownloading(song),
            failed = taskState.startsWith("失败"),
            paused = taskState.contains("暂停") || taskState.contains("中断"),
            progress = task?.progress ?: SongOkDownloadManager.getDownloadProgress(song),
        )
    }

    /** 当前播放歌曲的等价 key 集合（每帧可能变化，单独取）。 */
    private fun currentKeysSet(): Set<String> {
        val song = currentSong ?: return emptySet()
        return identityKeys(song).toSet()
    }

    private fun handleSongPrimary(song: Song) {
        var rendered = false
        when (browseMode) {
            "ordered" -> {
                if (currentSong?.equals(song) == true) toast("正在播放: ${song.title}")
                else playSong(song)   // 车机触屏: 点击已点歌立即播放
                rendered = true
            }
            "downloads" -> addToQueue(song)
            "sang" -> addToQueue(song)
            else -> addToQueue(song)
        }
        persistRuntimeState()
        if (!rendered) renderSongList()
    }

    private fun moveQueuedSongToNext(song: Song) {
        if (currentSong?.equals(song) == true) return
        orderQueue!!.remove(song)
        val target = if (currentSong != null && orderQueue!!.isNotEmpty()) 1 else 0
        orderQueue.add(target.coerceAtMost(orderQueue.size), song)
        persistRuntimeState()
        syncPlayerInfo()
        renderSongList()
    }

    /**
 * 从已点列表删除一首歌。
 * 车机是纯触屏、误触概率高，且已点队列是用户攒了一晚上的歌 —— 直接删掉没有提示会引发投诉，
 * 所以加二次确认（复用 confirmRemoveSong 的 AlertDialog + showForTv 模式）。
 */
    private fun deleteQueuedSong(song: Song) {
        if (currentSong?.equals(song) == true) {
            toast("正在播放的歌曲不能删除")
            return
        }
        val pendingPosition = orderQueue!!.indexOf(song)
        if (pendingPosition < 0) {
            toast("该歌曲已不在已点列表中")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("从已点列表移除")
            .setMessage("确定不再唱这首吗？\n\n" + song.displayTitle())
            .setPositiveButton(
                "确定移除",
                DialogInterface.OnClickListener { _: DialogInterface?, _: Int ->
                    orderQueue!!.remove(song)
                    persistRuntimeState()
                    syncPlayerInfo()
                    renderSongList()
                    toast("已从已点列表移除")
                })
            .setNegativeButton("取消", null)
            .create().showForTv()
    }

    private fun toggleDownloadPause(song: Song) {
        val task = downloads.lastOrNull { stableId(it.song) == stableId(song) }
        if (isDownloading(song)) {
            SongOkDownloadManager.cancelDownload(song)
            task?.state = "已暂停"
            stateIo.execute { stateDatabase.updateDownload(song, "paused", task?.progress ?: 0) }
            renderSongList()
        } else {
            downloadSong(song)
        }
    }

    /** 删除已下载的本地文件（不可逆，必须二次确认）。 */
    private fun deleteDownloadedSong(song: Song) {
        if (currentSong?.equals(song) == true) {
            toast("正在播放的歌曲不能删除")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("删除本地文件")
            .setMessage("确定删除这首已下载的歌曲吗？删除后需要重新下载。\n\n" + song.displayTitle())
            .setPositiveButton(
                "确定删除",
                DialogInterface.OnClickListener { _: DialogInterface?, _: Int ->
                    doDeleteDownloadedSong(song)
                })
            .setNegativeButton("取消", null)
            .create().showForTv()
    }

    private fun doDeleteDownloadedSong(song: Song) {
        SongOkDownloadManager.cancelDownload(song)
        val files = listOfNotNull(song.path?.let(::File), SongOkDownloadManager.getLocalFile(song))
            .distinctBy { it.absolutePath }
        io.execute {
            val failed = files.filter { file ->
                if (!file.exists()) return@filter false
                cleanupSidecars(file)
                !runCatching { file.delete() }.getOrDefault(false)
            }
            if (failed.isEmpty()) {
                song.path = null
                invalidateLocalFileCache()
                files.forEach { SongFileValidator.forget(it) }
                runCatching { stateDatabase.removeDownload(song) }
                main.post {
                    downloads.removeAll { stableId(it.song) == stableId(song) }
                    if (browseMode == "downloads") loadDownloadedList() else reloadCurrentBrowsePage()
                    toast("已删除本地文件")
                }
            } else {
                main.post { toast("文件删除失败，请检查存储权限") }
            }
        }
    }

    /**
     * 更新分页信息
     */
    private fun updatePageInfo() {
        textPageInfo!!.setText(String.format("第%d/%d页", browsePage + 1, max(1, browseTotalPages)))
    }

    /**
     * 上一页
     */
    private fun prevPage() {
        if (browsePage > 0) {
            browsePage--
            reloadCurrentBrowsePage()
        }
    }

    /**
     * 下一页
     */
    private fun nextPage() {
        if (browsePage < browseTotalPages - 1) {
            browsePage++
            reloadCurrentBrowsePage()
        }
    }

    private fun reloadCurrentBrowsePage() {
        when (browseMode) {
            "singer_list" -> loadSingerList(browseParam, activeSearchQuery)
            "local" -> loadLocalCatalogPage()
            "category_list" -> loadCategoryPlaylistPage(activeSearchQuery)
            "quick" -> loadQuickSearchPage(activeSearchQuery)
            else -> loadBrowsePage()
        }
    }

    /**
     * 显示已点列表对话框
     */
    private fun showQueueDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("已点列表 (" + orderQueue!!.size + ")")
        if (orderQueue.isEmpty()) {
            builder.setMessage("暂无已点歌曲")
            builder.setPositiveButton("确定", null)
        } else {
            val items: Array<String> = Array(orderQueue.size) { "" }
            for (i in orderQueue.indices) {
                val s = orderQueue.get(i)
                val marker = if (currentSong != null && currentSong!!.equals(s)) " ▶" else ""
                items[i] = (i + 1).toString() + ". " + s.title + " - " + s.singer + marker
            }
            builder.setItems(
                items,
                DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                    val selected = orderQueue.get(which)
                    if (currentSong != null && currentSong!!.equals(selected)) {
                        toast("正在播放: " + selected.title)
                    } else {
                        // 只设为下一首，不打断当前歌曲。
                        moveQueuedSongToNext(selected)
                    }
                })
            builder.setNegativeButton(
                "删除选中",
                DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                    // 显示删除对话框
                    val delItems: Array<String> = Array(orderQueue.size) { "" }
                    for (i in orderQueue.indices) {
                        delItems[i] = orderQueue.get(i).title + " - " + orderQueue.get(i).singer
                    }
                    AlertDialog.Builder(this)
                        .setTitle("选择要删除的歌曲")
                        .setItems(
                            delItems,
                            DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                                val removed = orderQueue.removeAt(w)
                                if (currentSong != null && currentSong!!.equals(removed)) {
                                    playNext()
                                }
                                updateOrderBadge()
                                saveState()
                                if (currentTabIndex == 4) loadOrderedList()
                                toast("已删除: " + removed.title)
                            })
                        .create().showForTv()
                })
            builder.setNeutralButton(
                "清空全部",
                DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                    AlertDialog.Builder(this)
                        .setTitle("确认清空")
                        .setMessage("确定要清空全部" + orderQueue.size + "首已点歌曲吗?")
                        .setPositiveButton(
                            "确定",
                            DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                                orderQueue.clear()
                                if (player != null) player!!.stopPlayback()
                                playbackPreparing = false
                                playWhenPrepared = false
                                currentMediaPlayer = null
                                releaseVocalPlayer()
                                currentSong = null
                                clearLyrics()
                                updateBottomBar(null, false)
                                updateOrderBadge()
                                saveState()
                                if (currentTabIndex == 4) loadOrderedList()
                                toast("已清空列表")
                            })
                        .setNegativeButton("取消", null)
                        .create().showForTv()
                })
        }
        builder.create().showForTv()
    }

    /**
     * 更新已点数量徽标(顶栏 + 子页面旧按钮)
     */
    private fun updateOrderBadge() {
        val n = if (orderQueue == null) 0 else orderQueue.size
        if (textTopOrderBadge != null) textTopOrderBadge!!.setText(n.toString())
        if (textOrderBadge != null) textOrderBadge!!.setText(n.toString())
    }

    /**
     * 添加歌曲到已点列表
     */
    private fun addToQueue(song: Song) {
        if (orderQueue!!.contains(song)) {
            toast("已在列表中: " + song.title)
            return
        }
        if (!isSongReadyForQueue(song)) {
            val id = stableId(song)
            val newlyPending = pendingQueueSongIds.add(id)
            toast(
                if (newlyPending) "正在下载，完成后自动加入已点：${song.title}"
                else "歌曲正在下载，尚未进入已点：${song.title}",
            )
            if (!isDownloading(song)) downloadSong(song)
            return
        }
        addReadySongToQueue(song)
    }

    private fun isSongReadyForQueue(song: Song): Boolean =
        CatalogPlayback.directStreamUrl(song.remote, song.videoUrl) != null ||
            SongOkDownloadManager.isDownloaded(song) || song.hasLocalFile()

    private fun addReadySongToQueue(song: Song) {
        if (orderQueue!!.contains(song)) return
        val wasQueueEmpty = orderQueue!!.isEmpty()
        orderQueue.add(song)
        song.playCount++
        if (::stateDatabase.isInitialized) runCatching {
            stateIo.execute { stateDatabase.markSongClicked(song) }
        }
        saveState()
        updateOrderBadge()
        if (currentPage == "已点" || currentPage == "首页") renderQueue()
        toast("已点: " + song.title + (if (song.singer != null) " - " + song.singer else ""))
        if (wasQueueEmpty) play(song)
    }

    private fun scheduleAutoFullscreen() {
        main.removeCallbacks(autoFullscreenRunnable)
        if (autoFullscreenSeconds > 0 && currentSong != null && playWhenPrepared && !isFullScreen) {
            main.postDelayed(autoFullscreenRunnable, autoFullscreenSeconds * 1000L)
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (!isFullScreen) scheduleAutoFullscreen()
    }

    /**
 * 异步操作的加载指示层（懒创建，挂在 content_frame 上层）。
 * 历史问题：buildShell() 里创建的 status/progress 因container 非空而未挂载 → busy() 全是空操作，
 * 导致"曲库加载中/正在下载"等提示完全不可见，车机用户以为卡死。
 */
private var busyOverlay: View? = null
private var busyText: TextView? = null
private var busySpinner: ProgressBar? = null
private var busyDepth = 0
private val busyTimeoutRunnable = Runnable {
        // 兜底：某些曲库未就绪的分支会 busy(true) 后直接 return 且不配对 hide，
        // 计数器会永远停在 >0，loading 卡死。超过 20 秒强制收起。
        if (busyDepth > 0) {
            Log.w(TAG, "busy timeout after 20s, force hide (depth=$busyDepth)")
            busyDepth = 0
            busyOverlay?.visibility = View.GONE
        }
    }

    private fun busy(show: Boolean, message: String?) {
        // 用计数器而不是布尔，避免多个并发异步任务互相把对方的 loading 关掉
        if (show) busyDepth++ else busyDepth = (busyDepth - 1).coerceAtLeast(0)
        val needShow = busyDepth > 0
        if (!needShow) {
            busyOverlay?.visibility = View.GONE
            return
        }
        message?.let { busyText?.text = it }
        ensureBusyOverlay()
        busyOverlay?.visibility = View.VISIBLE
        main.removeCallbacks(busyTimeoutRunnable)
        main.postDelayed(busyTimeoutRunnable, 20000L)
    }

    private fun ensureBusyOverlay() {
        if (busyOverlay != null) return
        val host = findViewById<FrameLayout?>(R.id.content_frame) ?: return
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            isClickable = true // 挡住底下误触
            visibility = View.GONE
        }
        busySpinner = ProgressBar(this).apply {
            isIndeterminate = true
        }
        overlay.addView(busySpinner, LinearLayout.LayoutParams(dp(72), dp(72)))
        busyText = label("加载中...", 18, Color.WHITE).apply {
            gravity = Gravity.CENTER
        }
        overlay.addView(busyText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)))
        host.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        busyOverlay = overlay
    }

    private fun toast(text: String?) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun prefs(): SharedPreferences? {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
    }

    /**
     * 将当前运行时状态同步到 KtvStore 并持久化到磁盘。
     * 
     * 
     * 供 Fragment 在修改已点队列、收藏等数据后调用,确保状态不丢失。
     */
    fun saveState() {
        store.loopOne = loopOne
        store.autoNext = autoNext
        store.originalVocal = originalVocal
        store.vocalChannelMode = vocalChannelMode
        store.musicVolume = musicVolume
        store.micVolume = micVolume
        store.tone = tone
        store.atmosphere = atmosphere
        store.eqEnabled = KtvEqualizer.enabled
        store.eqPreset = KtvEqualizer.preset
        store.eqGlobalMix = KtvEqualizer.globalMix
        store.eqLevels = KtvEqualizer.levelsSnapshot()
            .joinToString(",") { "%.1f".format(java.util.Locale.ROOT, it) }
        store.singMode = singMode
        store.recordEnabled = recordEnabled
        store.scoreEnabled = scoreEnabled
        store.marqueeEnabled = marqueeEnabled
        store.marqueeText = marqueeText!!
        store.playWhileDownloading = playWhileDownloading
        store.clearDownloadsOnBoot = clearDownloadsOnBoot
        store.autoFullscreenSeconds = autoFullscreenSeconds
        store.showUsbSongs = showUsbSongs
        store.autoDeleteSongs = autoDeleteSongs
        store.reserveStorageGb = reserveStorageGb
        store.floatingButtonEnabled = floatingButtonEnabled
        store.songTitleSubtitleEnabled = songTitleSubtitleEnabled
        store.orderedSongOriginal = orderedSongOriginal
        store.orderedVolumeMode = orderedVolumeMode
        store.orderedVocalMode = orderedVocalMode
        store.voiceEngineEnabled = voiceEngineEnabled
        store.voiceEngineVolume = voiceEngineVolume
        store.lightMode = lightMode!!
        store.audioDelayMs = audioDelayMs
        store.screenBrightness = screenBrightness
        store.screenMode = screenMode
        store.doubleScreenEnabled = doubleScreenEnabled
        store.tableBroadcastEnabled = tableBroadcastEnabled
        store.tableBroadcastSeconds = tableBroadcastSeconds
        store.tableBroadcastText = tableBroadcastText!!
        val storeSnapshot = store.snapshotJson()
        val hasStateDb = ::stateDatabase.isInitialized
        // P0 修复：JSON 快照与 SQLite 同步合并到一个 stateIo.execute 原子任务。
        // 此前分两个 execute 入队，应用被杀时可能"JSON 已写、SQLite 没写"——
        // 下次启动 restoreOrSeed 从 SQLite 反向覆盖内存队列，JSON 的设置字段与
        // SQLite 的队列数据各取一边，双持久化互相打架。同线程串行执行即一致。
        runCatching {
            stateIo.execute {
                store.writeSnapshot(storeSnapshot)
                if (hasStateDb) persistStateDatabase()
            }
        }
    }

    private fun persistRuntimeState() {
        // 保留方法签名供外部调用（如 onDestroy 最后一次落盘），内部走同一原子通道
        runCatching {
            stateIo.execute {
                if (::stateDatabase.isInitialized) persistStateDatabase()
            }
        }
    }

    /** 在 stateIo 线程内执行 SQLite 同步（**调用方必须已持有 stateIo 串行化保证**）。 */
    private fun persistStateDatabase() {
        if (!::stateDatabase.isInitialized) return
        val playbackState = when {
            currentSong == null -> "idle"
            playbackPreparing && playWhenPrepared -> "preparing"
            !playWhenPrepared -> "paused"
            player?.isPlaying == true -> "playing"
            else -> "queued"
        }
        val queueSnapshot = orderQueue.orEmpty().take(200).map { Song.fromJson(it.toJson()) }
        val historySnapshot = sangHistory.take(200).map { Song.fromJson(it.toJson()) }
        val currentId = currentSong?.let(::stableId)
        val currentSnapshot = currentId?.let { id ->
            queueSnapshot.firstOrNull { stableId(it) == id }
        }
        stateDatabase.syncQueue(queueSnapshot, historySnapshot, currentSnapshot, playbackState)
    }

    private fun loadStateFromStore() {
        loopOne = store.loopOne
        autoNext = store.autoNext
        originalVocal = store.originalVocal
        vocalChannelMode = store.vocalChannelMode
        musicVolume = store.musicVolume
        micVolume = store.micVolume
        tone = store.tone.coerceIn(-5, 5)
        atmosphere = store.atmosphere
        singMode = store.singMode
        recordEnabled = store.recordEnabled
        scoreEnabled = store.scoreEnabled
        marqueeEnabled = store.marqueeEnabled
        marqueeText = store.marqueeText
        playWhileDownloading = store.playWhileDownloading
        clearDownloadsOnBoot = store.clearDownloadsOnBoot
        autoFullscreenSeconds = store.autoFullscreenSeconds
        showUsbSongs = store.showUsbSongs
        autoDeleteSongs = store.autoDeleteSongs
        reserveStorageGb = store.reserveStorageGb
        floatingButtonEnabled = store.floatingButtonEnabled
        songTitleSubtitleEnabled = store.songTitleSubtitleEnabled
        orderedSongOriginal = store.orderedSongOriginal
        orderedVolumeMode = store.orderedVolumeMode
        orderedVocalMode = store.orderedVocalMode
        voiceEngineEnabled = store.voiceEngineEnabled
        voiceEngineVolume = store.voiceEngineVolume
        lightMode = store.lightMode
        audioDelayMs = store.audioDelayMs
        screenBrightness = store.screenBrightness
        screenMode = store.screenMode
        doubleScreenEnabled = store.doubleScreenEnabled
        tableBroadcastEnabled = store.tableBroadcastEnabled
        tableBroadcastSeconds = store.tableBroadcastSeconds
        tableBroadcastText = store.tableBroadcastText
    }

    private fun dp(value: Int): Int {
        return (value * getResources().getDisplayMetrics().density + 0.5f).toInt()
    }

    private fun hasStoragePermission(): Boolean =
        Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestStorage() {
        if (Build.VERSION.SDK_INT >= 23
            && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf<String>(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ), 10
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String?>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            recreate()
        }
        if (requestCode == 11 && grantResults.size > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startRecording()
    }

    // ================= 边缘左滑返回（对标风火轮 EdgeSwipeHomeHelper） =================
    // 车机 NETA OS 不提供返回键/返回手势，边缘左滑是从子页面退回的唯一途径。
    // 判定规则与风火轮一致：左侧 8% 起滑、横向位移 >100px、横向大于纵向、抬手(ACTION_UP)才判定。
    // 关键：纯旁路观察，命中后也永不消费 MotionEvent，保证列表滚动/按钮点击/焦点导航完全不受影响。
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var swipeTracking = false

    /** 仅从屏幕最左侧 8% 区域起滑的手势有效。 */
    private val edgeSwipeStartRatio = 0.08f

    /** 横向滑动距离阈值(px)，短距离误触忽略。 */
    private val edgeSwipeMinDistancePx = 100f

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // 全局边缘左滑识别：旁路观察，不消费事件，原有触摸链路完全独立。
        try {
            observeEdgeSwipe(event)
        } catch (ignored: Exception) {
        }
        return super.dispatchTouchEvent(event)
    }

    private fun observeEdgeSwipe(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val edgeLimit = resources.displayMetrics.widthPixels * edgeSwipeStartRatio
                swipeDownX = event.rawX
                swipeDownY = event.rawY
                swipeTracking = swipeDownX <= edgeLimit
            }
            MotionEvent.ACTION_CANCEL -> swipeTracking = false
            MotionEvent.ACTION_UP -> {
                if (swipeTracking) {
                    swipeTracking = false
                    val deltaX = event.rawX - swipeDownX
                    val deltaY = abs(event.rawY - swipeDownY)
                    // 距离不足或纵向位移更大（属于滚动）→ 忽略
                    if (deltaX >= edgeSwipeMinDistancePx && deltaX > deltaY) {
                        performEdgeSwipeBack()
                    }
                }
            }
        }
    }

    /** 边缘左滑命中后的返回语义（与风火轮一致：一次即回首页，首页不退出 App）。 */
    private fun performEdgeSwipeBack() {
        // 全屏播放是临时浮层：先退出全屏
        if (isFullScreen) {
            exitFullScreenPlayer()
            return
        }
        // 非首页：回首页（navigateBackToHome 等价于风火轮的 setRoot 清栈）
        if (currentTabIndex != -1 || "首页" != currentPage) {
            navigateBackToHome()
            return
        }
        // 已在首页：消费手势但绝不 finish / 不退后台（沿用风火轮"不退出"策略，防车上误关）
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        focusRestoreSerial++
        val code = event.getKeyCode()
        if (isFullScreen) {
            val center = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
                code == KeyEvent.KEYCODE_NUMPAD_ENTER
            val direction = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
                code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
            val volumePanelVisible = fullScreenVolumePanel?.visibility == View.VISIBLE
            val volumePanelHasFocus = volumePanelVisible && fullScreenVolumePanel?.hasFocus() == true
            if (code == KeyEvent.KEYCODE_BACK && volumePanelVisible) {
                main.removeCallbacks(hideFullScreenVolumeIndicatorRunnable)
                fullScreenVolumePanel?.visibility = View.GONE
                fullScreenToneControl?.requestFocus()
                setFullScreenChromeVisible(true)
                return true
            }
            if (center && !fullScreenChromeVisible) {
                setFullScreenChromeVisible(true)
                return true
            }
            if (volumePanelVisible && (center || direction) && !volumePanelHasFocus &&
                fullScreenControls?.hasFocus() != true
            ) {
                setFullScreenChromeVisible(true)
                fullScreenVolumeMinusButton?.requestFocus()
                return true
            }
            if (center && fullScreenControls?.hasFocus() != true && !volumePanelHasFocus) {
                setFullScreenChromeVisible(true)
                fullScreenControls?.getChildAt(0)?.requestFocus()
                return true
            }
            if (direction && !fullScreenChromeVisible) {
                setFullScreenChromeVisible(true)
                return true
            }
            if ((center || direction) && fullScreenChromeVisible) {
                main.removeCallbacks(hideFullScreenChromeRunnable)
                main.postDelayed(hideFullScreenChromeRunnable, 5000L)
            }
            if (volumePanelHasFocus && code == KeyEvent.KEYCODE_DPAD_UP) {
                fullScreenToneControl?.requestFocus()
                return true
            }
            if (volumePanelHasFocus && code == KeyEvent.KEYCODE_DPAD_DOWN) {
                return true
            }
            if (volumePanelVisible && window.decorView.findFocus() === fullScreenToneControl &&
                code == KeyEvent.KEYCODE_DPAD_DOWN
            ) {
                fullScreenVolumeMinusButton?.requestFocus()
                return true
            }
        }
        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_SPACE) {
            togglePlay()
            return true
        }
        // 车控静音键：车机把"静音/静音取消"发成 MUTE / VOLUME_MUTE，原先落到系统音量逻辑导致无法暂停。
        // 车机没有返回键时静音键常被当播放/暂停用，这里直接映射到播放暂停。
        if (code == KeyEvent.KEYCODE_MUTE || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            togglePlay()
            return true
        }
        if (code == KeyEvent.KEYCODE_MEDIA_NEXT) {
            playNext()
            return true
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (isFullScreen) {
                exitFullScreenPlayer()
                return true
            }
            if (currentTabIndex != -1 || "首页" != currentPage) {
                navigateBack()
                return true
            }
            // 首页按返回：弹退出确认，不直接 finish（沿用"不退出"策略，防车上误触）
            showExitDialog()
            return true
        }
        val homeDirection = homeLayout?.visibility == View.VISIBLE && currentPage == "首页" &&
            (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
                code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)
        if (homeDirection) {
            val focused = window.decorView.findFocus()
            val reverseTarget = lastHomeFocusSource
            if (focused === lastHomeFocusTarget && code == lastHomeFocusReverseKey &&
                reverseTarget?.isAttachedToWindow == true && reverseTarget.isShown && reverseTarget.isEnabled
            ) {
                if (reverseTarget.requestFocus()) {
                    lastHomeFocusSource = focused
                    lastHomeFocusTarget = reverseTarget
                    lastHomeFocusReverseKey = oppositeDpadKey(code)
                    return true
                }
                clearHomeFocusTransition()
            }

            val handled = super.dispatchKeyEvent(event)
            main.post {
                val destination = window.decorView.findFocus()
                if (focused != null && destination != null && destination !== focused &&
                    homeLayout?.visibility == View.VISIBLE && currentPage == "首页"
                ) {
                    lastHomeFocusSource = focused
                    lastHomeFocusTarget = destination
                    lastHomeFocusReverseKey = oppositeDpadKey(code)
                }
            }
            return handled
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.decorView.post { prepareTvFocusableTree(window.decorView) }
    }

    private fun navigateBack() {
        val returnPoint = focusReturnStack.pollLast()
        if (returnPoint != null) {
            restoringFocusRoute = true
            try {
                returnPoint.restorePage()
            } finally {
                restoringFocusRoute = false
            }
            restoreFocusBookmark(returnPoint.focus, returnPoint.directFocus)
            return
        }
        when (browseMode) {
            "playlist_id", "playlist_id_search" -> returnToCategoryPlaylists()
            "settings_section" -> showSettingsPage()
            else -> showHomePage()
        }
    }

    private fun navigateBackToHome() {
        while (focusReturnStack.size > 1) focusReturnStack.pollLast()
        if (focusReturnStack.isNotEmpty()) navigateBack() else showHomePage()
    }

    private fun rememberReturnPointIfNeeded(targetPage: String) {
        if (restoringFocusRoute || targetPage == currentPage) return
        val focus = captureFocusBookmark(window.decorView.findFocus())
        val fromHome = homeLayout?.visibility == View.VISIBLE || currentPage == "首页"
        if (fromHome) {
            focusReturnStack.clear()
            focusReturnStack.addLast(FocusReturnPoint({ showHomePage() }, focus, window.decorView.findFocus()))
            return
        }
        val currentDepth = currentPage.count { it == '/' }
        val targetDepth = targetPage.count { it == '/' }
        if (targetDepth <= currentDepth) return

        val savedMode = browseMode
        val savedParam = browseParam
        val savedPage = browsePage
        val savedQuery = activeSearchQuery
        val savedCategory = currentCategories.getOrNull(currentCategoryIndex)
        val restorePage: () -> Unit = when (savedMode) {
            "singer_list" -> ({
                loadSingers()
                browsePage = savedPage
                savedCategory?.let { browseParam = it }
                activeSearchQuery = savedQuery
                loadSingerList(savedCategory ?: "全部", savedQuery)
            })
            "category_list" -> ({
                searchScope = "playlist"
                activeSearchQuery = savedQuery
                setSearchText(savedQuery)
                currentTabIndex = 2
                showSubPageShell("主页 / 分类")
                setupTabs()
                currentCategories.clear()
                currentCategoryIndex = -1
                updateCategories()
                browseMode = "category_list"
                browseParam = savedParam
                browsePage = savedPage
                loadCategoryPlaylistPage(savedQuery)
            })
            "settings" -> ({ showSettingsPage(false) })
            else -> ({ showHomePage() })
        }
        focusReturnStack.addLast(FocusReturnPoint(restorePage, focus, window.decorView.findFocus()))
    }

    private fun captureFocusBookmark(view: View?): FocusBookmark? {
        if (view == null) return null
        val resourceName = if (view.id != View.NO_ID && (view.id ushr 24) != 0) {
            runCatching { resources.getResourceName(view.id) }.getOrNull()
        } else null
        val marker = view.focusMarker()
        val text = (view as? TextView)?.text?.toString()?.takeIf { it.isNotBlank() }
        val groupText = if (view is ViewGroup) collectFocusText(view).takeIf { it.isNotBlank() } else null
        return FocusBookmark(resourceName, marker, text, groupText)
    }

    private fun collectFocusText(view: View): String {
        val values = ArrayList<String>(3)
        fun collect(node: View) {
            if (values.size >= 3) return
            if (node is TextView) {
                node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(values::add)
            } else if (node is ViewGroup) {
                for (index in 0 until node.childCount) collect(node.getChildAt(index))
            }
        }
        collect(view)
        return values.joinToString("|")
    }

    private fun restoreFocusBookmark(bookmark: FocusBookmark?, preferredView: View? = null) {
        if (bookmark == null && preferredView == null) return
        val serial = ++focusRestoreSerial
        val settingsList = songList
        val settingsAdapter = settingsList?.adapter as? SettingsListAdapter
        val settingsPosition = bookmark?.marker?.let { marker ->
            settingsAdapter?.positionForMarker(marker)
        }
        if (settingsList != null && settingsAdapter != null && settingsPosition != null) {
            settingsAdapter.requestFocusAt(settingsList, settingsPosition)
            return
        }
        var restored = false
        listOf(0L, 60L, 180L, 450L, 900L).forEach { delay ->
            main.postDelayed({
                if (restored || serial != focusRestoreSerial) return@postDelayed
                val target = preferredView?.takeIf { it.isAttachedToWindow && it.isShown && it.isEnabled && it.isFocusable }
                    ?: bookmark?.let { findFocusTarget(window.decorView, it) }
                if (target != null) {
                    restored = target.requestFocus() || target.requestFocusFromTouch()
                    if (restored) {
                        target.refreshDrawableState()
                        target.jumpDrawablesToCurrentState()
                        target.invalidate()
                    }
                }
            }, delay)
        }
    }

    private fun findFocusTarget(root: View, bookmark: FocusBookmark): View? {
        var textMatch: View? = null
        var groupMatch: View? = null
        fun visit(view: View): View? {
            if (view.visibility != View.VISIBLE || !view.isShown || !view.isEnabled) return null
            if (view.isFocusable) {
                val resourceName = if (view.id != View.NO_ID) {
                    runCatching { resources.getResourceName(view.id) }.getOrNull()
                } else null
                if (bookmark.resourceName != null && resourceName == bookmark.resourceName) return view
                if (bookmark.marker != null && view.focusMarker() == bookmark.marker) return view
                if (textMatch == null && bookmark.text != null &&
                    (view as? TextView)?.text?.toString() == bookmark.text
                ) textMatch = view
                if (groupMatch == null && bookmark.groupText != null && view is ViewGroup &&
                    collectFocusText(view) == bookmark.groupText
                ) groupMatch = view
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        return visit(root) ?: textMatch ?: groupMatch
    }

    private fun isDescendantOf(view: View, ancestor: View?): Boolean {
        if (ancestor == null) return false
        var current: View? = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    /** 标记「搜索输入框」，保留触摸聚焦以唤起车机系统软键盘，TV 焦点样式不触碰它（定义见 companion object）。 */
    private fun prepareTvFocusableTree(view: View) {
        // 两个搜索框(主布局 search_input 与原生点歌台 search)需保留触摸聚焦能力，
        // 聚焦时才能唤起车机系统软键盘；不能被 TV 焦点样式(TvFocusStyler)强制关闭 isFocusableInTouchMode。
        if (view.id == R.id.search_input || view.getTag() == SYSTEM_KEYBOARD_TAG) {
            view.isFocusable = true
            view.isFocusableInTouchMode = true
        } else if (view.isClickable && view.visibility == View.VISIBLE && view.isEnabled) {
            TvFocusStyler.install(view)
            if (view.contentDescription.isNullOrBlank()) {
                val spoken = when (view) {
                    is TextView -> view.text?.toString()?.trim().orEmpty()
                    is ViewGroup -> view.visibleTextLabel()
                    else -> ""
                }
                if (spoken.isNotEmpty()) view.contentDescription = spoken
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) prepareTvFocusableTree(view.getChildAt(index))
        }
    }

    private fun showFavorites() {
        setPage("收藏")
        content!!.removeAllViews()
        val panel = panel()
        content!!.addView(panel, LinearLayout.LayoutParams(-1, -1))
        panel.addView(sectionTitle("我的收藏"))
        songList = listView()
        songAdapter = tvAdapter()
        songList!!.setAdapter(songAdapter)
        songList!!.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: kotlin.Long ->
            handleSong(
                visibleSongs.get(pos)
            )
        })
        songList!!.setOnItemLongClickListener(OnItemLongClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: kotlin.Long ->
            showSongActions(visibleSongs.get(pos))
            true
        })
        panel.addView(songList, LinearLayout.LayoutParams(-1, 0, 1f))
        visibleSongs.clear()
        for (song in library.allSongs()) {
            if (store.isFavorite(song)) visibleSongs.add(song)
        }
        renderSongs()
    }

    private fun showPlaylists() {
        setPage("歌单")
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(dp(300), -1))
        left.addView(sectionTitle("我的歌单"))
        for (playlist in store.playlists) {
            left.addView(
                button(
                    playlist + "  " + store.songsInPlaylist(playlist).size,
                    View.OnClickListener { v: View? -> showPlaylistSongs(playlist) })
            )
        }
        left.addView(
            button(
                "新建歌单",
                View.OnClickListener { v: View? -> showCreatePlaylistDialog() })
        )
        val right = panel()
        content!!.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        right.addView(sectionTitle("歌单歌曲"))
        right.addView(
            label(
                "选择左侧歌单查看歌曲；在点歌、排行、歌星等页面长按歌曲可加入或移出歌单。",
                20,
                Color.LTGRAY
            )
        )
    }

    private fun showPlaylistSongs(playlist: String) {
        setPage("歌单")
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(dp(300), -1))
        left.addView(sectionTitle("我的歌单"))
        for (item in store.playlists) {
            left.addView(
                button(
                    (if (item == playlist) "> " else "") + item + "  " + store.songsInPlaylist(item).size,
                    View.OnClickListener { v: View? -> showPlaylistSongs(item) })
            )
        }
        left.addView(
            button(
                "新建歌单",
                View.OnClickListener { v: View? -> showCreatePlaylistDialog() })
        )

        val right = panel()
        content!!.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        right.addView(sectionTitle(playlist))
        songList = listView()
        songAdapter = tvAdapter()
        songList!!.setAdapter(songAdapter)
        songList!!.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: kotlin.Long ->
            handleSong(
                visibleSongs.get(pos)
            )
        })
        songList!!.setOnItemLongClickListener(OnItemLongClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: kotlin.Long ->
            showSongActions(visibleSongs.get(pos))
            true
        })
        right.addView(songList, LinearLayout.LayoutParams(-1, 0, 1f))
        visibleSongs.clear()
        for (song in library.allSongs()) {
            if (store.isInPlaylist(playlist, song)) visibleSongs.add(song)
        }
        renderSongs()
    }

    private fun showPlaylistPicker(song: Song) {
        val items: Array<String> = Array(store.playlists.size) { "" }
        for (i in store.playlists.indices) {
            val playlist = store.playlists.get(i)
            items[i] = (if (store.isInPlaylist(playlist, song)) "移出 " else "加入 ") + playlist
        }
        AlertDialog.Builder(this)
            .setTitle("选择歌单")
            .setItems(
                items,
                DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                    val playlist = store.playlists.get(which)
                    val added = store.togglePlaylistSong(playlist, song)
                    saveState()
                    toast((if (added) "已加入：" else "已移出：") + playlist)
                    if ("歌单" == currentPage) showPlaylistSongs(playlist)
                })
            .create().showForTv()
    }

    companion object {
        /** 播放页话筒接入状态标签的 view id（动态创建，需固定 id 以便重复刷新时复用）。 */
        private const val MIC_STATUS_ID = 0x7f0c0001

        /**
         * 扫码弹窗自动关闭时长（毫秒）。
         *
         * 二维码是给手机扫的，车机驾驶员自己用不上；弹窗一直占着 1920×1080 的屏
         * 反而挡界面（还容易让人以为卡死）。10 秒足够扫完，超时自动收起 ——
         * 点歌/话筒服务在后台照常工作，手机该点歌开麦一点不受影响。
         */
        private const val QR_AUTO_CLOSE_MS = 10000L

        // ===== 主题色(对标原版风火轮 配色) =====
        /** 主背景:极深蓝黑(原版 #080C12 系)  */
        private val BG = Color.rgb(8, 12, 18)

        /** 面板背景:深蓝灰(原版卡片底色)  */
        private val PANEL = Color.rgb(22, 31, 42)

        /** 次级面板:更深(列表区背景)  */
        private val PANEL_2 = Color.rgb(15, 22, 31)

        /** 焦点色:青绿色(原版选中态)  */
        private val FOCUS = Color.rgb(39, 204, 164)

        /** 金色:标题与高亮(原版 color_yellow_3 #FFE363 系)  */
        private val GOLD = Color.rgb(245, 190, 89)

        /** 强调红:选中态/激活态(原版 color_red #FD3359)  */
        private val ACCENT_RED = Color.rgb(253, 51, 89)

        /** 文本白:主文本色(原版 color_white_3 #F1F1F1)  */
        private val TEXT_WHITE = Color.rgb(241, 241, 241)

        /** 次要文本:70% 透明白(原版 color_white_3_70)  */
        private val TEXT_DIM = Color.argb(180, 241, 241, 241)

        /** 导航选中背景:半透明红  */
        private val NAV_SELECTED = Color.argb(50, 253, 51, 89)

        /** 分割线颜色  */
        private val DIVIDER = Color.argb(80, 241, 241, 241)
        private const val PREFS = "ktv"
        private const val KEY_CATALOG_URL = "catalog_url"
        private const val KEY_SHOW_MOBILE_QR = "show_mobile_qr"

        /** 搜索输入框标记：保留触摸聚焦以唤起车机系统软键盘，TV 焦点样式跳过它。 */
        private const val SYSTEM_KEYBOARD_TAG = "ktv_sys_kb"

        private const val TAG = "MainActivity"
    }
}
