package io.voxphilosophica

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView

/** Album-like audio controls + fully readable source text, with no web UI dependencies. */
class ReaderActivity : Activity() {
    private lateinit var book: Book
    private lateinit var main: LinearLayout
    private lateinit var chapterSpinner: Spinner
    private lateinit var engineSpinner: Spinner
    private lateinit var voiceSpinner: Spinner
    private lateinit var speedSeek: SeekBar
    private lateinit var speedLabel: TextView
    private lateinit var readText: TextView
    private lateinit var status: TextView
    private lateinit var excerpt: TextView
    private lateinit var progressLabel: TextView
    private lateinit var progress: ProgressBar
    private lateinit var playToggle: android.widget.Button
    private lateinit var settings: LinearLayout
    private lateinit var readingPane: LinearLayout
    private var paused = true
    private var settingsOpen = false
    private var readerOpen = false
    private val playback = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val source = intent.getStringExtra("bookId")
            if (source != null && source != book.id) return
            val msg = intent.getStringExtra("message") ?: return
            if (!::status.isInitialized) return
            status.text = msg
            val snippet = intent.getStringExtra("segment").orEmpty()
            if (::excerpt.isInitialized) {
                excerpt.text = snippet.take(250).ifBlank { "Your chapter is ready to play." }
            }
            val chunk = intent.getIntExtra("chunk", 0)
            val total = intent.getIntExtra("total", 0)
            val section = intent.getIntExtra("chapter", 0)
            if (total > 0) {
                progress.progress = ((chunk + 1) * 1000L / total).toInt().coerceIn(0, 1000)
                progressLabel.text = "Passage " + (chunk + 1) + " of " + total
            }
            paused = intent.getBooleanExtra("paused", false)
            if (::playToggle.isInitialized) playToggle.text = if (paused) "▶" else "Ⅱ"
            if (::chapterSpinner.isInitialized && section in book.chapters.indices &&
                chapterSpinner.selectedItemPosition != section) {
                chapterSpinner.setSelection(section)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        darkSystemBars()
        book = runCatching {
            BookStore(this).load(intent.getStringExtra("bookId") ?: error("Missing book"))
        }.getOrElse { finish(); return }
        render()
    }

    private fun options(values: List<String>): Spinner = Spinner(this).apply {
        adapter = readableAdapter(values)
        background = Palette.shape(Palette.PANEL, dp(13).toFloat())
        minimumHeight = dp(50)
        setPadding(dp(10), dp(4), dp(10), dp(4))
    }
    private fun readableAdapter(values: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, values) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                optionView(getItem(position).orEmpty(), convertView)
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                optionView(getItem(position).orEmpty(), convertView)
            private fun optionView(label: String, reused: View?): TextView =
                (reused as? TextView ?: TextView(this@ReaderActivity)).apply {
                    text = label
                    textSize = 14f
                    setTextColor(Palette.WHITE)
                    setBackgroundColor(Palette.PANEL)
                    setPadding(dp(13), dp(14), dp(13), dp(14))
                    maxLines = 2
                }
        }

    private fun render() {
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            setBackgroundColor(Palette.BG)
        }
        main = column().apply { setPadding(dp(22), dp(20), dp(22), dp(36)) }
        scroll.addView(main)
        setContentView(scroll)

        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(action("‹", false) { finish() }.apply {
            textSize = 27f
            background = Palette.shape(Palette.BG, dp(24).toFloat())
        }, LinearLayout.LayoutParams(dp(52), dp(46)))
        top.addView(text("NOW LISTENING", 12f, Palette.GREEN, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = .19f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(text("♫", 24f, Palette.WHITE).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(dp(52), dp(46)))
        main.addFull(top)
        main.gap(this, 25)

        val artRow = LinearLayout(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
        artRow.addView(bookArt(book, 216, 266),
            LinearLayout.LayoutParams(dp(216), dp(266)))
        main.addFull(artRow)
        main.gap(this, 24)

        main.addView(text(book.title, 27f, Palette.WHITE, true).apply { maxLines = 3 })
        main.gap(this, 5)
        main.addView(text(book.author.ifBlank { "VOX Audiobook" }, 14f, Palette.MUTED))
        main.gap(this, 22)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(Palette.GREEN)
            progressBackgroundTintList =
                android.content.res.ColorStateList.valueOf(Palette.RAISED)
        }
        main.addFull(progress)
        main.gap(this, 7)
        progressLabel = text("Ready • " + book.chapters.size + " sections",
            12f, Palette.MUTED)
        main.addFull(progressLabel)
        main.gap(this, 18)

        val player = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fun control(label: String, action: () -> Unit, prominent: Boolean = false) {
            val button = action(label, prominent, action).apply {
                textSize = if (prominent) 28f else 19f
                if (!prominent) background = Palette.shape(Palette.BG, dp(40).toFloat())
            }
            player.addView(button, LinearLayout.LayoutParams(
                if (prominent) dp(80) else 0, dp(64),
                if (prominent) 0f else 1f))
        }
        control("❮", { NarrationService.dispatch(this, NarrationService.PREVIOUS) })
        control("▶  Play", { startFromSelected() }, true)
        playToggle = action("Ⅱ", false) {
            NarrationService.dispatch(this, if (paused) NarrationService.RESUME else NarrationService.PAUSE)
        }.apply {
            textSize = 23f
            background = Palette.shape(Palette.BG, dp(35).toFloat())
        }
        player.addView(playToggle, LinearLayout.LayoutParams(0, dp(64), 1f))
        control("❯", { NarrationService.dispatch(this, NarrationService.NEXT) })
        main.addFull(player)
        main.gap(this, 12)
        status = text("Select Play to narrate this chapter.", 13f, Palette.GREEN)
        status.gravity = Gravity.CENTER
        main.addFull(status)
        main.gap(this, 8)
        excerpt = text("The current narrated passage will appear here.", 12f, Palette.MUTED).apply {
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
        }
        main.addFull(excerpt)
        main.gap(this, 12)

        val sectionTitle = text("CHAPTER / SECTION", 12f, Palette.MUTED, true)
        main.addFull(sectionTitle)
        main.gap(this, 8)
        chapterSpinner = options(book.chapters.map { it.title })
        main.addFull(chapterSpinner)
        val preference = getSharedPreferences("vox_reader", MODE_PRIVATE)
        chapterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                preference.edit().putInt(book.id, position).apply()
                updateReadingText(position)
            }
        }
        val initial = preference.getInt(book.id, 0).coerceIn(0, book.chapters.lastIndex)
        chapterSpinner.setSelection(initial)

        main.gap(this, 20)
        val toggles = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        toggles.addView(action("♫  Voice settings", false) {
            settingsOpen = !settingsOpen
            settings.visibility = if (settingsOpen) View.VISIBLE else View.GONE
        }, LinearLayout.LayoutParams(0, dp(49), 1f))
        toggles.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        toggles.addView(action("▤  Read text", false) {
            readerOpen = !readerOpen
            readingPane.visibility = if (readerOpen) View.VISIBLE else View.GONE
        }, LinearLayout.LayoutParams(0, dp(49), 1f))
        main.addFull(toggles)
        main.gap(this, 15)

        settings = section("Your narrator").apply { visibility = View.GONE }
        settings.gap(this, 8)
        settings.addView(text("Voice engine", 13f, Palette.MUTED))
        settings.gap(this, 7)
        engineSpinner = options(listOf("Kokoro Studio · English", "Kokoro 1.1 · Multilingual",
            "Android installed TTS"))
        settings.addFull(engineSpinner)
        settings.gap(this, 14)
        settings.addView(text("Narrator", 13f, Palette.MUTED))
        settings.gap(this, 7)
        voiceSpinner = options(NarratorVoices.english.map { it.label })
        settings.addFull(voiceSpinner)
        engineSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val mode = when(position) {
                    0 -> "kokoro-english"
                    1 -> "kokoro-latest"
                    else -> "system"
                }
                voiceSpinner.adapter = readableAdapter(NarratorVoices.forMode(mode).map { it.label })
                voiceSpinner.setSelection(0)
            }
        }
        settings.gap(this, 14)
        speedLabel = text("Narration speed  1.00×", 13f, Palette.WHITE)
        settings.addFull(speedLabel)
        speedSeek = SeekBar(this).apply {
            max = 80
            progress = 30
            progressTintList = android.content.res.ColorStateList.valueOf(Palette.GREEN)
            thumbTintList = android.content.res.ColorStateList.valueOf(Palette.GREEN)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) {
                    speedLabel.text = "Narration speed  " +
                        String.format(java.util.Locale.US, "%.2f", (70 + value) / 100f) + "×"
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        settings.addFull(speedSeek)
        settings.gap(this, 7)
        settings.addFull(action("▶  Apply voice & play") { startFromSelected() })
        settings.gap(this, 8)
        settings.addFull(action("■  Stop audiobook", false) {
            NarrationService.dispatch(this, NarrationService.STOP)
        })
        main.addFull(settings)
        main.gap(this, 15)

        readingPane = section("The Great Reader").apply { visibility = View.GONE }
        readingPane.gap(this, 7)
        readingPane.addView(text("Source text • select and copy passages", 12f, Palette.MUTED))
        readingPane.gap(this, 15)
        readText = text("", 18f, Palette.WHITE).apply {
            setTextIsSelectable(true)
            setLineSpacing(dp(7).toFloat(), 1.16f)
            typeface = Typeface.create("serif", Typeface.NORMAL)
        }
        readingPane.addFull(readText)
        main.addFull(readingPane)
        updateReadingText(initial)

        main.gap(this, 20)
        val queue = section("Up next")
        queue.gap(this, 8)
        val next = book.chapters.drop(initial + 1).take(4)
        if (next.isEmpty()) queue.addView(text("End of book", 13f, Palette.MUTED))
        for (ch in next) {
            val idx = book.chapters.indexOf(ch)
            val row = text("♫  " + ch.title, 14f, Palette.MUTED).apply {
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { chapterSpinner.setSelection(idx) }
            }
            queue.addFull(row)
        }
        main.addFull(queue)

        val pref = getSharedPreferences("vox_playback", MODE_PRIVATE)
        val same = pref.getString("book_id", "") == book.id
        if (same) {
            status.text = pref.getString("message", "Ready") ?: "Ready"
            paused = pref.getBoolean("paused", true)
            playToggle.text = if (paused) "▶" else "Ⅱ"
        }
    }

    private fun updateReadingText(index: Int) {
        if (!::readText.isInitialized || index !in book.chapters.indices) return
        val chapter = book.chapters[index]
        // The underlying full chapter remains stored, even if Android's TextView cannot
        // display extremely long texts efficiently in one layout pass.
        readText.text = if (chapter.body.length > 250_000)
            chapter.body.take(250_000) + "\n\n[Preview capped at 250,000 characters; audio uses the full section.]"
            else chapter.body
    }

    private fun startFromSelected() {
        val mode = when (engineSpinner.selectedItemPosition) {
            0 -> "kokoro-english"
            1 -> "kokoro-latest"
            else -> "system"
        }
        val voices = NarratorVoices.forMode(mode)
        val id = voices.getOrNull(voiceSpinner.selectedItemPosition)?.speakerId
            ?: voices.first().speakerId
        paused = false
        playToggle.text = "Ⅱ"
        try {
            status.text = "Starting audiobook…"
            NarrationService.dispatch(this, NarrationService.START) {
                putExtra("bookId", book.id)
                putExtra("chapter", chapterSpinner.selectedItemPosition)
                putExtra("engine", mode)
                putExtra("speaker", id)
                putExtra("speed", (70 + speedSeek.progress) / 100f)
            }
        } catch (e: RuntimeException) {
            paused = true
            playToggle.text = "▶"
            status.text = "Cannot start audiobook: " + (e.message ?: e.javaClass.simpleName)
            android.util.Log.e("VOX.Player", "Foreground narration service failed", e)
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(NarrationService.EVENT)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(playback, filter, Context.RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(playback, filter)
        }
    }
    override fun onStop() {
        runCatching { unregisterReceiver(playback) }
        super.onStop()
    }
}
