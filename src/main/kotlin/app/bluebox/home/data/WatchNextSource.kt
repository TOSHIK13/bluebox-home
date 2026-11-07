package app.bluebox.home.data

import android.content.Context
import android.media.tv.TvContract
import android.util.Log
import app.bluebox.home.logic.WatchEntry

/**
 * Reads Watch Next rows from the system TvProvider. A non-privileged app sees only its OWN rows there
 * (pitfalls.md); [lastError] tells the UI/log whether the read was denied or just empty.
 */
class WatchNextSource(private val context: Context) {
    @Volatile var lastError: String? = null
        private set

    fun load(): List<WatchEntry> {
        lastError = null
        val cols = arrayOf(
            TvContract.WatchNextPrograms._ID,
            TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
            TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
            TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
        )
        return try {
            context.contentResolver.query(TvContract.WatchNextPrograms.CONTENT_URI, cols, null, null, null).use { c ->
                val out = ArrayList<WatchEntry>()
                if (c != null) while (c.moveToNext()) {
                    out += WatchEntry(
                        id = c.getLong(0), title = c.getString(1).orEmpty(), subtitle = c.getString(2),
                        posterUri = c.getString(3), intentUri = c.getString(4),
                        positionMs = c.getLong(5), durationMs = c.getLong(6), lastEngagementMs = c.getLong(7),
                        sourcePackage = c.getString(8), thumbUri = c.getString(9),
                    )
                }
                out
            }
        } catch (e: SecurityException) {
            lastError = "denied: ${e.message}"
            Log.w(TAG, "Watch Next read denied", e)
            emptyList()
        } catch (e: Exception) {
            lastError = "error: ${e.javaClass.simpleName}"
            Log.w(TAG, "Watch Next read failed", e)
            emptyList()
        }.also { Log.i(TAG, "watch-next rows=${it.size} packages=${it.mapNotNull { r -> r.sourcePackage }.distinct()} err=$lastError") }
    }

    private companion object { const val TAG = "BBHome" }
}
