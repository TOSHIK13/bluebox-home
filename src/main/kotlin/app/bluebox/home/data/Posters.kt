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
            val bmp = runCatching { decode(uri, reqW, reqH) }.getOrNull()
            if (bmp != null) cache.put(uri, bmp)
            main.post { done(bmp) }
        }
    }

    fun shutdown() = pool.shutdownNow()

    private fun open(uri: String): java.io.InputStream? {
        val u = Uri.parse(uri)
        return if (u.scheme == "http" || u.scheme == "https") {
            (URL(uri).openConnection() as HttpURLConnection).apply { connectTimeout = 5000; readTimeout = 8000 }.inputStream
        } else context.contentResolver.openInputStream(u)
    }

    private fun decode(uri: String, reqW: Int, reqH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
        return open(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }
}
