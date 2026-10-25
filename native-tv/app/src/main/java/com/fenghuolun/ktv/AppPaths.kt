package com.fenghuolun.ktv

import android.os.Environment
import java.io.File
import java.io.FileOutputStream

/** The only external-storage namespace used by the fresh Maidong KTV app. */
object AppPaths {
    const val ROOT_PATH = "/storage/emulated/0/MDKTV_Native"
    const val VIDEO_PATH = "$ROOT_PATH/video"

    val root = File(ROOT_PATH)
    val stateFile = File(root, "state.json")
    val songsDir = File(root, "songs")
    val importDir = File(root, "import")
    val catalogFile = File(root, "catalog.json")
    val recordsDir = File(root, "records")
    val moviesDir = File(root, "movies")
    val appsDir = File(root, "apps")
    val backupDir = File(root, "backup")
    val databaseDir = File(root, "database")
    val databaseFile = File(databaseDir, "muse.db")
    val videoDir = File(VIDEO_PATH)
    val cloudSongsDir = File(videoDir, "cloud-song")

    fun ensureDirectories() {
        listOf(root, songsDir, importDir, recordsDir, moviesDir, appsDir, backupDir,
            databaseDir, videoDir, cloudSongsDir).forEach {
            check(it.exists() || it.mkdirs()) { "Cannot create ${it.absolutePath}" }
        }
    }

    /** Keep the legacy call site without deleting a shared directory owned by other apps. */
    fun removeNativeLegacyLogDirectory() = Unit

    private fun size(text: Long): String =
        String.format(java.util.Locale.ROOT, "%.2fGB", text / 1024.0 / 1024.0 / 1024.0)

    /**
     * 真实写入探针：写 [sizeMB] 兆再删掉，验证「现在真的能写」。
     * `usableSpace` 只反映块空间；inode 耗尽/配额/ROM 限制都会报 ENOSPC 但仍显示几 GB 可用，
     * 所以判断「是不是空间问题」必须靠真实写入，而不是看数字。
     */
    fun probeWrite(dir: File, sizeMB: Int = 8): String = runCatching {
        val probe = File(dir, ".write_probe.tmp")
        try {
            val buffer = ByteArray(1024 * 1024)
            FileOutputStream(probe).use { out ->
                repeat(sizeMB) { out.write(buffer) }
                out.flush()
                out.fd.sync()
            }
            "ok(${sizeMB}MB)"
        } finally {
            runCatching { probe.delete() }
        }
    }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }

    private fun mountLines(): String = runCatching {
        File("/proc/mounts").readLines()
            .filter { line ->
                line.contains("sdcard") || line.contains("/data ") ||
                    line.contains("/storage") || line.contains("/mnt")
            }
            .take(12)
            .joinToString(" | ") { it.substringBefore(" ").trim() }
    }.getOrNull() ?: "(unreadable)"

    /** 一次性诊断串：空间、真实可写性、挂载点。失败时上报远程日志并显示在界面上。 */
    fun diagnose(): String {
        val dir = databaseDir
        val free = runCatching { dir.usableSpace }.getOrDefault(0L)
        val total = runCatching { dir.totalSpace }.getOrDefault(0L)
        val envState = runCatching { Environment.getExternalStorageState() }.getOrNull()
        return "root=${root.absolutePath} free=${size(free)} total=${size(total)} " +
            "envState=$envState probe=${probeWrite(dir)} mounts=[${mountLines()}]"
    }
}
