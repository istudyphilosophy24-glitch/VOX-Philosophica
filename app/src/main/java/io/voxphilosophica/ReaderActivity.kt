package io.voxphilosophica

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView

class ReaderActivity : Activity() {
    private lateinit var book: Book
    private lateinit var chapterSpinner: Spinner
    private lateinit var engineSpinner: Spinner
    private lateinit var voiceSpinner: Spinner
    private lateinit var speedSeek: SeekBar
    private lateinit var speedLabel: TextView
    private lateinit var display: TextView
    private lateinit var status: TextView
    private lateinit var excerpt: TextView
    private val broadcast = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            status.text = intent?.getStringExtra("message") ?: "Ready"
            val segment = intent?.getStringExtra("segment") ?: ""
            if (segment.isNotBlank()) excerpt.text = "NOW READING   " + segment
        }
    }
    private fun options(values: List<String>): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@ReaderActivity, android.R.layout.simple_spinner_dropdown_item, values)
        setBackgroundColor(Palette.RAISED)
        minimumHeight = dp(46)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        darkSystemBars()
        try { book = BookStore(this).load(intent.getStringExtra("bookId") ?: error("Book not selected")) }
        catch (error: Exception) { finish(); return }
        render()
    }
    private fun render() {
        val scroller = ScrollView(this).apply { setBackgroundColor(Palette.BG); fillViewport = true }
        val content = column().apply { setPadding(dp(21), dp(28), dp(21), dp(30)) }
        scroller.addView(content); setContentView(scroller)
        content.addView(action("←  Back to library", false) { finish() })
        content.gap(this, 19)
        content.addView(text(book.title, 27f, Palette.GOLD, true))
        content.gap(this, 8)
        content.addView(text("Chapter / section", 13f, Palette.MUTED))
        chapterSpinner = options(book.chapters.map { it.title })
        content.addFull(chapterSpinner)
        content.gap(this, 17)
        val controls = section("THE NARRATOR")
        controls.gap(this, 10)
        controls.addView(text("Speech engine", 13f, Palette.MUTED))
        engineSpinner = options(listOf("Android offline voice", "Kokoro local neural voice"))
        engineSpinner.setSelection(if (ModelInstaller(this).installed) 1 else 0)
        controls.addFull(engineSpinner)
        controls.gap(this, 10)
        controls.addView(text("Voice / speaker", 13f, Palette.MUTED))
        voiceSpinner = options((1..11).map { "Voice $it" })
        controls.addFull(voiceSpinner)
        controls.gap(this, 12)
        speedLabel = text("Narration speed · 1.00×", 13f, Palette.WHITE)
        controls.addView(speedLabel)
        speedSeek = SeekBar(this).apply {
            max = 80; progress = 30
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    speedLabel.text = "Narration speed · ${"%.2f".format((70 + progress) / 100f)}×"
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        controls.addFull(speedSeek)
        controls.gap(this, 10)
        status = text("Ready to narrate.", 13f, Palette.GREEN)
        controls.addView(status)
        controls.gap(this, 10)
        excerpt = text("CURRENT PASSAGE   —", 13f, Palette.MUTED)
        controls.addView(excerpt)
        controls.gap(this, 12)
        controls.addFull(action("▶  Narrate from selected section") {
            if (engineSpinner.selectedItemPosition == 1 && !ModelInstaller(this).installed) {
                status.text = "Install the Kokoro model from the library screen first."
                return@action
            }
            NarrationService.dispatch(this, NarrationService.START) {
                putExtra("bookId", book.id)
                putExtra("chapter", chapterSpinner.selectedItemPosition)
                putExtra("engine", if (engineSpinner.selectedItemPosition == 1) "kokoro" else "system")
                putExtra("speaker", voiceSpinner.selectedItemPosition)
                putExtra("speed", (70 + speedSeek.progress) / 100f)
            }
        })
        controls.gap(this, 8)
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun control(label: String, action: String) {
            row1.addView(action(label, false) { NarrationService.dispatch(this, action) },
                LinearLayout.LayoutParams(0, dp(46), 1f))
        }
        control("Pause", NarrationService.PAUSE)
        row1.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        control("Resume", NarrationService.RESUME)
        row1.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        control("Next", NarrationService.NEXT)
        controls.addFull(row1)
        controls.gap(this, 7)
        controls.addFull(action("■  Stop narration", false) { NarrationService.dispatch(this, NarrationService.STOP) })
        content.addFull(controls)
        content.gap(this, 22)
        content.addView(text("THE READER", 15f, Palette.GOLD, true))
        content.gap(this, 8)
        display = text("", 17f, Palette.WHITE)
        val reader = section("")
        reader.addFull(display)
        content.addFull(reader)
        chapterSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val body = book.chapters[position].body
                display.text = if (body.length > 100_000)
                    body.take(100_000) + "\n\n[This section's reader preview is truncated; full text is narrated.]" else body
            }
        }
    }
    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(NarrationService.EVENT)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(broadcast, filter, Context.RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(broadcast, filter)
        }
    }
    override fun onStop() {
        runCatching { unregisterReceiver(broadcast) }
        super.onStop()
    }
}
