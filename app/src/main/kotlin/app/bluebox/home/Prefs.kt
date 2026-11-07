package app.bluebox.home

import android.content.Context

/** The few launcher settings. Backdrop of the focused card is off by default (flat dark background). */
object Prefs {
    private const val FILE = "home"
    private const val BACKDROP = "backdrop"
    private const val DOCK = "dock"
    private const val ASKED = "asked_listings"
    private const val RECENTS = "recents"

    fun backdrop(c: Context): Boolean = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(BACKDROP, false)

    /** Dock slot tokens (package or "@recent") in order; null = never edited (five recent slots). */
    fun dock(c: Context): List<String>? =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(DOCK, null)?.split(',')?.filter { it.isNotBlank() }

    fun setDock(c: Context, tokens: List<String>) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(DOCK, tokens.joinToString(",")).apply()
    }

    /** Apps started from Home, newest first: the "recent" source when usage access is not granted. */
    fun recents(c: Context): List<String> =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(RECENTS, null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()

    fun pushRecent(c: Context, pkg: String) {
        val next = (listOf(pkg) + recents(c)).distinct().take(30)
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(RECENTS, next.joinToString(",")).apply()
    }

    fun askedListings(c: Context): Boolean = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(ASKED, false)

    fun setAskedListings(c: Context) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(ASKED, true).apply()
    }

    /** First-run permission steps ("usage", "notif"): each is asked once, later only from Settings. */
    fun asked(c: Context, step: String): Boolean = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("asked_$step", false)

    fun setAsked(c: Context, step: String) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("asked_$step", true).apply()
    }

    /** Ongoing notifications (VPN, downloads, …) in the quick panel: shown by default; all can be hidden or one by one. */
    fun hideOngoing(c: Context): Boolean = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("hide_ongoing", false)

    fun setHideOngoing(c: Context, on: Boolean) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("hide_ongoing", on).apply()
    }

    fun hiddenNotices(c: Context): Set<String> =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getStringSet("hidden_notices", emptySet()).orEmpty()

    fun hideNotice(c: Context, key: String) {
        val p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        p.edit().putStringSet("hidden_notices", HashSet(hiddenNotices(c) + key)).apply()
    }

    fun clearHiddenNotices(c: Context) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove("hidden_notices").apply()
    }

    /** Channel rows: owner's order and switched-off channels, by [app.bluebox.home.logic.ChannelRules.key]. */
    fun channelOrder(c: Context): List<String> =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("channel_order", null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    fun setChannelOrder(c: Context, keys: List<String>) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("channel_order", keys.joinToString("\n")).apply()
    }

    fun hiddenChannels(c: Context): Set<String> =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getStringSet("hidden_channels", emptySet()).orEmpty()

    fun setChannelHidden(c: Context, key: String, hidden: Boolean) {
        val next = HashSet(hiddenChannels(c)).also { if (hidden) it += key else it -= key }
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putStringSet("hidden_channels", next).apply()
    }

    /** Apps whose "continue watching" (Watch Next) rows are switched off on Home, by package. */
    fun hiddenWatchApps(c: Context): Set<String> =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getStringSet("hidden_watch_apps", emptySet()).orEmpty()

    fun setWatchAppHidden(c: Context, pkg: String, hidden: Boolean) {
        val next = HashSet(hiddenWatchApps(c)).also { if (hidden) it += pkg else it -= pkg }
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putStringSet("hidden_watch_apps", next).apply()
    }

    fun setBackdrop(c: Context, on: Boolean) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(BACKDROP, on).apply()
    }

    /** Updates: daily background check (on by default), beta channel (off), when it last ran, tag found newer than installed. */
    fun updateAuto(c: Context): Boolean = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("update_auto", true)

    fun setUpdateAuto(c: Context, on: Boolean) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("update_auto", on).apply()
    }

    fun updateBeta(c: Context): Boolean = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("update_beta", false)

    fun setUpdateBeta(c: Context, on: Boolean) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("update_beta", on).putString("update_found", null).apply()
    }

    fun updateCheckedAt(c: Context): Long = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getLong("update_checked", 0)

    fun updateFound(c: Context): String? = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("update_found", null)

    fun setUpdateResult(c: Context, nowMs: Long, newerTag: String?) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putLong("update_checked", nowMs).putString("update_found", newerTag).apply()
    }
}
