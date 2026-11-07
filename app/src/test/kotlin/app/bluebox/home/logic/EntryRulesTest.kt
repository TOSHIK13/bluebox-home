package app.bluebox.home.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EntryRulesTest {
    private val now = 1_000_000_000_000L

    private fun e(
        id: Long, uri: String? = "bluebox://w/$id", title: String = "T$id", pos: Long = 0, dur: Long = 0, at: Long = now - id,
    ) = WatchEntry(id, title, null, null, uri, pos, dur, at, "pkg")

    @Test fun `most recent first and limited`() {
        val r = EntryRules.select((1L..12L).map { e(it) }, now, limit = 3)
        assertEquals(listOf(1L, 2L, 3L), r.map { it.id })
    }

    @Test fun `rows without intent or title are dropped`() {
        val r = EntryRules.select(listOf(e(1, uri = null), e(2, uri = " "), e(3, title = ""), e(4)), now)
        assertEquals(listOf(4L), r.map { it.id })
    }

    @Test fun `stale and finished rows are dropped`() {
        val old = e(1, at = now - EntryRules.MAX_AGE_MS - 1)
        val done = e(2, pos = 98, dur = 100)
        val ok = e(3, pos = 50, dur = 100)
        assertEquals(listOf(3L), EntryRules.select(listOf(old, done, ok), now).map { it.id })
    }

    @Test fun `duplicate intents collapse to the newest`() {
        val a = e(1, uri = "x", at = now - 10)
        val b = e(2, uri = "x", at = now - 5)
        assertEquals(listOf(2L), EntryRules.select(listOf(a, b), now).map { it.id })
    }

    @Test fun `progress and remaining`() {
        val x = e(1, pos = 30 * 60_000, dur = 120 * 60_000)
        assertEquals(25, EntryRules.progressPct(x))
        assertEquals(90, EntryRules.remainingMinutes(x))
        assertEquals(0, EntryRules.progressPct(e(2)))
        assertNull(EntryRules.remainingMinutes(e(2)))
    }

    @Test fun `clock formatting`() {
        assertEquals("1:05", EntryRules.clock(65_000))
        assertEquals("1:01:01", EntryRules.clock(3_661_000))
        assertEquals("0:00", EntryRules.clock(-5))
    }

    @Test fun `episode tag from season and episode`() {
        assertEquals(EpisodeTag("2", "5", false), EntryRules.episodeTag(e(1).copy(season = "2", episode = "5")))
        assertEquals(EpisodeTag(null, "7", false), EntryRules.episodeTag(e(1).copy(season = "0", episode = "7")))
        assertEquals(EpisodeTag(null, null, true), EntryRules.episodeTag(e(1).copy(contentType = EntryRules.TYPE_MOVIE)))
        assertNull(EntryRules.episodeTag(e(1)))
    }

    @Test fun `new episode is flagged until started`() {
        assertEquals(true, EntryRules.isNew(e(1).copy(watchNextType = EntryRules.WN_NEXT)))
        assertEquals(true, EntryRules.isNew(e(1).copy(watchNextType = EntryRules.WN_NEW)))
        assertEquals(false, EntryRules.isNew(e(1, pos = 60_000, dur = 1_200_000).copy(watchNextType = EntryRules.WN_NEXT)))
        assertEquals(false, EntryRules.isNew(e(1).copy(watchNextType = 0)))
        assertEquals(false, EntryRules.isNew(e(1)))
    }
}
