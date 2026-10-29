package com.fenghuolun.ktv

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * 麦克风输入激活器。
 *
 * 背景: 电视/投影盒子等设备, 需要某个应用真正打开(占用)麦克风输入通道后,
 * 系统/音频 HAL 才会把外接麦克风的声音路由到扬声器(供 KTV 演唱)。仅持有
 * RECORD_AUDIO 权限而不实际录音是不够的。
 *
 * 做法: 用一个低开销的 [AudioRecord] 常驻打开麦克风并循环读取丢弃数据,
 * 保持输入通道占用(不写文件、不对外暴露声音)。与 MediaRecorder 录音互斥:
 * 开始录音前先 pause, 结束录音后自动 resume。
 *
 * 外接麦克风(USB/耳机麦)热插拔时, 系统会把默认输入路由切到外接设备并使
 * 原 AudioRecord 失效 —— 此时需重建 AudioRecord 并优先占用当前活动输入设备。
 * 因此对外暴露 [restart] 供设备变化监听调用; [setPreferredInput] 支持 Android 8.0+
 * 的 AudioRecord.setPreferredDevice 直接把输入锁定到指定麦克风(优先外接)。
 */
class MicActivator {
    private val tag = "MicActivator"
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var paused = false

    /** 期望优先使用的输入设备; null = 跟随系统默认(自动切到外接)。 */
    @Volatile private var preferredInput: AudioDeviceInfo? = null

    /**
     * 是否真正占住了麦克风输入通道。
     * 注意不能只看 running/paused（那只是线程意图）：车机上通道常被雷石等后台服务抢占，
     * 此时线程还活着但 AudioRecord 拿不到（record 一直为 null），必须用真实占用状态判断，
     * 否则 ensureMicActivated 里的重建逻辑永远不会触发。
     */
    val isActive: Boolean get() = running && !paused && record != null

    /** 打开麦克风并保持占用。失败静默(设备无麦克风时不影响 App 使用)。 */
    @Synchronized
    fun start() {
        if (running) return
        running = true
        paused = false
        spawnThread()
    }

    /**
     * 输入设备发生变化(外接麦克风插入/拔出等)时重建 AudioRecord, 跟随新路由。
     * 也用于从 paused(录音互斥)状态或抢占失败状态恢复占用 —— 车机上麦克风通道常被
     * 雷石等后台服务抢占，必须能随时重建抢回来，所以 running 为 true 也允许重建。
     */
    @Synchronized
    fun restart() {
        if (!running) {
            // 从未启动过 → 直接启动。必须先置 running=true，否则 readLoop 的 while 不进循环，线程空转退出。
            running = true
            paused = false
            spawnThread()
            return
        }
        if (paused) {
            // 录音互斥暂停中 → 恢复占用
            resume()
            return
        }
        releaseInternal()
        thread?.interrupt()
        thread = null
        spawnThread()
    }

    /** 指定首选输入设备(传入 null 恢复跟随系统默认)。 */
    @Synchronized
    fun setPreferredInput(device: AudioDeviceInfo?) {
        preferredInput = device
        restart()
    }

    @Synchronized
    private fun spawnThread() {
        thread = Thread({ readLoop() }, "mic-activator").also { it.isDaemon = true; it.start() }
    }

    /** 暂停占用(例如要使用 MediaRecorder 录音时), 保留重开能力。 */
    @Synchronized
    fun pause() {
        if (!running) return
        paused = true
        releaseInternal()
        thread?.interrupt()
        thread = null
    }

    /** 恢复占用。 */
    @Synchronized
    fun resume() {
        if (!running) return
        if (!paused) return
        paused = false
        spawnThread()
    }

    /** 彻底停止(不再自动恢复)。 */
    @Synchronized
    fun stop() {
        running = false
        paused = false
        releaseInternal()
        thread?.interrupt()
        thread = null
    }

    /**
     * 车机实测的抢麦问题与降级策略
 *
 * 真凶已核实(哪吒L NETA OS v1.2.1 系统 dump)：`system/priv-app/TsMicphoneService`
 * = `com.thunder.mic` / "ThunderMicSdk"，带 `RECEIVE_BOOT_COMPLETED` + `FOREGROUND_SERVICE`，
 * 开机由 `BootReceiver`(监听 BOOT_COMPLETED / MEDIA_MOUNTED) 拉起 `MicphoneService`
 * 前台服务**常驻占麦**。它自身没有 RECORD_AUDIO 权限却能占住通道，说明走的是厂商音频 HAL。
 *
 * 因此不能只死磕一个音源(换 VOICE_COMMUNICATION 也曾被它压住)，要**音源轮转降级**：
 * 逐个尝试多个输入源，谁能用就用谁，谁成功就锁定谁，避免每轮都从第一个源重新开始浪费窗口。
 */
    private fun readLoop() {
        // 音源降级链：优先 AEC 链路(人声干净)，失败逐级降级到更底层/更易抢占的源
        val sourceChain = intArrayOf(
            android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            android.media.MediaRecorder.AudioSource.MIC,
            android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
            android.media.MediaRecorder.AudioSource.VOICE_CALL,
            android.media.MediaRecorder.AudioSource.REMOTE_SUBMIX,
        )
        var sourceCursor = 0
        var consecutiveFails = 0
        while (running && !paused) {
            var recorder: AudioRecord? = null
            var source = sourceChain[sourceCursor % sourceChain.size]
            try {
                val minBuffer = AudioRecord.getMinBufferSize(
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minBuffer <= 0) {
                    // 无可用输入配置(可能无麦克风) → 稍后重试。
                    sleepQuietly(1500)
                    continue
                }
                recorder = AudioRecord(
                    source,
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuffer.coerceAtLeast(4096) * 2,
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    // 这个源被占(雷石等) → 轮转到下一个源试，而不是死磕同一个
                    sourceCursor++
                    sleepQuietly(400)
                    continue
                }
                // Android 8.0+: 指定首选输入设备(如刚插入的外接麦克风), 避免仍占用内建。
                val target = preferredInput
                if (target != null && android.os.Build.VERSION.SDK_INT >= 26) {
                    runCatching { recorder.setPreferredDevice(target) }
                }
                recorder.startRecording()
                // startRecording 不抛异常不代表真拿到通道：必须确认录音状态，RECORDSTATE_RECORDING
                // 才算真正抢到(通道被别人占时 startRecording 可能"成功"但立刻静音/失败)
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    Log.w(tag, "mic source=$source not recording (state=${recorder.recordingState}), rotate")
                    sourceCursor++
                    sleepQuietly(400)
                    continue
                }
                record = recorder
                Log.i(tag, "mic locked on source=$source (0x${Integer.toHexString(source)})")
                val buffer = ByteArray(minBuffer.coerceAtLeast(4096))
                consecutiveFails = 0
                while (running && !paused) {
                    // 丢弃读到的数据, 目的仅是让麦克风输入通道保持打开。
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read < 0) {
                        if (++consecutiveFails > 5) break
                        sleepQuietly(200)
                    } else {
                        consecutiveFails = 0
                    }
                }
            } catch (e: Throwable) {
                Log.w(tag, "mic keep-alive error source=$source: ${e.message}")
                // 通道可能被雷石等后台服务抢走 → 换源 + 快速重试抢占
                sourceCursor++
                sleepQuietly(1200)
            } finally {
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
                if (record === recorder) record = null
            }
        }
    }

    private fun releaseInternal() {
        val r = record
        record = null
        runCatching { r?.stop() }
        runCatching { r?.release() }
    }

    private fun sleepQuietly(ms: Long) {
        try { Thread.sleep(ms) } catch (ignored: InterruptedException) { Thread.currentThread().interrupt() }
    }
}

