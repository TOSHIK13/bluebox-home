package app.bluebox.home.logic

/** A "what to watch" row as the system TV provider hands it over (Watch Next). Pure data, no Android types. */
data class WatchEntry(
    val id: Long,
    val title: String,
    val subtitle: String?,
    val posterUri: String?,
    val intentUri: String?,
    val positionMs: Long,
    val durationMs: Long,
    val lastEngagementMs: Long,
    val sourcePackage: String?,
    val thumbUri: String? = null,
)

/** Selection and wording rules for the rows; kept free of Android so they run as plain JVM unit tests. */
object EntryRules {
    const val MAX_AGE_MS = 180L * 24 * 60 * 60 * 1000 // rows older than ~six months are stale
    const val HIDE_AT_PCT = 97 // provider rows linger after the end: do not offer "continue" at 99%
    const val DEFAULT_LIMIT = 8

    fun progressPct(e: WatchEntry): Int =
        if (e.durationMs <= 0 || e.positionMs <= 0) 0 else (e.positionMs * 100 / e.durationMs).toInt().coerceIn(0, 100)

    fun remainingMinutes(e: WatchEntry): Int? =
        if (e.durationMs <= 0 || e.positionMs <= 0) null else ((e.durationMs - e.positionMs).coerceAtLeast(0) / 60_000).toInt()

    /** Most recent first; no row without a way to open it, stale, finished or duplicated rows. */
    fun select(all: List<WatchEntry>, nowMs: Long, limit: Int = DEFAULT_LIMIT): List<WatchEntry> =
        all.asSequence()
            .filter { !it.intentUri.isNullOrBlank() && it.title.isNotBlank() }
            .filter { it.lastEngagementMs <= 0 || nowMs - it.lastEngagementMs <= MAX_AGE_MS }
            .filter { progressPct(it) < HIDE_AT_PCT }
            .sortedByDescending { it.lastEngagementMs }
            .distinctBy { it.intentUri }
            .take(limit)
            .toList()

    /** h:mm:ss, or m:ss under an hour. */
    fun clock(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }
}
