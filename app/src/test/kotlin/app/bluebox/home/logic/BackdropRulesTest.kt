package app.bluebox.home.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BackdropRulesTest {
    private fun entry(poster: String?, thumb: String? = null) =
        WatchEntry(1, "t", null, poster, "x://y", 0, 0, 0, null, thumb)

    @Test fun tmdbPosterIsRewrittenToW780() =
        assertEquals("https://image.tmdb.org/t/p/w780/a.jpg", BackdropRules.pick(entry("https://image.tmdb.org/t/p/w342/a.jpg")))

    @Test fun originalIsRewrittenToo() =
        assertEquals("https://image.tmdb.org/t/p/w780/a.jpg", BackdropRules.pick(entry("https://image.tmdb.org/t/p/original/a.jpg")))

    @Test fun thumbnailWinsOverPoster() =
        assertEquals("https://x/b.jpg", BackdropRules.pick(entry("https://x/p.jpg", "https://x/b.jpg")))

    @Test fun foreignUriStaysAsIs() =
        assertEquals("content://app/p/1", BackdropRules.pick(entry("content://app/p/1")))

    @Test fun nothingToShow() = assertNull(BackdropRules.pick(entry(null, " ")))
}
