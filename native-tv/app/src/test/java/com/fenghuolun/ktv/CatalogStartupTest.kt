package com.fenghuolun.ktv

import org.junit.Assert.*
import org.junit.Test

class CatalogStartupTest {
    @Test fun missingCatalogDoesNotDownloadOrBlockEntry() {
        val result = CatalogStartup.open(false, { false }, { error("Startup must not download") })
        assertFalse(result.available)
        assertNull(result.error)
    }

    @Test fun existingCatalogIsUsedWithoutCheckingNetwork() {
        val result = CatalogStartup.open(false, { true }, { error("Offline catalog must work") })
        assertTrue(result.available)
    }

    @Test fun explicitDownloadOpensNewCatalogAfterDownload() {
        var installed = false
        val result = CatalogStartup.open(true, { installed }, { installed = true; Result.success(Unit) })
        assertTrue(result.available)
        assertNull(result.error)
    }

    @Test fun failedOptionalDownloadKeepsExistingCatalog() {
        val noSpace = IllegalStateException("ENOSPC")
        val result = CatalogStartup.open(true, { true }, { Result.failure(noSpace) })
        assertTrue(result.available)
        assertSame(noSpace, result.error)
    }

    @Test fun failedFirstDownloadAllowsOperationWithoutCatalog() {
        val noSpace = IllegalStateException("ENOSPC")
        val result = CatalogStartup.open(true, { false }, { Result.failure(noSpace) })
        assertFalse(result.available)
        assertSame(noSpace, result.error)
    }

    @Test fun downloadedButUnreadableCatalogIsReportedWithoutBlockingEntry() {
        val result = CatalogStartup.open(true, { false }, { Result.success(Unit) })
        assertFalse(result.available)
        assertNotNull(result.error)
    }
}
