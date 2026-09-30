package com.zekikoese

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `compares versions semantically`() {
        assertTrue(UpdateChecker.isNewer("1.9.0", "1.8.0"))
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.2"))
        assertTrue(UpdateChecker.isNewer("2.0", "1.99.99"))
        assertFalse(UpdateChecker.isNewer("1.9.0", "1.9.0"))
        assertFalse(UpdateChecker.isNewer("1.8.9", "1.9.0"))
        assertFalse(UpdateChecker.isNewer("1.9.0-beta", "1.9.0"))
    }

    @Test
    fun `parses latest release with apk asset`() {
        val json = """{
            "tag_name": "v1.9.0", "draft": false, "prerelease": false, "body": "Neu: Timeshift",
            "assets": [
                {"name": "notes.txt", "browser_download_url": "https://x/notes.txt", "size": 10},
                {"name": "ZekIPTV-1.9.0.apk", "browser_download_url": "https://x/ZekIPTV-1.9.0.apk", "size": 9000000}
            ]
        }"""
        val release = UpdateChecker.parseRelease(json)!!
        assertEquals("1.9.0", release.version)
        assertEquals("https://x/ZekIPTV-1.9.0.apk", release.apkUrl)
        assertEquals(9_000_000L, release.apkSize)
        assertEquals("Neu: Timeshift", release.notes)
    }

    @Test
    fun `ignores releases without apk and prereleases`() {
        assertNull(UpdateChecker.parseRelease("""{"tag_name":"v2.0.0","assets":[]}"""))
        assertNull(UpdateChecker.parseRelease("""{"tag_name":"v2.0.0","prerelease":true,
            "assets":[{"name":"a.apk","browser_download_url":"u","size":1}]}"""))
    }

    @Test
    fun `no published release counts as up to date`() {
        assertNull(UpdateChecker.fetchLatest { throw java.io.IOException("HTTP 404") })
    }
}
