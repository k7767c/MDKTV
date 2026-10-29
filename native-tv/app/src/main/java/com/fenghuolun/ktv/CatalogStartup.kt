package com.fenghuolun.ktv

/** Loading an optional catalog must never force a network download during startup. */
object CatalogStartup {
    data class Outcome(val available: Boolean, val error: Throwable? = null)

    fun open(
        downloadRequested: Boolean,
        openLocal: () -> Boolean,
        download: () -> Result<Unit>,
    ): Outcome {
        if (!downloadRequested) return Outcome(openLocal())
        val result = download()
        val available = openLocal()
        val error = result.exceptionOrNull()
            ?: if (!available) IllegalStateException("下载后仍无法打开曲库，可继续使用本地或网络曲库") else null
        return Outcome(available, error)
    }
}
