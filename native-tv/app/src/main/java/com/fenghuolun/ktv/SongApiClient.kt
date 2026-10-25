package com.fenghuolun.ktv

import android.content.Context
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 歌曲 API 客户端 —— JS 热更新桥接
 * =================================
 * - 通过 KtvJsBridge 调用 Gitee 上的 JS 脚本
 * - API 变更时只需更新 Gitee JS 文件，App 无需发版
 * - 首次播放时实时获取 URL，不存在过期问题
 *
 * 这是进程级常驻单例，内部持有 KtvJsBridge（含一个 WebView）。
 * init 必须收 applicationContext：MainActivity 传的是 this，单例若持有 Activity 会泄漏。
 */
object SongApiClient {
    private const val TAG = "SongApiClient"

    private var bridge: KtvJsBridge? = null
    @Volatile private var ready = false
    /** 记住 applicationContext，供 release() 后自动重建（内存压力释放 WebView 再恢复）。 */
    @Volatile private var appContextRef: android.content.Context? = null

    @JvmStatic
    fun init(context: Context) {
        if (bridge != null) return
        val appContext = context.applicationContext
        appContextRef = appContext
        bridge = KtvJsBridge(appContext)
        bridge?.init { ok ->
            ready = ok
            Log.i(TAG, "JS Bridge init: $ok")
        }
    }

    /** 兼容旧接口 */
    @JvmStatic
    fun init(sn: String, mac: String) {
        // JS 脚本自己生成 MAC/SN
    }

    /**
     * 释放隐藏 WebView 与 JS 引擎。
     *
     * 单例常驻一个 WebView 会常驻一整套 JS/渲染上下文；AAOS 车机内存紧张时 LMK 会优先杀我们，
     * 表现为"第一次点歌要等很久或失败"。应在内存压力(onLowMemory/onTrimMemory)时释放，
     * 下次调用 getSongDownloadUrl 前 bridge 为 null 会自动重建。
     */
    @JvmStatic
    @Synchronized
    fun release() {
        ready = false
        runCatching { bridge?.destroy() }
        bridge = null
        Log.i(TAG, "bridge released")
    }

    /** 当前是否已持有桥（供内存压力判断/测试）。 */
    @JvmStatic
    fun hasBridge(): Boolean = bridge != null

    @JvmStatic
    fun getAuthToken(): String = ""

    @JvmStatic
    fun getSongDownloadUrl(musicno: String?): String? =
        musicno?.takeIf(String::isNotBlank)?.let { getSongDownloadUrl(it, "720", false) }

    @JvmStatic
    fun getSongDownloadUrl(musicno: String, resolution: String, h265: Boolean): String? {
        // 主线程同步等待 WebView 回调会死锁；当前播放/下载流程会在 IO 线程调用这里。
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "Synchronous JS request on main thread; using DB fallback")
            return fetchFromDb(musicno)
        }
        // 被 release() 释放过（内存压力）→ 先自动重建，否则下面 bridge==null 会白等 3 秒再走 DB 兜底
        if (bridge == null) appContextRef?.let { init(it) }
        // 等 Bridge 就绪 (最多 3 秒)
        var waited = 0
        while (!ready && waited < 30) {
            try { Thread.sleep(100) } catch (_: Exception) {}
            waited++
        }
        if (!ready || bridge == null) {
            Log.w(TAG, "Bridge not ready after ${waited*100}ms, DB fallback")
            return fetchFromDb(musicno)
        }
        val result = AtomicReference<String?>()
        val latch = CountDownLatch(1)
        bridge?.getSongUrl(musicno, resolution, h265) { url -> result.set(url); latch.countDown() }        // JS 需要覆盖主/备节点和最多 3 次设备重试；略长于桥内 180 秒总超时。
        try {
            latch.await(185, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return result.get() ?: fetchFromDb(musicno)
    }

    /** DB cloud_url fallback — 必须通过 demo 检测才能使用 */
    private fun fetchFromDb(musicno: String): String? {
        try {
            val db = MuseDatabase()
            if (db.open()) {
                val url = db.getCloudUrl(musicno)
                db.close()
                // DB 中的 cloud_url 可能也是 demo 地址, 做 URL 基本校验
                if (url != null && !isLikelyDemoUrl(url)) return url
            }
        } catch (_: Exception) {}
        return null
    }

    /** 快速判断是否为 demo URL (不含 JS 的完整逻辑, 仅做硬特征兜底) */
    private fun isLikelyDemoUrl(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return true
        // 纯 IP 地址 → 高概率 demo
        if (host.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"))) return true
        // Cloudflare R2 demo bucket (pub-*.r2.dev)
        if (host.matches(Regex("^pub-[a-z0-9]+\\.r2\\.dev$"))) return true
        return false
    }

    @JvmStatic
    fun clearTokenCache() {}
}
