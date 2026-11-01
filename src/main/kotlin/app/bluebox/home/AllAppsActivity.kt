package app.bluebox.home

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import app.bluebox.home.data.Apps
import app.bluebox.home.logic.DockRules
import java.util.concurrent.Executors

/** "All apps" plus a "System" row (settings, Wi-Fi, Bluetooth, VPN). Back returns to Home. */
class AllAppsActivity : Activity() {
    private lateinit var apps: Apps
    private lateinit var grid: GridLayout
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        apps = Apps(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0f161b"))
            setPadding(dp(Tok.SAFE_X), dp(Tok.SAFE_Y), dp(Tok.SAFE_X), dp(Tok.SAFE_Y))
        }
        root.addView(label(getString(R.string.system), 16, Tok.text72, bold = true))
        root.addView(space(0, 10))
        val system = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            getString(R.string.settings) to { apps.settings() },
            getString(R.string.wifi) to { apps.wifi() },
            getString(R.string.bluetooth) to { apps.bluetooth() },
            getString(R.string.vpn) to { apps.vpnSettings() },
        ).forEach { (name, go) ->
            system.addView(chip(name) { go() }, lp(dp(140), dp(48)).apply { rightMargin = dp(12) })
        }
        root.addView(system)
        root.addView(space(0, 20))
        root.addView(label(getString(R.string.all_apps), 16, Tok.text72, bold = true))
        root.addView(space(0, 10))
        grid = GridLayout(this).apply { columnCount = 6 }
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; addView(grid) }, lp(-1, 0, 1f))
        setContentView(root)

        io.execute {
            val list = DockRules.all(apps.installed(), packageName)
            runOnUiThread { if (!isDestroyed) fill(list.map { it.packageName to it.label }) }
        }
    }

    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }

    // default Back closes this screen and returns to Home
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = super.onKeyDown(keyCode, event)

    private fun fill(list: List<Pair<String, String>>) {
        grid.removeAllViews()
        list.forEach { (pkg, name) ->
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                plate(12); setOnClickListener { apps.launch(pkg) }; contentDescription = name
            }
            col.addView(ImageView(this).apply { setImageDrawable(apps.icon(pkg)) }, lp(dp(40), dp(40)))
            val t = label(name, 12).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(6), dp(6), dp(6), 0); gravity = Gravity.CENTER }
            col.addView(t)
            col.flipTexts(listOf(t))
            grid.addView(col, GridLayout.LayoutParams().apply { width = dp(120); height = dp(88); setMargins(0, 0, dp(12), dp(12)) })
        }
        grid.getChildAt(0)?.requestFocus()
    }

    private fun chip(text: String, onClick: () -> Unit): View {
        val t = label(text, 14, bold = true)
        val c = centered(t, dp(140), dp(48)).apply { plate(12); setOnClickListener { onClick() } }
        c.flipTexts(listOf(t))
        return c
    }
}
