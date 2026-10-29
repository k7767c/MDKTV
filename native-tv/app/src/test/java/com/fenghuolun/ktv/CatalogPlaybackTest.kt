package com.fenghuolun.ktv

import org.junit.Assert.*
import org.junit.Test

class CatalogPlaybackTest {
    @Test fun importedNasMediaCanStreamWithoutDownload() {
        val url = "http://192.168.1.20:8080/media/song.mp4"
        assertEquals(url, CatalogPlayback.directStreamUrl(true, url))
    }
    @Test fun signedHttpsMediaRetainsQueryParameters() {
        val url = "https://media.example.test/song.m3u8?token=fixture&expires=123"
        assertEquals(url, CatalogPlayback.directStreamUrl(true, url))
    }
    @Test fun databaseSongsKeepTheirExistingPlaybackRoute() {
        assertNull(CatalogPlayback.directStreamUrl(false, "https://media.example.test/song.ts"))
    }
    @Test fun localAndUnsupportedProtocolsAreNotRemoteStreams() {
        for (url in listOf("file:///sdcard/song.mp4", "content://media/1", "javascript:alert(1)", "https:///song", "not a URL", "")) {
            assertNull(url, CatalogPlayback.directStreamUrl(true, url))
        }
        assertNull(CatalogPlayback.directStreamUrl(true, null))
    }
    @Test fun urlWithEmbeddedCredentialsIsRejected() {
        assertNull(CatalogPlayback.directStreamUrl(true, "https://user:password@media.example.test/song.mp4"))
    }
}
