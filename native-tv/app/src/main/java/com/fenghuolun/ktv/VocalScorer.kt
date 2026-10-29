package com.fenghuolun.ktv

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 真人演唱评分引擎（v1.2.6）。
 *
 * 替代原来的 hash 假分（`60 + (stableId + 分钟数).hashCode() % 40` —— 纯随机数，
 * 跟唱成什么样没有任何关系）。输入是手机话筒回传的 PCM 帧
 * （16kHz / Int16LE / 单声道 / 40ms 一帧），按歌词时间轴逐行统计三项指标：
 *
 * - **演唱覆盖** coverage：该行播放期间有多少帧检测到人声（自适应噪声门）；
 * - **音高稳定性** stability：人声帧的音高（自相关检测）半音值标准差，越稳越高；
 * - **能量水平** energy：人声响度，太轻视为没真唱。
 *
 * 多人合唱时**按话筒分别统计**，取唱得最完整那一路作为最终分 —— 派对场景
 * "这一轮谁在主唱" 的自然语义；也避免把多个人的音高混在一起互相污染
 * （混合信号的自相关会给出垃圾音高，稳定性被无辜拖低）。
 *
 * 设计约束：
 * - 纯 Kotlin、零 Android 依赖 → 可直接 JVM 单测（见 VocalScorerTest）；
 * - 全部公开方法 synchronized —— 话筒服务线程（feed）与主线程（tick/finish）
 *   会并发进入，且 [feed] 里有约 10 万次乘加的自相关，必须互斥；
 * - 无歌词歌曲自动退化为"整首歌一个窗口"，不会因为没有 .lrc 就拒绝评分。
 */
class VocalScorer {

    /** 一首歌的评分结果。[score] 0..100，[singerId] 是得分最高的那路话筒。 */
    data class ScoreResult(
        val score: Int,
        val singerId: String,
        val linesSung: Int,
        val linesTotal: Int,
        val coverage: Double,
        val stability: Double,
    )

    private class LineStat {
        var frames = 0
        var voiced = 0
        var energySum = 0.0
        val semitones = ArrayList<Float>()
    }

    private class SingerStats {
        var noiseFloor = 500.0
        var totalVoiced = 0
        var totalFrames = 0
        val lines = ArrayList<LineStat>()
    }

    private val lock = Any()
    private val singers = HashMap<String, SingerStats>()
    private var windows: List<Pair<Int, Int>> = emptyList()
    private var currentLine = -1

    @Volatile
    private var active = false

    val isActive: Boolean get() = active

    /** 开始一首新歌（重置全部统计）。 */
    fun startSong() = synchronized(lock) {
        active = true
        singers.clear()
        currentLine = -1
    }

    /**
     * 设置歌词时间轴（毫秒闭开区间）。
     *
     * 空列表 = 整首歌当一个窗口（没有 .lrc 或时间轴全部非法的歌曲）。
     */
    fun setTimeline(lineWindows: List<Pair<Int, Int>>) = synchronized(lock) {
        windows = lineWindows.ifEmpty { listOf(0 to Int.MAX_VALUE) }
        singers.clear()
        currentLine = -1
    }

    /** 放弃当前歌曲（不产出分数）。 */
    fun abort() = synchronized(lock) {
        active = false
        singers.clear()
    }

    /**
     * 播放进度 tick（主线程，约 500ms 一次）。
     *
     * 只推进"当前行"指针；行的起止边界由调用方给的歌词时间轴决定，
     * 帧归属行的误差 ≤ tick 间隔，对统计量影响可忽略。
     */
    fun tick(positionMs: Long) = synchronized(lock) {
        if (!active || windows.isEmpty()) return
        var idx = -1
        for (i in windows.indices) {
            if (positionMs >= windows[i].first) idx = i else break
        }
        currentLine = idx
    }

    /**
     * 喂入一包话筒 PCM（话筒服务线程，40ms 一帧）。
     *
     * @param singerId 话筒客户端 id（手机唯一标识），多人分别统计
     */
    fun feed(singerId: String, pcm: ByteArray, length: Int) = synchronized(lock) {
        if (!active || windows.isEmpty() || length < 4) return
        val n = length / 2
        if (n <= 0) return
        var sum = 0.0
        var i = 0
        while (i + 1 < length) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            sum += (s * s).toDouble()
            i += 2
        }
        val rms = sqrt(sum / n)
        val stats = singers.getOrPut(singerId) { SingerStats().also { rebuildLines(it) } }
        stats.totalFrames++
        // 自适应噪声门：只用非人声帧缓慢贴合环境底噪（快升会把歌声算成底噪）
        val voiced = rms > max(stats.noiseFloor * 2.5, NOISE_FLOOR_ABS)
        if (voiced) stats.totalVoiced++ else stats.noiseFloor = stats.noiseFloor * 0.98 + rms * 0.02
        val lineIdx = currentLine
        if (lineIdx in stats.lines.indices) {
            val line = stats.lines[lineIdx]
            line.frames++
            line.energySum += rms
            if (voiced) {
                line.voiced++
                val hz = detectPitchHz(pcm, length)
                if (hz > 0.0) line.semitones.add(hzToSemitone(hz).toFloat())
            }
        }
    }

    /**
     * 收歌产出分数。
     *
     * @return 没有人声数据（没对话筒 / 整首没人唱）时返回 null —— 调用方应记录
     *   "未检测到演唱"，**不允许**再退回 hash 假分。
     */
    fun finish(): ScoreResult? = synchronized(lock) {
        active = false
        var best: ScoreResult? = null
        for ((id, stats) in singers) {
            if (stats.totalVoiced < MIN_VOICED_FRAMES) continue
            var sum = 0.0
            var counted = 0
            var sungLines = 0
            var coverageSum = 0.0
            var stabilitySum = 0.0
            for (line in stats.lines) {
                if (line.frames == 0) continue // 没播到的行不计分
                counted++
                val coverage = line.voiced.toDouble() / line.frames
                val stability = pitchStability(line.semitones)
                val energy = min(1.0, (line.energySum / line.frames) / ENERGY_REF)
                sum += COVERAGE_W * coverage + STABILITY_W * stability + ENERGY_W * energy
                if (line.voiced > 0) sungLines++
                coverageSum += coverage
                stabilitySum += stability
            }
            if (counted == 0) continue
            val avg = sum / counted
            val score = (BASE_SCORE + (100 - BASE_SCORE) * avg).roundToInt().coerceIn(0, 100)
            val result = ScoreResult(
                score = score,
                singerId = id,
                linesSung = sungLines,
                linesTotal = stats.lines.size,
                coverage = coverageSum / counted,
                stability = stabilitySum / counted,
            )
            if (best == null || result.score > best!!.score) best = result
        }
        singers.clear()
        best
    }

    private fun rebuildLines(stats: SingerStats) {
        stats.lines.clear()
        repeat(windows.size) { stats.lines.add(LineStat()) }
    }

    /** 音高稳定性：半音标准差 4 → 0 分，0 → 满分；样本不足给中性值。 */
    private fun pitchStability(semitones: List<Float>): Double {
        if (semitones.isEmpty()) return 0.0
        if (semitones.size < 3) return 0.5
        val mean = semitones.average()
        var variance = 0.0
        for (s in semitones) {
            val d = s - mean
            variance += d * d
        }
        variance /= semitones.size
        return (1.0 - sqrt(variance) / 4.0).coerceIn(0.0, 1.0)
    }

    /**
     * 自相关音高检测（人声 70–1000Hz）。
     *
     * 640 样本窗（40ms）上做 213 个 lag 的归一化自相关，峰值过 0.35 才认，
     * 抛物线插值细化 lag 精度（整数 lag 在 440Hz 附近有 ±0.3 半音量化误差，
     * 不插值会把稳定性统计做虚）。
     */
    private fun detectPitchHz(pcm: ByteArray, length: Int): Double {
        val n = min(length / 2, ANALYSIS_WINDOW)
        if (n < 320) return 0.0
        val buf = DoubleArray(n)
        var mean = 0.0
        var i = 0
        while (i < n) {
            val s = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toShort().toInt()
            buf[i] = s.toDouble()
            mean += buf[i]
            i++
        }
        mean /= n
        var energy = 0.0
        i = 0
        while (i < n) {
            buf[i] -= mean
            energy += buf[i] * buf[i]
            i++
        }
        if (energy < n * QUIET_ENERGY_PER_SAMPLE) return 0.0
        val minLag = (SAMPLE_RATE / MAX_PITCH_HZ).toInt()
        val maxLag = min((SAMPLE_RATE / MIN_PITCH_HZ).toInt(), n - 1)
        if (maxLag <= minLag + 2) return 0.0
        val corr = DoubleArray(maxLag - minLag + 1)
        var lag = minLag
        while (lag <= maxLag) {
            var s = 0.0
            var k = 0
            val limit = n - lag
            while (k < limit) {
                s += buf[k] * buf[k + lag]
                k++
            }
            corr[lag - minLag] = s / energy
            lag++
        }
        var peakIdx = 0
        var p = 1
        while (p < corr.size) {
            if (corr[p] > corr[peakIdx]) peakIdx = p
            p++
        }
        if (corr[peakIdx] < PITCH_CORR_THRESHOLD) return 0.0
        // 防八度错误：周期整数倍处的自相关同样接近 1，全局最大可能落在
        // 2T/3T 上（音高直接低一个/半个八度）。在所有"接近全局最大"的
        // 峰值里取**最小 lag**的那个 = 优先基频。
        val cutoff = corr[peakIdx] * 0.9
        var chosen = peakIdx
        var q = 0
        while (q < peakIdx) {
            if (corr[q] >= cutoff && corr[q] >= corr[q + 1]) {
                chosen = q
                break
            }
            q++
        }
        var refined = (minLag + chosen).toDouble()
        if (chosen in 1 until corr.size - 1) {
            val y0 = corr[chosen - 1]
            val y1 = corr[chosen]
            val y2 = corr[chosen + 1]
            val denom = y0 - 2.0 * y1 + y2
            if (abs(denom) > 1e-9) {
                val delta = 0.5 * (y0 - y2) / denom
                if (abs(delta) <= 1.0) refined += delta
            }
        }
        return SAMPLE_RATE / refined
    }

    private fun hzToSemitone(hz: Double): Double = 69.0 + 12.0 * log2(hz / 440.0)

    companion object {
        /** 与 MicStreamServer.SAMPLE_RATE 一致的话筒采样率。 */
        const val SAMPLE_RATE = 16000

        /** 分析窗：一帧 40ms = 640 样本。 */
        private const val ANALYSIS_WINDOW = 640

        private const val MIN_PITCH_HZ = 70.0
        private const val MAX_PITCH_HZ = 1000.0

        /** 自相关峰值门限（低于此视为无可靠周期）。 */
        private const val PITCH_CORR_THRESHOLD = 0.35

        /** 噪声门绝对下限（RMS）：低于此一律判静音，防底噪被当人声。 */
        private const val NOISE_FLOOR_ABS = 700.0

        /** RMS 5000 记满能量分。 */
        private const val ENERGY_REF = 5000.0

        /** 至少 0.5 秒人声帧才认为"唱了"，否则算未检测到演唱。 */
        private const val MIN_VOICED_FRAMES = 12

        private const val BASE_SCORE = 60
        private const val COVERAGE_W = 0.55
        private const val STABILITY_W = 0.30
        private const val ENERGY_W = 0.15

        /** 单样本能量静音门限（RMS ≈ 632 以下不测音高）。 */
        private const val QUIET_ENERGY_PER_SAMPLE = 4.0e5
    }
}
