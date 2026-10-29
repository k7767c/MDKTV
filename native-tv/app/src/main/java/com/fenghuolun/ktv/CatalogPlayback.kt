package com.fenghuolun.ktv

import java.net.URI

/** Explicit HTTP media from an imported catalog can play without a persistent song download. */
object CatalogPlayback {
    fun directStreamUrl(remoteCatalog: Boolean, videoUrl: String?): String? {
        if (!remoteCatalog) return null
        val value = videoUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        return value.takeIf {
            uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
        }
    }
}
