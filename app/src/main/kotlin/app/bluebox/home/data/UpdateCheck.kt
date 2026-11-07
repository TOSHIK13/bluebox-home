package app.bluebox.home.data

import android.content.Context
import app.bluebox.home.BuildConfig
import app.bluebox.home.Prefs
import app.bluebox.home.logic.UpdateRules

/** Quiet daily check started by Home: remembers the newer tag (if any) in Prefs. Blocking; call off the UI thread. */
object UpdateCheck {
    @Volatile private var tried = 0L

    fun maybe(c: Context) {
        val now = System.currentTimeMillis()
        if (!Prefs.updateAuto(c) || !UpdateRules.shouldCheck(Prefs.updateCheckedAt(c), now)) return
        if (now - tried < 10 * 60_000L) return // after a failure do not ask on every resume
        tried = now
        val r = UpdateSource(BuildConfig.UPDATE_REPO, Prefs.updateBeta(c)).latest()
        // a failed check is not remembered as a check: try again next time Home resumes
        if (r != null) Prefs.setUpdateResult(c, now, r.tag.takeIf { UpdateRules.isNewer(BuildConfig.BASE_VERSION, it) })
    }
}
