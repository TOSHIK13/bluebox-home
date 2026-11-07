package app.bluebox.home

import android.content.Context

/** The few launcher settings. Backdrop of the focused card is off by default (flat dark background). */
object Prefs {
    private const val FILE = "home"
    private const val BACKDROP = "backdrop"

    fun backdrop(c: Context): Boolean = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(BACKDROP, false)

    fun setBackdrop(c: Context, on: Boolean) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(BACKDROP, on).apply()
    }
}
