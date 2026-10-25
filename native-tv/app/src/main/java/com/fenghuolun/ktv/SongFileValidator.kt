package com.fenghuolun.ktv

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/** Rejects complete-looking CDN previews that pass the old size-only check. */
object SongFileValidator {
    const val MIN_VALID_FILE_SIZE = 6L * 1024L * 1024L
    const val MIN_SONG_DURATION_MS = 60_000L
    private const val TS_PACKET_SIZE = 188
    private const val PROBE_PACKET_COUNT = 24_000 // about 4.3 MiB at each end
    private const val PTS_WRAP = 1L shl 33

    data class Result(
        val valid: Boolean,
        val durationMs: Long? = null,
        val reason: String = "",
    )

    private data class Cached(val length: Long, val result: Result)
    private val cache = ConcurrentHashMap<String, Cached>()

    /**
     * 校验歌曲文件有效性（读文件头尾采样 TS 包）。
     *
     * 缓存命中判断只看 **路径 + 文件长度**，不看 lastModified。
     * 原因：MainActivity 播放前会 `file.setLastModified(now)`（用于"自动删歌"按最近使用时间排序），
     * 若把 lastModified 计入缓存 key，每首歌播一次缓存就失效，下一次列表 getView 又要重读
     * 头尾约 8.6MiB —— 下载中每 250ms 触发一轮 notifyDataSetChanged，在车机 eMMC 上会明显掉帧。
     * 文件内容不变（长度不变）就没必要重验；真正的内容变更必然伴随下载完成→长度变化或路径变化。
     * 需要强制失效时走 [forget]。
     */
    fun inspect(file: File, requireTransportStream: Boolean = true): Result {
        if (!file.isFile) return Result(false, reason = "file missing")
        val key = file.absolutePath
        val length = file.length()
        cache[key]?.takeIf { it.length == length }?.let { return it.result }
        val result = inspectUncached(file, requireTransportStream)
        cache[key] = Cached(length, result)
        return result
    }

    fun forget(file: File) {
        cache.remove(file.absolutePath)
    }

    fun requiresTransportStream(file: File): Boolean =
        file.extension.equals("ts", true) || file.extension.equals("ls", true)

    private fun inspectUncached(file: File, requireTransportStream: Boolean): Result {
        if (file.length() < MIN_VALID_FILE_SIZE) {
            return Result(false, reason = "file too small: ${file.length()}")
        }
        RandomAccessFile(file, "r").use { input ->
            val syncOffset = findSyncOffset(input)
            if (syncOffset < 0) {
                // 找不到 MPEG-TS 同步头(0x47)：
                // - TS/LS 文件必须失败（本来就是 TS 容器，找不到同步头=损坏）；
                // - 非 TS 文件也不能无条件放行（防任意大文件混进歌单随机播放时"点歌无声"），
                //   做文件头魔数识别：已知合法容器才过，未知一律拒绝。
                return if (requireTransportStream) Result(false, reason = "not an MPEG-TS file")
                else if (looksLikeKnownVideoContainer(input)) Result(true, reason = "non-TS container (size check only)")
                else Result(false, reason = "unknown file format (no MPEG-TS sync header)")
            }
            val packetCount = ((input.length() - syncOffset) / TS_PACKET_SIZE).coerceAtLeast(0)
            if (packetCount < 10) return Result(false, reason = "incomplete MPEG-TS file")
            val first = readPts(input, syncOffset, minOf(packetCount, PROBE_PACKET_COUNT.toLong()).toInt())
            val tailPackets = minOf(packetCount, PROBE_PACKET_COUNT.toLong()).toInt()
            val tailOffset = syncOffset + (packetCount - tailPackets) * TS_PACKET_SIZE
            val last = readPts(input, tailOffset, tailPackets)
            if (first.isEmpty() || last.isEmpty()) {
                // Some valid transport streams omit PTS in the sampled range; keep the old size check in that case.
                return Result(true, reason = "duration unavailable")
            }
            val startPts = first.minOrNull() ?: return Result(true, reason = "duration unavailable")
            val endPts = last.maxOrNull() ?: return Result(true, reason = "duration unavailable")
            val ticks = if (endPts >= startPts) endPts - startPts else endPts + PTS_WRAP - startPts
            val durationMs = ticks * 1_000L / 90_000L
            return if (durationMs < MIN_SONG_DURATION_MS) {
                Result(false, durationMs, "preview too short: ${durationMs}ms")
            } else {
                Result(true, durationMs)
            }
        }
    }

    /**
     * 非 TS 文件的文件头魔数识别：仅放行已知合法音视频容器（MP4/MP3/WAV/FLAC/OGG/TS 变体）。
     * 曲库分发全是 TS，本地导入/USB 场景才可能遇到这些；其余大文件（文本/文档/随机）一律拒。
     */
    private fun looksLikeKnownVideoContainer(input: RandomAccessFile): Boolean {
        val head = ByteArray(12)
        input.seek(0)
        val read = input.read(head)
        if (read < 4) return false
        // MP4/MOV: 第 4-8 字节 = 'f t y p'（0x66 0x74 0x79 0x70）
        if (head[4].toInt() == 0x66 && head[5].toInt() == 0x74 &&
            head[6].toInt() == 0x79 && head[7].toInt() == 0x70
        ) return true
        // MP3: 0xFFEx / 0xFFFx 帧同步，或 ID3 头
        if ((head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xE0) == 0xE0) return true
        if (head[0].toInt() == 'I'.code && head[1].toInt() == 'D'.code &&
            head[2].toInt() == '3'.code
        ) return true
        // WAV: RIFF....WAVE
        if (head[0].toInt() == 'R'.code && head[1].toInt() == 'I'.code &&
            head[2].toInt() == 'F'.code && head[3].toInt() == 'F'.code &&
            read >= 12 && head[8].toInt() == 'W'.code && head[9].toInt() == 'A'.code &&
            head[10].toInt() == 'V'.code && head[11].toInt() == 'E'.code
        ) return true
        // FLAC: fLaC
        if (head[0].toInt() == 'f'.code && head[1].toInt() == 'L'.code &&
            head[2].toInt() == 'a'.code && head[3].toInt() == 'C'.code
        ) return true
        // OGG: OggS
        if (head[0].toInt() == 'O'.code && head[1].toInt() == 'g'.code &&
            head[2].toInt() == 'g'.code && head[3].toInt() == 'S'.code
        ) return true
        return false
    }

    private fun findSyncOffset(input: RandomAccessFile): Long {
        val probeSize = minOf(input.length(), TS_PACKET_SIZE.toLong() * 8).toInt()
        if (probeSize < TS_PACKET_SIZE * 3) return -1
        val probe = ByteArray(probeSize)
        input.seek(0)
        input.readFully(probe)
        for (offset in 0 until minOf(TS_PACKET_SIZE, probe.size)) {
            var matches = 0
            var index = offset
            while (index < probe.size && matches < 5) {
                if (probe[index].toInt() and 0xff != 0x47) break
                matches++
                index += TS_PACKET_SIZE
            }
            if (matches >= 3) return offset.toLong()
        }
        return -1
    }

    private fun readPts(input: RandomAccessFile, offset: Long, count: Int): List<Long> {
        val values = ArrayList<Long>()
        val packet = ByteArray(TS_PACKET_SIZE)
        input.seek(offset)
        repeat(count) {
            if (input.read(packet) != TS_PACKET_SIZE) return@repeat
            if ((packet[0].toInt() and 0xff) != 0x47 || (packet[1].toInt() and 0x40) == 0) return@repeat
            val adaptationControl = packet[3].toInt().ushr(4) and 0x03
            if (adaptationControl != 1 && adaptationControl != 3) return@repeat
            var payload = 4
            if (adaptationControl == 3) payload += 1 + (packet[4].toInt() and 0xff)
            if (payload + 14 > TS_PACKET_SIZE) return@repeat
            if (packet[payload].toInt() != 0 || packet[payload + 1].toInt() != 0 ||
                packet[payload + 2].toInt() and 0xff != 1
            ) return@repeat
            if ((packet[payload + 7].toInt() and 0x80) == 0) return@repeat
            val p = payload + 9
            val pts = (((packet[p].toLong() ushr 1) and 0x07) shl 30) or
                ((packet[p + 1].toLong() and 0xff) shl 22) or
                (((packet[p + 2].toLong() ushr 1) and 0x7f) shl 15) or
                ((packet[p + 3].toLong() and 0xff) shl 7) or
                ((packet[p + 4].toLong() ushr 1) and 0x7f)
            values += pts
        }
        return values
    }
}
