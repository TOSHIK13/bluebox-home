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
import android.widget.ScrollView
import android.graphics.drawable.StateListDrawable
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

/** Home screen "A": left dock of apps, full-bleed backdrop, title over a row of wide "continue watching" cards. Plain Views. */
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

    // ---- UI (design A: dock on the left, backdrop full-bleed, title over a row of wide cards) ----

    private lateinit var dockPanel: FrameLayout
    private lateinit var dockScroll: ScrollView
    private lateinit var titleView: TextView
    private lateinit var content: View
    private val dockLabels = ArrayList<TextView>()
    private var dockExpanded = false

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Tok.surface); clipChildren = false }

        backdrop = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        // static scrims: darker on the left (under the dock and the title), darker at the bottom (under the cards)
        backdropScrim = View(this).apply { visibility = View.GONE; setBackgroundColor(Color.argb(60, 21, 33, 42)) }
        root.addView(backdropScrim, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.argb(179, 21, 33, 42), Color.argb(0, 21, 33, 42)),
            )
        }, FrameLayout.LayoutParams(resources.displayMetrics.widthPixels * 55 / 100, -1))
        root.addView(View(this).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(0, 21, 33, 42), Color.argb(235, 21, 33, 42)),
            )
        }, FrameLayout.LayoutParams(-1, dp(250), Gravity.BOTTOM))

        // title block + cards, bottom-anchored, 104 dp from the left edge (72 dock + 32 gap)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        column.addView(label(getString(R.string.continue_watching), 14, Tok.text72, bold = true), lp(-2, -2).apply { leftMargin = dp(16) })
        titleView = label("", 42).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setShadowLayer(dp(8).toFloat(), 0f, dp(1).toFloat(), Color.argb(130, 0, 0, 0))
        }
        column.addView(titleView, lp(-1, -2).apply { topMargin = dp(4); leftMargin = dp(16) })
        cards = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; setPadding(dp(16), 0, dp(16), 0) }
        cardsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipToPadding = false; clipChildren = false; isFillViewport = false
            addView(cards)
        }
        column.addView(cardsScroll, lp(-1, dp(104)).apply { topMargin = dp(22) })
        content = column
        root.addView(column, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { leftMargin = dp(88); bottomMargin = dp(44) })

        // clock top-right
        val time = TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"; textSize = 22f; setTextColor(Tok.text); includeFontPadding = false
            setShadowLayer(dp(6).toFloat(), 0f, dp(1).toFloat(), Color.argb(130, 0, 0, 0))
        }
        root.addView(time, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = dp(28); rightMargin = dp(48) })

        // dock on the left; grows to 240 dp with captions while focus is inside
        dock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(20), dp(14), dp(20)) }
        dockScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(dock) }
        dockPanel = FrameLayout(this).apply { setBackgroundColor(Color.argb(115, 10, 14, 18)); addView(dockScroll, FrameLayout.LayoutParams(-1, -1)) }
        root.addView(dockPanel, FrameLayout.LayoutParams(dp(72), -1))
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, now -> setDockExpanded(now != null && isInDock(now)) }

        vpnDot = View(this); vpnButton = View(this) // replaced when the dock is built
        showVpn(VpnState.Off)
        return root
    }

    private fun isInDock(v: View): Boolean {
        var p: android.view.ViewParent? = v.parent
        while (p != null) { if (p === dockPanel) return true; p = p.parent }
        return false
    }

    private fun setDockExpanded(on: Boolean) {
        if (on == dockExpanded) return
        dockExpanded = on
        dockPanel.layoutParams = dockPanel.layoutParams.apply { width = dp(if (on) 240 else 72) }
        dockPanel.setBackgroundColor(Color.argb(if (on) 209 else 115, 10, 14, 18))
        dockPanel.elevation = if (on) dp(8).toFloat() else 0f
        content.alpha = if (on) 0.7f else 1f // as in A3: the content stays in place, slightly dimmed
        dockLabels.forEach { it.visibility = if (on) View.VISIBLE else View.GONE }
    }

    private fun backdropOn() = Prefs.backdrop(this)

    override fun onStart() {
        super.onStart()
        if (!backdropOn()) hideBackdrop()
    }

    private fun onCardFocus(e: WatchEntry, focused: Boolean) {
        backdropHandler.removeCallbacksAndMessages(null)
        if (!focused) {
            // focus left the card (to a neighbour card the next callback re-arms; to the dock we clear)
            backdropHandler.postDelayed({ if (cards.focusedChild == null) hideBackdrop() }, BackdropRules.DELAY_MS)
            return
        }
        titleView.text = e.title
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
        if (rows.isEmpty()) { titleView.text = ""; cards.addView(emptyCard()); return }
        rows.forEach { cards.addView(card(it), lp(dp(168), dp(94)).apply { rightMargin = dp(16); gravity = Gravity.CENTER_VERTICAL }) }
        titleView.text = rows.first().title
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

    /** Small dark chip over the picture (episode tag) or an accent chip ("НОВАЯ"). */
    private fun chip(text: String, fill: Int, gravity: Int, left: Int, top: Int, bottom: Int): TextView =
        label(text, 11, Tok.text, bold = true).apply {
            this.gravity = Gravity.CENTER
            setPadding(dp(6), 0, dp(6), 0)
            background = rounded(fill, dp(3).toFloat())
            layoutParams = FrameLayout.LayoutParams(-2, dp(18), gravity).apply { leftMargin = dp(left); topMargin = dp(top); bottomMargin = dp(bottom) }
        }

    private fun card(e: WatchEntry): View {
        val w = dp(168); val h = dp(94)
        val frame = FrameLayout(this).apply {
            background = rounded(Tok.surface2, dp(8).toFloat()); clipToOutline = true
            isFocusable = true; isFocusableInTouchMode = false; isClickable = true
            // focus: white 3 dp ring and a 184x104 card (168x94 scaled by 1.095, neighbours keep their 16 dp gap)
            foreground = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.TRANSPARENT); setStroke(dp(3), Tok.plate); cornerRadius = dp(8).toFloat()
                })
            }
            setOnClickListener { e.intentUri?.let(apps::launchUri) }
            contentDescription = e.title
            setOnFocusChangeListener { v, f -> v.scaleX = if (f) 1.095f else 1f; v.scaleY = v.scaleX; onCardFocus(e, f) }
        }
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        (e.thumbUri?.takeIf { it.isNotBlank() } ?: e.posterUri?.takeIf { it.isNotBlank() })?.let {
            posters.load(it, w, h) { bmp -> if (bmp != null) { image.scaleType = ImageView.ScaleType.MATRIX; image.imageMatrix = topCrop(bmp, w, h); image.setImageBitmap(bmp) } }
        }
        if (EntryRules.isNew(e)) frame.addView(chip(getString(R.string.badge_new), Tok.accentFill, Gravity.TOP or Gravity.START, 8, 8, 0))
        EntryRules.episodeTag(e)?.let { t ->
            val text = when {
                t.movie -> getString(R.string.badge_movie)
                t.season != null -> getString(R.string.badge_se, t.season, t.episode)
                else -> getString(R.string.badge_e, t.episode)
            }
            frame.addView(chip(text, Color.argb(153, 0, 0, 0), Gravity.BOTTOM or Gravity.START, 8, 0, 10).apply { textSize = 12f })
        }
        val pct = EntryRules.progressPct(e)
        if (pct > 0) {
            frame.addView(View(this).apply { setBackgroundColor(Tok.accentFill) },
                FrameLayout.LayoutParams(maxOf(dp(4), w * pct / 100), dp(4), Gravity.BOTTOM or Gravity.START))
        }
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
        return row.also { it.layoutParams = lp(dp(560), dp(104)) }
    }

    private fun installedPackage(p: String) = runCatching { packageManager.getPackageInfo(p, 0); true }.getOrDefault(false)

    /** Long OK on any dock tile opens the dock editor (move / remove / add). */
    private fun editDock(index: Int) =
        startActivity(android.content.Intent(this, DockEditActivity::class.java).putExtra(DockEditActivity.EXTRA_INDEX, index))

    private fun showDock(installed: List<AppEntry>, recent: List<String>) {
        dock.removeAllViews(); dockLabels.clear()
        dockItem(getString(R.string.search), null, android.R.drawable.ic_menu_search, -1, round = true) { apps.search() }
        // the editor works on the normalized slot list, so the index is the position among tokens, empty recent slots included
        DockRules.slots(installed, packageName, Prefs.dock(this), recent).forEachIndexed { i, sl ->
            val a = sl.app ?: return@forEachIndexed
            dockItem(a.label, apps.icon(a.packageName), null, i) { apps.launch(a.packageName) }
        }
        dockItem(getString(R.string.all_apps), null, R.drawable.ic_all_apps, -1) { startActivity(android.content.Intent(this, AllAppsActivity::class.java)) }
        val v = dockItem(getString(R.string.vpn), null, R.drawable.ic_vpn, -1, dot = true) { apps.vpnSettings() }
        vpnButton = v; vpnDot = v.findViewWithTag("dot")
        dockItem(getString(R.string.settings), null, R.drawable.ic_settings, -1) { startActivity(android.content.Intent(this, QuickSettingsActivity::class.java)) }
        showVpn(VpnState.Off)
        dockLabels.forEach { it.visibility = if (dockExpanded) View.VISIBLE else View.GONE }
    }

    /**
     * One dock row: 44 dp icon (round for search) + caption shown only while the dock is expanded.
     * Focus = translucent plate with a 2 dp white ring. Long OK on an app opens the dock editor.
     */
    private fun dockItem(name: String, icon: android.graphics.drawable.Drawable?, iconRes: Int?, index: Int, round: Boolean = false, dot: Boolean = false, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            contentDescription = name
            isFocusable = true; isFocusableInTouchMode = false; isClickable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.argb(41, 255, 255, 255)); setStroke(dp(2), Tok.plate); cornerRadius = dp(if (round) 22 else 8).toFloat()
                })
            }
            setOnClickListener { onClick() }
            if (index >= 0) setOnLongClickListener { editDock(index); true }
        }
        val box = FrameLayout(this)
        val img = ImageView(this)
        if (icon != null) { img.setImageDrawable(icon); box.addView(img, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER)) }
        else {
            box.background = rounded(Color.argb(31, 255, 255, 255), dp(if (round) 22 else 8).toFloat())
            img.setImageResource(iconRes ?: 0); img.setColorFilter(Tok.text)
            box.addView(img, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        }
        if (dot) box.addView(View(this).apply { tag = "dot"; background = rounded(Tok.off, dp(4).toFloat()) },
            FrameLayout.LayoutParams(dp(9), dp(9), Gravity.TOP or Gravity.END).apply { topMargin = dp(4); rightMargin = dp(4) })
        row.addView(box, lp(dp(44), dp(44)))
        val cap = label(name, 16, Tok.text, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(12), 0, dp(8), 0); visibility = View.GONE }
        row.addView(cap, lp(0, -2, 1f))
        dockLabels += cap
        dock.addView(row, lp(-1, dp(48)).apply { bottomMargin = dp(6) })
        return row
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
