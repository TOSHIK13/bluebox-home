package app.bluebox.home

import android.animation.ObjectAnimator
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
import android.graphics.drawable.StateListDrawable
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import app.bluebox.home.data.Apps
import app.bluebox.home.logic.ChannelRow
import app.bluebox.home.logic.ChannelRules
import app.bluebox.home.data.ChannelSource
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
    private lateinit var channelSource: ChannelSource
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
        runCatching {
            for (u in listOf(android.media.tv.TvContract.WatchNextPrograms.CONTENT_URI, android.media.tv.TvContract.PreviewPrograms.CONTENT_URI, android.media.tv.TvContract.Channels.CONTENT_URI))
                contentResolver.registerContentObserver(u, true, observer)
        }
        backdrops = Posters(this, maxCacheKb = 6 * 1024)
        apps = Apps(this)
        watchNext = WatchNextSource(this)
        channelSource = ChannelSource(this)
        posters = Posters(this, maxCacheKb = (Runtime.getRuntime().maxMemory() / 8 / 1024).toInt().coerceIn(4 * 1024, 32 * 1024))
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

    private val dataChanged = java.util.concurrent.atomic.AtomicBoolean(true) // set by the provider's change notification
    private var loadedAt = 0L
    private var lastLoadEmpty = true
    @Volatile private var lastRows: List<WatchEntry> = emptyList()
    @Volatile private var lastChannels: List<ChannelRow> = emptyList()
    private val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { dataChanged.set(true) }
    }

    override fun onResume() {
        super.onResume()
        askPermissions()
        vpn.start()
        NotificationFeed.badge = { runOnUiThread { if (!isDestroyed) updateBadge() } }
        updateBadge()
        Thread { runCatching { app.bluebox.home.data.UpdateCheck.maybe(applicationContext) } }.start() // not on io: a slow network must not delay the cards
        // coming back from an app usually changes nothing: read the provider again only when it said so (or once a minute, as a net)
        val stale = android.os.SystemClock.elapsedRealtime() - loadedAt > 60_000L
        val reload = dataChanged.getAndSet(false) || stale || lastLoadEmpty || !cardsBuilt
        if (reload) loadedAt = android.os.SystemClock.elapsedRealtime()
        // a failure here must never take the launcher process down (it would restart in a loop)
        runCatching {
            io.execute {
                runCatching {
                    if (reload) {
                        lastRows = EntryRules.select(watchNext.load().filter { it.sourcePackage !in Prefs.hiddenWatchApps(this) }, System.currentTimeMillis())
                        lastChannels = ChannelRules.visible(
                            ChannelRules.arrange(channelSource.load().filter { debuggable || !it.packageName.startsWith("app.bluebox.home") }, Prefs.channelOrder(this)),
                            Prefs.hiddenChannels(this),
                        ).filter { it.entries.isNotEmpty() }
                    }
                    val rows = lastRows; val channels = lastChannels
                    val installed = apps.installed()
                    val recent = apps.recent()
                    channels.forEach { labels.getOrPut(it.packageName) { appLabel(it.packageName) } }
                    installed.forEach { a -> if (!icons.containsKey(a.packageName)) apps.icon(a.packageName)?.let { icons[a.packageName] = it } }
                    runOnUiThread { lastLoadEmpty = rows.isEmpty() && channels.isEmpty(); if (!isDestroyed) refresh(rows, channels, installed, recent) }
                }
            }
        }
    }

    /** Rebuilds cards and dock, then puts focus back on the same tile (matched by description). */
    private fun refresh(rows: List<WatchEntry>, channels: List<ChannelRow>, installed: List<AppEntry>, recent: List<String>) {
        // coming back from Settings or the quick panel usually brings the same data: rebuild only what really changed
        // (a rebuild resets the shelves' scroll and animation state and reloads every picture)
        // the playback position and "last watched" time change on every play: only the shown percentage counts, not the raw numbers
        fun shape(e: WatchEntry) = e.copy(positionMs = EntryRules.progressPct(e).toLong(), lastEngagementMs = 0L)
        val cardsKey = listOf(rows.map(::shape), channels.map { c -> c.copy(entries = c.entries.map(::shape)) })
        val dockKey = listOf(installed, recent, Prefs.dock(this))
        val cardsSame = cardsKey == lastCardsKey && cardsBuilt
        val dockSame = dockKey == lastDockKey
        if (cardsSame && dockSame) return
        lastCardsKey = cardsKey; lastDockKey = dockKey
        val keep = currentFocus?.takeIf { it.parent === dock || it.parent === dockBottom || blocks.any { b -> it.parent === b.cards } }?.contentDescription?.toString()
        if (!cardsSame) { showCards(rows, channels); cardsBuilt = true }
        if (!dockSame || !cardsSame) showDock(installed, recent)
        if (editing) { focusEditRow(); return }
        val same = keep?.let { k -> blocks.flatMap { b -> (0 until b.cards.childCount).map { b.cards.getChildAt(it) } }.plus(dockViews()).firstOrNull { it.contentDescription?.toString() == k } }
        if (same != null) same.requestFocus() else ensureFocus()
    }

    private var lastCardsKey: List<Any?>? = null
    private var lastDockKey: List<Any?>? = null
    private var cardsBuilt = false

    private fun dockViews(): List<View> = (0 until dock.childCount).map { dock.getChildAt(it) } + (0 until dockBottom.childCount).map { dockBottom.getChildAt(it) }

    override fun onPause() { vpn.stop(); NotificationFeed.badge = null; super.onPause() }

    override fun onDestroy() {
        runCatching { contentResolver.unregisterContentObserver(observer) }
        backdropHandler.removeCallbacksAndMessages(null)
        io.shutdownNow(); posters.shutdown(); backdrops.shutdown(); super.onDestroy()
    }

    /** Home never exits on Back. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_BACK) true else super.onKeyDown(keyCode, event)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // Home pressed while already on Home: leave editing, back to the first card.
        if (editing) exitEdit()
        focusSet = false
        ensureFocus()
        cardsScroll.smoothScrollTo(0, 0)
        if (blocks.isNotEmpty()) scrollToBlock(blocks[0])
    }

    // ---- UI (design A: dock on the left, backdrop full-bleed, title over a row of wide cards) ----

    private lateinit var dockPanel: FrameLayout
    private lateinit var dockBottom: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var content: View
    private val dockLabels = ArrayList<TextView>()
    private var dockExpanded = false

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Tok.surface); clipChildren = false }

        backdrop = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        // vertical posters take the right ~third of the screen, fading into the background on their left edge
        posterSide = FrameLayout(this).apply {
            visibility = View.GONE
            addView(ImageView(context).apply { tag = "poster"; scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(-1, -1))
            addView(View(context).apply {
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Tok.surface, Color.argb(0, 21, 33, 42)),
                )
            }, FrameLayout.LayoutParams(dp(180), -1, Gravity.START))
        }
        root.addView(posterSide, FrameLayout.LayoutParams(dp(520), -1, Gravity.END))
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

        // title block + cards, bottom-anchored. The column starts exactly at the collapsed dock's right edge (72 dp), so the row
        // is clipped there and never slides under the dock; the 32 dp padding keeps the first card's ring inside the clip.
        val main = makeBlock(getString(R.string.continue_watching), 42)
        blocks += main
        titleView = main.title; cards = main.cards; cardsScroll = main.scroll
        // rows: "continue watching" first, then other apps' own channels below; the list scrolls vertically so the focused row sits at the top
        extra = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        topSpacer = View(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(topSpacer, lp(-1, 0)); addView(main.root); addView(extra); addView(View(context), lp(-1, dp(SHELF_BOTTOM)))
        }
        rowsScroll = object : ScrollView(this) {
            // the shelves are placed by scrollToBlock; the default "scroll the focused card just into view" would undo it
            override fun computeScrollDeltaToGetChildRectOnScreen(rect: android.graphics.Rect): Int = 0
        }.apply { isVerticalScrollBarEnabled = false; addView(body) }
        content = rowsScroll
        rowsScroll.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob -> if (b - t != ob - ot && blocks.isNotEmpty()) rowsScroll.post { fitSpacer() } }
        root.addView(rowsScroll, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { leftMargin = dp(72) })

        // clock top-right
        val time = TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"; textSize = 22f; setTextColor(Tok.text); includeFontPadding = false
            setShadowLayer(dp(6).toFloat(), 0f, dp(1).toFloat(), Color.argb(130, 0, 0, 0))
        }
        root.addView(time, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = dp(28); rightMargin = dp(48) })

        // dock on the left; grows to 240 dp with captions while focus is inside. No scrolling: as many apps as fit the screen
        // from the top, "all apps / VPN / settings" glued to the bottom.
        dock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dockBottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val dockColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(20), dp(14), dp(20)) }
        dockColumn.addView(dock, lp(-1, 0, 1f)); dockColumn.addView(dockBottom, lp(-1, -2))
        dockPanel = FrameLayout(this).apply { setBackgroundColor(Color.argb(115, 10, 14, 18)); addView(dockColumn, FrameLayout.LayoutParams(-1, -1)) }
        root.addView(dockPanel, FrameLayout.LayoutParams(dp(72), -1))
        buildEditPopup(root)
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            setDockExpanded(now != null && (isIn(now, dockPanel) || isIn(now, editPopup)))
            if (editing && !moving && !rebuilding) onEditFocus(now)
        }

        vpnDot = View(this); vpnButton = View(this) // replaced when the dock is built
        showVpn(VpnState.Off)
        return root
    }

    private fun isIn(v: View, ancestor: View): Boolean {
        var p: android.view.ViewParent? = v.parent
        while (p != null) { if (p === ancestor) return true; p = p.parent }
        return false
    }

    private fun setDockExpanded(on: Boolean) {
        if (on == dockExpanded) return
        dockExpanded = on
        dockPanel.layoutParams = dockPanel.layoutParams.apply { width = dp(if (on) 240 else 72) }
        dockPanel.setBackgroundColor(Color.argb(if (on) 209 else 115, 10, 14, 18))
        content.alpha = if (on) 0.7f else 1f // as in A3: the content stays in place, slightly dimmed
        dockLabels.forEach { it.visibility = if (on) View.VISIBLE else View.GONE }
    }

    private fun backdropOn() = Prefs.backdrop(this)

    override fun onStart() {
        super.onStart()
        if (!backdropOn()) hideBackdrop()
    }

    override fun onStop() {
        backdropHandler.removeCallbacksAndMessages(null)
        backdrops.evictAll() // a backdrop is ~1 MB: nothing to keep while another app is on screen
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) { posters.evictAll(); backdrops.evictAll() }
    }

    private var wantedBackdrop: String? = null // what the focused card asks for now: an older answer must not replace it
    private var shownBackdrop: String? = null

    private fun anyCardFocused() = blocks.any { it.cards.focusedChild != null }

    private fun onCardFocus(e: WatchEntry, b: Block, focused: Boolean) {
        backdropHandler.removeCallbacksAndMessages(null)
        if (!focused) {
            // focus left the card (to a neighbour card the next callback re-arms; to the dock we clear)
            backdropHandler.postDelayed({ if (!anyCardFocused()) hideBackdrop() }, BackdropRules.DELAY_MS)
            return
        }
        b.title.text = e.title
        if (!backdropOn()) return
        val uri = BackdropRules.pick(e) ?: return
        wantedBackdrop = uri
        if (uri == shownBackdrop) return // the same picture is already on screen
        backdropHandler.postDelayed({
            backdrops.load(uri, 960, 540) { bmp ->
                if (bmp != null && uri == wantedBackdrop && anyCardFocused() && backdropOn()) {
                    shownBackdrop = uri
                    if (bmp.height > bmp.width * 1.1) {
                        // vertical poster: shown whole on the right part of the screen, the rest stays the flat dark background
                        backdrop.visibility = View.GONE; backdropScrim.visibility = View.GONE
                        posterSide.findViewWithTag<ImageView>("poster").setImageBitmap(bmp); posterSide.visibility = View.VISIBLE
                    } else {
                        posterSide.visibility = View.GONE
                        backdrop.setImageBitmap(bmp); backdrop.visibility = View.VISIBLE; backdropScrim.visibility = View.VISIBLE
                    }
                }
            }
        }, BackdropRules.DELAY_MS)
    }

    private fun hideBackdrop() {
        wantedBackdrop = null; shownBackdrop = null
        backdrop.visibility = View.GONE; backdropScrim.visibility = View.GONE; backdrop.setImageDrawable(null)
        posterSide.visibility = View.GONE; posterSide.findViewWithTag<ImageView>("poster").setImageDrawable(null)
    }

    private lateinit var posterSide: FrameLayout

    private var gearBadge: View? = null

    private fun updateBadge() {
        gearBadge?.visibility = if (NotificationFeed.hasUnread()) View.VISIBLE else View.GONE
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

    /** One shelf: small header, big title of the focused card, horizontal strip of cards. */
    private class Block(val root: View, val title: TextView, val cards: LinearLayout, val scroll: HorizontalScrollView)

    private lateinit var rowsScroll: ScrollView
    private lateinit var extra: LinearLayout
    private lateinit var topSpacer: View
    private val blocks = ArrayList<Block>()

    private fun makeBlock(header: String, titleSp: Int, stripH: Int = 116): Block {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        col.addView(label(header, 14, Tok.text72, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
            lp(-2, -2).apply { leftMargin = dp(32) })
        val title = label("", titleSp).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setShadowLayer(dp(8).toFloat(), 0f, dp(1).toFloat(), Color.argb(130, 0, 0, 0))
        }
        // fixed height: glyphs from fallback fonts (►, quotes) make a line taller, and the shelf must not jump between cards
        title.gravity = android.view.Gravity.CENTER_VERTICAL; title.includeFontPadding = false
        col.addView(title, lp(-1, dp(titleSp * 14 / 10)).apply { topMargin = dp(4); leftMargin = dp(32) })
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipToPadding = false; setPadding(dp(32), 0, dp(32), 0) }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; isFillViewport = false // clips at its own bounds = the dock's right edge
            addView(strip)
        }
        // 116 dp: a focused card (94 dp x 1.095 plus the 3 dp ring) needs room above and below or the ring is cut
        col.addView(scroll, lp(-1, dp(stripH)).apply { topMargin = dp(10) })
        return Block(col, title, strip, scroll)
    }

    /** Height of the tallest shelf (kept for the layout of the first one). */
    private var shelfH = 0
    private val debuggable get() = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 // debug builds show their own seeded channels
    private var lastFocused = 0
    private var lastMoveAt = 0L
    private val fades = ArrayList<android.animation.ObjectAnimator>() // alpha of a shelf coming from above is delayed: a plain animator, cancelled on the next move
    private var lastTarget = 0

    /** The real height of the scroll window (after layout), not the display's: the window may be shorter than the screen. */
    private fun windowH() = rowsScroll.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
    private var anim = 0 // bumped on every jump: end actions of an older jump must not touch the shelves any more

    /** The first shelf sits on the bottom line when not scrolled; shorter than the window by the spacer on top. */
    private fun fitSpacer() {
        val h = maxOf(0, windowH() - dp(SHELF_BOTTOM) - blocks[0].root.measuredHeight)
        if (topSpacer.layoutParams.height != h) topSpacer.layoutParams = topSpacer.layoutParams.apply { height = h }
    }
    private fun bodyBottom(b: Block) = if (b === blocks[0]) b.root.bottom else extra.top + b.root.bottom

    /**
     * The focused shelf is bottom-anchored where the single row used to be. The shelf before it slides up off the screen and fades out
     * (the centre stays clean); shelves further up are hidden; the next one only shows its header under the focused one.
     */
    private fun scrollToBlock(b: Block, animate: Boolean = true) {
        val i = blocks.indexOf(b)
        val k = lastFocused
        val target = maxOf(0, bodyBottom(b) - (windowH() - dp(SHELF_BOTTOM)))
        val shift = (target - lastTarget).toFloat() // where a shelf was before the jump, relative to where it is now
        if (animate && i == k && target == lastTarget) return
        lastFocused = i; lastTarget = target
        val gen = ++anim
        loadPictures(i - 1); loadPictures(i); loadPictures(i + 1)
        fades.forEach { it.cancel() }; fades.clear()
        rowsScroll.scrollTo(0, target) // the jump is instant; every shelf is then animated from its old place by hand
        val now = android.os.SystemClock.uptimeMillis()
        val fast = now - lastMoveAt < 220 // key held or tapped quickly: shorter moves, so they do not pile up
        lastMoveAt = now
        val k1 = if (fast) 0.5f else 1f
        val enter = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
        val leave = android.view.animation.PathInterpolator(0.3f, 0f, 1f, 1f)
        val slide = dp(72).toFloat()
        blocks.forEachIndexed { j, x ->
            val v = x.root
            v.animate().cancel()
            v.animate().setStartDelay(0) // a delay set earlier would otherwise stick to the next run
            val alpha = when { j == i -> 1f; j == i + 1 -> 0f; else -> 0f }
            if (!animate) { v.alpha = alpha; v.translationY = 0f; return@forEachIndexed }
            // the scroll jumped by `shift`: add it to where the shelf is now, so it carries on from the same place on screen
            val from = v.translationY + shift
            when {
                j == i && k < i -> { // came up from below: rises to its place
                    v.translationY = from
                    v.animate().translationY(0f).alpha(1f).setDuration((280 * k1).toLong()).setInterpolator(enter).withLayer().start()
                }
                j == i -> { // came down from above: flies down to its place, shows itself only in the last part of the way
                    v.translationY = from.coerceIn(-dp(300).toFloat(), dp(300).toFloat()); v.alpha = 0f
                    v.animate().translationY(0f).setDuration((280 * k1).toLong()).setInterpolator(enter).withLayer().start()
                    fades += ObjectAnimator.ofFloat(v, View.ALPHA, 0f, 1f).apply { startDelay = (110 * k1).toLong(); duration = (170 * k1).toLong(); start() }
                }
                j == k && k < i -> { // left upwards: leaves from its old place, gone before the middle
                    v.translationY = from
                    v.animate().translationY(from - slide).alpha(0f).setDuration((140 * k1).toLong()).setInterpolator(leave)
                        .withEndAction { if (gen == anim) v.translationY = 0f }.withLayer().start()
                }
                j == k && j == i + 1 -> { // left downwards and is now the "next" header: settles to the bottom edge, no blinking
                    v.translationY = from
                    v.animate().translationY(0f).alpha(0f).setDuration((280 * k1).toLong()).setInterpolator(enter).withLayer().start()
                }
                j == k -> { // left downwards, further away
                    v.translationY = from
                    v.animate().translationY(from + slide).alpha(0f).setDuration((140 * k1).toLong()).setInterpolator(leave)
                        .withEndAction { if (gen == anim) v.translationY = 0f }.withLayer().start()
                }
                j == i + 1 -> { // the next shelf shows only its header, a moment after the others
                    v.translationY = 0f
                    v.alpha = 0f // the shelf below stays invisible: no bits of its text (descenders) peek out at the bottom edge
                }
                else -> { v.alpha = 0f; v.translationY = 0f }
            }
        }
    }

    /** How much of the previous shelf stays visible above the focused one: just the bottom edge of its cards. */
    private val PEEK_TOP get() = dp(34)

    private val labels = java.util.concurrent.ConcurrentHashMap<String, String>() // filled on the io thread
    private val icons = java.util.concurrent.ConcurrentHashMap<String, android.graphics.drawable.Drawable>()

    private fun appLabel(pkg: String): String =
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    private fun showCards(rows: List<WatchEntry>, channels: List<ChannelRow>) {
        val main = blocks[0]
        while (blocks.size > 1) blocks.removeAt(blocks.size - 1)
        extra.removeAllViews()
        cards.removeAllViews()
        if (rows.isEmpty()) { titleView.text = ""; cards.addView(emptyCard()) }
        else {
            rows.forEach { cards.addView(card(it, main), lp(dp(168), dp(94)).apply { rightMargin = dp(16); gravity = Gravity.CENTER_VERTICAL }) }
            titleView.text = rows.first().title
        }
        channels.forEach { ch ->
            val portrait = ch.portrait
            val cw = if (portrait) 96 else 168; val chh = if (portrait) 144 else 94
            val b = makeBlock("${ch.name.ifBlank { ch.packageName }} · ${labels[ch.packageName] ?: appLabel(ch.packageName)}", 42, if (portrait) 168 else 116)
            b.root.setPadding(0, dp(10), 0, 0)
            ch.entries.forEach { b.cards.addView(card(it, b, cw, chh), lp(dp(cw), dp(chh)).apply { rightMargin = dp(16); gravity = Gravity.CENTER_VERTICAL }) }
            b.title.text = ch.entries.first().title
            blocks += b; extra.addView(b.root)
        }
        // the window is the tallest shelf + 38 dp; shorter shelves are pushed down by the top spacer so the first one stays where it was
        val w = View.MeasureSpec.makeMeasureSpec(resources.displayMetrics.widthPixels - dp(72), View.MeasureSpec.EXACTLY)
        val unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        blocks.forEach { it.root.measure(w, unspec) }
        shelfH = blocks.maxOf { it.root.measuredHeight }
        rowsScroll.layoutParams = (rowsScroll.layoutParams as FrameLayout.LayoutParams).apply { height = -1; bottomMargin = 0 }
        fitSpacer()
        // up from the first row and down from the last stay put (the dock is to the left, never above/below)
        lockEdge(blocks.first(), up = true); lockEdge(blocks.last(), up = false)
        rowsScroll.scrollTo(0, 0); lastFocused = 0; lastTarget = 0
        loadPictures(0); loadPictures(1)
        blocks.forEachIndexed { j, x -> x.root.translationY = 0f; x.root.alpha = if (j <= 1) (if (j == 0) 1f else 0f) else 0f }
    }

    /** Starts the picture loads of a shelf, once. Shelves far from the focused one stay empty until the user comes close. */
    private fun loadPictures(j: Int) {
        val b = blocks.getOrNull(j) ?: return
        for (n in 0 until b.cards.childCount) {
            val v = b.cards.getChildAt(n)
            (v.tag as? Runnable)?.run()
            v.tag = null
        }
    }

    private fun lockEdge(b: Block, up: Boolean) {
        for (i in 0 until b.cards.childCount) b.cards.getChildAt(i).let { if (up) it.nextFocusUpId = it.id else it.nextFocusDownId = it.id }
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

    private fun card(e: WatchEntry, b: Block, wDp: Int = 168, hDp: Int = 94): View {
        val w = dp(wDp); val h = dp(hDp)
        val frame = FrameLayout(this).apply {
            background = rounded(Tok.surface2, dp(8).toFloat()); clipToOutline = true
            isFocusable = true; isFocusableInTouchMode = false; isClickable = true
            // the first row's up and the last row's down are pinned in lockEdge; between rows the system picks the card below/above
            id = View.generateViewId()
            // focus: white 3 dp ring and a 184x104 card (168x94 scaled by 1.095, neighbours keep their 16 dp gap)
            foreground = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.TRANSPARENT); setStroke(dp(3), Tok.plate); cornerRadius = dp(8).toFloat()
                })
            }
            setOnClickListener { e.intentUri?.let(apps::launchUri) }
            contentDescription = e.title
            setOnFocusChangeListener { v, f ->
                v.scaleX = if (f) 1.095f else 1f; v.scaleY = v.scaleX; onCardFocus(e, b, f)
                // the focused card sits 32 dp from the clip edge: its ring is never cut and nothing slides under the dock
                if (f) {
                    b.scroll.smoothScrollTo(maxOf(0, v.left - b.cards.paddingLeft), 0)
                    scrollToBlock(b)
                }
            }
        }
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        (e.thumbUri?.takeIf { it.isNotBlank() } ?: e.posterUri?.takeIf { it.isNotBlank() })?.let {
            // not loaded yet: only the focused shelf and its neighbours get their pictures (see loadPictures)
            frame.tag = Runnable {
                posters.load(it, w, h) { bmp -> if (bmp != null) { image.scaleType = ImageView.ScaleType.MATRIX; image.imageMatrix = topCrop(bmp, w, h); image.setImageBitmap(bmp) } }
            }
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

    // ---- dock editing in place: long OK on an app → actions «Переместить» / «Скрыть» appear to the right of that app ----

    private var installedNow: List<AppEntry> = emptyList()
    private var recentNow: List<String> = emptyList()
    private var tokens: List<String> = emptyList()
    private var visibleTokens: List<Int> = emptyList()
    private var rebuilding = false // rows are being recreated: focus the system hands out meanwhile is not the user's
    private var editing = false
    private var moving = false
    private var editToken = -1
    private lateinit var editPopup: FrameLayout
    private lateinit var editChips: LinearLayout
    private lateinit var editHint: TextView
    private lateinit var chipMove: View
    private lateinit var chipHide: View

    private fun buildEditPopup(root: FrameLayout) {
        editChips = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun chip(text: String, onClick: () -> Unit): View {
            val t = label(text, 14, bold = true)
            return centered(t, dp(170), dp(44)).apply { plate(10, Tok.surface2); elevation = dp(4).toFloat(); id = View.generateViewId(); setOnClickListener { onClick() }; flipTexts(listOf(t)) }
        }
        chipMove = chip(getString(R.string.dock_move)) { moving = true; showEditUi() }
        chipHide = chip(getString(R.string.dock_hide)) { hideEdited() }
        chipMove.nextFocusUpId = chipMove.id; chipMove.nextFocusDownId = chipHide.id; chipMove.nextFocusRightId = chipMove.id
        chipHide.nextFocusUpId = chipMove.id; chipHide.nextFocusDownId = chipHide.id; chipHide.nextFocusRightId = chipHide.id
        editChips.addView(chipMove, lp(dp(170), dp(44)).apply { bottomMargin = dp(6) })
        editChips.addView(chipHide, lp(dp(170), dp(44)))
        editHint = label(getString(R.string.dock_moving), 13, Tok.text).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8)); background = rounded(Tok.surface2, dp(8).toFloat()); visibility = View.GONE
        }
        editPopup = FrameLayout(this).apply { visibility = View.GONE }
        editPopup.addView(editChips); editPopup.addView(editHint, FrameLayout.LayoutParams(-2, -2))
        root.addView(editPopup, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
    }

    private fun enterEdit(token: Int) {
        editing = true; moving = false; editToken = token
        // arranging by hand: the "recent" slots become the apps shown now, otherwise moving swaps identical slots and nothing moves
        val pinned = DockRules.slots(installedNow, packageName, DockRules.normalize(installedNow, packageName, Prefs.dock(this)), recentNow)
            .mapNotNull { it.app?.packageName }
        if (pinned.isNotEmpty()) save(pinned)
        rowsScroll.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS // no way to the cards while editing
        rowsScroll.isFocusable = false
        showDock(installedNow, recentNow)
        focusEditRow()
    }

    private fun exitEdit() {
        editing = false; moving = false
        editPopup.visibility = View.GONE
        rowsScroll.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        rowsScroll.isFocusable = true
        showDock(installedNow, recentNow)
        // focus back on the edited app, or on the first card when it is gone
        (dock.findViewWithTag<View>("app$editToken") ?: cards.getChildAt(0) ?: dock.getChildAt(0))?.requestFocus()
    }

    /** Focus the edited app's row (or "+" when it is gone) and put the actions next to it. */
    private fun focusEditRow() {
        val row = dock.findViewWithTag<View>("app$editToken")
        if (row == null) { editToken = -1; dock.findViewWithTag<View>("add")?.requestFocus() ?: dock.getChildAt(0)?.requestFocus() }
        else row.requestFocus()
        showEditUi()
    }

    /** Focus moved while editing: actions follow the focused app, hidden for any other row. */
    private fun onEditFocus(now: View?) {
        if (now == null || isIn(now, editPopup)) return
        val tag = now.tag as? String
        editToken = if (tag != null && tag.startsWith("app")) tag.removePrefix("app").toIntOrNull() ?: -1 else -1
        showEditUi()
    }

    private fun showEditUi() {
        val row = dock.findViewWithTag<View>("app$editToken")
        if (!editing || row == null) { editPopup.visibility = View.GONE; return }
        row.nextFocusRightId = chipMove.id
        chipMove.nextFocusLeftId = row.id; chipHide.nextFocusLeftId = row.id
        editChips.visibility = if (moving) View.GONE else View.VISIBLE
        editHint.visibility = if (moving) View.VISIBLE else View.GONE
        editPopup.visibility = View.VISIBLE
        row.post {
            val r = IntArray(2); val p = IntArray(2)
            row.getLocationOnScreen(r); (editPopup.parent as View).getLocationOnScreen(p)
            editPopup.layoutParams = (editPopup.layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin = dp(240 + 8); topMargin = r[1] - p[1]
            }
        }
    }

    private fun save(next: List<String>) { tokens = next; Prefs.setDock(this, next) }

    /** Moves the edited app to the next visible app up (-1) or down (+1); every step is saved at once. */
    private fun step(delta: Int) {
        val pos = visibleTokens.indexOf(editToken)
        val target = visibleTokens.getOrNull(pos + delta) ?: return
        var t = tokens; var i = editToken
        while (i != target) { t = DockRules.move(t, i, delta); i += delta }
        save(t); editToken = target
        showDock(installedNow, recentNow)
        dock.findViewWithTag<View>("app$editToken")?.requestFocus()
        showEditUi()
    }

    private fun hideEdited() {
        val gone = editToken
        save(DockRules.removeAt(tokens, gone))
        editToken = gone // the next app slides into this place; focusEditRow falls back to "+" when none does
        showDock(installedNow, recentNow)
        focusEditRow()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (!editing) return super.dispatchKeyEvent(e)
        val down = e.action == KeyEvent.ACTION_DOWN
        if (moving) {
            if (down) when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> step(-1)
                KeyEvent.KEYCODE_DPAD_DOWN -> step(+1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> { moving = false; showEditUi() }
            }
            return true // swallow the rest: nothing may leave the row, key-up of OK must not click
        }
        if (e.keyCode == KeyEvent.KEYCODE_BACK) { if (down) exitEdit(); return true }
        return super.dispatchKeyEvent(e)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        if (requestCode == PICK && resultCode == RESULT_OK) {
            data?.getStringExtra(AllAppsActivity.EXTRA_PACKAGE)?.let { pkg ->
                save(DockRules.add(tokens, pkg)); editToken = tokens.indexOf(pkg)
            }
        } else super.onActivityResult(requestCode, resultCode, data)
    }

    /** Rows that fit between the top padding and the three bottom buttons (search takes one); no scrolling in the dock. */
    private fun maxApps(): Int {
        val hDp = resources.displayMetrics.heightPixels / resources.displayMetrics.density
        return ((hDp - 40 - 54 * 4) / 54).toInt().coerceAtLeast(1)
    }

    /** Leaves the dock before a button opens another screen, so the dock is collapsed when Home comes back. */
    private fun leaveDock() {
        setDockExpanded(false)
        cards.getChildAt(0)?.requestFocus()
    }

    private fun showDock(installed: List<AppEntry>, recent: List<String>) {
        installedNow = installed; recentNow = recent
        rebuilding = true
        dock.removeAllViews(); dockBottom.removeAllViews(); dockLabels.clear()
        // search stays on top while editing too; "+" at the end of the list takes one app row's room
        dockItem(getString(R.string.search), null, android.R.drawable.ic_menu_search, -1, round = true) { apps.search() }
        // indexes are positions among the normalized tokens, so the editor and the saved list agree (empty recent slots included)
        tokens = DockRules.normalize(installed, packageName, Prefs.dock(this))
        // the last row is always kept free for "+": entering the editor changes nothing, and "+" never pushes an app out
        val room = maxApps() - 1
        val shown = ArrayList<Int>()
        DockRules.slots(installed, packageName, tokens, recent).forEachIndexed { i, sl ->
            val a = sl.app ?: return@forEachIndexed
            if (shown.size >= room) return@forEachIndexed
            shown += i
            dockItem(a.label, icons[a.packageName] ?: apps.icon(a.packageName), null, i) { apps.launch(a.packageName) }.tag = "app$i"
        }
        visibleTokens = shown
        if (editing && tokens.size < DockRules.MAX_SLOTS) {
            dockItem(getString(R.string.dock_add), null, R.drawable.ic_add, -1) {
                startActivityForResult(android.content.Intent(this, AllAppsActivity::class.java).putExtra(AllAppsActivity.EXTRA_PICK, true), PICK)
            }.tag = "add"
        }
        dockItem(getString(R.string.all_apps), null, R.drawable.ic_all_apps, -1, into = dockBottom) { startActivity(android.content.Intent(this, AllAppsActivity::class.java)) }
        val v = dockItem(getString(R.string.vpn), null, R.drawable.ic_vpn, -1, dot = true, into = dockBottom) { apps.vpnSettings() }
        vpnButton = v; vpnDot = v.findViewWithTag("dot")
        val gear = dockItem(getString(R.string.settings), null, R.drawable.ic_settings, -1, into = dockBottom) { startActivity(android.content.Intent(this, QuickSettingsActivity::class.java)) }
        // red dot on the gear while there is an unread (dismissable) notification; ongoing ones never light it
        val unreadDot = View(this).apply { background = rounded(Tok.error, dp(5).toFloat()); visibility = View.GONE }
        ((gear as LinearLayout).getChildAt(0) as FrameLayout).addView(unreadDot,
            FrameLayout.LayoutParams(dp(10), dp(10), Gravity.TOP or Gravity.END).apply { topMargin = dp(3); rightMargin = dp(3) })
        gearBadge = unreadDot
        updateBadge()
        showVpn(VpnState.Off)
        dockLabels.forEach { it.visibility = if (dockExpanded) View.VISIBLE else View.GONE }
        rebuilding = false
    }

    /**
     * One dock row: 44 dp icon (round for search) + caption shown only while the dock is expanded.
     * Focus = translucent plate with a 2 dp white ring. Long OK on an app starts editing the dock.
     */
    private fun dockItem(name: String, icon: android.graphics.drawable.Drawable?, iconRes: Int?, index: Int, round: Boolean = false, dot: Boolean = false, into: LinearLayout = dock, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            contentDescription = name
            id = View.generateViewId()
            isFocusable = true; isFocusableInTouchMode = false; isClickable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.argb(41, 255, 255, 255)); setStroke(dp(2), Tok.plate); cornerRadius = dp(if (round) 22 else 8).toFloat()
                })
            }
            setOnClickListener {
                when {
                    editing && index >= 0 -> if (!moving) chipMove.requestFocus() // OK on the app moves focus to its actions
                    editing && tag == "add" -> onClick()
                    editing -> { exitEdit(); onClick() }
                    else -> { leaveDock(); onClick() }
                }
            }
            if (index >= 0) setOnLongClickListener { if (!editing) enterEdit(index); true }
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
        into.addView(row, lp(-1, dp(48)).apply { bottomMargin = dp(6) })
        return row
    }

    private fun ensureFocus() {
        if (focusSet) return
        val first = if (cards.childCount > 0) cards.getChildAt(0) else dock.getChildAt(0)
        if (first?.requestFocus() == true) focusSet = true
    }

    private companion object {
        const val PICK = 1
        /**
         * Gap under the shelf strip, dp. The pictures' bottom edge lines up with the bottom dock icon (settings):
         * icon bottom = 20 dp padding + 6 dp row margin + 2 dp centring = 28 dp; a card sits 11 dp above its strip's bottom.
         */
        const val SHELF_BOTTOM = 17
        val BLUEBOX_PACKAGE_FALLBACKS = DockRules.DEFAULT_PINNED.take(3)
    }
}
