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
        seedChannels(context, intent.getIntExtra("channels", 4))
    }
}

/** Own preview channels with a few programs each (extra "channels" = how many, 0 = none): rows to test scrolling between shelves. */
private fun seedChannels(context: Context, n: Int) {
    val cr = context.contentResolver
    runCatching { cr.delete(TvContract.Channels.CONTENT_URI, null, null) }
    for (c in 1..n) {
        val cv = ContentValues().apply {
            put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_PREVIEW)
            put(TvContract.Channels.COLUMN_DISPLAY_NAME, "Тестовый ряд $c")
            put(TvContract.Channels.COLUMN_APP_LINK_INTENT_URI, "bluebox://seed/channel/$c")
        }
        val uri = runCatching { cr.insert(TvContract.Channels.CONTENT_URI, cv) }.onFailure { Log.w("BBHome", "seed channel failed", it) }.getOrNull() ?: continue
        val id = android.content.ContentUris.parseId(uri)
        for (p in 1..8) {
            val pv = ContentValues().apply {
                put(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, id)
                put(TvContract.PreviewPrograms.COLUMN_TYPE, TvContract.PreviewPrograms.TYPE_CLIP)
                put(TvContract.PreviewPrograms.COLUMN_TITLE, "Ряд $c · видео $p")
                put(TvContract.PreviewPrograms.COLUMN_INTENT_URI, "bluebox://seed/$c/$p")
                put(TvContract.PreviewPrograms.COLUMN_WEIGHT, 100 - p)
                if (c == 2) { // channel 2 carries tall 2:3 posters (a generated picture in the app cache), to test portrait shelves
                    put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI, poster(context, p).toString())
                    put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, TvContract.PreviewPrograms.ASPECT_RATIO_2_3)
                }
            }
            runCatching { cr.insert(TvContract.PreviewPrograms.CONTENT_URI, pv) }.onFailure { Log.w("BBHome", "seed program failed", it) }
        }
    }
    Log.i("BBHome", "seed channels=$n")
}

private fun poster(context: Context, n: Int): android.net.Uri {
    val f = java.io.File(context.cacheDir, "seed_poster_$n.png")
    val bmp = android.graphics.Bitmap.createBitmap(400, 600, android.graphics.Bitmap.Config.ARGB_8888)
    val cv = android.graphics.Canvas(bmp)
    cv.drawColor(android.graphics.Color.HSVToColor(floatArrayOf((n * 40f) % 360, 0.5f, 0.55f)))
    val paint = android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = 160f; isAntiAlias = true }
    cv.drawText("$n", 150f, 330f, paint)
    f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    return android.net.Uri.fromFile(f)
}

private data class Seed(val title: String, val pct: Int, val season: Int, val episode: Int, val fresh: Boolean = false)
