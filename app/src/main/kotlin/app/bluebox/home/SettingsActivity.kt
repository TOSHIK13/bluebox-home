package app.bluebox.home

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.FrameLayout
import app.bluebox.home.data.Apps
import app.bluebox.home.data.NotificationFeed

/** Home settings: card backdrop switch and shortcuts to the system settings. Back returns to Home. */
class SettingsActivity : Activity() {
    private lateinit var apps: Apps
    private lateinit var backdropValue: TextView
    private lateinit var permRow: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        apps = Apps(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0f161b"))
            setPadding(dp(Tok.SAFE_X), dp(Tok.SAFE_Y), dp(Tok.SAFE_X), dp(Tok.SAFE_Y))
        }
        root.addView(label(getString(R.string.settings), 24, bold = true))
        root.addView(space(0, 20))

        root.addView(label(getString(R.string.home_section), 16, Tok.text72, bold = true))
        root.addView(space(0, 10))
        val value = label(valueText(), 14, Tok.text72)
        backdropValue = value
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            plate(12)
            contentDescription = getString(R.string.backdrop_title)
            setOnClickListener {
                Prefs.setBackdrop(this@SettingsActivity, !Prefs.backdrop(this@SettingsActivity))
                backdropValue.text = valueText()
            }
        }
        val title = label(getString(R.string.backdrop_title), 16, bold = true)
        row.addView(title, lp(0, -2, 1f))
        row.addView(value)
        row.flipTexts(listOf(title, value))
        root.addView(row, lp(dp(520), dp(56)))
        root.addView(space(0, 6))
        root.addView(label(getString(R.string.backdrop_hint), 13, Tok.text56))

        root.addView(space(0, 28))
        root.addView(label(getString(R.string.system), 16, Tok.text72, bold = true))
        root.addView(space(0, 10))
        val system = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            getString(R.string.android_settings) to { apps.settings() },
            getString(R.string.wifi) to { apps.wifi() },
            getString(R.string.bluetooth) to { apps.bluetooth() },
            getString(R.string.vpn) to { apps.vpnSettings() },
        ).forEach { (name, go) ->
            system.addView(chip(name) { go() }, lp(dp(160), dp(48)).apply { rightMargin = dp(12) })
        }
        root.addView(system)

        root.addView(space(0, 28))
        root.addView(label(getString(R.string.permissions), 16, Tok.text72, bold = true))
        root.addView(space(0, 10))
        permRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(permRow)
        root.addView(space(0, 6))
        root.addView(label(getString(R.string.permissions_hint), 13, Tok.text56))

        root.addView(space(0, 28))
        root.addView(label(getString(R.string.stock_launcher), 16, Tok.text72, bold = true))
        root.addView(space(0, 10))
        val rollback = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rollback.addView(chip(getString(R.string.stock_launcher)) { apps.restoreStockHome() }, lp(dp(200), dp(48)).apply { rightMargin = dp(12) })
        rollback.addView(chip(getString(R.string.choose_launcher)) { apps.homeSettings() }, lp(dp(200), dp(48)))
        root.addView(rollback)
        root.addView(space(0, 6))
        root.addView(label(getString(R.string.launcher_hint), 13, Tok.text56))
        setContentView(android.widget.ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0f161b")); isVerticalScrollBarEnabled = false; addView(root)
        })
        row.requestFocus()
    }

    override fun onResume() { super.onResume(); renderPermissions() }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) { renderPermissions() }

    /** Three grants the launcher can use; each chip shows the state and asks (or opens the system screen) on OK. */
    private fun renderPermissions() {
        permRow.removeAllViews()
        val listings = "android.permission.READ_TV_LISTINGS"
        val items = listOf(
            R.string.perm_notifications to (NotificationFeed.hasAccess(this) to { apps.notificationAccess(); Unit }),
            R.string.perm_usage to (usageGranted() to { apps.usageAccessSettings(); Unit }),
            R.string.perm_listings to ((checkSelfPermission(listings) == android.content.pm.PackageManager.PERMISSION_GRANTED) to
                { runCatching { requestPermissions(arrayOf(listings), 2) }; Unit }),
        )
        items.forEach { (name, st) ->
            val (ok, go) = st
            val label = getString(name) + " · " + getString(if (ok) R.string.on else R.string.off)
            permRow.addView(chip(label, dp(230)) { go() }, lp(dp(230), dp(48)).apply { rightMargin = dp(12) })
        }
    }

    private fun usageGranted(): Boolean = runCatching {
        val ops = getSystemService(android.app.AppOpsManager::class.java)
        ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) == android.app.AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    private fun valueText() = getString(if (Prefs.backdrop(this)) R.string.on else R.string.off)

    private fun chip(text: String, width: Int = dp(160), onClick: () -> Unit): View {
        val t = label(text, 14, bold = true)
        val c = centered(t, width, dp(48)).apply { plate(12); setOnClickListener { onClick() } }
        c.flipTexts(listOf(t))
        return c
    }
}
