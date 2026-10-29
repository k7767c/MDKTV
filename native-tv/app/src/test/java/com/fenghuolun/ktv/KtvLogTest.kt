package com.fenghuolun.ktv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [KtvLog] 的纯 JVM 单测：内存环、日志文件挑选与轮转清理。
 *
 * 不碰 Android 组件（Context/Log 都有 runCatching 兜底），
 * 保证在 CI/本机 `gradlew test` 下稳定通过。
 */
class KtvLogTest {

    @Test
    fun recentRingKeepsLatestLines() {
        // 连续记 5 条，取最近 3 条应是最新的 3 条且顺序不变
        val stamp = System.nanoTime()
        for (i in 1..5) KtvLog.i("Test", "line-$stamp-$i")
        val last3 = KtvLog.recent(3)
        assertEquals(3, last3.size)
        assertTrue(last3[2].endsWith("line-$stamp-5"))
        assertTrue(last3[0].endsWith("line-$stamp-3"))
        // 非 main 线程要带线程名（服务端问题定位的关键线索）
        val t = Thread {
            KtvLog.w("Test", "from-worker-$stamp")
            assertTrue(KtvLog.recent(1)[0].contains("[Thread-"))
        }
        t.start()
        t.join()
    }

    @Test
    fun pickLogFileCreatesNewWhenDirEmpty() {
        val dir = tempDir()
        val picked = KtvLog.pickLogFile(dir)
        assertTrue(picked.name.startsWith("ktv_log_"))
        assertTrue(picked.parentFile == dir)
    }

    @Test
    fun pickLogFileReusesExistingSmallFile() {
        val dir = tempDir()
        val existing = File(dir, "ktv_log_20260101_000000.txt").apply { writeText("hello") }
        val picked = KtvLog.pickLogFile(dir)
        assertEquals(existing.absolutePath, picked.absolutePath)
    }

    @Test
    fun pickLogFileRotatesWhenOversized() {
        val dir = tempDir()
        // 造一个超限文件（1MB + 1 字节）
        val fat = File(dir, "ktv_log_20260101_000000.txt")
        fat.writeBytes(ByteArray(1024 * 1024 + 1))
        val picked = KtvLog.pickLogFile(dir)
        assertTrue("超限文件不应被复用", picked.absolutePath != fat.absolutePath)
        assertTrue(picked.name.startsWith("ktv_log_"))
    }

    @Test
    fun pruneOldFilesKeepsOnlyNewestThree() {
        val dir = tempDir()
        val files = (1..5).map { i ->
            File(dir, "ktv_log_2026010${i}_000000.txt").apply {
                writeText("x")
                setLastModified(1000L * i)
            }
        }
        KtvLog.pruneOldFiles(dir)
        val left = dir.listFiles()?.filter { it.name.startsWith("ktv_log_") } ?: emptyList()
        assertEquals(3, left.size)
        // 留下的应该是最新的 3 个
        assertTrue(left.none { it.name == files[0].name || it.name == files[1].name })
    }

    private fun tempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "ktvlog-test-${System.nanoTime()}")
        dir.mkdirs()
        dir.deleteOnExit()
        return dir
    }
}
