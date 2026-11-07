package app.bluebox.home

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Design tokens from the Blue Box design system (docs/design/ux-vision.md); a 1080p TV is 320 dpi, so 1 dp = 2 px. */
object Tok {
    val surface = Color.parseColor("#15212a")
    val surface2 = Color.parseColor("#1c2a34")
    val plate = Color.WHITE
    val onPlate = Color.parseColor("#111111")
    val accentFill = Color.parseColor("#2a6ef0")
    val accentSoft = Color.parseColor("#8fb8ff")
    val text = Color.WHITE
    val text72 = Color.argb(184, 255, 255, 255)
    val text56 = Color.argb(143, 255, 255, 255)
    val ok = Color.parseColor("#7bd88f")
    val warn = Color.parseColor("#f2c14e")
    val error = Color.parseColor("#ff8a80")
    val off = Color.parseColor("#8c9399")
    const val SAFE_X = 56
    const val SAFE_Y = 27
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun rounded(color: Int, radiusPx: Float): GradientDrawable =
    GradientDrawable().apply { setColor(color); cornerRadius = radiusPx }

/** White focus plate, no scale: unfocused = translucent surface. */
fun View.plate(radiusDp: Int, idle: Int = Tok.surface) {
    val r = context.dp(radiusDp).toFloat()
    background = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(Tok.plate, r))
        addState(intArrayOf(), rounded(idle, r))
    }
    isFocusable = true
    isFocusableInTouchMode = false
    isClickable = true
}

/** Text colours flip with focus: light on the dark surface, dark on the white plate. */
fun View.flipTexts(primary: List<TextView>, secondary: List<TextView> = emptyList()) {
    val apply = { focused: Boolean ->
        primary.forEach { it.setTextColor(if (focused) Tok.onPlate else Tok.text) }
        secondary.forEach { it.setTextColor(if (focused) Color.argb(160, 17, 17, 17) else Tok.text72) }
    }
    apply(false)
    setOnFocusChangeListener { _, f -> apply(f) }
}

fun Context.label(text: String, sp: Int, color: Int = Tok.text, bold: Boolean = false): TextView = TextView(this).apply {
    this.text = text
    textSize = sp.toFloat()
    setTextColor(color)
    includeFontPadding = false
    if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
}

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

fun Context.space(wDp: Int, hDp: Int = 0) = View(this).apply { layoutParams = lp(dp(wDp), dp(hDp)) }

fun Context.centered(child: View, w: Int, h: Int) = FrameLayout(this).apply {
    layoutParams = lp(w, h)
    addView(child, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
}
