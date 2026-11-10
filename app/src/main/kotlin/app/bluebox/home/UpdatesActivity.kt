package app.bluebox.home

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.bluebox.home.data.UpdateInstaller
import app.bluebox.home.data.UpdateSource
import app.bluebox.home.logic.UpdateInfo
import app.bluebox.home.logic.UpdateRules

/** Settings → «Обновления»: installed version, newest release on GitHub, check / install, channel, daily check. */
class UpdatesActivity : Activity() {
    private lateinit var list: LinearLayout
    private var info: UpdateInfo? = null
    private var status: String? = null
    private var busy = false
    private var focusIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0f161b"))
            setPadding(dp(Tok.SAFE_X), dp(Tok.SAFE_Y), dp(Tok.SAFE_X), dp(Tok.SAFE_Y))
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(label(getString(R.string.updates_title), 26, bold = true))
        left.addView(space(0, 8))
        left.addView(label(getString(R.string.updates_help), 14, Tok.text56))
        root.addView(left, lp(dp(300), -1))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }, lp(0, -1, 1f))
        setContentView(root)
        render()
        // opened from the "new version" notification: fetch the release now so "Скачать и установить" is already under focus
        if (Prefs.updateFound(this) != null) { focusInstall = true; check() }
    }

    private var focusInstall = false
    private var installRow: View? = null

    private fun newer(i: UpdateInfo?) = i != null && UpdateRules.isNewer(BuildConfig.BASE_VERSION, i.tag)

    private fun check() {
        if (busy) return
        busy = true; status = getString(R.string.updates_checking); render()
        Thread {
            val src = UpdateSource(BuildConfig.UPDATE_REPO, Prefs.updateBeta(this))
            val r = src.latest()
            Prefs.setUpdateResult(this, System.currentTimeMillis(), r?.takeIf { newer(it) }?.tag)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy = false; info = r
                status = when {
                    r == null -> src.lastError
                    newer(r) -> null
                    else -> getString(R.string.updates_latest)
                }
                render()
            }
        }.start()
    }

    private fun install() {
        val i = info ?: return
        if (busy) return
        busy = true; status = getString(R.string.updates_downloading, 0); render()
        Thread {
            var last = -10
            val err = UpdateInstaller.install(this, i) { p ->
                if (p - last >= 5) { last = p; runOnUiThread { if (!isDestroyed) { status = getString(R.string.updates_downloading, p); render() } } }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy = false; status = err ?: getString(R.string.updates_confirm); render()
            }
        }.start()
    }

    private fun render() {
        val keep = list.focusedChild?.let { list.indexOfChild(it) }?.takeIf { it >= 0 } ?: focusIndex
        list.removeAllViews()
        installRow = null
        val i = info
        val found = if (newer(i)) i else null

        section(getString(R.string.updates_title), first = true)
        row(getString(R.string.updates_installed), null, BuildConfig.VERSION_NAME, enabled = false) {}
        val checked = Prefs.updateCheckedAt(this)
        val latestText = when {
            found != null -> found.tag + (if (found.prerelease) " (бета)" else "") + if (found.publishedAt.isNotEmpty()) " · ${found.publishedAt}" else ""
            i != null -> i.tag
            Prefs.updateFound(this) != null -> Prefs.updateFound(this)!!
            else -> "—"
        }
        row(getString(R.string.updates_latest_title), status ?: if (checked > 0) lastCheckText(checked) else null, latestText, enabled = false) {}
        row(getString(R.string.updates_check), null, null) { check() }
        if (found != null) {
            if (found.apk == null || found.sha == null) {
                row(getString(R.string.updates_install), getString(R.string.updates_no_files), null, enabled = false) {}
            } else {
                installRow = row(getString(R.string.updates_install), found.notes.takeIf { it.isNotBlank() }?.let { UpdateRules.shortNotes(it) }, found.tag) { install() }
            }
        }

        section(getString(R.string.updates_settings))
        row(getString(R.string.updates_auto), getString(R.string.updates_auto_hint), onOff(Prefs.updateAuto(this))) {
            Prefs.setUpdateAuto(this, !Prefs.updateAuto(this)); render()
        }
        row(getString(R.string.updates_beta), getString(R.string.updates_beta_hint), onOff(Prefs.updateBeta(this))) {
            Prefs.setUpdateBeta(this, !Prefs.updateBeta(this)); info = null; status = null; render()
        }
        list.addView(space(0, 24))
        if (focusInstall && installRow != null) { focusInstall = false; installRow?.requestFocus(); return }
        val target = list.getChildAt(keep.coerceIn(0, list.childCount - 1))
        (if (target?.isFocusable == true) target else (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it.isFocusable })?.requestFocus()
    }

    private fun lastCheckText(at: Long): String =
        getString(R.string.updates_checked_at, java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at)))

    private fun onOff(on: Boolean) = getString(if (on) R.string.on else R.string.off)

    private fun section(title: String, first: Boolean = false) {
        if (!first) list.addView(space(0, 22))
        list.addView(label(title.uppercase(), 12, Tok.accentSoft, bold = true).apply { setPadding(dp(4), 0, 0, dp(8)) })
    }

    private fun row(title: String, hint: String?, value: String?, enabled: Boolean = true, onClick: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
            minimumHeight = dp(56)
            plate(10)
            if (enabled) setOnClickListener { focusIndex = list.indexOfChild(this); onClick() }
            else { isFocusable = false; isClickable = false }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val t = label(title, 16, bold = true)
        col.addView(t)
        val subs = ArrayList<TextView>()
        hint?.let { col.addView(space(0, 3)); val s = label(it, 12, Tok.text56).apply { maxLines = 8 }; col.addView(s); subs += s }
        box.addView(col, lp(0, -2, 1f))
        val right = label(value ?: "›", if (value != null) 14 else 22, Tok.text72, bold = value != null)
        box.addView(right)
        box.flipTexts(listOf(t, right), subs)
        list.addView(box, lp(-1, -2).apply { bottomMargin = dp(6) })
        return box
    }
}
