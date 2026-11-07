package app.bluebox.home

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import app.bluebox.home.data.Apps
import app.bluebox.home.data.NotificationFeed
import app.bluebox.home.data.VpnMonitor
import app.bluebox.home.logic.VpnRules
import app.bluebox.home.logic.VpnState

/**
 * Quick settings, opened by the gear in Home's top bar: a panel on the right edge like the Google TV shade.
 * Time and date, "Все настройки" (Home's own settings), system shortcuts as tiles, and status cards (VPN, developer mode).
 * Back closes it; Home stays visible (dimmed) behind.
 */
class QuickSettingsActivity : Activity() {
    private lateinit var apps: Apps
    private lateinit var vpn: VpnMonitor
    private lateinit var notices: LinearLayout
    private var devText: TextView? = null
    private var lastVpn = VpnState.Off

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        apps = Apps(this)
        val scrim = FrameLayout(this).apply { setBackgroundColor(Color.argb(150, 0, 0, 0)) }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(Tok.SAFE_Y), dp(20), dp(Tok.SAFE_Y))
        }
        val scroll = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#F00f161b"))
                val r = dp(20).toFloat()
                cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            }
            addView(panel, FrameLayout.LayoutParams(-1, -2))
        }
        scrim.addView(scroll, FrameLayout.LayoutParams(dp(360), -1, Gravity.END))

        // time + date, then the entry to the full settings
        val time = TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"
            textSize = 30f; setTextColor(Tok.text); includeFontPadding = false
            setTypeface(typeface, Typeface.BOLD)
        }
        val date = TextClock(this).apply {
            format24Hour = "EEEE, d MMMM"; format12Hour = "EEEE, d MMMM"
            textSize = 12f; setTextColor(Tok.text72); includeFontPadding = false
        }
        panel.addView(time)
        panel.addView(space(0, 2))
        panel.addView(date)
        panel.addView(space(0, 16))
        panel.addView(wideRow(R.drawable.ic_settings, getString(R.string.all_settings), null) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }.also { it.tag = "all" }, lp(-1, dp(52)))
        panel.addView(space(0, 12))

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(tile(R.drawable.ic_wifi, getString(R.string.wifi)) { apps.wifi() }, tileLp(true))
        row1.addView(tile(R.drawable.ic_bluetooth, getString(R.string.bluetooth)) { apps.bluetooth() }, tileLp(false))
        panel.addView(row1)
        panel.addView(space(0, 16))

        // VPN card (state, uptime, traffic) stays in place; below it the system notifications like the stock shade
        panel.addView(buildVpnCard(), lp(-1, -2).apply { bottomMargin = dp(12) })
        notices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(notices)

        setContentView(scrim)
        vpn = VpnMonitor(this) { lastVpn = it; showVpn(it) }
        panel.findViewWithTag<View>("all")?.requestFocus()
    }

    override fun onStart() { super.onStart(); vpn.start(); ticker.run() }
    override fun onStop() { ticker.stop(); vpn.stop(); NotificationFeed.changed = null; super.onStop() }

    override fun onResume() {
        super.onResume()
        NotificationFeed.changed = { runOnUiThread { if (!isDestroyed) renderNotices() } }
        renderNotices()
    }

    /** Re-reads uptime / traffic once a second while the panel is on screen. */
    private val clock = android.os.Handler(android.os.Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { showVpn(lastVpn); clock.postDelayed(this, 1000) }
        fun stop() = clock.removeCallbacks(this)
    }

    private lateinit var vpnTitleDot: View
    private lateinit var vpnState: TextView
    private lateinit var vpnStats: LinearLayout
    private lateinit var vpnTime: TextView
    private lateinit var vpnRx: TextView
    private lateinit var vpnTx: TextView

    /** VPN card: dot + state on top; while connected, three figures (time, received, sent) underneath. */
    private fun buildVpnCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            plate(14); contentDescription = getString(R.string.vpn); setOnClickListener {
                // the system VPN notification opens the stock dialog (app, session time, traffic); settings as fallback
                val sent = NotificationFeed.vpn()?.open?.let { runCatching { it.send() }.isSuccess } ?: false
                if (!sent) apps.vpnSettings()
            }
        }
        val img = ImageView(this).apply { setImageResource(R.drawable.ic_vpn); setColorFilter(Tok.text) }
        val title = label(getString(R.string.vpn), 14, bold = true)
        vpnState = label("", 12, Tok.text72)
        vpnTitleDot = View(this)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(img, lp(dp(24), dp(24)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        col.addView(title); col.addView(space(0, 2)); col.addView(vpnState)
        top.addView(col, lp(0, -2, 1f))
        top.addView(vpnTitleDot, lp(dp(10), dp(10)))
        card.addView(top)

        vpnTime = label("–", 16, bold = true); vpnRx = label("–", 16, bold = true); vpnTx = label("–", 16, bold = true)
        val caps = listOf(R.string.vpn_time, R.string.vpn_rx, R.string.vpn_tx).map { label(getString(it), 11, Tok.text72) }
        vpnStats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        listOf(vpnTime, vpnRx, vpnTx).forEachIndexed { i, v ->
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            c.addView(caps[i]); c.addView(space(0, 2)); c.addView(v)
            vpnStats.addView(c, lp(0, -2, 1f))
        }
        card.addView(vpnStats, lp(-1, -2).apply { topMargin = dp(12) })
        card.setOnFocusChangeListener { _, f ->
            val on = if (f) Tok.onPlate else Tok.text
            val dim = if (f) Color.argb(160, 17, 17, 17) else Tok.text72
            img.setColorFilter(on); title.setTextColor(on); listOf(vpnTime, vpnRx, vpnTx).forEach { it.setTextColor(on) }
            vpnState.setTextColor(dim); caps.forEach { it.setTextColor(dim) }
        }
        return card
    }

    private fun renderNotices() {
        notices.removeAllViews()
        if (NotificationFeed.hasAccess(this)) {
            devText = null
            val hideAll = Prefs.hideOngoing(this)
            val hidden = Prefs.hiddenNotices(this)
            val shown = NotificationFeed.items
                .filter { it.tag != "VpnStatusObserver" }
                .filter { it.clearable || !(hideAll || it.key in hidden) }
                .take(5)
            shown.forEach { n ->
                val sub = if (n.text.isNotEmpty() && n.title.isNotEmpty()) label(n.text, 12, Tok.text72).apply { maxLines = 2 } else null
                // OK opens the notification and marks it read; long OK marks it read without opening
                val card = wideRow(R.drawable.ic_bell, n.title.ifEmpty { n.text }, sub, apps.icon(n.pkg)) {
                    runCatching { n.open?.send() }
                    if (n.clearable) NotificationFeed.dismiss(n.key)
                }
                // long OK: mark read; for a permanent one that cannot be cleared, hide it from this panel instead
                card.setOnLongClickListener {
                    if (n.clearable) NotificationFeed.dismiss(n.key) else { Prefs.hideNotice(this, n.key); renderNotices() }
                    true
                }
                notices.addView(card, lp(-1, dp(if (sub != null) 64 else 52)).apply { bottomMargin = dp(12) })
            }
            if (shown.any { it.clearable }) {
                val hint = label(getString(R.string.notice_read_hint), 11, Tok.text56)
                notices.addView(wideRow(R.drawable.ic_bell, getString(R.string.notice_read_all), hint) { NotificationFeed.dismissAll() }, lp(-1, dp(64)))
            }
            return
        }
        // no access: developer card plus the request for access
        val dt = label(getString(if (apps.developerEnabled()) R.string.developer_on else R.string.developer_off), 12, Tok.text72); devText = dt
        notices.addView(wideRow(R.drawable.ic_code, getString(R.string.developer), dt) { apps.developer() }, lp(-1, dp(64)).apply { bottomMargin = dp(12) })
        val hint = label(getString(R.string.notice_access_hint), 11, Tok.text56).apply { maxLines = 3 }
        notices.addView(wideRow(R.drawable.ic_bell, getString(R.string.notice_access), hint) { apps.notificationAccess() }, lp(-1, dp(76)))
    }

    private fun showVpn(s: VpnState) {
        if (!::vpnState.isInitialized) return
        val (color, textRes) = when (s) {
            VpnState.On -> Tok.ok to R.string.vpn_on
            VpnState.Off -> Tok.off to R.string.vpn_off
            VpnState.Connecting -> Tok.warn to R.string.vpn_connecting
            VpnState.Error -> Tok.error to R.string.vpn_error
        }
        vpnTitleDot.background = rounded(color, dp(5).toFloat())
        val vpnApp = if (s == VpnState.On) NotificationFeed.vpnApp() else null
        vpnState.text = getString(textRes) + (vpnApp?.let { " · $it" } ?: "")
        val info = if (s == VpnState.On) vpn.info() else null
        vpnStats.visibility = if (info != null) View.VISIBLE else View.GONE
        if (info != null) {
            vpnTime.text = VpnRules.duration(info.upMs)
            vpnRx.text = info.rx?.let(VpnRules::bytes) ?: "–"
            vpnTx.text = info.tx?.let(VpnRules::bytes) ?: "–"
        }
    }

    private fun tileLp(first: Boolean) = lp(0, dp(80), 1f).apply { if (first) rightMargin = dp(12) }

    /** Square-ish tile: icon on top, name below. */
    private fun tile(icon: Int, name: String, onClick: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            plate(14); contentDescription = name; setOnClickListener { onClick() }
        }
        val img = ImageView(this).apply { setImageResource(icon); setColorFilter(Tok.text) }
        box.addView(img, lp(dp(26), dp(26)))
        val t = label(name, 12, bold = true).apply { maxLines = 2; gravity = Gravity.CENTER; setPadding(dp(6), dp(6), dp(6), 0) }
        box.addView(t)
        box.setOnFocusChangeListener { _, f ->
            img.setColorFilter(if (f) Tok.onPlate else Tok.text)
            t.setTextColor(if (f) Tok.onPlate else Tok.text)
        }
        return box
    }

    /** Wide row: icon, title (+ optional status line below it). The returned row accepts an extra trailing view. */
    private fun wideRow(icon: Int, name: String, status: TextView?, appIcon: android.graphics.drawable.Drawable? = null, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            plate(14); contentDescription = name; setOnClickListener { onClick() }
        }
        // an app's own icon keeps its colours; system glyphs are tinted
        val img = ImageView(this).apply { if (appIcon != null) setImageDrawable(appIcon) else { setImageResource(icon); setColorFilter(Tok.text) } }
        row.addView(img, lp(dp(24), dp(24)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        val t = label(name, 14, bold = true)
        col.addView(t)
        if (status != null) { col.addView(space(0, 2)); col.addView(status) }
        row.addView(col, lp(0, -2, 1f))
        row.setOnFocusChangeListener { _, f ->
            if (appIcon == null) img.setColorFilter(if (f) Tok.onPlate else Tok.text)
            t.setTextColor(if (f) Tok.onPlate else Tok.text)
            status?.setTextColor(if (f) Color.argb(160, 17, 17, 17) else Tok.text72)
        }
        return row
    }
}
