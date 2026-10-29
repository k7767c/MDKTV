package com.fenghuolun.ktv.player

import android.content.Context
import android.graphics.Color
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import java.util.concurrent.Executors
import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import tv.danmaku.ijk.media.player.misc.ITrackInfo

/**
 * Kotlin port of the original MuseVideoView display structure: a FrameLayout
 * containing a ResizeSurfaceView. Playback lives in one shared engine, so page
 * and fullscreen changes only redirect the decoder output surface.
 */
class KtvVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val surfaceView = KtvSurfaceView(context)
    private var engine: KtvPlaybackEngine? = null
    private var surfaceCallback: SurfaceHolder.Callback? = null
    /**
     * Surface 在切页面/移除宿主时已销毁、尚未重建。
     * engine 据此判断是否需要暂停解码器（防 IJK 渲染到已回收 Surface 黑屏）。
     */
    @Volatile internal var surfaceLost = false

    /** 因 Surface 丢失（切页面）暂停过解码器，surfaceCreated 时据此恢复播放。 */
    @Volatile internal var pausedForSurfaceLoss = false

    init {
        setBackgroundColor(Color.BLACK)
        addView(
            surfaceView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER),
        )
    }

    fun bind(engine: KtvPlaybackEngine) {
        this.engine = engine
    }

    fun setMediaOverlay(enabled: Boolean) {
        surfaceView.setZOrderMediaOverlay(enabled)
    }

    fun setVideoURI(uri: Uri) = engine?.setVideoUri(uri) ?: Unit
    fun setOnPreparedListener(listener: MediaPlayer.OnPreparedListener?) = engine?.setOnPreparedListener(listener) ?: Unit
    fun setOnCompletionListener(listener: MediaPlayer.OnCompletionListener?) = engine?.setOnCompletionListener(listener) ?: Unit
    fun setOnErrorListener(listener: MediaPlayer.OnErrorListener?) = engine?.setOnErrorListener(listener) ?: Unit
    fun start() = engine?.start() ?: Unit
    fun pause() = engine?.pause() ?: Unit
    fun seekTo(positionMs: Int) = engine?.seekTo(positionMs) ?: Unit
    fun stopPlayback() = engine?.stop() ?: Unit
    fun setPlaybackVolume(left: Float, right: Float) = engine?.setVolume(left, right) ?: Unit
    fun setTone(step: Int) = engine?.setTone(step) ?: Unit
    fun selectAudioTrack(original: Boolean): Boolean = engine?.selectAudioTrack(original) == true
    fun audioTrackCount(): Int = engine?.audioTrackCount() ?: 0
    fun selectAudioChannel(channel: Int, volume: Float) = engine?.selectAudioChannel(channel, volume) ?: Unit
    fun audioSessionId(): Int = engine?.audioSessionId() ?: 0
    val isPlaying: Boolean get() = engine?.isPlaying == true
    val currentPosition: Int get() = engine?.currentPosition ?: 0
    val duration: Int get() = engine?.duration ?: 0

    internal fun updateVideoSize(width: Int, height: Int) {
        surfaceView.setVideoSize(width, height)
    }

    internal fun installSurfaceCallback(callback: SurfaceHolder.Callback) {
        surfaceCallback?.let(surfaceView.holder::removeCallback)
        surfaceCallback = callback
        surfaceView.holder.addCallback(callback)
        surfaceLost = surfaceView.holder.surface?.isValid != true
    }

    internal fun currentHolder(): SurfaceHolder = surfaceView.holder

    /**
     * 宿主被移除/切走时由 engine 调用：暂停 IJK 解码（防渲染到已回收 Surface 黑屏），
     * 并标记 surfaceLost，等 surfaceCreated 重建后恢复。
     */
    internal fun markSurfaceLost() {
        surfaceLost = true
    }

    internal fun markSurfaceRestored() {
        surfaceLost = false
    }

    internal fun markPausedForSurfaceLoss() {
        pausedForSurfaceLoss = true
    }

    internal fun engineWasPausedForSurfaceLoss(): Boolean {
        val was = pausedForSurfaceLoss
        pausedForSurfaceLoss = false
        return was
    }
}

/** Resize behavior ported from the original ResizeSurfaceView, scale mode 1. */
private class KtvSurfaceView(context: Context) : SurfaceView(context) {
    private var videoWidth = 0
    private var videoHeight = 0

    fun setVideoSize(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        if (width > 0 && height > 0) holder.setFixedSize(width, height)
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = getDefaultSize(videoWidth, widthMeasureSpec).coerceAtLeast(1)
        val maxHeight = getDefaultSize(videoHeight, heightMeasureSpec).coerceAtLeast(1)
        var measuredWidth = maxWidth
        var measuredHeight = measuredWidth * 9 / 16
        if (measuredHeight > maxHeight) {
            measuredHeight = maxHeight
            measuredWidth = measuredHeight * 16 / 9
        }
        setMeasuredDimension(measuredWidth.coerceAtLeast(1), measuredHeight.coerceAtLeast(1))
    }
}

/**
 * IJK 播放器引擎。
 *
 * 线程约定：IJK 的回调(prepared/videoSizeChanged/error/completion)都在它自己的内部线程触发，
 * 而主线程会读 prepared / startWhenPrepared / videoWidth 等状态判断播放流程。
 * 这些字段必须 @Volatile，否则 JMM 下主线程可能永远读到旧值（表现为"点了歌没反应"）。
 * 任何 View/SurfaceHolder 操作必须经 [postToMain] 切回主线程，不能在 IJK 回调里直接改。
 */
class KtvPlaybackEngine(context: Context) {
    private companion object {
        const val TAG = "KtvPlaybackEngine"
        const val FFP_PROP_FLOAT_PLAYBACK_PITCH = 10008
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * IJK release()的串行线程池。
     * 原来每次 releaseIjk() 都 new Thread().start()，连续快速切歌会同时起多个 release 线程，
     * 互相争抢 IJK 全局锁反而拉长每个 release 的时间。改为单线程串行，一次只释放一个。
     */
    private val releaseExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ktv-ijk-release").apply { isDaemon = true }
    }

    /** 把动作切回主线程执行（已在主线程则直接跑，避免多余延迟）。 */
    private fun postToMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    @Volatile private var ijkPlayer: IjkMediaPlayer? = null
    @Volatile private var targetView: KtvVideoView? = null
    @Volatile private var prepared = false
    @Volatile private var startWhenPrepared = false
    private var preparedListener: MediaPlayer.OnPreparedListener? = null
    private var completionListener: MediaPlayer.OnCompletionListener? = null
    private var errorListener: MediaPlayer.OnErrorListener? = null
    @Volatile private var videoWidth = 0
    @Volatile private var videoHeight = 0
    @Volatile private var toneStep = 0

    /** 切页面时标记宿主 Surface 已销毁，防 IJK 继续渲染到已回收 Surface（黑屏根源）。 */
    @Volatile private var surfaceLost = false

    val isPlaying: Boolean get() = runCatching { ijkPlayer?.isPlaying == true }.getOrDefault(false)
    val currentPosition: Int get() = runCatching {
        (ijkPlayer?.currentPosition ?: 0L)
            .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }.getOrDefault(0)
    val duration: Int get() = runCatching {
        (ijkPlayer?.duration ?: 0L)
            .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }.getOrDefault(0)

    fun setOnPreparedListener(listener: MediaPlayer.OnPreparedListener?) {
        preparedListener = listener
    }

    fun setOnCompletionListener(listener: MediaPlayer.OnCompletionListener?) {
        completionListener = listener
    }

    fun setOnErrorListener(listener: MediaPlayer.OnErrorListener?) {
        errorListener = listener
    }

    fun attach(view: KtvVideoView) {
        targetView = view
        surfaceLost = false
        view.bind(this)
        view.keepScreenOn = true
        view.updateVideoSize(videoWidth, videoHeight)
        view.installSurfaceCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (targetView === view) {
                    Log.i(TAG, "surfaceCreated valid=${holder.surface?.isValid == true} surfaceLost=$surfaceLost")
                    view.markSurfaceRestored()
                    surfaceLost = false
                    // Surface 重建后重新绑定解码输出；若此前因 Surface 丢失暂停过，恢复播放
                    setOutputHolder(holder)
                    if (view.engineWasPausedForSurfaceLoss()) {
                        Log.i(TAG, "surface restored, resuming player")
                        runCatching { ijkPlayer?.start() }
                    }
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (targetView === view) {
                    Log.i(TAG, "surfaceDestroyed wasPlaying=${runCatching { ijkPlayer?.isPlaying == true }.getOrDefault(false)}")
                    // P0 修复：Surface 被回收时若正在解码，IJK 会继续往已回收的
                    // Surface 送帧 → 黑屏或 native crash。立即断开 display 并暂停解码，
                    // 等 surfaceCreated 重建后再 setDisplay + 恢复播放。
                    runCatching { ijkPlayer?.setDisplay(null) }
                    if (runCatching { ijkPlayer?.isPlaying == true }.getOrDefault(false)) {
                        runCatching { ijkPlayer?.pause() }
                        view.markPausedForSurfaceLoss()
                    }
                    view.markSurfaceLost()
                }
            }
        })
        view.currentHolder().takeIf { it.surface?.isValid == true }?.let(::setOutputHolder)
    }

    /** 供宿主切换（movePlayerToHost/exitFullScreenPlayer）时调用：主动暂停并标记。 */
    fun detachForHostChange() {
        val view = targetView ?: return
        if (view.currentHolder().surface?.isValid != true) {
            // 宿主已不可见：Surface 随时会被回收，先暂停防黑屏
            if (runCatching { ijkPlayer?.isPlaying == true }.getOrDefault(false)) {
                runCatching { ijkPlayer?.pause() }
                view.markPausedForSurfaceLoss()
            }
            view.markSurfaceLost()
        }
    }

    fun setVideoUri(uri: Uri) {
        prepared = false
        startWhenPrepared = false
        Log.i(TAG, "setVideoUri uri=$uri surface=${targetView?.currentHolder()?.surface?.isValid == true}")
        val ijk = ensureIjkPlayer()
        if (ijk != null) {
            val firstAttempt = runCatching { prepareIjkPlayer(ijk, uri) }
            if (firstAttempt.isFailure) {
                Log.e(TAG, "IJK prepare failed; recreating player", firstAttempt.exceptionOrNull())
                releaseIjk()
                val retry = ensureIjkPlayer()
                val retryAttempt = retry?.let { runCatching { prepareIjkPlayer(it, uri) } }
                if (retryAttempt == null || retryAttempt.isFailure) {
                    Log.e(
                        TAG,
                        "IJK recreate failed; platform fallback is disabled",
                        retryAttempt?.exceptionOrNull(),
                    )
                    prepared = false
                    errorListener?.onError(null, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
                }
            }
        } else {
            prepared = false
            Log.e(TAG, "IJK unavailable; platform fallback is disabled")
            errorListener?.onError(null, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
        }
    }

    private fun prepareIjkPlayer(player: IjkMediaPlayer, uri: Uri) {
        player.reset()
        configure(player)
        targetView?.currentHolder()?.takeIf { it.surface?.isValid == true }?.let(player::setDisplay)
        val source = if (uri.scheme.equals("file", true)) uri.path.orEmpty() else uri.toString()
        Log.i(TAG, "prepare source=$source surface=${targetView?.currentHolder()?.surface?.isValid == true}")
        player.setDataSource(source)
        player.prepareAsync()
    }

    fun start() {
        if (!prepared) {
            startWhenPrepared = true
            Log.i(TAG, "start deferred until prepared")
            return
        }
        Log.i(TAG, "start prepared player")
        runCatching { ijkPlayer?.start() }
    }

    fun pause() {
        startWhenPrepared = false
        runCatching {
            if (ijkPlayer?.isPlaying == true) ijkPlayer?.pause()
        }
    }

    fun seekTo(positionMs: Int) {
        val target = positionMs.coerceAtLeast(0)
        runCatching {
            ijkPlayer?.seekTo(target.toLong())
        }
    }

    fun stop() {
        startWhenPrepared = false
        prepared = false
        runCatching { ijkPlayer?.stop() }
    }

    fun setVolume(left: Float, right: Float) {
        val safeLeft = left.coerceIn(0f, 1f)
        val safeRight = right.coerceIn(0f, 1f)
        runCatching { ijkPlayer?.setVolume(safeLeft, safeRight) }
    }

    /**
     * IJK 播放器内部 AudioTrack 的会话 id —— [KtvEqualizer] 按会话挂载音效用。
     *
     * 每次 prepare 可能新建 AudioTrack（会话随之变化），调用方需要周期性
     * syncSessions 对齐；取不到返回 0（调用方应忽略 0）。
     */
    fun audioSessionId(): Int = runCatching { ijkPlayer?.audioSessionId ?: 0 }.getOrDefault(0)

    /** Matches the original 32-bit backend: -5..5 steps, 8% per step. */
    fun setTone(step: Int) {
        toneStep = step.coerceIn(-5, 5)
        ijkPlayer?.let(::applyTone)
    }

    private fun applyTone(player: IjkMediaPlayer) {
        val pitch = 1.0f + toneStep * 0.08f
        runCatching {
            val method = IjkMediaPlayer::class.java.getDeclaredMethod(
                "_setPropertyFloat",
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
            )
            method.isAccessible = true
            method.invoke(player, FFP_PROP_FLOAT_PLAYBACK_PITCH, pitch)
            Log.i(TAG, "tone step=$toneStep pitch=$pitch")
        }.onFailure { Log.e(TAG, "Cannot apply tone step=$toneStep", it) }
    }

    fun selectAudioTrack(original: Boolean): Boolean = runCatching {
        val ijk = ijkPlayer
        if (ijk != null) {
            val audioTracks = ijk.trackInfo.indices.filter {
                ijk.trackInfo[it].trackType == ITrackInfo.MEDIA_TRACK_TYPE_AUDIO
            }
            if (audioTracks.size < 2) {
                Log.i(TAG, "audio track switch unavailable tracks=$audioTracks original=$original")
                return@runCatching false
            }
            // 原版在没有明确轨道索引时按“第一条音轨=原唱、第二条音轨=伴唱”回退。
            // trackInfo 还可能含有视频轨，所以使用筛选后的音轨顺序。
            val targetTrack = if (original) audioTracks[0] else audioTracks[1]
            val selectedTrack = ijk.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO)
            if (selectedTrack != targetTrack) {
                ijk.selectTrack(targetTrack)
            }
            Log.i(
                TAG,
                "audio track switch tracks=$audioTracks selected=$selectedTrack target=$targetTrack original=$original",
            )
            return@runCatching true
        }
        false
    }.getOrDefault(false)

    fun audioTrackCount(): Int = runCatching {
        ijkPlayer?.trackInfo?.count { it.trackType == ITrackInfo.MEDIA_TRACK_TYPE_AUDIO } ?: 0
    }.getOrDefault(0)

    fun selectAudioChannel(channel: Int, volume: Float) {
        val safeVolume = volume.coerceIn(0f, 1f)
        val ijk = ijkPlayer
        if (ijk != null) {
            runCatching {
                ijk.setVolume(safeVolume, safeVolume)
                ijk.seletcAudioChannel(channel)
                Log.i(TAG, "audio channel=$channel volume=$safeVolume")
            }.onFailure { Log.e(TAG, "Cannot select audio channel=$channel", it) }
            return
        }
        when (channel) {
            IjkMediaPlayer.AUDIO_CHANNEL_LEFT -> setVolume(safeVolume, 0f)
            IjkMediaPlayer.AUDIO_CHANNEL_RIGHT -> setVolume(0f, safeVolume)
            else -> setVolume(safeVolume, safeVolume)
        }
    }

    fun release() {
        startWhenPrepared = false
        prepared = false
        releaseIjk()
        targetView = null
    }

    private fun ensureIjkPlayer(): IjkMediaPlayer? {
        ijkPlayer?.let { return it }
        return runCatching {
            IjkMediaPlayer().also {
                ijkPlayer = it
                com.fenghuolun.ktv.AppPaths.removeNativeLegacyLogDirectory()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    { com.fenghuolun.ktv.AppPaths.removeNativeLegacyLogDirectory() },
                    1_500L,
                )
            }
        }
            .onFailure { Log.e(TAG, "Cannot create IJK player", it) }
            .getOrNull()
    }

    private fun configure(player: IjkMediaPlayer) {
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 48L)
        // The switch path seeks the newly opened audio stream back to the
        // rendered position, so audio can remain the A/V master without making
        // the MV chase a future packet after a track change.
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "sync-type", 0L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 0L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 0L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "soundtouch", 1L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "overlay-format", IjkMediaPlayer.SDL_FCC_RV16.toLong())
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", 6_291_456L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http-detect-range-support", 0L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzemaxduration", 100L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 1_048_576L)
        // Old KTV MPEG-TS files corrupt reference frames in emulator/device OMX decoders.
        // Keep the whole playback path on IJK's bundled FFmpeg decoder.
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 0L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-all-videos", 0L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 0L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 0L)
        player.setAudioStreamType(AudioManager.STREAM_MUSIC)
        player.setScreenOnWhilePlaying(true)
        applyTone(player)
        player.setOnPreparedListener { preparedPlayer: IMediaPlayer ->
            prepared = true
            videoWidth = preparedPlayer.videoWidth
            videoHeight = preparedPlayer.videoHeight
            // IJK 回调在内部线程，View/SurfaceHolder 只能在主线程改（否则车机随机黑屏/尺寸错乱）
            postToMain { targetView?.updateVideoSize(videoWidth, videoHeight) }
            Log.i(TAG, "onPrepared size=${videoWidth}x$videoHeight deferred=$startWhenPrepared")
            postToMain { preparedListener?.onPrepared(null) }
            if (startWhenPrepared && !preparedPlayer.isPlaying) {
                Log.i(TAG, "starting deferred player")
                runCatching { preparedPlayer.start() }
            }
        }
        player.setOnVideoSizeChangedListener { _, width, height, _, _ ->
            videoWidth = width
            videoHeight = height
            postToMain { targetView?.updateVideoSize(width, height) }
        }
        player.setOnCompletionListener { completionListener?.onCompletion(null) }
        player.setOnErrorListener { _, what, extra ->
            prepared = false
            Log.e(TAG, "onError what=$what extra=$extra")
            errorListener?.onError(null, what, extra) ?: true
        }
    }

    private fun setOutputHolder(holder: SurfaceHolder) {
        Log.i(TAG, "setOutputHolder valid=${holder.surface?.isValid == true}")
        runCatching { ijkPlayer?.setDisplay(holder) }
    }

    private fun releaseIjk() {
        val player = ijkPlayer
        ijkPlayer = null
        // IJK 的 release() 会等待内部线程结束，可能阻塞数秒到数十秒。
        // 若在主线程同步调用，切歌/退出会把主线程堵死触发 ANR（实测卡死25s）。
        // 这里立即置空防复用，实际释放放到串行后台线程（串行化避免多线程争抢 IJK 全局锁）。
        runCatching {
            releaseExecutor.execute {
                runCatching { player?.setDisplay(null) }
                runCatching { player?.release() }
            }
        }
    }
}
