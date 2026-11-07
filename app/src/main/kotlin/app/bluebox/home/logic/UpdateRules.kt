package app.bluebox.home.logic

/** A release asset as the GitHub API lists it. */
data class ReleaseAsset(val name: String, val url: String, val size: Long)

/** The newest release for the chosen channel; `apk`/`sha` are null when the release has no matching file. */
data class UpdateInfo(
    val tag: String,
    val notes: String,
    val publishedAt: String,
    val prerelease: Boolean,
    val apk: ReleaseAsset?,
    val sha: ReleaseAsset?,
)

/** A parsed `v1.2.3` / `1.2.3-beta.1`; a version without a suffix is newer than the same numbers with one. */
data class Version(val major: Int, val minor: Int, val patch: Int, val pre: String?) : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        val a = listOf(major, minor, patch); val b = listOf(other.major, other.minor, other.patch)
        for (i in 0..2) if (a[i] != b[i]) return a[i].compareTo(b[i])
        return when {
            pre == other.pre -> 0
            pre == null -> 1
            other.pre == null -> -1
            else -> comparePre(pre, other.pre)
        }
    }

    private fun comparePre(x: String, y: String): Int {
        val p = x.split('.'); val q = y.split('.')
        for (i in 0 until minOf(p.size, q.size)) {
            val a = p[i].toIntOrNull(); val b = q[i].toIntOrNull()
            val c = when {
                a != null && b != null -> a.compareTo(b)
                a != null -> -1 // numeric identifiers sort before text ones
                b != null -> 1
                else -> p[i].compareTo(q[i])
            }
            if (c != 0) return c
        }
        return p.size.compareTo(q.size)
    }
}

/** Pure update logic: version parsing, comparison, picking the files of a release, how often to ask. */
object UpdateRules {
    const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    fun parse(s: String?): Version? {
        val m = Regex("""^v?(\d{1,4})\.(\d{1,4})\.(\d{1,4})(?:-([0-9A-Za-z.-]+))?$""").matchEntire(s?.trim().orEmpty()) ?: return null
        val (a, b, c) = m.destructured
        return Version(a.toInt(), b.toInt(), c.toInt(), m.groupValues[4].ifEmpty { null })
    }

    /** Is the release tag newer than the installed base version? Unparsable input is never "newer". */
    fun isNewer(current: String, tag: String): Boolean {
        val cur = parse(current) ?: return false
        val rel = parse(tag) ?: return false
        return rel > cur
    }

    /** versionCode a tag stands for (must match -PversionCode of the build): major*10000 + minor*100 + patch. */
    fun versionCode(tag: String): Int? = parse(tag)?.let { it.major * 10000 + it.minor * 100 + it.patch }

    /** The APK of a release and its checksum file; names look like `bluebox-home-0.2.1.apk` / `….apk.sha256`. */
    fun pickApk(assets: List<ReleaseAsset>): ReleaseAsset? =
        assets.firstOrNull { it.name.startsWith("bluebox-home-") && it.name.endsWith(".apk") }

    fun pickSha(assets: List<ReleaseAsset>, apk: ReleaseAsset): ReleaseAsset? =
        assets.firstOrNull { it.name == apk.name + ".sha256" }

    /** First 64-hex token of a `sha256sum`-style file, lower-case; null if there is none. */
    fun parseSha256(text: String): String? =
        Regex("""(?i)\b[0-9a-f]{64}\b""").find(text)?.value?.lowercase()

    fun shouldCheck(lastCheckMs: Long, nowMs: Long): Boolean = lastCheckMs <= 0 || nowMs - lastCheckMs >= CHECK_INTERVAL_MS || nowMs < lastCheckMs

    /** Release notes cut for a TV screen. */
    fun shortNotes(body: String, max: Int = 600): String {
        val t = body.replace("\r", "").trim()
        return if (t.length <= max) t else t.take(max).trimEnd() + "…"
    }
}
