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
    private lateinit var vpnDot: View
    private var vpnText: TextView? = null
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

        // notifications of the system (VPN active, developer mode, ...) like the stock shade; fallback cards without access
        notices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(notices)

        setContentView(scrim)
        vpn = VpnMonitor(this) { lastVpn = it; showVpn(it) }
        panel.findViewWithTag<View>("all")?.requestFocus()
    }

    override fun onStart() { super.onStart(); vpn.start() }
    override fun onStop() { vpn.stop(); NotificationFeed.changed = null; super.onStop() }

    override fun onResume() {
        super.onResume()
        NotificationFeed.changed = { runOnUiThread { if (!isDestroyed) renderNotices() } }
        renderNotices()
    }

    private fun renderNotices() {
        notices.removeAllViews()
        if (NotificationFeed.hasAccess(this)) {
            vpnText = null; devText = null
            NotificationFeed.items.take(4).forEach { n ->
                val sub = if (n.text.isNotEmpty()) label(n.text, 12, Tok.text72).apply { maxLines = 2 } else null
                val card = wideRow(R.drawable.ic_bell, n.title.ifEmpty { n.text }, sub) {
                    runCatching { n.open?.send() }
                }
                notices.addView(card, lp(-1, dp(if (sub != null) 64 else 52)).apply { bottomMargin = dp(12) })
            }
            return
        }
        // no access: our own VPN / developer cards plus the request for access
        val vt = label("", 12, Tok.text72); vpnText = vt
        val vpnCard = wideRow(R.drawable.ic_vpn, getString(R.string.vpn), vt) { apps.vpnSettings() }
        vpnDot = View(this)
        vpnCard.addView(vpnDot, lp(dp(10), dp(10)))
        notices.addView(vpnCard, lp(-1, dp(64)).apply { bottomMargin = dp(12) })
        val dt = label(getString(if (apps.developerEnabled()) R.string.developer_on else R.string.developer_off), 12, Tok.text72); devText = dt
        notices.addView(wideRow(R.drawable.ic_code, getString(R.string.developer), dt) { apps.developer() }, lp(-1, dp(64)).apply { bottomMargin = dp(12) })
        val hint = label(getString(R.string.notice_access_hint), 11, Tok.text56).apply { maxLines = 3 }
        notices.addView(wideRow(R.drawable.ic_bell, getString(R.string.notice_access), hint) { apps.notificationAccess() }, lp(-1, dp(76)))
        showVpn(lastVpn)
    }

    private fun showVpn(s: VpnState) {
        val vt = vpnText ?: return
        val (color, textRes) = when (s) {
            VpnState.On -> Tok.ok to R.string.vpn_on
            VpnState.Off -> Tok.off to R.string.vpn_off
            VpnState.Connecting -> Tok.warn to R.string.vpn_connecting
            VpnState.Error -> Tok.error to R.string.vpn_error
        }
        vpnDot.background = rounded(color, dp(5).toFloat())
        vt.text = getString(textRes)
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
    private fun wideRow(icon: Int, name: String, status: TextView?, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            plate(14); contentDescription = name; setOnClickListener { onClick() }
        }
        val img = ImageView(this).apply { setImageResource(icon); setColorFilter(Tok.text) }
        row.addView(img, lp(dp(24), dp(24)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        val t = label(name, 14, bold = true)
        col.addView(t)
        if (status != null) { col.addView(space(0, 2)); col.addView(status) }
        row.addView(col, lp(0, -2, 1f))
        row.setOnFocusChangeListener { _, f ->
            img.setColorFilter(if (f) Tok.onPlate else Tok.text)
            t.setTextColor(if (f) Tok.onPlate else Tok.text)
            status?.setTextColor(if (f) Color.argb(160, 17, 17, 17) else Tok.text72)
        }
        return row
    }
}
