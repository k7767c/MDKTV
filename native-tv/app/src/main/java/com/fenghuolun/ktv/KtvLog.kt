package com.fenghuolun.ktv

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

/**
 * 统一日志设施。
 *
 * ## 为什么需要它
 *
 * 之前工程里只有零散的 `Log.i/Log.w/Log.e`：日志只进 logcat，车机上拔线/重启后
 * 什么都没剩下。老板反馈"没个日志系统，修复全都靠猜"—— 手机截图只能看到"话筒
 * 传输中断"，至于是车机服务没起来、TLS 握手失败、AudioTrack 建失败还是 app 崩了，
 * 完全看不出来。这个类把日志落到**车机本地文件**，并提供三个出口：
 *
 * 1. **车机端**：工具 → 运行日志（对话框直接看，可分享）
 * 2. **手机端**：浏览器打开 `https://<车机IP>:8890/logs`（纯文本网页，随扫随看）
 * 3. **接口**：`GET /api/v1/logs`（JSON，供页面/自动化取）
 *
 * 另外崩溃由 [installCrashHandler] 兜底：任何线程抛未捕获异常都会把堆栈 +
 * 最近 200 条日志写进 `crash_*.txt`，下次启动 toast 提示 —— 崩溃不再无声无息。
 *
 * ## 实现要点
 *
 * - **不阻塞业务线程**：调用方只入队（有界队列，满丢最旧），落盘由单写线程干。
 * - **内存环**：最近 [MAX_MEMORY] 条常驻，查看器/接口/崩溃现场都从这取。
 * - **logcat 镜像**：同时写 android.util.Log，adb logcat 老习惯不变。
 * - **崩溃走同步写**：进程马上要被杀，队列未必来得及刷，崩溃文件必须当场落盘。
 * - **轮转**：单文件超 [MAX_FILE_BYTES] 换新，只留最近 [KEEP_FILES] 个。
 */
object KtvLog {
    private const val TAG = "KtvLog"

    /** 内存环容量：足够覆盖一次问题复现，又不吃多少内存（每条约 80~120 字节）。 */
    private const val MAX_MEMORY = 1000

    /** 单文件上限 1MB，超过换下一个文件。 */
    private const val MAX_FILE_BYTES = 1024 * 1024L

    /** 保留的日志文件个数（更老的自动删）。 */
    private const val KEEP_FILES = 3

    /** 日志目录名（位于 app files 下，卸载随行，不需要任何存储权限）。 */
    const val DIR_NAME = "logs"

    /** 崩溃文件前缀。 */
    private const val CRASH_PREFIX = "crash_"

    /** 普通日志文件前缀。 */
    private const val LOG_PREFIX = "ktv_log_"

    private val lineFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT)
    private val stampFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)

    private val memoryLock = Object()
    private val memory = ArrayDeque<String>()

    private val queue = LinkedBlockingQueue<String>(2000)
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var dir: File? = null

    @Volatile
    private var current: File? = null

    @Volatile
    private var currentSize = 0L

    /** 用 app context 初始化并启动写线程。可重复调用（幂等）。 */
    @JvmStatic
    fun init(context: Context) {
        val d = File(context.filesDir, DIR_NAME)
        runCatching { d.mkdirs() }
        dir = d
        if (started.getAndSet(true)) return
        Thread({ writeLoop() }, "ktv-log-writer").apply { isDaemon = true }.start()
        i(TAG, "==== KtvLog init: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ====")
    }

    fun v(tag: String, msg: String) = log("V", tag, msg, null)

    fun d(tag: String, msg: String) = log("D", tag, msg, null)

    fun i(tag: String, msg: String) = log("I", tag, msg, null)

    fun w(tag: String, msg: String) = log("W", tag, msg, null)

    fun e(tag: String, msg: String) = log("E", tag, msg, null)

    fun e(tag: String, msg: String, tr: Throwable?) = log("E", tag, msg, tr)

    fun w(tag: String, msg: String, tr: Throwable?) = log("W", tag, msg, tr)

    /**
     * 记一条日志。线程安全、非阻塞（最坏情况丢日志也不影响业务）。
     *
     * 非 main 线程会带上线程名 —— 服务端线程（accept/pool）的问题靠这个定位。
     */
    private fun log(level: String, tag: String, msg: String, tr: Throwable?) {
        val thread = Thread.currentThread().name
        val line = buildString {
            append(lineFmt.format(Date()))
            append(' ')
            append(level)
            append('/')
            append(tag)
            if (thread != "main") {
                append('[')
                append(thread)
                append(']')
            }
            append(": ")
            append(msg.replace('\n', ' '))
            if (tr != null) {
                append(" | ")
                append(stackOf(tr))
            }
        }
        // 内存环 + logcat 立即生效（查看器不等写线程）
        synchronized(memoryLock) {
            memory.addLast(line)
            while (memory.size > MAX_MEMORY) memory.removeFirst()
        }
        mirrorLogcat(level, tag, line)
        // 落盘异步；满了丢最旧，保证最新日志能进队
        if (!queue.offer(line)) {
            queue.poll()
            queue.offer(line)
        }
    }

    /** logcat 镜像：安卓 Stub 环境（单测）下不能崩，整体包保护。 */
    private fun mirrorLogcat(level: String, tag: String, line: String) {
        runCatching {
            when (level) {
                "E" -> Log.e(tag, line)
                "W" -> Log.w(tag, line)
                "D" -> Log.d(tag, line)
                "V" -> Log.v(tag, line)
                else -> Log.i(tag, line)
            }
        }
    }

    private fun stackOf(tr: Throwable): String = try {
        val sw = StringWriter()
        tr.printStackTrace(PrintWriter(sw))
        sw.toString().replace("\n", " ⏎ ")
    } catch (_: Throwable) {
        tr.javaClass.simpleName
    }

    // ─── 读取出口 ────────────────────────────────────

    /** 最近 [count] 条日志（查看器/接口用）。 */
    @JvmStatic
    fun recent(count: Int = 200): List<String> = synchronized(memoryLock) {
        if (count <= 0) emptyList()
        else if (memory.size <= count) memory.toList()
        else memory.drop(memory.size - count)
    }

    /** 最近日志拼成的文本（分享/网页用）。 */
    @JvmStatic
    fun recentText(count: Int = 300): String = recent(count).joinToString("\n")

    /** 最近日志的 JSON（`/api/v1/logs` 用）。 */
    @JvmStatic
    fun recentJson(count: Int = 300): String = runCatching {
        val array = org.json.JSONArray()
        recent(count).forEach { array.put(it) }
        org.json.JSONObject()
            .put("ok", true)
            .put("count", array.length())
            .put("lines", array)
            .toString()
    }.getOrDefault("{\"ok\":false,\"error\":\"日志序列化失败\"}")

    /** 全部日志文件（新→旧）。 */
    @JvmStatic
    fun logFiles(): List<File> {
        val d = dir ?: return emptyList()
        return (d.listFiles { f -> f.name.startsWith(LOG_PREFIX) } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
    }

    /** 全部崩溃文件（新→旧）。 */
    @JvmStatic
    fun crashFiles(): List<File> {
        val d = dir ?: return emptyList()
        return (d.listFiles { f -> f.name.startsWith(CRASH_PREFIX) } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
    }

    /** 最近一次崩溃文件（启动提示用）。 */
    @JvmStatic
    fun lastCrashFile(): File? = crashFiles().firstOrNull()

    /** 清空内存环与所有日志/崩溃文件（查看器"清空"按钮）。 */
    @JvmStatic
    fun clearAll() {
        synchronized(memoryLock) { memory.clear() }
        runCatching { logFiles().forEach { it.delete() } }
        runCatching { crashFiles().forEach { it.delete() } }
        current = null
        currentSize = 0
        i(TAG, "logs cleared")
    }

    // ─── 崩溃落盘（同步，进程即将被杀） ─────────────────

    /**
     * 未捕获异常处理：堆栈 + 最近 200 条日志**当场**写进 crash_*.txt。
     *
     * 不能走队列 —— 默认 handler 返回后进程就没了，异步写线程未必有机会刷盘。
     */
    @JvmStatic
    fun writeCrash(thread: Thread, tr: Throwable) {
        val d = dir ?: return
        runCatching {
            if (!d.exists()) d.mkdirs()
            val file = File(d, CRASH_PREFIX + stampFmt.format(Date()) + ".txt")
            file.printWriter(Charsets.UTF_8).use { out ->
                out.println("thread: ${thread.name}")
                out.println("time: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date()))
                out.println("version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                out.println("---- stack ----")
                tr.printStackTrace(out)
                out.println("---- recent logs ----")
                recent(200).forEach { out.println(it) }
            }
            // 顺带入队，让这次崩溃也出现在常规日志流里
            log("E", TAG, "CRASH on ${thread.name}: ${tr.javaClass.simpleName}: ${tr.message}", null)
            pruneOldFiles()
        }
    }

    // ─── 写线程与轮转 ────────────────────────────────

    private fun writeLoop() {
        while (true) {
            val line = try {
                queue.take()
            } catch (_: InterruptedException) {
                break
            }
            runCatching { appendToFile(line) }
        }
    }

    private fun appendToFile(line: String) {
        val d = dir ?: return
        val f = current ?: pickLogFile(d).also { current = it; currentSize = it.length() }
        if (currentSize > MAX_FILE_BYTES) {
            val next = File(d, LOG_PREFIX + stampFmt.format(Date()) + ".txt")
            current = next
            currentSize = 0L
            pruneOldFiles()
        }
        f.appendText(line + "\n", Charsets.UTF_8)
        currentSize += line.length + 1
    }

    /** 选当前该追加的文件：同一天最新的且没超限；否则开一个带时间戳的新文件。 */
    internal fun pickLogFile(d: File): File {
        val existing = (d.listFiles { f -> f.name.startsWith(LOG_PREFIX) } ?: emptyArray())
            .filter { it.isFile }
            .sortedByDescending { it.lastModified() }
        existing.firstOrNull()?.let { if (it.length() < MAX_FILE_BYTES) return it }
        return File(d, LOG_PREFIX + stampFmt.format(Date()) + ".txt")
    }

    /** 只保留最近 [KEEP_FILES] 个日志文件（崩溃文件不轮转，量极小）。 */
    internal fun pruneOldFiles(d: File? = dir) {
        val target = d ?: return
        val files = (target.listFiles { f -> f.name.startsWith(LOG_PREFIX) } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
        if (files.size > KEEP_FILES) {
            files.drop(KEEP_FILES).forEach { runCatching { it.delete() } }
        }
    }
}
