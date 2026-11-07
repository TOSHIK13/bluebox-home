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
import app.bluebox.home.data.NotificationFeed
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
    private lateinit var vpnButton: View
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

    /**
     * First start asks for every grant the launcher can use, one step per resume (each opens a dialog or a system screen,
     * Back returns here and the next step follows): READ_TV_LISTINGS, usage access, notification access.
     * Each step is asked once; a refusal is final until the owner changes it in Settings → Разрешения.
     */
    private fun askPermissions() {
        val perm = "android.permission.READ_TV_LISTINGS"
        if (!Prefs.askedListings(this)) {
            Prefs.setAskedListings(this)
            if (checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                if (runCatching { requestPermissions(arrayOf(perm), 1) }.isSuccess) return
            }
        }
        if (!Prefs.asked(this, "usage")) {
            Prefs.setAsked(this, "usage")
            if (!apps.hasUsageAccess()) { apps.usageAccessSettings(); return }
        }
        if (!Prefs.asked(this, "notif")) {
            Prefs.setAsked(this, "notif")
            if (!NotificationFeed.hasAccess(this)) apps.notificationAccess()
        }
    }

    override fun onResume() {
        super.onResume()
        askPermissions()
        vpn.start()
        // a failure here must never take the launcher process down (it would restart in a loop)
        runCatching {
            io.execute {
                runCatching {
                    val rows = EntryRules.select(watchNext.load(), System.currentTimeMillis())
                    val installed = apps.installed()
                    val recent = apps.recent()
                    runOnUiThread { if (!isDestroyed) refresh(rows, installed, recent) }
                }
            }
        }
    }

    /** Rebuilds cards and dock, then puts focus back on the same tile (matched by description). */
    private fun refresh(rows: List<WatchEntry>, installed: List<AppEntry>, recent: List<String>) {
        val keep = currentFocus?.takeIf { it.parent === dock || it.parent === cards }?.contentDescription?.toString()
        showCards(rows); showDock(installed, recent)
        val same = keep?.let { k -> (0 until cards.childCount).map { cards.getChildAt(it) }.plus((0 until dock.childCount).map { dock.getChildAt(it) }).firstOrNull { it.contentDescription?.toString() == k } }
        if (same != null) same.requestFocus() else ensureFocus()
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
            setPadding(dp(Tok.SAFE_X), dp(Tok.SAFE_Y), dp(Tok.SAFE_X), dp(18))
        }
        root.addView(topBar())
        // empty space goes above "continue watching": the row sits low, right over the dock
        root.addView(space(0, 0).apply { layoutParams = lp(0, 0, 1f) })
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
        root.addView(space(0, 28))
        dock =LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
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
        if (!backdropOn()) hideBackdrop()
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
        time.textSize = 32f
        date.textSize = 12f
        date.gravity = Gravity.END
        time.gravity = Gravity.END
        val clock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(time); addView(date) }
        bar.addView(searchBar(), lp(0, dp(44), 1f).apply { rightMargin = dp(16) })

        val vpnBtn = iconButton(R.drawable.ic_vpn, getString(R.string.vpn), withDot = true) { apps.vpnSettings() }
        vpnBtn.tag = "vpn"
        vpnDot = (vpnBtn as FrameLayout).getChildAt(1)
        vpnButton = vpnBtn
        bar.addView(vpnBtn, lp(dp(44), dp(44)))
        bar.addView(space(8))
        bar.addView(iconButton(R.drawable.ic_settings, getString(R.string.settings)) { startActivity(android.content.Intent(this, QuickSettingsActivity::class.java)) }, lp(dp(44), dp(44)))
        bar.addView(space(24))
        bar.addView(clock, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        showVpn(VpnState.Off)
        return bar
    }

    /** Round 44 dp icon button; the icon flips dark on the white focus plate. Optional status dot in the corner. */
    private fun iconButton(res: Int, desc: String, withDot: Boolean = false, onClick: () -> Unit): View {
        val box = FrameLayout(this).apply { plate(22); contentDescription = desc; setOnClickListener { onClick() } }
        val icon = ImageView(this).apply { setImageResource(res); setColorFilter(Tok.text) }
        box.addView(icon, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        if (withDot) box.addView(View(this).apply { background = rounded(Tok.off, dp(4).toFloat()) },
            FrameLayout.LayoutParams(dp(9), dp(9), Gravity.TOP or Gravity.END).apply { topMargin = dp(8); rightMargin = dp(8) })
        box.setOnFocusChangeListener { _, f -> icon.setColorFilter(if (f) Tok.onPlate else Tok.text) }
        return box
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

    private fun showVpn(s: VpnState) {
        val (color, textRes) = when (s) {
            VpnState.On -> Tok.ok to R.string.vpn_on
            VpnState.Off -> Tok.off to R.string.vpn_off
            VpnState.Connecting -> Tok.warn to R.string.vpn_connecting
            VpnState.Error -> Tok.error to R.string.vpn_error
        }
        vpnDot.background = rounded(color, dp(4).toFloat())
        vpnButton.contentDescription = getString(textRes)
    }

    private fun showCards(rows: List<WatchEntry>) {
        cards.removeAllViews()
        if (rows.isEmpty()) { cards.addView(emptyCard()); return }
        rows.forEach { cards.addView(card(it), lp(dp(200), dp(112)).apply { rightMargin = dp(16) }) }
    }

    /**
     * Wide pictures fill the card (center-crop, only the sides are lost); portrait posters are shown whole, centered,
     * because any crop of a 2:3 poster to a 16:9 card cuts heads or the title.
     */
    private fun topCrop(bmp: android.graphics.Bitmap, w: Int, h: Int): android.graphics.Matrix {
        val portrait = bmp.height > bmp.width
        val s = if (portrait) h.toFloat() / bmp.height else maxOf(w.toFloat() / bmp.width, h.toFloat() / bmp.height)
        return android.graphics.Matrix().apply { setScale(s, s); postTranslate((w - bmp.width * s) / 2f, (h - bmp.height * s) / 2f) }
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
        (e.thumbUri?.takeIf { it.isNotBlank() } ?: e.posterUri?.takeIf { it.isNotBlank() })?.let { posters.load(it, dp(200), dp(112)) { bmp -> if (bmp != null) { image.scaleType = ImageView.ScaleType.MATRIX; image.imageMatrix = topCrop(bmp, dp(200) - dp(6), dp(112) - dp(6)); image.setImageBitmap(bmp) } } }

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

    /** Long OK on any dock tile opens the dock editor (move / remove / add). */
    private fun editDock(index: Int) =
        startActivity(android.content.Intent(this, DockEditActivity::class.java).putExtra(DockEditActivity.EXTRA_INDEX, index))

    private fun showDock(installed: List<AppEntry>, recent: List<String>) {
        dock.removeAllViews()
        // the editor works on the normalized slot list, so the index is the position among tokens, empty recent slots included
        DockRules.slots(installed, packageName, Prefs.dock(this), recent).forEachIndexed { i, s ->
            val a = s.app ?: return@forEachIndexed
            dock.addView(dockTile(a.label, apps.icon(a.packageName), null, i) { apps.launch(a.packageName) }, lp(dp(56), dp(56)).apply { rightMargin = dp(10) })
        }
        dock.addView(dockTile(getString(R.string.all_apps), null, R.drawable.ic_all_apps, -1) {
            startActivity(android.content.Intent(this, AllAppsActivity::class.java))
        }, lp(dp(56), dp(56)))
    }

    /** Compact icon-only favourite: no caption (it is in contentDescription), white plate on focus. */
    private fun dockTile(name: String, icon: android.graphics.drawable.Drawable?, iconRes: Int?, index: Int, onClick: () -> Unit): View {
        val box = FrameLayout(this).apply { plate(12); setOnClickListener { onClick() }; setOnLongClickListener { editDock(index); true }; contentDescription = name }
        val img = ImageView(this).apply {
            if (icon != null) setImageDrawable(icon) else if (iconRes != null) { setImageResource(iconRes); setColorFilter(Tok.text) }
        }
        box.addView(img, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
        if (icon == null) box.setOnFocusChangeListener { _, f -> img.setColorFilter(if (f) Tok.onPlate else Tok.text) }
        return box
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
