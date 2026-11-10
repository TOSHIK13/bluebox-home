package app.bluebox.home.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.bluebox.home.BuildConfig
import app.bluebox.home.Prefs
import app.bluebox.home.R
import app.bluebox.home.UpdatesActivity
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
        if (r == null) return
        val before = Prefs.updateFound(c)
        val newer = r.tag.takeIf { UpdateRules.isNewer(BuildConfig.BASE_VERSION, it) }
        Prefs.setUpdateResult(c, now, newer)
        // tell once per version; OK on the notification opens the screen where one more OK downloads and installs
        if (newer != null && newer != before) notify(c, newer)
        if (newer == null) runCatching { c.getSystemService(NotificationManager::class.java).cancel(NOTICE_ID) }
    }

    private const val NOTICE_ID = 4201
    private const val CHANNEL = "updates"

    private fun notify(c: Context, tag: String) = runCatching {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.updates_title), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            c, 0, Intent(c, UpdatesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(NOTICE_ID, Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_settings)
            .setContentTitle(c.getString(R.string.updates_notice_title))
            .setContentText(c.getString(R.string.updates_available, tag))
            .setContentIntent(open).setAutoCancel(true).build())
    }
}
