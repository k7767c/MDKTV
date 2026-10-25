package com.fenghuolun.ktv

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher

/**
 * 官方曲库同步：login → cookie → sqlite/sync → 下载 gz → 解压安装。
 *
 * 这是原版「时光KTV / 麦动」的曲库分发方式，取代此前临时使用的分片分发方案。
 * 授权门控发生在服务端(未授权设备 sync 返回空 files)，本地只负责完整复刻请求协议。
 */
object MuseDbSync {
    private const val TAG = "MuseDbSync"
    private const val LOGIN_URL = "https://mconn.cherryonline.cn/mls-api/v1/login"
    private const val SYNC_URL = "https://mconn.cherryonline.cn/mls-api/v1/sqlite/sync"
    private const val RSA_PUBLIC_KEY =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAucL0oFErd7REM6TSNa3EZdN1YaOs4J1eCybLPyoQ9ru3q1HU67agC9FzhrCG/RvAQUya5iPmQ8Caed05vqcCVcyJChmkSOGQ7DVShe2rGuTMNlpoRV6UzfcraaVS++7m2K/+kSZJ8OAhhhVuqPruMjsFYpdtstAwvyZT28b+eENwzpp9UHqsooZc7FZ0H8kTbs6XMkw4nIWo+4HoPAhNLEY+xdHvwY6drF/3WDTvsaoMrs73TVQCEEHzZNIz2H/is9VLMnIyOfnfcJi9br78Fj2xHzxu3sAySBOTVLmUMxqYh/g1ox5OXGcW93HJkQLkBi42tFAEkWYlYyl93+jbbQIDAQAB"
    private const val MUSIC_FILE_TYPE = 214
    private const val CHANNEL = "common"
    private const val MODE = "ott"
    private const val IP = "172.16.32.15"

    private data class DbFile(val url: String, val md5: String, val version: String)

    private val target: File get() = MuseDatabase.defaultDbFile()

    /** 并行下载线程数：车机 4 核；弱网高 RTT 下多连接才能把带宽跑满。 */
    private const val DL_THREADS = 4

    /** 单段重试上限：弱网断流时把这一段续上，不用整包 412MB 重下。 */
    private const val DL_MAX_RETRIES = 6

    // 原版「时光KTV」mls-api 登录使用的设备标识(已验证:用该 id 从任意网络
    // 调 sqlite/sync 都能返回文件;随机 id 或 b 前缀的 mad-api box id 均返回空)。
    // 注意 muse_open=0 并不阻止下载,真正门槛是 mls 登录的 device_id。
    private val deviceId: String = "s00e04b021b2d"

    /** 取远端曲库版本；失败返回 null。版本取自 files[0].date(毫秒时间戳)。 */
    fun fetchVersion(): String? = runCatching {
        val cookie = login() ?: return null
        val file = sync(cookie) ?: return null
        file.version
    }.getOrNull()

    /**
     * 下载并安装曲库。
     * @param onProgress (百分比, 明细文案)；明细非空时优先显示（如「正在解压曲库 512MB」），
     *        因为解压/校验阶段很慢，只给百分比会让界面长时间不动、看着像卡死。
     */
    fun download(onProgress: (Int, String?) -> Unit): File {
        val cookie = login() ?: error("登录曲库服务失败")
        val file = sync(cookie) ?: error("曲库同步失败或无可用文件")
        val version = file.version

        // ⚠️ 下载前空间预检（车机实测教训）：
        // 曲库 gz≈430MB、解压后≈965MB，升级安装瞬间新旧库并存，峰值 ≈2.4GB。
        // 以前不预检直接写 —— 车机存储余量不够时会被我们写爆，系统存储压力会踩醒
        // 车机自带的存储清理服务（com.hozonauto.spacecleanupservice），该服务自身有
        // NPE bug（String.equals on null）会崩溃循环弹窗。宁可明确拒绝并告诉用户清哪里，
        // 也不能把用户存储写爆。
        ensureSpaceFor(file.url)

        val gzFile = File(target.parentFile, "${target.name}.gz.download")
        val tmpDb = File(target.parentFile, "${target.name}.download")
        try {
            downloadFile(file.url, gzFile) { pct -> onProgress((pct * 80 / 100).coerceIn(0, 80), null) }
            onProgress(80, "正在校验曲库文件")
            if (file.md5.isNotEmpty()) {
                check(md5(gzFile).equals(file.md5, ignoreCase = true)) { "曲库文件校验失败" }
            }
            onProgress(84, "正在解压曲库 0MB")
            GZIPInputStream(gzFile.inputStream().buffered(256 * 1024)).use { gz ->
                BufferedOutputStream(FileOutputStream(tmpDb)).use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var written = 0L
                    var lastStep = -1
                    while (true) {
                        val count = gz.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                        written += count
                        // 解压约 1GB、在该设备上要几分钟：每 64MB 报一次，界面才不会像是卡死。
                        val step = (written / (64L * 1024 * 1024)).toInt()
                        if (step != lastStep) {
                            lastStep = step
                            onProgress(84, "正在解压曲库 ${written / 1048576}MB")
                        }
                    }
                }
            }
            // 解压完立刻删 gz 包：随后"新旧库并存"的安装时刻能少占 430MB
            runCatching { gzFile.delete() }
            onProgress(96, "正在安装曲库")
            val backup = File(target.parentFile, "${target.name}.backup")
            backup.delete()
            if (target.exists()) check(target.renameTo(backup)) { "无法备份旧曲库" }
            if (!tmpDb.renameTo(target)) {
                backup.renameTo(target)
                error("无法安装下载的曲库")
            }
            backup.delete()
            saveVersion(version)
            onProgress(100, "曲库就绪")
            return target
        } finally {
            // 失败时两个临时文件都要清掉：残留的 .gz.download(430MB) + .download(965MB)
            // 白占约 1.4GB，反复重试时正是"空间不足"的常见来源。
            // 成功路径上 tmpDb 已 rename 成正式库，这里的 delete 是空操作。
            runCatching { gzFile.delete() }
            runCatching { tmpDb.delete() }
        }
    }

    private fun saveVersion(version: String) {
        runCatching { File(target.parentFile, "db_version.txt").writeText(version) }
    }

    /**
     * 下载前空间预检：估算曲库落地所需的峰值空间，不够就带明确原因拒绝。
     *
     * 峰值构成：gz 本体 + 解压产物（≈2.3 倍 gz）+ 旧库（升级瞬间新旧并存）。
     * 远端大小拿不到时按文档最坏值 2.5GB 估（保守方向错不伤人）。
     */
    private fun ensureSpaceFor(url: String) {
        val gzSize = remoteFileSize(url)
        val existing = runCatching { target.length() }.getOrDefault(0L)
        val needed = if (gzSize > 0) {
            gzSize + (gzSize * 23 / 10) + existing
        } else {
            2560L * 1024 * 1024
        }
        val parent = target.parentFile ?: return
        val free = runCatching { parent.usableSpace }.getOrDefault(0L)
        if (free >= needed) return
        val needMb = needed / 1048576
        val freeMb = free / 1048576
        KtvLog.w(TAG, "db download refused: need=${needMb}MB free=${freeMb}MB (gz=$gzSize old=$existing)")
        error(
            "车机存储不足：同步曲库峰值需要约 $needMb MB，当前仅剩 $freeMb MB。\n" +
                "请先到车机设置清理存储（或删除 MDKTV_Native/database 里的旧曲库），再重新打开本应用。"
        )
    }

    /** 远端文件大小（HEAD 探一次；拿不到返回 -1，调用方按保守值处理）。 */
    private fun remoteFileSize(url: String): Long = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "HEAD"
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        try {
            conn.contentLength.toLong().coerceAtLeast(-1L)
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(-1L)

    private fun login(): String? {
        val encrypted = rsaEncrypt(RSA_PUBLIC_KEY, """{"device_id":"$deviceId"}""")
        val body = JSONObject()
            .put("encrypted_data", encrypted)
            .put("ip", IP)
            .put("dns", "")
            .put("router", "")
            .put("subnet_mask", "255.255.255.0")
            .put("channel", CHANNEL)
            .put("mode", MODE)
            .toString()
        return postForCookie(LOGIN_URL, body)
    }

    private fun sync(cookie: String): DbFile? {
        val body = JSONObject()
            .put("last_sync_at", 0)
            .put("music_file_type", MUSIC_FILE_TYPE)
            .toString()
        val resp = postJson(SYNC_URL, body, cookie) ?: return null
        val files = resp.optJSONArray("files") ?: return null
        if (files.length() == 0) return null
        val f = files.getJSONObject(0)
        val url = f.optString("url")
        if (url.isBlank()) return null
        val version = f.optLong("date", 0L).takeIf { it > 0 }?.toString()
            ?: f.optString("day").takeIf(String::isNotBlank)
            ?: f.optLong("db_vc", 0L).takeIf { it > 0 }?.toString()
            ?: "0"
        return DbFile(url, f.optString("md5"), version)
    }

    /** POST 并捕获 Set-Cookie 中的 muse-portal-connector。 */
    private fun postForCookie(url: String, jsonBody: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 30_000
                doOutput = true
                instanceFollowRedirects = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("User-Agent", "MDKTV_Native/1.2")
            }
            conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) {
                MiliLog.warn("MUSE-DB login HTTP ${conn.responseCode} (url=$url)")
                return null
            }
            conn.inputStream.use { it.readBytes() }
            conn.getHeaderField("Set-Cookie")
                ?.substringBefore(';')
                ?.takeIf { it.startsWith("muse-portal-connector=") }
        } catch (error: Throwable) {
            Log.w(TAG, "login failed: ${error.message}")
            // 老设备上最常见的就是证书链校验失败（SSLHandshakeException），必须上报。
            MiliLog.warn("MUSE-DB login error: ${error.javaClass.simpleName}: ${error.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun postJson(url: String, jsonBody: String, cookie: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 30_000
                doOutput = true
                instanceFollowRedirects = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Cookie", cookie)
                setRequestProperty("User-Agent", "MDKTV_Native/1.2")
            }
            conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) {
                MiliLog.warn("MUSE-DB sync HTTP ${conn.responseCode} (url=$url)")
                return null
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONObject(text)
        } catch (error: Throwable) {
            Log.w(TAG, "sync failed: ${error.message}")
            MiliLog.warn("MUSE-DB sync error: ${error.javaClass.simpleName}: ${error.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 下载曲库 gz：优先多线程 Range 并行 + 断点续传，服务端不支持 Range 时回退单连接。
     *
     * 为什么必须多线程：官方源单连接在车机弱网（4G / 远处 Wi-Fi，RTT 100ms+）下
     * TCP 窗口小、带宽跑不满；412MB 的包就是"半小时"和"十分钟"的区别。
     * 断点续传：每段一个 .partN 文件，中途断网/退出后下次从各段已下载偏移继续，
     * 不会把已下的 400MB 丢掉重来。
     */
    private fun downloadFile(url: String, dest: File, onProgress: (Int) -> Unit) {
        val total = probeContentLength(url)
        if (total <= 0) {
            // 服务端不返回 Content-Range（不支持 Range）→ 原单连接逻辑
            downloadFileSingle(url, dest, onProgress)
            return
        }
        // URL 路径末段即曲库文件标识（带日期，如 muse.portal.ott_common.full.261012.db.gz）：
        // 曲库每天更新，旧 part 内容和新版本对不上，标识变了必须删掉重下。
        val fileKey = url.substringBefore('?').substringAfterLast('/')
        val meta = File(dest.parentFile, dest.name + ".part.meta")
        if (meta.exists() && meta.readText().trim() != fileKey) {
            cleanupParts(dest)
        }
        meta.writeText(fileKey)
        val chunk = (total + DL_THREADS - 1) / DL_THREADS
        val done = AtomicLong(0L)
        for (i in 0 until DL_THREADS) {
            val partN = File(dest.parentFile, dest.name + ".part$i")
            val end = minOf((i + 1) * chunk, total)
            val start = i * chunk
            if (start >= total) break
            // 段文件超长（异常残留）→ 删掉重下这一段
            if (partN.length() > end - start) partN.delete()
            done.addAndGet(partN.length())
        }
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val lastPercent = AtomicInteger(-1)
        val lock = Object()
        val latch = CountDownLatch(DL_THREADS)
        val pool = Executors.newFixedThreadPool(DL_THREADS)
        try {
            for (i in 0 until DL_THREADS) {
                val start = i * chunk
                val end = minOf(start + chunk, total) - 1
                if (start > end) { latch.countDown(); continue }
                pool.execute {
                    val partN = File(dest.parentFile, dest.name + ".part$i")
                    // 段文件已有字节 = 续传起点（段内偏移，直接追加写）
                    var pos = start + partN.length()
                    var retry = 0
                    try {
                        while (pos <= end) {
                            var conn: HttpURLConnection? = null
                            try {
                                conn = openRange(url, pos, end)
                                check(conn.responseCode == 206) { "HTTP ${conn.responseCode}" }
                                conn.inputStream.buffered(256 * 1024).use { input ->
                                    // append=true：续传时接着段文件末尾写
                                    FileOutputStream(partN, true).buffered(256 * 1024).use { out ->
                                        val buffer = ByteArray(256 * 1024)
                                        while (pos <= end) {
                                            val count = input.read(buffer)
                                            if (count < 0) break
                                            val w = minOf(count.toLong(), end - pos + 1).toInt()
                                            out.write(buffer, 0, w)
                                            pos += w
                                            done.addAndGet(w.toLong())
                                            val percent = ((done.get() * 100) / total).toInt().coerceIn(0, 99)
                                            synchronized(lock) {
                                                if (percent != lastPercent.get()) {
                                                    lastPercent.set(percent)
                                                    onProgress(percent)
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (e: Throwable) {
                                if (pos > end) break
                                retry++
                                if (retry > DL_MAX_RETRIES) { errors.add(e); break }
                                // 指数退避：弱网断流大多几百毫秒就恢复，别把 CPU 空转掉
                                runCatching { Thread.sleep(1000L shl retry) }
                            } finally {
                                conn?.disconnect()
                            }
                        }
                    } finally {
                        latch.countDown()
                    }
                }
            }
            latch.await()
        } finally {
            pool.shutdownNow()
        }
        if (errors.isNotEmpty()) {
            // 保留 .partN 供下次续传；只把原因抛出去
            error("曲库分段下载失败（已保留断点，重试将继续）：${errors[0].message}")
        }
        // 合并四段 → dest，然后逐段删除
        BufferedOutputStream(FileOutputStream(dest)).use { out ->
            val buffer = ByteArray(1024 * 1024)
            for (i in 0 until DL_THREADS) {
                val partN = File(dest.parentFile, dest.name + ".part$i")
                if (!partN.exists()) continue
                partN.inputStream().buffered().use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                    }
                }
                partN.delete()
            }
        }
        check(dest.length() == total) { "合并后大小不符：${dest.length()} != $total" }
        onProgress(99)
    }

    /** 探测服务端是否支持 Range 并返回总长度；不支持返回 -1。 */
    private fun probeContentLength(url: String): Long = runCatching {
        val conn = openRange(url, 0, 0)
        val code = conn.responseCode
        if (code != 206) { conn.disconnect(); return@runCatching -1L }
        val range = conn.getHeaderField("Content-Range")
        conn.disconnect()
        range?.substringAfterLast('/', "")?.trim()?.toLongOrNull() ?: -1L
    }.getOrDefault(-1L)

    /** 打开一个 Range 请求（from..to 闭区间）。 */
    private fun openRange(url: String, from: Long, to: Long): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MDKTV_Native/1.2")
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Range", "bytes=$from-$to")
        }

    /** 清掉所有分段文件与 meta（曲库版本变化 / 校验失败时调用）。 */
    private fun cleanupParts(dest: File) {
        for (i in 0 until DL_THREADS) {
            runCatching { File(dest.parentFile, dest.name + ".part$i").delete() }
        }
        runCatching { File(dest.parentFile, dest.name + ".part.meta").delete() }
    }

    /** 单连接下载（服务端不支持 Range 时的回退路径）。 */
    private fun downloadFileSingle(url: String, dest: File, onProgress: (Int) -> Unit) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 120_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "MDKTV_Native/1.2")
                setRequestProperty("Accept", "*/*")
            }
            val code = conn.responseCode
            check(code in 200..299) { "HTTP $code" }
            val total = conn.contentLengthCompat()
            val startedAt = System.currentTimeMillis()
            var lastPercent = -1
            conn.inputStream.buffered(256 * 1024).use { input ->
                dest.outputStream().buffered(256 * 1024).use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var read = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                        read += count
                        if (total > 0) {
                            val percent = ((read * 100) / total).toInt().coerceIn(0, 99)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                                if (percent % 10 == 0) {
                                    val seconds = ((System.currentTimeMillis() - startedAt) / 1000.0)
                                        .coerceAtLeast(0.001)
                                    val mbps = read / 1048576.0 / seconds
                                    MiliLog.info(
                                        "MUSE-DB progress $percent% " +
                                            "${read / 1048576}MB/${total / 1048576}MB " +
                                            "${String.format(Locale.ROOT, "%.2f", mbps)}MB/s",
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            conn?.disconnect()
        }
    }

    private fun rsaEncrypt(publicKeyBase64: String, plaintext: String): String = runCatching {
        val keyBytes = Base64.decode(publicKeyBase64.replace("\\s".toRegex(), ""), Base64.DEFAULT)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        Base64.encodeToString(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }.getOrDefault("")

    private fun md5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().buffered(256 * 1024).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and 0xff) }
    }
}
