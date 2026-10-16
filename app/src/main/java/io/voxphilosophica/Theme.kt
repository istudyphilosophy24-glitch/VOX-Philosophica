package io.voxphilosophica

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object Palette {
    val BG = 0xFF10111C.toInt()
    val PANEL = 0xFF1C2030.toInt()
    val RAISED = 0xFF292F43.toInt()
    val GOLD = 0xFFE9C68E.toInt()
    val WHITE = 0xFFF4F0EB.toInt()
    val MUTED = 0xFFADB2C0.toInt()
    val GREEN = 0xFF8BCDAF.toInt()
    fun shape(color: Int, radius: Float = 18f): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
    }
}
fun Activity.darkSystemBars() {
    window.statusBarColor = Palette.BG
    window.navigationBarColor = Palette.BG
    window.decorView.systemUiVisibility = 0
}
fun Context.dp(n: Int): Int = (resources.displayMetrics.density * n + 0.5f).toInt()
fun Context.text(s: String, size: Float = 15f, tint: Int = Palette.WHITE, bold: Boolean = false): TextView =
    TextView(this).apply { text = s; textSize = size; setTextColor(tint)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
fun Context.action(label: String, primary: Boolean = true, click: () -> Unit): Button =
    Button(this).apply {
        text = label; isAllCaps = false; textSize = 13f
        setTextColor(if (primary) Palette.BG else Palette.WHITE)
        background = Palette.shape(if (primary) Palette.GOLD else Palette.RAISED, dp(14).toFloat())
        setOnClickListener { click() }
    }
fun Context.column(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.section(title: String): LinearLayout = column().apply {
    background = Palette.shape(Palette.PANEL, dp(18).toFloat())
    setPadding(dp(18), dp(16), dp(18), dp(18))
    addView(text(title, 17f, Palette.GOLD, true))
}
fun LinearLayout.gap(context: Context, n: Int = 12) {
    addView(View(context), LinearLayout.LayoutParams(1, context.dp(n)))
}
fun LinearLayout.addFull(view: View) = addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
fun LinearLayout.addCentered(view: View) = addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER })
