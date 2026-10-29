package com.fenghuolun.ktv

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.concurrent.CopyOnWriteArrayList

class KtvStore {
    private val file = AppPaths.stateFile

    fun stateFile(): File = file

    @JvmField var loopOne = false
    @JvmField var autoNext = true
    @JvmField var originalVocal = false
    @JvmField var vocalChannelMode = "自动"
    @JvmField var musicVolume = 70
    @JvmField var micVolume = 70
    @JvmField var tone = 0
    @JvmField var atmosphere = "标准"
    // ===== 均衡器（v1.2.6 接线 KtvEqualizer 后新增持久化）=====
    @JvmField var eqEnabled = false
    @JvmField var eqPreset = "标准"
    /** 各频段增益 dB，逗号分隔；空 = 未保存过。 */
    @JvmField var eqLevels = ""
    @JvmField var eqGlobalMix = false
    @JvmField var singMode = "普通演唱"
    @JvmField var recordEnabled = true
    @JvmField var scoreEnabled = true
    @JvmField var lastScore = 0
    @JvmField var lastScoreSong = ""
    @JvmField var marqueeEnabled = true
    @JvmField var marqueeText = "欢迎使用风火轮"
    @JvmField var playWhileDownloading = false
    @JvmField var clearDownloadsOnBoot = false
    @JvmField var autoFullscreenSeconds = 0
    @JvmField var showUsbSongs = true
    @JvmField var autoDeleteSongs = true
    @JvmField var reserveStorageGb = 1.0
    @JvmField var floatingButtonEnabled = true
    @JvmField var songTitleSubtitleEnabled = true
    @JvmField var orderedSongOriginal = false
    @JvmField var orderedVolumeMode = 0
    @JvmField var orderedVocalMode = 0
    @JvmField var voiceEngineEnabled = true
    @JvmField var voiceEngineVolume = 70
    @JvmField var lightMode = "自动"
    @JvmField var audioDelayMs = 0
    @JvmField var screenBrightness = 80
    @JvmField var screenMode = "适应屏幕"
    @JvmField var doubleScreenEnabled = false
    @JvmField var tableBroadcastEnabled = true
    @JvmField var tableBroadcastSeconds = 8
    @JvmField var tableBroadcastText = "欢迎光临"
    /**
     * 已点队列。
     *
     * 用 CopyOnWriteArrayList 而不是 mutableListOf：这份数据同时被 UI 主线程、下载进度回调、
     * 以及 LocalRemoteServer 的 HTTP 工作线程读写（扫码点歌会从线程池遍历它生成 JSON，
     * 同时主线程在 addToQueue/remove），裸 ArrayList 并发遍历会抛 ConcurrentModificationException，
     * 车机上表现为进"已点"页随机闪退。
     * 写少读多（一次点歌写一次、列表/JSON 读很多次）正是 COWAL 的适用场景。
     */
    @JvmField val orderQueue = CopyOnWriteArrayList<Song>()

    /** 已唱历史，同样是主线程 + 远程回调线程共享（COWAL，见 orderQueue 注释）。 */
    @JvmField val sangHistory = CopyOnWriteArrayList<Song>()
    @JvmField val scoreHistory = CopyOnWriteArrayList<String>()
    @JvmField val broadcastHistory = CopyOnWriteArrayList<String>()
    @JvmField val favoriteIds = LinkedHashSet<String>()
    @JvmField val hiddenSongIds = LinkedHashSet<String>()
    @JvmField val playlists = mutableListOf<String>()
    @JvmField val playlistSongs = LinkedHashMap<String, MutableSet<String>>()

    fun load() {
        runCatching {
            if (!file.exists()) return@runCatching
            val root = JSONObject(file.readText())
            loopOne = root.optBoolean("loopOne", false)
            autoNext = root.optBoolean("autoNext", true)
            originalVocal = root.optBoolean("originalVocal", false)
            vocalChannelMode = root.optString("vocalChannelMode", "自动")
            musicVolume = root.optInt("musicVolume", 70)
            micVolume = root.optInt("micVolume", 70)
            tone = root.optInt("tone", 0)
            atmosphere = root.optString("atmosphere", "标准")
            eqEnabled = root.optBoolean("eqEnabled", false)
            eqPreset = root.optString("eqPreset", "标准")
            eqLevels = root.optString("eqLevels", "")
            eqGlobalMix = root.optBoolean("eqGlobalMix", false)
            singMode = root.optString("singMode", "普通演唱")
            recordEnabled = root.optBoolean("recordEnabled", true)
            scoreEnabled = root.optBoolean("scoreEnabled", true)
            lastScore = root.optInt("lastScore", 0)
            lastScoreSong = root.optString("lastScoreSong", "")
            marqueeEnabled = root.optBoolean("marqueeEnabled", true)
            marqueeText = root.optString("marqueeText", "欢迎使用风火轮")
            playWhileDownloading = root.optBoolean("playWhileDownloading", false)
            clearDownloadsOnBoot = root.optBoolean("clearDownloadsOnBoot", false)
            autoFullscreenSeconds = root.optInt("autoFullscreenSeconds", 0)
            showUsbSongs = root.optBoolean("showUsbSongs", true)
            autoDeleteSongs = root.optBoolean("autoDeleteSongs", true)
            reserveStorageGb = root.optDouble("reserveStorageGb", 1.0).coerceIn(0.5, 64.0)
            floatingButtonEnabled = root.optBoolean("floatingButtonEnabled", true)
            songTitleSubtitleEnabled = root.optBoolean("songTitleSubtitleEnabled", true)
            orderedSongOriginal = root.optBoolean("orderedSongOriginal", false)
            orderedVolumeMode = root.optInt("orderedVolumeMode", 0)
            orderedVocalMode = root.optInt("orderedVocalMode", 0)
            voiceEngineEnabled = root.optBoolean("voiceEngineEnabled", true)
            voiceEngineVolume = root.optInt("voiceEngineVolume", 70)
            lightMode = root.optString("lightMode", "自动")
            audioDelayMs = root.optInt("audioDelayMs", 0)
            screenBrightness = root.optInt("screenBrightness", 80)
            screenMode = root.optString("screenMode", "适应屏幕")
            doubleScreenEnabled = root.optBoolean("doubleScreenEnabled", false)
            tableBroadcastEnabled = root.optBoolean("tableBroadcastEnabled", true)
            tableBroadcastSeconds = root.optInt("tableBroadcastSeconds", 8)
            tableBroadcastText = root.optString("tableBroadcastText", "欢迎光临")
            readSongs(root.optJSONArray("orderQueue"), orderQueue)
            readSongs(root.optJSONArray("sangHistory"), sangHistory)
            root.optJSONArray("scoreHistory").copyStringsTo(scoreHistory)
            root.optJSONArray("broadcastHistory").copyStringsTo(broadcastHistory)
            root.optJSONArray("favoriteIds").copyStringsTo(favoriteIds)
            root.optJSONArray("hiddenSongIds").copyStringsTo(hiddenSongIds)
            root.optJSONArray("playlists").copyStringsTo(playlists)
            root.optJSONObject("playlistSongs")?.let { map ->
                map.names()?.let { names ->
                    repeat(names.length()) {
                        val name = names.optString(it)
                        val values = LinkedHashSet<String>()
                        map.optJSONArray(name).copyStringsTo(values)
                        playlistSongs[name] = values
                    }
                }
            }
        }
        ensureDefaultPlaylists()
    }

    fun save() {
        writeSnapshot(snapshotJson())
    }

    fun snapshotJson(): String = runCatching {
            val root = JSONObject().apply {
                put("loopOne", loopOne)
                put("autoNext", autoNext)
                put("originalVocal", originalVocal)
                put("vocalChannelMode", vocalChannelMode)
                put("musicVolume", musicVolume)
                put("micVolume", micVolume)
                put("tone", tone)
                put("atmosphere", atmosphere)
                put("eqEnabled", eqEnabled)
                put("eqPreset", eqPreset)
                put("eqLevels", eqLevels)
                put("eqGlobalMix", eqGlobalMix)
                put("singMode", singMode)
                put("recordEnabled", recordEnabled)
                put("scoreEnabled", scoreEnabled)
                put("lastScore", lastScore)
                put("lastScoreSong", lastScoreSong)
                put("marqueeEnabled", marqueeEnabled)
                put("marqueeText", marqueeText)
                put("playWhileDownloading", playWhileDownloading)
                put("clearDownloadsOnBoot", clearDownloadsOnBoot)
                put("autoFullscreenSeconds", autoFullscreenSeconds)
                put("showUsbSongs", showUsbSongs)
                put("autoDeleteSongs", autoDeleteSongs)
                put("reserveStorageGb", reserveStorageGb)
                put("floatingButtonEnabled", floatingButtonEnabled)
                put("songTitleSubtitleEnabled", songTitleSubtitleEnabled)
                put("orderedSongOriginal", orderedSongOriginal)
                put("orderedVolumeMode", orderedVolumeMode)
                put("orderedVocalMode", orderedVocalMode)
                put("voiceEngineEnabled", voiceEngineEnabled)
                put("voiceEngineVolume", voiceEngineVolume)
                put("lightMode", lightMode)
                put("audioDelayMs", audioDelayMs)
                put("screenBrightness", screenBrightness)
                put("screenMode", screenMode)
                put("doubleScreenEnabled", doubleScreenEnabled)
                put("tableBroadcastEnabled", tableBroadcastEnabled)
                put("tableBroadcastSeconds", tableBroadcastSeconds)
                put("tableBroadcastText", tableBroadcastText)
                put("orderQueue", writeSongs(orderQueue))
                put("sangHistory", writeSongs(sangHistory))
                put("scoreHistory", JSONArray(scoreHistory.take(100)))
                put("broadcastHistory", JSONArray(broadcastHistory.take(100)))
                put("favoriteIds", JSONArray(favoriteIds.toList()))
                put("hiddenSongIds", JSONArray(hiddenSongIds.toList()))
                put("playlists", JSONArray(playlists))
                put("playlistSongs", JSONObject().apply {
                    playlistSongs.forEach { (name, ids) -> put(name, JSONArray(ids.toList())) }
                })
            }
            root.toString(2)
        }.getOrDefault("{}")

    fun writeSnapshot(snapshot: String) {
        runCatching {
            file.parentFile?.mkdirs()
            // 原子写：先写临时文件再 renameTo。直接 writeText 是 truncate+write，
            // 中途崩溃/被杀会留下截断的半截 JSON —— load() 读它时 parse 失败，
            // 全部设置回退默认值（歌单清空、音量变 70）。rename 是原子的，要么全有要么全无。
            val tmp = java.io.File(
                file.parentFile,
                "${file.name}.${java.util.concurrent.ThreadLocalRandom.current().nextLong()}.tmp"
            )
            tmp.writeText(snapshot)
            if (!tmp.renameTo(file)) {
                // 同盘 rename 理论上不会失败；失败就回退直接写，保证不丢状态
                file.writeText(snapshot)
            }
            runCatching { tmp.delete() }
        }
    }

    fun toggleFavorite(song: Song): Boolean = toggleId(favoriteIds, stableId(song))

    fun isFavorite(song: Song): Boolean = stableId(song) in favoriteIds

    fun togglePlaylistSong(playlist: String, song: Song): Boolean = toggleId(songsInPlaylist(playlist), stableId(song))

    fun isInPlaylist(playlist: String, song: Song): Boolean = stableId(song) in songsInPlaylist(playlist)

    fun songsInPlaylist(playlist: String): MutableSet<String> = playlistSongs.getOrPut(playlist) { LinkedHashSet() }

    private fun ensureDefaultPlaylists() {
        listOf("我的收藏", "常唱歌曲").forEach { if (it !in playlists) playlists += it }
        playlists.forEach(::songsInPlaylist)
    }

    private fun toggleId(ids: MutableSet<String>, id: String): Boolean = if (ids.remove(id)) false else ids.add(id)

    companion object {
        @JvmStatic
        fun stableId(song: Song): String = song.id?.takeIf(String::isNotEmpty)
            ?: song.path?.takeIf(String::isNotEmpty)
            ?: "${song.title}|${song.singer}"

        private fun readSongs(array: JSONArray?, out: MutableList<Song>) {
            out.clear()
            if (array == null) return
            repeat(array.length()) { out += Song.fromJson(array.getJSONObject(it)) }
        }

        private fun writeSongs(songs: List<Song>): JSONArray = JSONArray().apply {
            songs.take(200).forEach { put(it.toJson()) }
        }

        private fun JSONArray?.copyStringsTo(out: MutableCollection<String>) {
            if (this == null) return
            repeat(length()) { out += optString(it) }
        }
    }
}
