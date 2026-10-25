package com.fenghuolun.ktv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 下载进度条"原地更新"契约测试。
 *
 * 背景：下载中每 250ms 一次进度回调，原来走 loadDownloadedList() → renderSongList() →
 * notifyDataSetChanged() 整页重建。现改为给进度条打 tag（SongListAdapter.progressTag），
 * MainActivity.refreshVisibleProgressCells() 按 tag 定位可见进度条原地 setProgress。
 *
 * 最容易出的错是两边 key 规则不一致（tag 拼错 → 永远定位不到 → 进度条不动），
 * 这个测试就是把那个契约钉住。
 */
public class SongListProgressTagTest {

    private static Song song(String id, String filename, String title, String singer) {
        Song song = new Song();
        song.id = id;
        song.filename = filename;
        song.title = title;
        song.singer = singer;
        return song;
    }

    @Test
    public void progressTag_usesSongKeyAndPrefix() {
        Song song = song("12345", null, "稻香", "周杰伦");
        String tag = SongListAdapter.progressTag(song);
        assertTrue("tag 必须带前缀，供 findViewWithTag 过滤", tag.startsWith(SongListAdapter.PROGRESS_TAG_PREFIX));
        assertEquals(SongListAdapter.PROGRESS_TAG_PREFIX + "12345", tag);
    }

    @Test
    public void progressTag_isStableForSameSong() {
        Song a = song("777", null, "晴天", "周杰伦");
        Song b = song("777", null, "晴天", "周杰伦");
        // 同一首歌每次 getView 重建，tag 必须一致，否则旧行定位不到
        assertEquals(SongListAdapter.progressTag(a), SongListAdapter.progressTag(b));
    }

    @Test
    public void progressTag_differsAcrossSongs() {
        Song a = song("1", null, "稻香", "周杰伦");
        Song b = song("2", null, "青花瓷", "周杰伦");
        assertNotEquals(SongListAdapter.progressTag(a), SongListAdapter.progressTag(b));
    }

    @Test
    public void progressTag_fallsBackToTitleSingerWhenNoId() {
        // songKey 的兜底顺序：id → dbId → filename → "title|singer"
        Song noId = song(null, null, "七里香", "周杰伦");
        String tag = SongListAdapter.progressTag(noId);
        assertEquals(SongListAdapter.PROGRESS_TAG_PREFIX + "七里香|周杰伦", tag);
    }

    @Test
    public void progressTag_prefersFilenameOverTitleSinger() {
        Song withFile = song(null, "qlx.ts", "七里香", "周杰伦");
        assertEquals(SongListAdapter.PROGRESS_TAG_PREFIX + "qlx.ts", SongListAdapter.progressTag(withFile));
    }

    @Test
    public void progressTag_extractKeyRoundTrips() {
        // MainActivity 侧从 tag 反解 key 再和 downloads 里的歌比对，必须能对上
        Song song = song("9988", null, "夜曲", "周杰伦");
        String tag = SongListAdapter.progressTag(song);
        String extracted = tag.substring(SongListAdapter.PROGRESS_TAG_PREFIX.length());
        assertEquals(SongListAdapter.songKey(song), extracted);
    }

    @Test
    public void progressTag_handlesNullSingerGracefully() {
        Song song = song("42", null, "无名歌", null);
        // 不能抛 NPE；singer 为 null 时 title 分支不生效，仍走 id
        assertEquals(SongListAdapter.PROGRESS_TAG_PREFIX + "42", SongListAdapter.progressTag(song));
    }
}
