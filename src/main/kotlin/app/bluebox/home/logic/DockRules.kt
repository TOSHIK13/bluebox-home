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

    /** All apps for the "all apps" screen: alphabetical, without ourselves. */
    fun all(installed: List<AppEntry>, self: String): List<AppEntry> =
        installed.filter { it.packageName != self }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
}
