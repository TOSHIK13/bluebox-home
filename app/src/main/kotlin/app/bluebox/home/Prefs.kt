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

    fun setBackdrop(c: Context, on: Boolean) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(BACKDROP, on).apply()
    }
}
