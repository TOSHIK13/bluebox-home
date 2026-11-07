package app.bluebox.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvContract.WatchNextPrograms
import android.content.ContentValues
import android.util.Log

/** Debug seed: writes OWN Watch Next rows so the home screen can be smoke-tested on an emulator. */
class SeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // scenarios must not be interrupted by the first-run permission chain (see m6-home-permissions.txt for that)
        if (intent.getBooleanExtra("reset_asked", false)) {
            context.getSharedPreferences("home", Context.MODE_PRIVATE).edit()
                .remove("asked_listings").remove("asked_usage").remove("asked_notif").apply()
            return
        }
        Prefs.setAskedListings(context); Prefs.setAsked(context, "usage"); Prefs.setAsked(context, "notif")
        val cr = context.contentResolver
        runCatching { cr.delete(WatchNextPrograms.CONTENT_URI, null, null) }
        // title, percent watched, season, episode (0 = movie), new-episode flag
        val titles = listOf(
            Seed("Магическая битва", 0, 2, 6, true), Seed("Во все тяжкие", 12, 1, 2), Seed("Дюна: Часть вторая", 40, 0, 0),
            Seed("Твоё имя", 75, 0, 0), Seed("Начало", 98, 0, 0), Seed("Игра престолов", 30, 3, 9),
        )
        var ok = 0
        titles.forEachIndexed { i, (title, pct, season, episode, fresh) ->
            val v = ContentValues().apply {
                put(WatchNextPrograms.COLUMN_TYPE, if (episode > 0) WatchNextPrograms.TYPE_TV_EPISODE else WatchNextPrograms.TYPE_MOVIE)
                put(WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, if (fresh) WatchNextPrograms.WATCH_NEXT_TYPE_NEXT else WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                if (episode > 0) {
                    put(WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER, season.toString())
                    put(WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER, episode.toString())
                }
                put(WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, System.currentTimeMillis() - i * 60_000L)
                put(WatchNextPrograms.COLUMN_TITLE, title)
                put(WatchNextPrograms.COLUMN_DURATION_MILLIS, 120 * 60_000L)
                put(WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, 120 * 60_000L * pct / 100)
                put(WatchNextPrograms.COLUMN_INTENT_URI, "bluebox://seed/$i")
            }
            if (runCatching { cr.insert(WatchNextPrograms.CONTENT_URI, v) }.onFailure { Log.w("BBHome", "seed failed", it) }.getOrNull() != null) ok++
        }
        Log.i("BBHome", "seed inserted=$ok of ${titles.size}")
    }
}

private data class Seed(val title: String, val pct: Int, val season: Int, val episode: Int, val fresh: Boolean = false)
