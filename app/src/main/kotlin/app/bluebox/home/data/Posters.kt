package app.bluebox.home.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Minimal poster loader (no Coil: the home APK stays dependency-free). Decodes down to the card size.
 * The memory cache is measured in kilobytes (not pictures); the same picture requested twice while it is loading is fetched once;
 * a failed address is not retried for [RETRY_MS]. Call [load] and [evictAll] from the main thread only.
 */
class Posters(private val context: Context, maxCacheKb: Int = 8 * 1024) {
    private val pool = Executors.newFixedThreadPool(2) { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "posters")
    }
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(maxCacheKb) {
        override fun sizeOf(key: String, value: Bitmap) = maxOf(1, value.byteCount / 1024)
    }
    private val waiting = HashMap<String, MutableList<(Bitmap?) -> Unit>>() // main thread only
    private val failedAt = HashMap<String, Long>() // main thread only

    fun load(uri: String, reqW: Int, reqH: Int, done: (Bitmap?) -> Unit) {
        val key = "$uri#${reqW}x$reqH"
        cache.get(key)?.let { done(it); return }
        failedAt[key]?.let { if (android.os.SystemClock.elapsedRealtime() - it < RETRY_MS) { done(null); return } }
        waiting[key]?.let { it += done; return } // already on its way: wait for that one
        waiting[key] = mutableListOf(done)
        try {
            pool.execute {
                val bmp = runCatching { decode(uri, reqW, reqH) }
                    .onFailure { android.util.Log.w("BBHome", "image failed ${Uri.parse(uri).scheme}://${Uri.parse(uri).host}: ${it.javaClass.simpleName} ${it.message}") }
                    .getOrNull()
                main.post {
                    if (bmp != null) cache.put(key, bmp) else failedAt[key] = android.os.SystemClock.elapsedRealtime()
                    waiting.remove(key)?.forEach { it(bmp) }
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            waiting.remove(key) // the loader is shut down (the screen is gone)
        }
    }

    /** Frees the pictures held in memory (low memory / the screen left the foreground for long). */
    fun evictAll() = cache.evictAll()

    fun shutdown() = pool.shutdownNow()

    private fun open(uri: String): java.io.InputStream? {
        val u = Uri.parse(uri)
        return if (u.scheme == "http" || u.scheme == "https") fromDisk(uri) else context.contentResolver.openInputStream(u)
    }

    /**
     * Pictures of https cards are kept as files in the cache folder (the system may clear it): the same URL is downloaded once,
     * not on every return to Home, and one decode no longer fetches the file twice (bounds, then pixels).
     * Only a complete answer (200, an image, the announced length) is stored; anything else is never written to the cache.
     */
    private fun fromDisk(uri: String): java.io.InputStream {
        val dir = java.io.File(context.cacheDir, "posters").apply { mkdirs() }
        val name = java.security.MessageDigest.getInstance("SHA-1").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = java.io.File(dir, name)
        if (file.length() > 0) { file.setLastModified(System.currentTimeMillis()); return file.inputStream() }
        val tmp = java.io.File(dir, "$name.${Thread.currentThread().id}.tmp")
        val conn = (URL(uri).openConnection() as HttpURLConnection).apply { connectTimeout = 5000; readTimeout = 8000 }
        try {
            if (conn.responseCode != 200) throw java.io.IOException("http ${conn.responseCode}")
            val type = conn.contentType.orEmpty()
            if (type.isNotEmpty() && !type.startsWith("image/", ignoreCase = true)) throw java.io.IOException("not an image: $type")
            val expected = conn.contentLengthLong
            var got = 0L
            conn.inputStream.use { i ->
                tmp.outputStream().use { o ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = i.read(buf); if (n < 0) break
                        got += n
                        if (got > MAX_FILE) throw java.io.IOException("too large")
                        o.write(buf, 0, n)
                    }
                }
            }
            if (expected >= 0 && got != expected) throw java.io.IOException("truncated $got/$expected")
            if (got == 0L || !tmp.renameTo(file)) throw java.io.IOException("cache store failed")
        } catch (e: Exception) {
            tmp.delete(); throw e
        } finally {
            conn.disconnect()
        }
        trim(dir)
        return file.inputStream()
    }

    /** Keeps the folder under [MAX_DISK] bytes, dropping the least recently used files first. */
    private fun trim(dir: java.io.File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_DISK) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_DISK * 3 / 4) break
            total -= f.length(); f.delete()
        }
    }

    private companion object {
        const val MAX_DISK = 64L * 1024 * 1024
        const val MAX_FILE = 8L * 1024 * 1024
        const val RETRY_MS = 60_000L
    }

    private fun decode(uri: String, reqW: Int, reqH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
        val bmp = open(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (bmp == null) { // a broken file must not stay in the cache and fail forever
            val u = Uri.parse(uri)
            if (u.scheme == "http" || u.scheme == "https") {
                val name = java.security.MessageDigest.getInstance("SHA-1").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }
                java.io.File(java.io.File(context.cacheDir, "posters"), name).delete()
            }
        }
        return bmp
    }
}
