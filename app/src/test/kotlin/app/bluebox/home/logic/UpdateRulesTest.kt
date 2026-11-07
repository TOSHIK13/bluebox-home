package app.bluebox.home.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateRulesTest {
    @Test fun `parse tags`() {
        assertEquals(Version(0, 2, 1, null), UpdateRules.parse("v0.2.1"))
        assertEquals(Version(1, 0, 0, "beta.1"), UpdateRules.parse("1.0.0-beta.1"))
        assertNull(UpdateRules.parse("latest"))
        assertNull(UpdateRules.parse("1.2"))
        assertNull(UpdateRules.parse(null))
    }

    @Test fun `newer compares numbers not text`() {
        assertTrue(UpdateRules.isNewer("0.1.0", "v0.2.0"))
        assertTrue(UpdateRules.isNewer("0.9.0", "0.10.0"))
        assertFalse(UpdateRules.isNewer("0.2.0", "v0.2.0"))
        assertFalse(UpdateRules.isNewer("0.3.0", "v0.2.9"))
        assertFalse(UpdateRules.isNewer("0.1.0", "garbage"))
        assertFalse(UpdateRules.isNewer("garbage", "0.2.0"))
    }

    @Test fun `prerelease is older than its release and orders by identifier`() {
        assertTrue(UpdateRules.isNewer("0.3.0-beta.1", "0.3.0"))
        assertFalse(UpdateRules.isNewer("0.3.0", "0.3.0-beta.1"))
        assertTrue(UpdateRules.isNewer("0.3.0-beta.2", "0.3.0-beta.10"))
        assertTrue(UpdateRules.isNewer("0.3.0-beta.1", "0.3.0-rc.1"))
    }

    @Test fun `version code from tag`() {
        assertEquals(201, UpdateRules.versionCode("v0.2.1"))
        assertEquals(10203, UpdateRules.versionCode("1.2.3"))
        assertNull(UpdateRules.versionCode("x"))
    }

    @Test fun `picks apk and its checksum`() {
        val a = listOf(
            ReleaseAsset("notes.txt", "u0", 1),
            ReleaseAsset("bluebox-home-0.2.1.apk.sha256", "u2", 80),
            ReleaseAsset("bluebox-home-0.2.1.apk", "u1", 5_000_000),
        )
        val apk = UpdateRules.pickApk(a)
        assertEquals("u1", apk?.url)
        assertEquals("u2", UpdateRules.pickSha(a, apk!!)?.url)
        assertNull(UpdateRules.pickApk(listOf(ReleaseAsset("other.apk", "x", 1))))
        assertNull(UpdateRules.pickSha(a.take(1) + a.drop(2), apk))
    }

    @Test fun `checksum parsing`() {
        val h = "A".repeat(64)
        assertEquals("a".repeat(64), UpdateRules.parseSha256("$h  bluebox-home-0.2.1.apk\n"))
        assertNull(UpdateRules.parseSha256("not a hash"))
        assertNull(UpdateRules.parseSha256("a".repeat(63)))
    }

    @Test fun `check at most once a day`() {
        val day = UpdateRules.CHECK_INTERVAL_MS
        assertTrue(UpdateRules.shouldCheck(0, 1000))
        assertFalse(UpdateRules.shouldCheck(1000, 1000 + day - 1))
        assertTrue(UpdateRules.shouldCheck(1000, 1000 + day))
        assertTrue(UpdateRules.shouldCheck(5000, 100)) // clock went back
    }

    @Test fun `notes are cut`() {
        assertEquals("abc", UpdateRules.shortNotes("  abc \r\n"))
        assertEquals("abc…", UpdateRules.shortNotes("abcdef", 3))
    }
}
