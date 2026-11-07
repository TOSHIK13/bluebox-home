package app.bluebox.home

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.bluebox.home.data.Apps
import app.bluebox.home.logic.AppEntry
import app.bluebox.home.logic.DockRules
import java.util.concurrent.Executors

/**
 * Dock editor, opened by long OK on a dock tile ([EXTRA_INDEX]). Same mechanics as Google TV's app row: the long-pressed
 * tile gets "Переместить" / "Удалить"; in move mode LEFT/RIGHT carry the tile along the row, OK or Back drops it.
 * The last tile "+" opens the app picker (all apps, most recently used first). Every change is saved at once.
 */
class DockEditActivity : Activity() {
    private lateinit var apps: Apps
    private val io = Executors.newSingleThreadExecutor()
    private var installed: List<AppEntry> = emptyList()
    private var recent: List<String> = emptyList()
    private var tokens: List<String> = emptyList()
    private var selected = -1
    private var moving = false
    private lateinit var title: TextView
    private lateinit var hint: TextView
    private lateinit var row: LinearLayout
    private lateinit var actions: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        apps = Apps(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0f161b"))
            setPadding(dp(Tok.SAFE_X), dp(Tok.SAFE_Y), dp(Tok.SAFE_X), dp(Tok.SAFE_Y))
        }
        title = label("", 24, bold = true)
        hint = label("", 13, Tok.text56)
        root.addView(title)
        root.addView(space(0, 6))
        root.addView(hint)
        root.addView(space(0, 24))
        row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(row)
        root.addView(space(0, 20))
        actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(actions)
        setContentView(root)

        val index = intent.getIntExtra(EXTRA_INDEX, -1)
        io.execute {
            val inst = apps.installed()
            val rec = apps.recent()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                installed = inst; recent = rec
                tokens = DockRules.normalize(inst, packageName, Prefs.dock(this))
                selected = if (index in tokens.indices) index else -1
                render { it.tag == if (selected >= 0) "move" else "add" }
            }
        }
    }

    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }

    /** In move mode the arrows carry the tile; OK / Back drop it. */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (moving) {
            if (e.action == KeyEvent.ACTION_DOWN) when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> step(-1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> step(+1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> {
                    moving = false; render { it.tag == "move" }
                }
            }
            // swallow everything else (up/down must not leave the row, key-up of the drop key must not click)
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    private fun step(delta: Int) {
        if (selected + delta !in tokens.indices) return
        save(DockRules.move(tokens, selected, delta)); selected += delta
        render { it.tag == "tile$selected" }
    }

    private fun save(next: List<String>) { tokens = next; Prefs.setDock(this, next) }

    /** Rebuilds tiles and actions; [focus] picks the view to focus afterwards (default: the first). */
    private fun render(focus: ((View) -> Boolean)?) {
        row.removeAllViews(); actions.removeAllViews()
        title.text = getString(if (moving) R.string.dock_arrange_title else R.string.dock_edit_title)
        hint.text = getString(if (moving) R.string.dock_move_hint else R.string.dock_edit_hint)
        val slots = DockRules.slots(installed, packageName, tokens, recent)
        val views = ArrayList<View>()
        tokens.forEachIndexed { i, t ->
            val app = slots.getOrNull(i)?.app
            val name = if (t == DockRules.RECENT) getString(R.string.dock_recent) else app?.label.orEmpty()
            val tile = tile(name, app?.let { apps.icon(it.packageName) }, i == selected) {
                if (!moving) { selected = if (selected == i) -1 else i; render { v -> v.tag == "move" || v.tag == "tile$i" } }
            }
            tile.tag = "tile$i"
            row.addView(tile, lp(dp(96), dp(104)).apply { rightMargin = dp(10) })
            views += tile
        }
        if (!moving && tokens.size < DockRules.MAX_SLOTS) {
            val add = tile(getString(R.string.dock_add), null, false, R.drawable.ic_add) {
                startActivityForResult(Intent(this, AllAppsActivity::class.java).putExtra(AllAppsActivity.EXTRA_PICK, true), PICK)
            }
            add.tag = "add"
            row.addView(add, lp(dp(96), dp(104)))
            views += add
        }
        if (!moving && selected in tokens.indices) {
            fun act(text: String, tag: String, go: () -> Unit) {
                val c = chip(text) { go() }
                c.tag = tag
                actions.addView(c, lp(dp(170), dp(48)).apply { rightMargin = dp(12) })
                views += c
            }
            act(getString(R.string.dock_move), "move") { moving = true; render { it.tag == "tile$selected" } }
            act(getString(R.string.dock_remove), "remove") { save(DockRules.removeAt(tokens, selected)); selected = -1; render { it.tag == "add" } }
            act(getString(R.string.dock_done), "done") { selected = -1; render { it.tag == "add" } }
        }
        (focus?.let { f -> views.firstOrNull(f) } ?: views.firstOrNull())?.requestFocus()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == PICK && resultCode == RESULT_OK) {
            data?.getStringExtra(AllAppsActivity.EXTRA_PACKAGE)?.let { pkg ->
                save(DockRules.add(tokens, pkg)); selected = -1
                render { it.tag == "tile${tokens.size - 1}" }
            }
        } else super.onActivityResult(requestCode, resultCode, data)
    }

    private fun tile(name: String, icon: android.graphics.drawable.Drawable?, marked: Boolean, iconRes: Int? = null, onClick: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            plate(12); setOnClickListener { onClick() }; contentDescription = name
        }
        val img = ImageView(this).apply {
            if (icon != null) setImageDrawable(icon) else if (iconRes != null) { setImageResource(iconRes); setColorFilter(Tok.text) }
        }
        box.addView(FrameLayout(this).apply { addView(img, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER)) }, lp(dp(40), dp(40)))
        val t = label(if (marked) "‹ $name ›" else name, 12, Tok.text).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER; setPadding(dp(6), dp(8), dp(6), 0)
        }
        box.addView(t)
        box.flipTexts(listOf(t))
        if (icon == null && iconRes != null) box.setOnFocusChangeListener { _, f -> img.setColorFilter(if (f) Tok.onPlate else Tok.text) }
        return box
    }

    private fun chip(text: String, onClick: () -> Unit): View {
        val t = label(text, 14, bold = true)
        return centered(t, dp(170), dp(48)).apply { plate(12); setOnClickListener { onClick() }; flipTexts(listOf(t)) }
    }

    companion object {
        const val EXTRA_INDEX = "index"
        private const val PICK = 1
    }
}
