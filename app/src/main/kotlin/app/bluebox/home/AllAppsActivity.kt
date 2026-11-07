package app.bluebox.home

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import app.bluebox.home.data.Apps
import app.bluebox.home.logic.AppEntry
import app.bluebox.home.logic.DockRules
import java.util.concurrent.Executors

/**
 * "All apps" with labels, alphabetical; OK launches. In pick mode ([EXTRA_PICK], opened from the dock editor) the list is
 * ordered by recent use and OK returns the chosen package in [EXTRA_PACKAGE] instead of launching. Back returns.
 */
class AllAppsActivity : Activity() {
    private lateinit var apps: Apps
    private lateinit var grid: GridLayout
    private val io = Executors.newSingleThreadExecutor()
    private val pick get() = intent.getBooleanExtra(EXTRA_PICK, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        apps = Apps(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0f161b"))
            setPadding(dp(Tok.SAFE_X), dp(Tok.SAFE_Y), dp(Tok.SAFE_X), dp(Tok.SAFE_Y))
        }
        root.addView(label(getString(if (pick) R.string.pick_title else R.string.all_apps), 16, Tok.text72, bold = true))
        root.addView(space(0, 10))
        grid = GridLayout(this).apply { columnCount = 6 }
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; addView(grid) }, lp(-1, 0, 1f))
        setContentView(root)

        io.execute {
            val installed = apps.installed()
            val list = if (pick) DockRules.byRecency(installed, packageName, apps.recent()) else DockRules.all(installed, packageName)
            runOnUiThread { if (!isDestroyed) fill(list) }
        }
    }

    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }

    private fun fill(list: List<AppEntry>) {
        grid.removeAllViews()
        list.forEach { a ->
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                plate(12); contentDescription = a.label
                setOnClickListener {
                    if (pick) {
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_PACKAGE, a.packageName))
                        finish()
                    } else apps.launch(a.packageName)
                }
            }
            col.addView(ImageView(this).apply { setImageDrawable(apps.icon(a.packageName)) }, lp(dp(40), dp(40)))
            val t = label(a.label, 12).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(6), dp(6), dp(6), 0); gravity = Gravity.CENTER }
            col.addView(t)
            col.flipTexts(listOf(t))
            grid.addView(col, GridLayout.LayoutParams().apply { width = dp(120); height = dp(88); setMargins(0, 0, dp(12), dp(12)) })
        }
        grid.getChildAt(0)?.requestFocus()
    }

    companion object {
        const val EXTRA_PICK = "pick"
        const val EXTRA_PACKAGE = "package"
    }
}
