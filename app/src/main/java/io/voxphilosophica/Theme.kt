package io.voxphilosophica

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** High-contrast, music-library-inspired styling with VOX's own identity. */
object Palette {
    val BG = Color.rgb(12, 13, 16)
    val PANEL = Color.rgb(30, 31, 37)
    val RAISED = Color.rgb(43, 45, 53)
    val GOLD = Color.rgb(223, 193, 139)
    val WHITE = Color.rgb(249, 249, 251)
    val MUTED = Color.rgb(168, 171, 180)
    val GREEN = Color.rgb(29, 211, 118)
    val SOFT_GREEN = Color.rgb(21, 64, 44)
    fun shape(color: Int, radius: Float = 18f) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
    }
    fun gradient(start: Int, end: Int, radius: Float = 18f) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(start, end)).apply {
            cornerRadius = radius
        }
}
fun Activity.darkSystemBars() {
    window.statusBarColor = Palette.BG
    window.navigationBarColor = Palette.BG
    window.decorView.systemUiVisibility = 0
}
fun Context.dp(n: Int): Int = (resources.displayMetrics.density * n + 0.5f).toInt()
fun Context.text(s: String, size: Float = 15f, tint: Int = Palette.WHITE, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = s
        textSize = size
        setTextColor(tint)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
fun Context.action(label: String, primary: Boolean = true, click: () -> Unit): Button =
    Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        letterSpacing = 0f
        elevation = 0f
        setPadding(dp(14), 0, dp(14), 0)
        setTextColor(if (primary) Color.BLACK else Palette.WHITE)
        background = Palette.shape(if (primary) Palette.GREEN else Palette.RAISED, dp(28).toFloat())
        setOnClickListener { click() }
    }
fun Context.column(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
}
fun Context.section(title: String): LinearLayout = column().apply {
    background = Palette.shape(Palette.PANEL, dp(18).toFloat())
    setPadding(dp(18), dp(18), dp(18), dp(18))
    if (title.isNotBlank()) {
        addView(text(title, 17f, Palette.WHITE, true))
    }
}
fun LinearLayout.gap(context: Context, n: Int = 12) {
    addView(View(context), LinearLayout.LayoutParams(1, context.dp(n)))
}
fun LinearLayout.addFull(view: View) {
    addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
}
fun LinearLayout.addCentered(view: View) {
    addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER })
}

/** Lightweight book-cover component; imported cover artwork stays private. */
fun Context.bookArt(book: Book, width: Int, height: Int): FrameLayout {
    val seed = kotlin.math.abs(book.title.hashCode().toLong())
    val colors = intArrayOf(
        Color.rgb(37, 79, 76), Color.rgb(84, 48, 82),
        Color.rgb(52, 62, 107), Color.rgb(111, 71, 51),
        Color.rgb(48, 75, 55)
    )
    val start = colors[(seed % colors.size).toInt()]
    val end = Color.rgb(14, 19, 24)
    return FrameLayout(this).apply {
        layoutParams = ViewGroup.LayoutParams(dp(width), dp(height))
        background = Palette.gradient(start, end, dp(8).toFloat())
        clipToOutline = true
        val cover = BookStore(this@bookArt).cover(book.id)
        if (cover != null) {
            val bmp = BitmapFactory.decodeFile(cover.absolutePath)
            if (bmp != null) {
                addView(ImageView(context).apply {
                    setImageBitmap(bmp)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }, FrameLayout.LayoutParams(-1, -1))
            }
        } else {
            val text = TextView(context).apply {
                val short = book.title.trim().take(42)
                this.text = short.ifBlank { "VOX" }
                textSize = if (width > 180) 24f else 15f
                setTextColor(Color.WHITE)
                typeface = Typeface.create("serif", Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            addView(text, FrameLayout.LayoutParams(-1, -1))
        }
    }
}
