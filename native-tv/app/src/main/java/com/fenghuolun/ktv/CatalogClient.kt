package com.fenghuolun.ktv

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class CatalogClient {
    /**
     * 曲库源响应体上限 8MB。
     *
     * 车机内存有限：org.json 解析时对象树膨胀约为文本的 5-10 倍，一个几十 MB 的
     * catalog.json 能把默认 Dalvik 堆直接吃穿（我们已去掉 largeHeap，更得防着）。
     * 8MB JSON ≈ 2-4 万首歌，远超实际需要，超了明确报错而不是硬解析。
     */
    private val maxBodyBytes = 8L * 1024 * 1024

    fun fetch(url: String): JSONArray {
        val body = (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 10_000
            readTimeout = 15_000
            requestMethod = "GET"
            try {
                check(responseCode in 200..299) { "HTTP $responseCode" }
                inputStream.buffered().reader(Charsets.UTF_8).use { reader ->
                    val buffer = StringBuilder()
                    val chunk = CharArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val n = reader.read(chunk)
                        if (n < 0) break
                        total += n
                        check(total <= maxBodyBytes) { "曲库源超过 8MB，已中止（车机内存有限）" }
                        buffer.append(chunk, 0, n)
                    }
                    buffer.toString().trim()
                }
            } finally {
                disconnect()
            }
        }
        if (body.startsWith("[")) return JSONArray(body)
        val root = JSONObject(body)
        return root.optJSONArray("songs") ?: root.getJSONArray("data")
    }
}
