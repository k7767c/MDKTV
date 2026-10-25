package com.fenghuolun.ktv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher

/**
 * 在隐藏 WebView 中运行可热更新的歌曲接口脚本。
 *
 * JS 决定接口地址、参数、签名和响应解析；Android 只提供 HTTP 与 RSA 这两个
 * WebView 中不可靠的底层能力。这样接口变化时只需更新 Gitee 上的 ktv_api.js。
 */
class KtvJsBridge(context: Context) {
    companion object {
        private const val TAG = "KtvJsBridge"
        private const val JS_FILE = "mobile/ktv_api.js"
        private const val CACHE_FILE = "ktv_api_cache.js"
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val JS_REQUEST_TIMEOUT_MS = 180_000
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbacks = ConcurrentHashMap<String, (String?) -> Unit>()

    @Volatile private var webView: WebView? = null
    @Volatile private var ready = false
    @Volatile private var destroyed = false
    private var loadedFromCache = false
    private var assetRetryAttempted = false

    /** 必须最终在主线程创建 WebView；可从任意线程调用。 */
    fun init(callback: (Boolean) -> Unit) {
        mainHandler.post {
            if (destroyed) {
                callback(false)
                return@post
            }
            try {
                // 歌源主机已修复为本包内置版本(zk.ktv1.top:6677)，强制使用内置脚本并停用远端热更新，
                // 避免上游仓库的失效脚本(返回 demo 占位)覆盖修复。同时清理可能存在的旧缓存。
                loadedFromCache = false
                File(appContext.filesDir, CACHE_FILE).delete()
                createWebView(readAssetScript(), callback)
            } catch (error: Throwable) {
                Log.e(TAG, "WebView init failed", error)
                callback(false)
            }
        }
    }

    private fun createWebView(script: String, callback: (Boolean) -> Unit) {
        webView?.destroy()
        ready = false
        webView = WebView(appContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(NativeBridge(), "android")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript("typeof window.KtvApi === 'function'") { result ->
                        if (result == "true") {
                            ready = true
                            Log.i(TAG, "JS runtime ready (${if (loadedFromCache) "cache" else "asset"})")
                            callback(true)
                        } else if (loadedFromCache && !assetRetryAttempted) {
                            Log.w(TAG, "Cached JS failed to load; falling back to packaged asset")
                            assetRetryAttempted = true
                            loadedFromCache = false
                            createWebView(readAssetScript(), callback)
                        } else {
                            Log.e(TAG, "KtvApi was not exported by JS")
                            callback(false)
                        }
                    }
                }
            }
            loadDataWithBaseURL("https://localhost/", wrapHtml(script), "text/html", "UTF-8", null)
        }
    }

    fun getSongUrl(
        musicNo: String,
        resolution: String,
        h265: Boolean,
        callback: (String?) -> Unit,
    ) {
        if (!ready || destroyed) {
            callback(null)
            return
        }
        val requestId = UUID.randomUUID().toString()
        callbacks[requestId] = callback
        val script = """
            (function() {
              var requestId = ${JSONObject.quote(requestId)};
              var completed = false;
              function finish(url) {
                if (completed) return;
                completed = true;
                clearTimeout(timer);
                android.onUrl(requestId, url || '');
              }
              var timer = setTimeout(function() { finish(''); }, $JS_REQUEST_TIMEOUT_MS);
              Promise.resolve().then(async function() {
                if (!window.api) window.api = new window.KtvApi({debug: true});
                if (!window._apiInitPromise) {
                  window._apiInitPromise = window.api.init().then(function(ok) {
                    if (!ok) throw new Error('API initialization failed');
                    return true;
                  }).catch(function(error) {
                    window._apiInitPromise = null;
                    throw error;
                  });
                }
                await window._apiInitPromise;
                return window.api.getSongUrl(
                  ${JSONObject.quote(musicNo)}, ${JSONObject.quote(resolution)}, ${if (h265) "true" else "false"}
                );
              }).then(finish).catch(function(error) {
                android.log('getSongUrl failed: ' + (error && error.message ? error.message : error));
                finish('');
              });
            })();
        """.trimIndent()
        mainHandler.post {
            if (!ready) complete(requestId, null)
            else webView?.evaluateJavascript(script, null) ?: complete(requestId, null)
        }
    }

    // JS isDemoUrl 已做第一层正则过滤; 这里再做域名白名单兜底, 防止 demo URL 绕过
    private val trustedHostSuffixes = listOf(
        "ktvsky.com", "origjoy.com", "cherryonline.cn", "ac16.vip",
        "kk456.top", "ktvdaren.com", "j-make.cn",
        // 内置歌源（zk.ktv1.top:6677，v1.2.x 起强制使用，见 init() 注释）
        "ktv1.top",
    )
    // 已知 demo 域名模式 (Cloudflare R2 测试桶、IP 地址等)
    private val demoHostPatterns = listOf(
        Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"),  // 纯 IP
        Regex("^pub-[a-z0-9]+\\.r2\\.dev$"),                      // R2 demo bucket
    )

    private fun isTrustedDownloadUrl(url: String?): Boolean {
        if (url == null) return false
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return false
        if (demoHostPatterns.any { it.matches(host) }) {
            Log.w(TAG, "Rejected demo URL host: $host")
            return false
        }
        val trusted = trustedHostSuffixes.any { host.endsWith(it) }
        if (!trusted) Log.w(TAG, "Untrusted URL host: $host")
        return trusted
    }

    private fun complete(requestId: String, url: String?) {
        callbacks.remove(requestId)?.invoke(url?.takeIf { isTrustedDownloadUrl(it) })
    }

    private fun readAssetScript(): String =
        appContext.assets.open(JS_FILE).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun isValidScript(file: File): Boolean =
        runCatching {
            file.isFile && file.length() in 1_001..500_000 &&
                file.readText(Charsets.UTF_8).let {
                    it.contains("KtvApi") && it.contains("KTV_BRIDGE_API: 3")
                }
        }.getOrDefault(false)

    /** 在线热更新脚本已停用(远程链接已移除), 不再向 Gitee 拉取; 仅用本包内置脚本。 */
    private fun updateCache(cacheFile: File) {
        // 断掉远端 gitee 脚本热更新, 强制使用内置 ktv_api.js。
        return
    }

    private fun wrapHtml(script: String): String {
        val escaped = script.replace("</script", "<\\/script", ignoreCase = true)
        return "<!doctype html><html><head><meta charset=\"utf-8\"></head><body><script>$escaped</script></body></html>"
    }

    inner class NativeBridge {
        @JavascriptInterface
        fun log(message: String) = Log.d(TAG, message)

        @JavascriptInterface
        fun onUrl(requestId: String, url: String) {
            Log.d(TAG, "URL result ${requestId.take(8)}: ${url.take(100)}")
            complete(requestId, url)
        }

        @JavascriptInterface
        fun rsaEncryptPkcs1(publicKeyBase64: String, plaintext: String): String =
            runCatching {
                val keyBytes = Base64.decode(publicKeyBase64.replace("\\s".toRegex(), ""), Base64.DEFAULT)
                val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
                val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
                cipher.init(Cipher.ENCRYPT_MODE, publicKey)
                Base64.encodeToString(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            }.onFailure { Log.e(TAG, "RSA encryption failed", it) }.getOrDefault("")

        @JavascriptInterface
        fun httpGet(url: String, headersJson: String, timeoutMs: Int): String =
            request("GET", url, null, headersJson, timeoutMs)

        @JavascriptInterface
        fun httpPost(url: String, body: String, headersJson: String, timeoutMs: Int): String =
            request("POST", url, body, headersJson, timeoutMs)

        private fun request(method: String, url: String, body: String?, headersJson: String, timeoutMs: Int): String {
            var connection: HttpURLConnection? = null
            return try {
                require(url.startsWith("http://") || url.startsWith("https://")) { "Unsupported URL" }
                // SSRF 防护：host 白名单。ktv_api.js 的所有合法请求都落在 trustedHostSuffixes 内
                // （zk.ktv1.top:6677 歌源、cherryonline.cn 曲库同步、ac16.vip/kk456.top 等 CDN）。
                // 白名单外的 host 直接拒掉返回 status=0，杜绝 JS 任意外发/读内网。
                if (!isTrustedDownloadUrl(url)) {
                    Log.w(TAG, "$method rejected: untrusted host in $url")
                    return JSONObject().put("status", 0).put("body", "")
                        .put("error", "untrusted host").toString()
                }
                connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = method
                connection.connectTimeout = timeoutMs.coerceIn(1_000, 30_000)
                connection.readTimeout = timeoutMs.coerceIn(1_000, 30_000)
                connection.instanceFollowRedirects = true
                val headers = JSONObject(headersJson.ifBlank { "{}" })
                headers.keys().forEach { name -> connection.setRequestProperty(name, headers.optString(name)) }
                if (body != null) {
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val status = connection.responseCode
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                JSONObject().put("status", status).put("body", responseBody).toString()
            } catch (error: Throwable) {
                Log.w(TAG, "$method request failed: ${error.message}")
                JSONObject().put("status", 0).put("body", "").put("error", error.message.orEmpty()).toString()
            } finally {
                connection?.disconnect()
            }
        }
    }

    fun destroy() {
        destroyed = true
        ready = false
        callbacks.keys.toList().forEach { complete(it, null) }
        mainHandler.post {
            webView?.removeJavascriptInterface("android")
            webView?.destroy()
            webView = null
        }
    }
}
