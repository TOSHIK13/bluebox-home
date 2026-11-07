package app.bluebox.home

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.bluebox.home.data.Apps
import app.bluebox.home.data.NotificationFeed
import app.bluebox.home.data.WatchNextSource

/**
 * Home settings in the usual TV-settings layout: title on the left, one column of full-width rows on the right
 * (name, optional hint, value or chevron), grouped by section. Back returns to Home.
 */
class SettingsActivity : Activity() {
    private lateinit var apps: Apps
    private lateinit var list: LinearLayout
    private var focusIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        apps = Apps(this)
        val bg = Color.parseColor("#0f161b")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(bg)
            setPadding(dp(Tok.SAFE_X), dp(Tok.SAFE_Y), dp(Tok.SAFE_X), dp(Tok.SAFE_Y))
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(label(getString(R.string.settings), 30, bold = true))
        left.addView(space(0, 8))
        left.addView(label(getString(R.string.app_name), 14, Tok.text56))
        root.addView(left, lp(dp(300), -1))

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }, lp(0, -1, 1f))
        setContentView(root)
        render()
    }

    override fun onResume() { super.onResume(); render() }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) { render() }

    /** Rebuilds the column; keeps focus on the same row so toggling does not jump to the top. */
    private fun render() {
        val keep = list.focusedChild?.let { list.indexOfChild(it) }?.takeIf { it >= 0 } ?: focusIndex
        list.removeAllViews()

        section(getString(R.string.system), first = true)
        row(getString(R.string.android_settings), null, null) { apps.settings() }

        section(getString(R.string.home_section))
        row(getString(R.string.backdrop_title), getString(R.string.backdrop_hint), onOff(Prefs.backdrop(this))) {
            Prefs.setBackdrop(this, !Prefs.backdrop(this)); render()
        }

        section(getString(R.string.sec_notifications))
        row(getString(R.string.notice_access), null, onOff(NotificationFeed.hasAccess(this))) { apps.notificationAccess() }
        row(getString(R.string.ongoing_title), getString(R.string.ongoing_hint),
            getString(if (Prefs.hideOngoing(this)) R.string.ongoing_hidden else R.string.ongoing_shown)) {
            Prefs.setHideOngoing(this, !Prefs.hideOngoing(this)); render()
        }
        val n = Prefs.hiddenNotices(this).size
        if (n > 0) row(getString(R.string.ongoing_reset), null, n.toString()) { Prefs.clearHiddenNotices(this); render() }

        section(getString(R.string.permissions))
        row(getString(R.string.perm_usage), null, onOff(apps.hasUsageAccess())) { apps.usageAccessSettings() }
        val listings = "android.permission.READ_TV_LISTINGS"
        row(getString(R.string.perm_listings), null,
            onOff(checkSelfPermission(listings) == android.content.pm.PackageManager.PERMISSION_GRANTED)) {
            runCatching { requestPermissions(arrayOf(listings), 2) }
        }

        section(getString(R.string.channels_title))
        row(getString(R.string.channels_title), getString(R.string.channels_hint), null) {
            startActivity(android.content.Intent(this, ChannelsActivity::class.java))
        }

        section(getString(R.string.updates_title))
        row(getString(R.string.updates_title), Prefs.updateFound(this)?.let { getString(R.string.updates_available, it) },
            BuildConfig.VERSION_NAME) { startActivity(android.content.Intent(this, UpdatesActivity::class.java)) }

        section(getString(R.string.sec_search))
        row(getString(R.string.search_where), apps.searchTarget() ?: getString(R.string.search_none), null) { apps.search() }
        diagnostics()

        section(getString(R.string.stock_launcher))
        row(getString(R.string.stock_launcher), getString(R.string.launcher_hint), null) { apps.restoreStockHome() }
        row(getString(R.string.choose_launcher), null, null) { apps.homeSettings() }

        list.addView(space(0, 24))
        val target = list.getChildAt(keep.coerceIn(0, list.childCount - 1))
        (if (target?.isFocusable == true) target else list.children().firstOrNull { it.isFocusable })?.requestFocus()
    }

    /** One row showing what the TV provider hands this app: package → number of rows. OK reads again. */
    private fun diagnostics() {
        val hint = label(getString(R.string.diag_loading), 12, Tok.text56).apply { maxLines = 3 }
        val v = row(getString(R.string.diag_title), null, null, sub = hint) { render() }
        Thread {
            val src = WatchNextSource(this)
            val rows = src.load()
            val shown = app.bluebox.home.logic.EntryRules.select(rows, System.currentTimeMillis()).groupBy { it.sourcePackage ?: "?" }
            // "package: on the shelf / shown on Home"
            val byPkg = rows.groupBy { it.sourcePackage ?: "?" }.map { (p, r) -> "$p: ${r.size}/${shown[p]?.size ?: 0}" }
            val text = when {
                src.lastError != null -> src.lastError!!
                rows.isEmpty() -> "Строк нет"
                else -> "Строк ${rows.size} · " + byPkg.joinToString(", ")
            } + "\n" + channelsText() + "\n" + getString(R.string.diag_hint)
            runOnUiThread { if (!isDestroyed) hint.text = text }
        }.start()
        v.contentDescription = getString(R.string.diag_title)
    }

    /** "Свои строки приложений: pkg «имя» N, …" or the reason there are none. Runs off the UI thread. */
    private fun channelsText(): String {
        val src = app.bluebox.home.data.ChannelSource(this)
        val ch = src.load()
        return when {
            src.lastError != null -> "Свои строки: " + src.lastError
            ch.isEmpty() -> "Свои строки приложений: нет"
            else -> "Свои строки: " + ch.joinToString(", ") { "${it.packageName} «${it.name}» ${it.entries.size}" }
        }
    }

    private fun onOff(on: Boolean) = getString(if (on) R.string.on else R.string.off)

    private fun section(title: String, first: Boolean = false) {
        if (!first) list.addView(space(0, 22))
        list.addView(label(title.uppercase(), 12, Tok.accentSoft, bold = true).apply { setPadding(dp(4), 0, 0, dp(8)) })
    }

    /** Full-width row: name (+ hint) on the left, value or chevron on the right. Returns the row. */
    private fun row(title: String, hint: String?, value: String?, sub: TextView? = null, onClick: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
            minimumHeight = dp(56)
            plate(10)
            setOnClickListener { focusIndex = list.indexOfChild(this); onClick() }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val t = label(title, 16, bold = true)
        col.addView(t)
        val subs = ArrayList<TextView>()
        (sub ?: hint?.let { label(it, 12, Tok.text56).apply { maxLines = 3 } })?.let { s ->
            col.addView(space(0, 3)); col.addView(s); subs += s
        }
        box.addView(col, lp(0, -2, 1f))
        val right = label(value ?: "›", if (value != null) 14 else 22, Tok.text72, bold = value != null)
        box.addView(right)
        box.flipTexts(listOf(t, right), subs)
        list.addView(box, lp(-1, -2).apply { bottomMargin = dp(6) })
        return box
    }

    private fun LinearLayout.children(): List<View> = (0 until childCount).map { getChildAt(it) }
}
