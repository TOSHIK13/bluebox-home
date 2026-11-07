package app.bluebox.home.logic

/** What a channel hands over, counted over all its programs (for Settings → «Каналы»). */
data class ChannelStats(
    val total: Int,
    val noLink: Int,
    val withPoster: Int,
    val withThumb: Int,
    val portraitPoster: Int,
    val withProgress: Int,
)

/** One app-published channel with its programs. [entries] are what Home may show (usable, capped); [stats] cover everything. */
data class ChannelRow(
    val channelId: Long,
    val packageName: String,
    val name: String,
    val description: String?,
    val entries: List<WatchEntry>,
    val stats: ChannelStats,
) {
    val key: String get() = ChannelRules.key(packageName, name)

    /** Most programs come with a 2:3 poster and no wide frame: show the row as tall cards. */
    val portrait: Boolean get() = stats.total > 0 && stats.portraitPoster * 2 > stats.total
}

/** Order and visibility of channel rows; pure JVM. Keys survive restarts (package + name, not the provider's row id). */
object ChannelRules {
    fun key(packageName: String, name: String) = "$packageName|$name"

    /** Saved order first; channels the owner has not placed yet follow in provider order. */
    fun arrange(all: List<ChannelRow>, order: List<String>): List<ChannelRow> {
        val pos = HashMap<String, Int>()
        order.forEachIndexed { i, k -> pos.putIfAbsent(k, i) }
        return all.withIndex()
            .sortedWith(compareBy({ pos[it.value.key] ?: Int.MAX_VALUE }, { it.index }))
            .map { it.value }
    }

    fun visible(arranged: List<ChannelRow>, hidden: Set<String>): List<ChannelRow> = arranged.filter { it.key !in hidden }

    /** [keys] is the order currently shown; returns it with [key] moved by [delta] places (clamped). */
    fun move(keys: List<String>, key: String, delta: Int): List<String> {
        val from = keys.indexOf(key)
        if (from < 0) return keys
        val to = (from + delta).coerceIn(0, keys.size - 1)
        if (to == from) return keys
        return keys.toMutableList().also { it.removeAt(from); it.add(to, key) }
    }
}
