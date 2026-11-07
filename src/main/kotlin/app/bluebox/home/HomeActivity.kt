package app.bluebox.home

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import app.bluebox.home.data.Apps
import app.bluebox.home.data.Posters
import app.bluebox.home.data.VpnMonitor
import app.bluebox.home.data.WatchNextSource
import app.bluebox.home.logic.AppEntry
import app.bluebox.home.logic.BackdropRules
import app.bluebox.home.logic.DockRules
import app.bluebox.home.logic.EntryRules
import app.bluebox.home.logic.VpnState
import app.bluebox.home.logic.WatchEntry
import java.util.concurrent.Executors

/** Home screen "B": clock, VPN and settings on top, "continue watching" row, dock of apps. Plain Views. */
class HomeActivity : Activity() {
    private lateinit var apps: Apps
    private lateinit var watchNext: WatchNextSource
    private lateinit var posters: Posters
    private lateinit var vpn: VpnMonitor
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var vpnDot: View
    private lateinit var vpnText: TextView
    private lateinit var cards: LinearLayout
    private lateinit var dock: LinearLayout
    private lateinit var cardsScroll: HorizontalScrollView
    private var focusSet = false
    private lateinit var backdrop: ImageView
    private lateinit var backdropScrim: View
    private val backdropHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var backdrops: Posters

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backdrops = Posters(this, cacheSize = 3)
        apps = Apps(this)
        watchNext = WatchNextSource(this)
        posters = Posters(this)
        vpn = VpnMonitor(this, ::showVpn)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        vpn.start()
        io.execute {
            val rows = EntryRules.select(watchNext.load(), System.currentTimeMillis())
            val installed = apps.installed()
            runOnUiThread { if (!isDestroyed) { showCards(rows); showDock(installed); ensureFocus() } }
        }
    }

    override fun onPause() { vpn.stop(); super.onPause() }

    override fun onDestroy() {
        backdropHandler.removeCallbacksAndMessages(null)
        io.shutdownNow(); posters.shutdown(); backdrops.shutdown(); super.onDestroy()
    }

    /** Home never exits on Back. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_BACK) true else super.onKeyDown(keyCode, event)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // Home pressed while already on Home: back to the first card.
        focusSet = false
        ensureFocus()
        cardsScroll.smoothScrollTo(0, 0)
    }

    // ---- UI ----

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0f161b"))
            setPadding(dp(Tok.SAFE_X), dp(Tok.SAFE_Y), dp(Tok.SAFE_X), dp(Tok.SAFE_Y))
        }
        root.addView(topBar())
        root.addView(space(0, 20))
        root.addView(label(getString(R.string.continue_watching), 16, Tok.text72, bold = true))
        root.addView(space(0, 10))
        cards = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        cardsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            isFillViewport = false
            addView(cards)
        }
        root.addView(cardsScroll, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(space(0, 0).apply { layoutParams = lp(0, 0, 1f) })
        dock = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        root.addView(dock, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Default look is the flat dark background; the focused card's backdrop sits under a static scrim (no blur, no crossfade).
        backdrop = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
        val scrim = View(this).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(200, 15, 22, 27), Color.argb(150, 15, 22, 27), Color.argb(245, 15, 22, 27)),
            )
        }
        backdropScrim = scrim.also { it.visibility = View.GONE }
        return FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0f161b"))
            addView(backdrop, FrameLayout.LayoutParams(-1, -1))
            addView(scrim, FrameLayout.LayoutParams(-1, -1))
            addView(root, FrameLayout.LayoutParams(-1, -1))
        }
    }

    private fun backdropOn() = Prefs.backdrop(this)

    override fun onStart() {
        super.onStart()
        if (!backdropOn()) hideBackdrop() // the toggle lives on the "All apps" screen
    }

    private fun onCardFocus(e: WatchEntry, focused: Boolean) {
        backdropHandler.removeCallbacksAndMessages(null)
        if (!focused) {
            // focus left the card (to a neighbour card the next callback re-arms; to the dock/pills we clear)
            backdropHandler.postDelayed({ if (cards.focusedChild == null) hideBackdrop() }, BackdropRules.DELAY_MS)
            return
        }
        if (!backdropOn()) return
        val uri = BackdropRules.pick(e) ?: return
        backdropHandler.postDelayed({
            backdrops.load(uri, 960, 540) { bmp ->
                if (bmp != null && cards.focusedChild != null && backdropOn()) {
                    backdrop.setImageBitmap(bmp); backdrop.visibility = View.VISIBLE; backdropScrim.visibility = View.VISIBLE
                }
            }
        }, BackdropRules.DELAY_MS)
    }

    private fun hideBackdrop() {
        backdrop.visibility = View.GONE; backdropScrim.visibility = View.GONE; backdrop.setImageDrawable(null)
    }

    private fun topBar(): View {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val time = TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"
            textSize = 40f; setTextColor(Tok.text); includeFontPadding = false
            setTypeface(typeface, Typeface.BOLD)
        }
        val date = TextClock(this).apply {
            format24Hour = "EEEE, d MMMM"; format12Hour = "EEEE, d MMMM"
            textSize = 14f; setTextColor(Tok.text72); includeFontPadding = false
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(time); addView(date) }
        bar.addView(left, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bar.addView(searchBar(), lp(0, dp(44), 1f).apply { leftMargin = dp(24); rightMargin = dp(24) })

        val pill = pill(getString(R.string.vpn)) { apps.vpnSettings() }
        vpnDot = pill.getChildAt(0)
        vpnText = pill.getChildAt(1) as TextView
        bar.addView(pill, lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
        bar.addView(space(8))
        bar.addView(pill(getString(R.string.settings), withDot = false) { apps.settings() }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
        showVpn(VpnState.Off)
        return bar
    }

    /** Wide search field: stretches between the clock and the chips, opens Blue Box search on OK. */
    private fun searchBar(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            plate(22)
            contentDescription = getString(R.string.search)
            setOnClickListener { apps.search() }
        }
        val icon = ImageView(this).apply { setImageResource(android.R.drawable.ic_menu_search); alpha = 0.8f }
        row.addView(icon, lp(dp(20), dp(20)))
        val hint = label(getString(R.string.search_hint), 15, Tok.text72).apply { maxLines = 1; setPadding(dp(12), 0, 0, 0) }
        row.addView(hint, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.flipTexts(listOf(hint))
        return row
    }

    /** Chip: 36 dp tall, optional status dot, white plate on focus. */
    private fun pill(text: String, withDot: Boolean = true, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            plate(18)
            setOnClickListener { onClick() }
        }
        val dot = View(this).apply { background = rounded(Tok.off, dp(4).toFloat()); visibility = if (withDot) View.VISIBLE else View.GONE }
        row.addView(dot, lp(dp(8), dp(8)))
        val t = label(text, 14)
        row.addView(t, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (withDot) leftMargin = dp(8) })
        row.flipTexts(listOf(t))
        return row
    }

    private fun showVpn(s: VpnState) {
        val (color, textRes) = when (s) {
            VpnState.On -> Tok.ok to R.string.vpn_on
            VpnState.Off -> Tok.off to R.string.vpn_off
            VpnState.Connecting -> Tok.warn to R.string.vpn_connecting
            VpnState.Error -> Tok.error to R.string.vpn_error
        }
        vpnDot.background = rounded(color, dp(4).toFloat())
        vpnText.text = getString(textRes)
    }

    private fun showCards(rows: List<WatchEntry>) {
        cards.removeAllViews()
        if (rows.isEmpty()) { cards.addView(emptyCard()); return }
        rows.forEach { cards.addView(card(it), lp(dp(200), dp(112)).apply { rightMargin = dp(16) }) }
    }

    private fun card(e: WatchEntry): View {
        val frame = FrameLayout(this).apply {
            plate(12)
            setOnClickListener { e.intentUri?.let(apps::launchUri) }
            contentDescription = e.title
            setOnFocusChangeListener { _, f -> onCardFocus(e, f) }
        }
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setPadding(dp(3), dp(3), dp(3), dp(3)) }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        e.posterUri?.let { posters.load(it, dp(200), dp(112)) { bmp -> if (bmp != null) image.setImageBitmap(bmp) } }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(24), dp(10), dp(8))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.argb(220, 10, 14, 18)),
            )
        }
        val title = label(e.title, 14, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        box.addView(title)
        EntryRules.remainingMinutes(e)?.let { box.addView(label(getString(R.string.minutes_left, it), 12, Tok.text72)) }
        val pct = EntryRules.progressPct(e)
        if (pct > 0) {
            val bar = FrameLayout(this).apply { background = rounded(Color.argb(60, 255, 255, 255), dp(2).toFloat()) }
            bar.addView(View(this).apply { background = rounded(Tok.accentSoft, dp(2).toFloat()) },
                FrameLayout.LayoutParams(maxOf(dp(4), dp(180) * pct / 100), -1))
            box.addView(bar, lp(-1, dp(3)).apply { topMargin = dp(6) })
        }
        frame.addView(box, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        return frame
    }

    private fun emptyCard(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            plate(12)
            setOnClickListener { apps.launch(BLUEBOX_PACKAGE_FALLBACKS.firstOrNull { p -> installedPackage(p) } ?: "app.bluebox.tv") }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val t1 = label(getString(R.string.empty_title), 16, bold = true)
        val t2 = label(getString(R.string.empty_hint), 14, Tok.text72).apply { setPadding(0, dp(4), 0, 0) }
        val t3 = label(getString(R.string.open_bluebox), 14, Tok.accentSoft, bold = true).apply { setPadding(0, dp(8), 0, 0) }
        col.addView(t1); col.addView(t2); col.addView(t3)
        row.addView(col)
        row.flipTexts(listOf(t1, t3), listOf(t2))
        return row.also { it.layoutParams = lp(dp(560), dp(112)) }
    }

    private fun installedPackage(p: String) = runCatching { packageManager.getPackageInfo(p, 0); true }.getOrDefault(false)

    private fun showDock(installed: List<AppEntry>) {
        dock.removeAllViews()
        DockRules.pick(installed, packageName).forEach { a ->
            dock.addView(appTile(a.label, apps.icon(a.packageName)) { apps.launch(a.packageName) }, lp(dp(120), dp(88)).apply { rightMargin = dp(12) })
        }
        dock.addView(appTile(getString(R.string.all_apps), null) {
            startActivity(android.content.Intent(this, AllAppsActivity::class.java))
        }, lp(dp(120), dp(88)))
    }

    internal fun appTile(name: String, icon: android.graphics.drawable.Drawable?, onClick: () -> Unit): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            plate(12)
            setOnClickListener { onClick() }
            contentDescription = name
        }
        val img = ImageView(this).apply {
            if (icon != null) setImageDrawable(icon) else { setImageResource(R.drawable.ic_launcher); alpha = 0.9f }
        }
        col.addView(img, lp(dp(40), dp(40)))
        val t = label(name, 12).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(6), dp(6), dp(6), 0); gravity = Gravity.CENTER }
        col.addView(t, lp(-1, -2))
        col.flipTexts(listOf(t))
        return col
    }

    private fun ensureFocus() {
        if (focusSet) return
        val first = if (cards.childCount > 0) cards.getChildAt(0) else dock.getChildAt(0)
        if (first?.requestFocus() == true) focusSet = true
    }

    private companion object {
        val BLUEBOX_PACKAGE_FALLBACKS = DockRules.DEFAULT_PINNED.take(3)
    }
}
