package com.fenghuolun.ktv

import android.content.Context
import java.io.BufferedInputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 仅供同一局域网的手机点歌用的轻量 HTTP 服务（无第三方依赖）。
 *
 * 注意：构造参数在内部转成 applicationContext —— MainActivity 传的是 this，
 * 若直接持有 Activity，配合 onDestroy 的自续定时任务会形成泄漏链。
 */
class LocalRemoteServer(context: Context) {
    private val context: Context = context.applicationContext
    data class ApiResponse(
        val status: Int = 200,
        val body: String = "",
        val contentType: String = "application/json; charset=utf-8",
        val bytes: ByteArray? = null
    )

    interface Callback {
        fun statusJson(): String
        fun catalogJson(): String
        fun queueJson(): String
        fun rankJson(): String
        fun filterJson(type: String?, value: String?): String
        fun favoritesJson(): String
        fun search(query: String?): String
        fun order(query: String?): String
        fun command(action: String?, params: Map<String, String>): String
        fun handle(method: String, path: String, query: Map<String, String>, body: String): ApiResponse =
            ApiResponse(404, "{\"ok\":false,\"code\":\"NOT_FOUND\",\"error\":\"接口不存在\"}")
    }

    private var pool = Executors.newCachedThreadPool()
    @Volatile private var running = false
    @Volatile private var boundAddress: String? = null
    @Volatile private var boundPort = -1
    @Volatile private var lastError: String? = null
    private var server: ServerSocket? = null
    private var callback: Callback? = null

    @Synchronized
    fun start(address: String?, callback: Callback): Boolean {
        if (running && address == boundAddress) return true
        stop()
        this.callback = callback
        if (!isPrivateIpv4(address)) {
            lastError = "请先连接 Wi-Fi 或有线局域网"
            return false
        }
        if (pool.isShutdown) pool = Executors.newCachedThreadPool()
        var candidate: ServerSocket? = null
        for (port in 8765..8775) {
            try {
                candidate = ServerSocket(port, 32)
                boundPort = port
                break
            } catch (_: Exception) {
            }
        }
        if (candidate == null) {
            lastError = "端口 8765–8775 均被占用"
            return false
        }
        server = candidate
        boundAddress = address
        lastError = null
        running = true
        pool.execute {
            try {
                while (running) {
                    val socket = server?.accept() ?: break
                    pool.execute { handle(socket) }
                }
            } catch (e: Exception) {
                if (running) lastError = e.message ?: "本地服务异常"
            } finally {
                running = false
            }
        }
        return true
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        boundPort = -1
        boundAddress = null
        pool.shutdownNow()
    }

    fun isRunning(): Boolean = running
    fun port(): Int = boundPort
    fun address(): String? = boundAddress
    fun error(): String? = lastError
    fun url(): String? = if (running && boundAddress != null && boundPort > 0) {
        "http://$boundAddress:$boundPort/mobile"
    } else null

    /** 一行式状态（日志/诊断面板用）。 */
    fun statusLine(): String =
        "remote running=$running addr=$boundAddress port=$boundPort" + (lastError?.let { " error=$it" } ?: "")

    private fun handle(socket: Socket) {
        runCatching {
            socket.soTimeout = 5000
            socket.use { client ->
                val remote = client.inetAddress
                if (!isPrivateAddress(remote)) {
                    write(client, ApiResponse(403, error("FORBIDDEN", "仅允许局域网设备访问")))
                    return
                }
                val input = BufferedInputStream(client.getInputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) {
                    write(client, ApiResponse(400, error("BAD_REQUEST", "请求格式错误")))
                    return
                }
                val method = parts[0].uppercase(Locale.US)
                val target = parts[1]
                val headers = linkedMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).lowercase(Locale.US)] = line.substring(colon + 1).trim()
                }
                if (!validHost(headers["host"]) || !validOrigin(headers["origin"])) {
                    write(client, ApiResponse(403, error("BAD_ORIGIN", "请求来源无效")))
                    return
                }
                val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 65536) ?: 0
                val bodyBytes = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val count = input.read(bodyBytes, offset, length - offset)
                    if (count < 0) break
                    offset += count
                }
                val path = target.substringBefore('?')
                val query = parseQuery(target.substringAfter('?', ""))
                val body = String(bodyBytes, 0, offset, Charsets.UTF_8)
                // P0-1：API token 鉴权。点歌服务 /api/v1/* 要求请求带 token（query ?token= 或 X-Ktv-Token 头）。
                // 旧版手机端不带 token → 401 UNAUTHORIZED；新版手机从页面注入 token。
                if (path.startsWith("/api/v1/")) {
                    val expected = apiTokenProvider?.invoke()
                    val provided = query["token"] ?: headers["x-ktv-token"]
                    if (expected != null && expected.isNotEmpty() && provided != expected) {
                        // 401 响应里回传当前 token：手机页面若被浏览器缓存成旧 HTML（token 过期），
                        // 拿到新 token 后可自愈重试一次，不至于整页报废。token 本身只在局域网内传输。
                        val body = "{\"ok\":false,\"code\":\"UNAUTHORIZED\",\"error\":\"token 无效或过期\",\"token\":\"$expected\"}"
                        write(client, ApiResponse(401, body))
                        return
                    }
                }
                val response = when {
                    path == "/" || path == "/mobile" || path == "/mobile/" -> mobileIndex()
                    path == "/mobile/app.css" -> asset("mobile/app.css", "text/css; charset=utf-8")
                    path == "/mobile/logo.css" -> asset("mobile/logo.css", "text/css; charset=utf-8")
                    path == "/mobile/app.js" -> asset("mobile/app.js", "application/javascript; charset=utf-8")
                    path == "/res/drawable-nodpi/ktv_logo.png" -> logoAsset()
                    path.startsWith("/api/v1/") -> callback?.handle(method, path, query, body)
                        ?: ApiResponse(503, error("NOT_READY", "服务尚未准备完成"))
                    else -> ApiResponse(404, error("NOT_FOUND", "接口不存在"))
                }
                write(client, response)
            }
        }
    }

    private fun asset(name: String, type: String): ApiResponse = try {
        val text = context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
        ApiResponse(200, text, type)
    } catch (_: Exception) {
        ApiResponse(404, error("ASSET_NOT_FOUND", "页面资源缺失"))
    }

    /**
     * 点歌首页：注入话筒服务地址后返回。
     *
     * 点歌与话筒跑在**两个端口**（点歌 8765-8775 / 话筒 8780-8799，区间刻意错开），手机页面本身是从点歌端口打开的，
     * 所以话筒地址必须由车机注入（window.__MIC_BASE__），否则页面里的 fetch
     * 会把音频打到点歌服务上 —— 这是"扫一次码两样都能用"的关键一环。
     */
    private fun mobileIndex(): ApiResponse = try {
        val html = context.assets.open("mobile/index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val micBase = micBaseProvider?.invoke() ?: ""
        // P0-1：注入 API token，手机端所有 /api/v1/* 与 /mic/* 请求都带它（X-Ktv-Token 头或 ?token=）。
        val apiToken = apiTokenProvider?.invoke() ?: ""
        val tokenScript = if (apiToken.isNotEmpty()) {
            "<script>window.__KTV_TOKEN__=\"$apiToken\";</script>"
        } else ""
        val injected = if (micBase.isBlank() && apiToken.isEmpty()) {
            html
        } else {
            html.replace(
                "<script src=\"/mobile/app.js\"></script>",
                (tokenScript + "\n" + if (micBase.isNotBlank()) "<script>window.__MIC_BASE__=\"$micBase\";</script>" else "").let { scripts ->
                    if (scripts.isEmpty()) "<script src=\"/mobile/app.js\"></script>" else "$scripts\n  <script src=\"/mobile/app.js\"></script>"
                }
            )
        }
        ApiResponse(200, injected, "text/html; charset=utf-8")
    } catch (_: Exception) {
        ApiResponse(404, error("ASSET_NOT_FOUND", "页面资源缺失"))
    }

    /** 由 MainActivity 注入话筒服务基址（如 http://192.168.43.1:8780）。 */
    var micBaseProvider: (() -> String)? = null

    /** P0-1：由 MainActivity 注入 API token 提供器（返回 MicStreamServer.apiToken）。 */
    var apiTokenProvider: (() -> String?)? = null

    private fun logoAsset(): ApiResponse = try {
        val bytes = context.resources.openRawResource(R.drawable.ktv_logo).use { it.readBytes() }
        ApiResponse(200, contentType = "image/png", bytes = bytes)
    } catch (_: Exception) {
        ApiResponse(404, error("ASSET_NOT_FOUND", "Logo 资源缺失"))
    }

    private fun validHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val expected = boundAddress ?: return false
        val name = host.substringBefore(':')
        return name == expected || isPrivateIpv4(name)
    }

    private fun validOrigin(origin: String?): Boolean {
        if (origin.isNullOrBlank() || origin == "null") return true
        return runCatching {
            val uri = URI(origin)
            uri.scheme == "http" && uri.port == boundPort && isPrivateIpv4(uri.host)
        }.getOrDefault(false)
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>()
        while (bytes.size < 8192) {
            val b = input.read()
            if (b < 0) return if (bytes.isEmpty()) null else bytes.toByteArray().toString(Charsets.UTF_8)
            if (b == '\n'.code) break
            if (b != '\r'.code) bytes.add(b.toByte())
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    private fun parseQuery(query: String): Map<String, String> = buildMap {
        if (query.isBlank()) return@buildMap
        query.split('&').forEach { part ->
            val key = URLDecoder.decode(part.substringBefore('='), "UTF-8")
            val value = URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            put(key, value)
        }
    }

    private fun write(socket: Socket, response: ApiResponse) {
        val bytes = response.bytes ?: response.body.toByteArray(Charsets.UTF_8)
        val reason = when (response.status) {
            200 -> "OK"; 201 -> "Created"; 204 -> "No Content"; 400 -> "Bad Request"
            403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"; else -> "Error"
        }
        val output = socket.getOutputStream()
        output.write(("HTTP/1.1 ${response.status} $reason\r\n" +
            "Content-Type: ${response.contentType}\r\n" +
            "Cache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Content-Security-Policy: default-src 'self'; style-src 'self'; script-src 'self'; connect-src 'self'\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
        output.write(bytes)
        output.flush()
    }

    companion object {
        fun isPrivateIpv4(value: String?): Boolean = runCatching {
            val address = InetAddress.getByName(value)
            address is Inet4Address && isPrivateAddress(address)
        }.getOrDefault(false)

        /**
         * 是否属于"可访问的内网地址"。
         * 车机热点的手机会拿到自己的内网 IP（多数车机是 192.168.43.x/192.168.x.x，但部分厂商用
         * 10.x 或 172.16-31.x），只要不是公网回环/组播就应放行，否则手机明明连上了车机热点
         * 却被判 403「仅允许局域网设备访问」，这是扫码点歌最常见的隐形失败原因。
         */
        private fun isPrivateAddress(address: InetAddress): Boolean {
            if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
            if (address is Inet6Address) {
                val raw = address.address ?: return true
                // fc00::/7 唯一本地地址(相当于 IPv6 的私有段)
                if ((raw[0].toInt() and 0xfe) == 0xfc) return true
                // fe80::/10 链路本地
                if ((raw[0].toInt() and 0xff) == 0xfe && (raw[1].toInt() and 0xc0) == 0x80) return true
                // NAT64 / IPv4-mapped：末 4 字节是内嵌 IPv4，取出再判一次
                val tail = (raw[12].toInt() and 0xff) or (raw[13].toInt() and 0xff shl 8) or
                    (raw[14].toInt() and 0xff shl 16) or (raw[15].toInt() and 0xff shl 24)
                if (tail != 0) {
                    return isPrivateAddress(
                        InetAddress.getByAddress(
                            byteArrayOf(raw[12], raw[13], raw[14], raw[15])
                        )
                    )
                }
                return false
            }
            val b = address.address
            if (b == null || b.size != 4) return false
            val o0 = b[0].toInt() and 0xff
            val o1 = b[1].toInt() and 0xff
            // 10.0.0.0/8
            if (o0 == 10) return true
            // 172.16.0.0/12
            if (o0 == 172 && o1 in 16..31) return true
            // 192.168.0.0/16
            if (o0 == 192 && o1 == 168) return true
            // 100.64.0.0/10（运营商级 NAT / 热点常用）
            if (o0 == 100 && (o1 and 0xc0) == 64) return true
            // 169.254.0.0/16 链路本地
            if (o0 == 169 && o1 == 254) return true
            return false
        }

        private fun error(code: String, message: String): String =
            "{\"ok\":false,\"code\":\"$code\",\"error\":\"$message\"}"
    }
}
