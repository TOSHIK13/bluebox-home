package app.bluebox.home.logic

/** An app that can be started from the box. */
data class AppEntry(val packageName: String, val label: String)

object DockRules {
    /** Packages put first, in this order, when installed: Blue Box (all variants), SmartTube, Plex. */
    val DEFAULT_PINNED = listOf(
        "app.bluebox.tv", "app.bluebox.tv.beta", "app.bluebox.tv.debug",
        "com.teamsmart.videomanager.tv", "com.plexapp.android",
    )

    /** Pinned installed apps first (in pin order), then the rest by label, up to [size]. Our own package is never listed. */
    fun pick(installed: List<AppEntry>, self: String, pinned: List<String> = DEFAULT_PINNED, size: Int = 4): List<AppEntry> {
        val others = installed.filter { it.packageName != self }.distinctBy { it.packageName }
        val first = pinned.mapNotNull { p -> others.firstOrNull { it.packageName == p } }.distinctBy { it.packageName }
        val rest = others.filter { a -> first.none { it.packageName == a.packageName } }.sortedBy { it.label.lowercase() }
        // debug/beta builds of one app share a label: the dock shows it once
        return (first + rest).distinctBy { it.label.lowercase() }.take(size)
    }

    // ---- dock slots: a slot is either a fixed app (its package) or [RECENT] = "the next most recently used app" ----

    const val RECENT = "@recent"
    const val MAX_SLOTS = 8
    val DEFAULT_SLOTS: List<String> = List(5) { RECENT }

    /** [app] is null only for a recent slot when there is nothing left to show. */
    data class Slot(val token: String, val app: AppEntry?) { val recent get() = token == RECENT }

    /**
     * Resolves slot tokens ([saved] == null: five recent slots). Recent slots take the next app from [recent]
     * (newest first), then from the automatic pick; apps fixed in other slots and ourselves are skipped.
     * A fixed app that is no longer installed has no slot.
     */
    fun slots(installed: List<AppEntry>, self: String, saved: List<String>?, recent: List<String>): List<Slot> {
        val tokens = saved ?: DEFAULT_SLOTS
        val byPkg = installed.filter { it.packageName != self }.distinctBy { it.packageName }.associateBy { it.packageName }
        val fixed = tokens.filter { it != RECENT }.toSet()
        val pool = ArrayDeque(
            (recent + pick(installed, self, size = installed.size).map { it.packageName })
                .distinct().filter { it in byPkg && it !in fixed },
        )
        return tokens.mapNotNull { t ->
            if (t == RECENT) Slot(t, pool.removeFirstOrNull()?.let { byPkg[it] }) else byPkg[t]?.let { Slot(t, it) }
        }
    }

    /** Drops fixed apps that are not installed, so slot indexes match token indexes in the editor. */
    fun normalize(installed: List<AppEntry>, self: String, saved: List<String>?): List<String> {
        val have = installed.map { it.packageName }.toSet() - self
        return (saved ?: DEFAULT_SLOTS).filter { it == RECENT || it in have }
    }

    fun move(tokens: List<String>, index: Int, delta: Int): List<String> {
        val j = index + delta
        if (index !in tokens.indices || j !in tokens.indices) return tokens
        return tokens.toMutableList().also { val t = it[index]; it[index] = it[j]; it[j] = t }
    }

    fun removeAt(tokens: List<String>, index: Int): List<String> =
        if (index in tokens.indices) tokens.filterIndexed { i, _ -> i != index } else tokens

    /** Appends [token]; a fixed app is added once, the dock holds at most [MAX_SLOTS]. */
    fun add(tokens: List<String>, token: String): List<String> =
        if (tokens.size >= MAX_SLOTS || (token != RECENT && token in tokens)) tokens else tokens + token

    /** Apps for the picker: most recently used first, the rest alphabetical. */
    fun byRecency(installed: List<AppEntry>, self: String, recent: List<String>): List<AppEntry> {
        val all = all(installed, self)
        val rank = recent.withIndex().associate { it.value to it.index }
        return all.sortedWith(compareBy({ rank[it.packageName] ?: Int.MAX_VALUE }, { it.label.lowercase() }))
    }

    /** All apps for the "all apps" screen: alphabetical, without ourselves. */
    fun all(installed: List<AppEntry>, self: String): List<AppEntry> =
        installed.filter { it.packageName != self }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
}
