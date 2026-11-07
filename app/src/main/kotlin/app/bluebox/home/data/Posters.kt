package app.bluebox.home.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Minimal poster loader (no Coil: the home APK stays dependency-free). Decodes down to the card size. */
class Posters(private val context: Context, cacheSize: Int = 24) {
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(cacheSize) {}

    fun load(uri: String, reqW: Int, reqH: Int, done: (Bitmap?) -> Unit) {
        cache.get(uri)?.let { done(it); return }
        pool.execute {
            val bmp = runCatching { decode(uri, reqW, reqH) }
                .onFailure { android.util.Log.w("BBHome", "image failed ${Uri.parse(uri).scheme}://${Uri.parse(uri).host}: ${it.javaClass.simpleName} ${it.message}") }
                .getOrNull()
            if (bmp == null) android.util.Log.w("BBHome", "image null ${Uri.parse(uri).scheme}://${Uri.parse(uri).host}")
            if (bmp != null) cache.put(uri, bmp)
            main.post { done(bmp) }
        }
    }

    fun shutdown() = pool.shutdownNow()

    private fun open(uri: String): java.io.InputStream? {
        val u = Uri.parse(uri)
        return if (u.scheme == "http" || u.scheme == "https") fromDisk(uri) else context.contentResolver.openInputStream(u)
    }

    /**
     * Pictures of https cards are kept as files in the cache folder (the system may clear it): the same URL is downloaded once,
     * not on every return to Home, and one decode no longer fetches the file twice (bounds, then pixels).
     */
    private fun fromDisk(uri: String): java.io.InputStream {
        val dir = java.io.File(context.cacheDir, "posters").apply { mkdirs() }
        val name = java.security.MessageDigest.getInstance("SHA-1").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = java.io.File(dir, name)
        if (file.length() > 0) { file.setLastModified(System.currentTimeMillis()); return file.inputStream() }
        val tmp = java.io.File(dir, "$name.${Thread.currentThread().id}.tmp")
        (URL(uri).openConnection() as HttpURLConnection).apply { connectTimeout = 5000; readTimeout = 8000 }.inputStream.use { i ->
            tmp.outputStream().use { o -> i.copyTo(o) }
        }
        if (!tmp.renameTo(file)) { tmp.delete(); throw java.io.IOException("cache rename failed") }
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

    private companion object { const val MAX_DISK = 64L * 1024 * 1024 }

    private fun decode(uri: String, reqW: Int, reqH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
        return open(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }
}
