package app.bluebox.home.logic

/** Backdrop of the focused card: which image to fetch and how long focus must rest before we fetch it. Pure JVM. */
object BackdropRules {
    const val DELAY_MS = 400L
    const val TMDB_SIZE = "w780"
    private val tmdbSize = Regex("(/t/p/)(w\\d+|original)(/)")

    /** Prefers the wide thumbnail; a TMDB poster/backdrop URL is rewritten to w780, other URIs stay as they are. */
    fun pick(e: WatchEntry): String? {
        val raw = e.thumbUri?.takeIf { it.isNotBlank() } ?: e.posterUri?.takeIf { it.isNotBlank() } ?: return null
        return tmdbSize.replace(raw) { "${it.groupValues[1]}$TMDB_SIZE${it.groupValues[3]}" }
    }
}
