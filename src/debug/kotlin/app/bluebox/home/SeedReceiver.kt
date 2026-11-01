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
        val cr = context.contentResolver
        runCatching { cr.delete(WatchNextPrograms.CONTENT_URI, null, null) }
        val titles = listOf("Дюна: Часть вторая" to 40, "Во все тяжкие" to 12, "Твоё имя" to 75, "Начало" to 98)
        var ok = 0
        titles.forEachIndexed { i, (title, pct) ->
            val v = ContentValues().apply {
                put(WatchNextPrograms.COLUMN_TYPE, WatchNextPrograms.TYPE_MOVIE)
                put(WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
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
