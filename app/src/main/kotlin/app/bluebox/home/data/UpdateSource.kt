package app.bluebox.home.data

import app.bluebox.home.logic.ReleaseAsset
import app.bluebox.home.logic.UpdateInfo
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * Asks the GitHub Releases API (no token, HTTPS only, owner/repo fixed at build time) for the newest release of the
 * chosen channel. A network or format problem is reported in [lastError] and never thrown: Home must not break on it.
 * Blocking; call off the UI thread.
 */
class UpdateSource(private val repo: String, private val beta: Boolean) {
    var lastError: String? = null
        private set

    fun latest(): UpdateInfo? {
        lastError = null
        if (!Regex("""[\w.-]+/[\w.-]+""").matches(repo)) { lastError = "Репозиторий обновлений не задан"; return null }
        return try {
            val base = "https://api.github.com/repos/$repo/releases"
            if (beta) {
                val arr = JSONArray(get("$base?per_page=10"))
                val first = (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { !it.optBoolean("draft") }
                first?.let(::info) ?: run { lastError = "Релизов нет"; null }
            } else {
                info(JSONObject(get("$base/latest")))
            }
        } catch (e: java.io.FileNotFoundException) {
            lastError = "Релизов нет"; null
        } catch (e: Exception) {
            lastError = "Нет связи с GitHub: ${e.javaClass.simpleName}"; null
        }
    }

    private fun info(o: JSONObject): UpdateInfo {
        val arr = o.optJSONArray("assets") ?: JSONArray()
        val assets = (0 until arr.length()).map {
            val a = arr.getJSONObject(it)
            ReleaseAsset(a.optString("name"), a.optString("browser_download_url"), a.optLong("size"))
        }.filter { it.url.startsWith("https://") }
        val apk = app.bluebox.home.logic.UpdateRules.pickApk(assets)
        return UpdateInfo(
            tag = o.optString("tag_name"),
            notes = o.optString("body"),
            publishedAt = o.optString("published_at").take(10),
            prerelease = o.optBoolean("prerelease"),
            apk = apk,
            sha = apk?.let { app.bluebox.home.logic.UpdateRules.pickSha(assets, it) },
        )
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "BlueBoxHome")
        try { return c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }

    companion object {
        /** Small text file (the checksum) from a release asset URL. */
        fun text(url: String): String {
            require(url.startsWith("https://"))
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "BlueBoxHome")
            try { return c.inputStream.bufferedReader().use { it.readText().take(4096) } } finally { c.disconnect() }
        }
    }
}
