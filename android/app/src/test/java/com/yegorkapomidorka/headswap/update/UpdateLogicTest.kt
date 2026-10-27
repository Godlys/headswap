package com.yegorkapomidorka.headswap.update

import com.yegorkapomidorka.headswap.update.UpdateLogic.Asset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {
    @Test
    fun parsesVersionsWithPrefixAndSuffix() {
        assertEquals(listOf(1, 1, 10), UpdateLogic.parseVersion("1.1.10"))
        assertEquals(listOf(1, 1, 10), UpdateLogic.parseVersion("v1.1.10"))
        assertEquals(listOf(1, 1, 10), UpdateLogic.parseVersion("1.1.10-cx5test1"))
        assertEquals(listOf(2), UpdateLogic.parseVersion("2"))
        assertNull(UpdateLogic.parseVersion("?"))
        assertNull(UpdateLogic.parseVersion(""))
    }

    @Test
    fun comparesNumericallyNotAsStrings() {
        assertTrue(UpdateLogic.isNewer("1.1.10", "1.1.9"))
        assertFalse(UpdateLogic.isNewer("1.1.9", "1.1.10"))
        assertTrue(UpdateLogic.isNewer("1.2", "1.1.10"))
        assertTrue(UpdateLogic.isNewer("2.0.0", "1.9.9"))
        assertFalse(UpdateLogic.isNewer("1.1.10", "1.1.10"))
        assertFalse(UpdateLogic.isNewer("1.2", "1.2.0"))
        assertTrue(UpdateLogic.isNewer("1.2.0.1", "1.2"))
    }

    @Test
    fun testBuildSuffixIsIgnored() {
        // 测试构建 1.1.10-cx5test1 不把 1.1.10 当作更新，而 1.1.11 会被当作更新
        assertFalse(UpdateLogic.isNewer("1.1.10", "1.1.10-cx5test1"))
        assertTrue(UpdateLogic.isNewer("1.1.11", "1.1.10-cx5test1"))
    }

    @Test
    fun unparsableVersionIsNeverNewer() {
        assertFalse(UpdateLogic.isNewer("latest", "1.1.10"))
        assertFalse(UpdateLogic.isNewer("1.1.11", "?"))
    }

    @Test
    fun versionFromTag() {
        assertEquals("1.1.10", UpdateLogic.versionFromTag("v1.1.10"))
        assertEquals("1.1.10", UpdateLogic.versionFromTag("1.1.10"))
    }

    @Test
    fun picksApkByNameThenSingleApk() {
        val apk = Asset("headswap-1.1.11.apk", "https://x/a.apk", 3_000_000, "sha256:00")
        val other = Asset("headswap-1.1.11.apk.sha256", "https://x/a.sha", 64, null)
        assertEquals(apk, UpdateLogic.pickApk(listOf(other, apk), "1.1.11"))
        val renamed = Asset("Headswap.apk", "https://x/h.apk", 3_000_000, null)
        assertEquals(renamed, UpdateLogic.pickApk(listOf(renamed, other), "1.1.11"))
        assertNull(UpdateLogic.pickApk(listOf(other), "1.1.11"))
        // 两个 APK 都不是目标文件名：该装哪个，说不清
        assertNull(UpdateLogic.pickApk(listOf(renamed, renamed.copy(name = "debug.apk")), "1.1.11"))
    }

    @Test
    fun sha256FromGithubDigest() {
        val hex = "6A29D0035349124C850A1FFFDB676161987C047E2B7DE78F31F41A2390943E11"
        assertEquals(hex.lowercase(), UpdateLogic.sha256("sha256:$hex"))
        assertNull(UpdateLogic.sha256(null))
        assertNull(UpdateLogic.sha256("md5:abc"))
        assertNull(UpdateLogic.sha256("sha256:abc"))
    }

    @Test
    fun tagFromRedirectLocation() {
        assertEquals("v1.1.11", UpdateLogic.tagFromLocation("https://github.com/YegorkaPomidorka/headswap/releases/tag/v1.1.11"))
        assertNull(UpdateLogic.tagFromLocation("https://github.com/YegorkaPomidorka/headswap/releases"))
        assertNull(UpdateLogic.tagFromLocation(null))
        assertEquals(
            "https://github.com/YegorkaPomidorka/headswap/releases/download/v1.1.11/headswap-1.1.11.apk",
            UpdateLogic.assetUrl("v1.1.11", "1.1.11"),
        )
    }

    @Test
    fun notesAreTrimmedAndCapped() {
        assertEquals("a\n\nb", UpdateLogic.notes("\r\n a\r\n\r\n\r\n\r\nb \r\n"))
        assertEquals("", UpdateLogic.notes(null))
        val long = UpdateLogic.notes("x".repeat(5000))
        assertTrue(long.length <= 1501)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun checksAtMostEverySixHours() {
        val h = 3600_000L
        val last = 100 * h
        assertFalse(UpdateLogic.shouldCheck(false, 0, last))
        assertTrue(UpdateLogic.shouldCheck(true, 0, last))
        assertFalse(UpdateLogic.shouldCheck(true, last, last + 5 * h))
        assertTrue(UpdateLogic.shouldCheck(true, last, last + 6 * h))
        // 时钟被回拨：不要从“未来”的时间戳起再等 6 小时
        assertTrue(UpdateLogic.shouldCheck(true, last, last - h))
    }

    @Test
    fun failedCheckRetriesInAnHour() {
        // Updater 出错后写入 lastCheck = now - CHECK_INTERVAL_MS + RETRY_MS
        val now = 1_000_000_000L
        val last = now - UpdateLogic.CHECK_INTERVAL_MS + UpdateLogic.RETRY_MS
        assertFalse(UpdateLogic.shouldCheck(true, last, now + UpdateLogic.RETRY_MS - 1))
        assertTrue(UpdateLogic.shouldCheck(true, last, now + UpdateLogic.RETRY_MS))
    }
}
