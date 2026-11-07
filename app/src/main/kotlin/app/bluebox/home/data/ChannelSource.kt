package app.bluebox.home.data

import android.content.Context
import android.media.tv.TvContract
import android.util.Log
import app.bluebox.home.logic.ChannelRow
import app.bluebox.home.logic.ChannelStats
import app.bluebox.home.logic.WatchEntry

/**
 * Reads other apps' own channels and their preview programs from the TV provider. Same access rule as Watch Next
 * (READ_TV_LISTINGS, no WHERE clause): everything is filtered here, on the client. Returns every channel, even without
 * usable programs, so Settings can show what an app publishes; Home shows only rows with [ChannelRow.entries].
 */
class ChannelSource(private val context: Context) {
    @Volatile var lastError: String? = null
        private set

    private class Raw(val pkg: String, val name: String, val description: String?)

    fun load(): List<ChannelRow> {
        lastError = null
        return try {
            val channels = LinkedHashMap<Long, Raw>()
            context.contentResolver.query(
                TvContract.Channels.CONTENT_URI,
                arrayOf(
                    TvContract.Channels._ID, TvContract.Channels.COLUMN_PACKAGE_NAME, TvContract.Channels.COLUMN_DISPLAY_NAME,
                    TvContract.Channels.COLUMN_TYPE, TvContract.Channels.COLUMN_DESCRIPTION,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(3) != TvContract.Channels.TYPE_PREVIEW) continue
                    channels[c.getLong(0)] = Raw(c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getString(4))
                }
            }
            val all = HashMap<Long, MutableList<WatchEntry>>()
            val weights = HashMap<Long, Int>()
            val portrait = HashMap<Long, Int>() // channel id -> programs with a tall poster
            val cols = arrayOf(
                TvContract.PreviewPrograms._ID, TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, TvContract.PreviewPrograms.COLUMN_TITLE,
                TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION, TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
                TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI, TvContract.PreviewPrograms.COLUMN_INTENT_URI,
                TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS,
                TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME, TvContract.PreviewPrograms.COLUMN_WEIGHT,
                TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER, TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
                TvContract.PreviewPrograms.COLUMN_TYPE, TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
            )
            context.contentResolver.query(TvContract.PreviewPrograms.CONTENT_URI, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val ch = c.getLong(1)
                    if (ch !in channels) continue
                    val id = c.getLong(0)
                    weights[id] = c.getInt(10)
                    if (!c.isNull(14) && c.getInt(14) == TvContract.PreviewPrograms.ASPECT_RATIO_2_3 &&
                        !c.getString(4).isNullOrBlank()) portrait.merge(ch, 1, Int::plus)
                    all.getOrPut(ch) { ArrayList() } += WatchEntry(
                        id = id, title = c.getString(2).orEmpty(), subtitle = c.getString(3),
                        posterUri = c.getString(4), intentUri = c.getString(6),
                        positionMs = c.getLong(7), durationMs = c.getLong(8), lastEngagementMs = 0L,
                        sourcePackage = c.getString(9), thumbUri = c.getString(5),
                        season = c.getString(11), episode = c.getString(12), contentType = if (c.isNull(13)) -1 else c.getInt(13),
                    )
                }
            }
            channels.map { (id, raw) ->
                val list = all[id].orEmpty()
                val stats = ChannelStats(
                    total = list.size,
                    noLink = list.count { it.intentUri.isNullOrBlank() },
                    withPoster = list.count { !it.posterUri.isNullOrBlank() },
                    withThumb = list.count { !it.thumbUri.isNullOrBlank() },
                    portraitPoster = portrait[id] ?: 0,
                    withProgress = list.count { it.positionMs > 0 },
                )
                val usable = list.filter { it.title.isNotBlank() && !it.intentUri.isNullOrBlank() }
                    .sortedByDescending { weights[it.id] ?: 0 }.take(MAX_PER_ROW)
                ChannelRow(id, raw.pkg, raw.name, raw.description, usable, stats)
            }.take(MAX_CHANNELS)
        } catch (e: SecurityException) {
            lastError = "denied: ${e.message}"; Log.w(TAG, "channels denied", e); emptyList()
        } catch (e: Exception) {
            lastError = "error: ${e.javaClass.simpleName}"; Log.w(TAG, "channels failed", e); emptyList()
        }.also { Log.i(TAG, "channels n=${it.size} ${it.map { r -> r.key + "=" + r.entries.size + "/" + r.stats.total }} err=$lastError") }
    }

    private companion object {
        const val TAG = "BBHome"
        const val MAX_CHANNELS = 40
        const val MAX_PER_ROW = 20
    }
}
