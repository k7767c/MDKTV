package com.fenghuolun.ktv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * [VocalScorer] 真人评分引擎单测（v1.2.6）。
 *
 * 纯 JVM 测试：用合成正弦 PCM 当"人声"，覆盖静音不评分、稳定演唱高分、
 * 半首沉默扣分、音高抖动扣分、多人取最优、无歌词退化、八度错误防护。
 */
class VocalScorerTest {

    private val frameSamples = 640 // 40ms @ 16kHz
    private val frameBytes = frameSamples * 2

    /** 生成一帧 Int16LE 正弦 PCM。 */
    private fun sineFrame(freqHz: Double, amplitude: Int): ByteArray {
        val out = ByteArray(frameBytes)
        var i = 0
        while (i < frameSamples) {
            val v = (amplitude * sin(2.0 * PI * freqHz * i / 16000.0)).toInt()
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
            i++
        }
        return out
    }

    private val silence = ByteArray(frameBytes)

    /** 两行歌词：0-4s / 4-8s。 */
    private val timeline = listOf(0 to 4000, 4000 to 8000)

    private fun sing(scorer: VocalScorer, singer: String, linePos: Long, frames: Int, freqHz: Double) {
        repeat(frames) {
            scorer.tick(linePos)
            scorer.feed(singer, sineFrame(freqHz, 8000), frameBytes)
        }
    }

    @Test
    fun silence_isNotScored() {
        val scorer = VocalScorer()
        scorer.startSong()
        scorer.setTimeline(timeline)
        repeat(30) {
            scorer.tick(0)
            scorer.feed("phone1", silence, frameBytes)
        }
        assertNull("整首静音不应产出分数（旧实现会编 60-100 随机分）", scorer.finish())
    }

    @Test
    fun feedWhenInactive_isIgnored() {
        val scorer = VocalScorer()
        scorer.setTimeline(timeline)
        // 没调 startSong → active=false
        repeat(30) {
            scorer.tick(0)
            scorer.feed("phone1", sineFrame(440.0, 8000), frameBytes)
        }
        assertNull(scorer.finish())
    }

    @Test
    fun stableSinger_getsHighScore() {
        val scorer = VocalScorer()
        scorer.startSong()
        scorer.setTimeline(timeline)
        sing(scorer, "phone1", 0L, 25, 440.0)
        sing(scorer, "phone1", 4000L, 25, 440.0)
        val result = scorer.finish()
        assertNotNull(result)
        assertTrue("稳定满唱应得高分，实际 ${result!!.score}", result.score >= 85)
        assertEquals(2, result.linesTotal)
        assertEquals(2, result.linesSung)
        assertTrue("音高稳定性应接近 1，实际 ${result.stability}", result.stability > 0.9)
    }

    @Test
    fun halfSilent_scoresLowerThanFull() {
        val full = VocalScorer().also {
            it.startSong()
            it.setTimeline(timeline)
        }
        sing(full, "phone1", 0L, 25, 440.0)
        sing(full, "phone1", 4000L, 25, 440.0)
        val fullScore = full.finish()!!.score

        val half = VocalScorer().also {
            it.startSong()
            it.setTimeline(timeline)
        }
        sing(half, "phone1", 0L, 25, 440.0)
        repeat(25) {
            half.tick(4000L)
            half.feed("phone1", silence, frameBytes)
        }
        val halfResult = half.finish()!!
        assertTrue("第二行整行沉默应明显扣分（full=$fullScore half=${halfResult.score}）",
            halfResult.score <= fullScore - 10)
        assertEquals(1, halfResult.linesSung)
    }

    @Test
    fun unstablePitch_scoresLowerThanStable() {
        val stable = VocalScorer().also {
            it.startSong()
            it.setTimeline(timeline)
        }
        sing(stable, "phone1", 0L, 25, 440.0)
        sing(stable, "phone1", 4000L, 25, 440.0)
        val stableScore = stable.finish()!!.score

        val jitter = VocalScorer().also {
            it.startSong()
            it.setTimeline(timeline)
        }
        // 每帧在 220/440Hz 之间横跳：音高稳定性应被判为极差
        repeat(25) { jitter.tick(0L); jitter.feed("phone1", sineFrame(if (it % 2 == 0) 220.0 else 440.0, 8000), frameBytes) }
        repeat(25) { jitter.tick(4000L); jitter.feed("phone1", sineFrame(if (it % 2 == 0) 220.0 else 440.0, 8000), frameBytes) }
        val jitterScore = jitter.finish()!!.score
        assertTrue("音高抖动应扣稳定性分（stable=$stableScore jitter=$jitterScore）",
            jitterScore <= stableScore - 5)
    }

    @Test
    fun pitchDetection_avoidsOctaveError() {
        // 220Hz 全程稳定：若检测器落到 110Hz（低八度）或 440Hz（高八度），
        // 半音值会大幅抖动 → stability 崩掉。用 stability 间接验证基频优先。
        val scorer = VocalScorer()
        scorer.startSong()
        scorer.setTimeline(timeline)
        sing(scorer, "phone1", 0L, 25, 220.0)
        sing(scorer, "phone1", 4000L, 25, 220.0)
        val result = scorer.finish()!!
        assertTrue("220Hz 应被测成稳定基频，stability=${result.stability}", result.stability > 0.9)
        assertTrue(result.score >= 85)
    }

    @Test
    fun multipleSingers_bestOneWins() {
        val scorer = VocalScorer()
        scorer.startSong()
        scorer.setTimeline(timeline)
        // phone1 只断续唱了第一行前半段（覆盖率约一半）
        repeat(25) {
            scorer.tick(0L)
            scorer.feed("phone1", if (it < 13) sineFrame(440.0, 8000) else silence, frameBytes)
        }
        // phone2 两行全唱
        sing(scorer, "phone2", 0L, 25, 440.0)
        sing(scorer, "phone2", 4000L, 25, 440.0)
        val result = scorer.finish()!!
        assertEquals("phone2", result.singerId)
        assertTrue(result.score >= 85)
    }

    @Test
    fun noLyrics_stillScores() {
        val scorer = VocalScorer()
        scorer.startSong()
        scorer.setTimeline(emptyList()) // 无 .lrc → 整首歌一个窗口
        sing(scorer, "phone1", 0L, 50, 440.0)
        val result = scorer.finish()!!
        assertEquals(1, result.linesTotal)
        assertTrue(result.score >= 85)
    }

    @Test
    fun score_staysInBounds() {
        val scorer = VocalScorer()
        scorer.startSong()
        scorer.setTimeline(timeline)
        // 极响 + 满唱：不应超过 100
        repeat(25) { scorer.tick(0L); scorer.feed("phone1", sineFrame(440.0, 30000), frameBytes) }
        repeat(25) { scorer.tick(4000L); scorer.feed("phone1", sineFrame(440.0, 30000), frameBytes) }
        val result = scorer.finish()!!
        assertTrue("score=${result.score}", result.score in 0..100)
    }

    @Test
    fun shortFrames_areHandled() {
        val scorer = VocalScorer()
        scorer.startSong()
        scorer.setTimeline(timeline)
        // 超短/畸形包不应崩溃
        scorer.tick(0)
        scorer.feed("phone1", ByteArray(2), 2)
        scorer.feed("phone1", ByteArray(0), 0)
        repeat(30) {
            scorer.tick(0)
            scorer.feed("phone1", sineFrame(440.0, 8000), frameBytes)
        }
        assertNotNull(scorer.finish())
    }
}
