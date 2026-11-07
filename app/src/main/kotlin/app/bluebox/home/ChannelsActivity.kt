package app.bluebox.home

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.bluebox.home.data.ChannelSource
import app.bluebox.home.data.WatchNextSource
import app.bluebox.home.logic.ChannelRow
import app.bluebox.home.logic.ChannelRules
import app.bluebox.home.logic.EntryRules
import app.bluebox.home.logic.WatchEntry

/**
 * Settings → «Каналы приложений»: every channel an app has published (what it is called, who owns it, what data its
 * programs carry), switch a row on or off for Home, and set the order. OK toggles, long OK moves (up/down, OK).
 */
class ChannelsActivity : Activity() {
    private lateinit var list: LinearLayout
    private var all: List<ChannelRow> = emptyList()
    private var watchNext: List<WatchEntry> = emptyList()
    private var error: String? = null
    private var loaded = false
    private var moving: String? = null
    private var swallowUp = false
    private var awaitRelease = false
    private var focusIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0f161b"))
            setPadding(dp(Tok.SAFE_X), dp(Tok.SAFE_Y), dp(Tok.SAFE_X), dp(Tok.SAFE_Y))
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(label(getString(R.string.channels_title), 26, bold = true))
        left.addView(space(0, 8))
        left.addView(label(getString(R.string.channels_help), 14, Tok.text56))
        root.addView(left, lp(dp(300), -1))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }, lp(0, -1, 1f))
        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        Thread {
            val src = ChannelSource(this)
            val rows = src.load()
            val wn = WatchNextSource(this).load()
            runOnUiThread { if (!isDestroyed) { all = rows; watchNext = wn; error = src.lastError; loaded = true; render() } }
        }.start()
    }

    private fun arranged() = ChannelRules.arrange(all, Prefs.channelOrder(this))

    /** While a row is being moved, every key belongs to the move: up/down shift it, OK or Back finish. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val key = moving
        if (key == null) {
            if (swallowUp && event.action == KeyEvent.ACTION_UP) { swallowUp = false; return true }
            return super.dispatchKeyEvent(event)
        }
        val center = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
        // the OK that started the move is still held (its repeats and its release arrive after the list was rebuilt): ignore them
        if (awaitRelease && !center && event.action == KeyEvent.ACTION_DOWN) awaitRelease = false
        if (awaitRelease) {
            if (center && event.action == KeyEvent.ACTION_UP) awaitRelease = false
            if (center) return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when {
                event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val delta = if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                    Prefs.setChannelOrder(this, ChannelRules.move(arranged().map { it.key }, key, delta))
                    render()
                }
                center || event.keyCode == KeyEvent.KEYCODE_BACK -> { moving = null; swallowUp = true; render() }
            }
        }
        return true
    }

    private fun render() {
        val keep = list.focusedChild?.let { list.indexOfChild(it) }?.takeIf { it >= 0 } ?: focusIndex
        list.removeAllViews()
        var focusTarget: View? = null

        section(getString(R.string.channels_section), first = true)
        val rows = arranged()
        val hidden = Prefs.hiddenChannels(this)
        when {
            !loaded -> list.addView(label(getString(R.string.channels_loading), 14, Tok.text56))
            error != null -> list.addView(label(error!!, 14, Tok.text56))
            rows.isEmpty() -> list.addView(label(getString(R.string.channels_none), 14, Tok.text56))
        }
        rows.forEachIndexed { i, r ->
            val on = r.key !in hidden
            val isMoving = moving == r.key
            val v = row(
                title = r.name.ifBlank { r.packageName },
                sub = channelInfo(r),
                value = if (isMoving) "↕" else "${i + 1} · " + getString(if (on) R.string.on else R.string.off),
                dim = !on,
                onClick = { Prefs.setChannelHidden(this, r.key, on); render() },
                onLong = { moving = r.key; awaitRelease = true; render() },
            )
            if (isMoving) focusTarget = v
        }
        if (moving != null) list.addView(label(getString(R.string.channels_moving), 12, Tok.accentSoft, bold = true).apply { setPadding(dp(4), dp(4), 0, 0) })

        if (loaded && watchNext.isNotEmpty()) {
            section(getString(R.string.channels_wn_section))
            val hiddenWn = Prefs.hiddenWatchApps(this)
            val shown = EntryRules.select(watchNext.filter { it.sourcePackage !in hiddenWn }, System.currentTimeMillis()).groupBy { it.sourcePackage ?: "?" }
            watchNext.groupBy { it.sourcePackage ?: "?" }.forEach { (pkg, rs) ->
                val ex = rs.firstOrNull { it.title.isNotBlank() }?.title
                val noLink = rs.count { it.intentUri.isNullOrBlank() }
                val sub = buildString {
                    append(pkg)
                    append("\nСтрок: ${rs.size} · на главном: ${shown[pkg]?.size ?: 0}")
                    append(" · постер: ${rs.count { !it.posterUri.isNullOrBlank() }} · кадр: ${rs.count { !it.thumbUri.isNullOrBlank() }}")
                    append(" · прогресс: ${rs.count { it.positionMs > 0 }}")
                    if (noLink > 0) append(" · без ссылки: $noLink")
                    if (ex != null) append("\nНапример: «$ex»")
                }
                val wnOn = pkg !in hiddenWn
                row(title = appLabel(pkg), sub = sub, value = getString(if (wnOn) R.string.on else R.string.off), dim = !wnOn,
                    onClick = { Prefs.setWatchAppHidden(this, pkg, wnOn); render() }, onLong = null)
            }
        }
        list.addView(space(0, 24))

        val target = focusTarget ?: list.getChildAt(keep.coerceIn(0, list.childCount - 1))
        (if (target?.isFocusable == true) target else (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it.isFocusable })?.requestFocus()
    }

    private fun channelInfo(r: ChannelRow): String {
        val s = r.stats
        return buildString {
            append(appLabel(r.packageName)).append(" · ").append(r.packageName)
            append("\nПрограмм: ${s.total} · постер: ${s.withPoster} (2:3: ${s.portraitPoster}) · кадр 16:9: ${s.withThumb} · прогресс: ${s.withProgress}")
            if (s.noLink > 0) append(" · без ссылки: ${s.noLink}")
            r.description?.takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
            r.entries.firstOrNull()?.let { append("\nНапример: «${it.title}»") }
            if (r.entries.isEmpty()) append("\nНа главный экран не попадёт: нет программ с названием и ссылкой")
        }
    }

    // render() runs on every move step and asks for the label of every row: PackageManager is slow, so ask once per package
    private val labels = HashMap<String, String>()

    private fun appLabel(pkg: String): String = labels.getOrPut(pkg) {
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    private fun section(title: String, first: Boolean = false) {
        if (!first) list.addView(space(0, 22))
        list.addView(label(title.uppercase(), 12, Tok.accentSoft, bold = true).apply { setPadding(dp(4), 0, 0, dp(8)) })
    }

    private fun row(title: String, sub: String, value: String?, dim: Boolean, onClick: () -> Unit, onLong: (() -> Unit)?): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
            minimumHeight = dp(56)
            plate(10)
            alpha = if (dim) 0.6f else 1f
            setOnClickListener { focusIndex = list.indexOfChild(this); onClick() }
            if (onLong != null) setOnLongClickListener { focusIndex = list.indexOfChild(this); onLong(); true }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val t = label(title, 16, bold = true)
        val s: TextView = label(sub, 12, Tok.text56).apply { maxLines = 6 }
        col.addView(t); col.addView(space(0, 3)); col.addView(s)
        box.addView(col, lp(0, -2, 1f))
        val texts = arrayListOf(t)
        if (value != null) {
            val right = label(value, 14, Tok.text72, bold = true).apply { setPadding(dp(16), 0, 0, 0) }
            box.addView(right); texts += right
        }
        box.flipTexts(texts, listOf(s))
        list.addView(box, lp(-1, -2).apply { bottomMargin = dp(6) })
        return box
    }
}
