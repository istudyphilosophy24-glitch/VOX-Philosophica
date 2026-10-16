package io.voxphilosophica

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.text.TextWatcher
import android.text.Editable
import android.widget.Toast
import android.widget.ProgressBar
import android.net.Uri
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

/** Dedicated library, discovery and voice studio views with a persistent mini-player. */
class MainActivity : Activity() {
    private val ioWorker = Executors.newSingleThreadExecutor()
    private val modelWorker = Executors.newSingleThreadExecutor()
    private val store by lazy { BookStore(this) }
    private lateinit var content: LinearLayout
    private lateinit var mini: LinearLayout
    private lateinit var bottomNavigation: LinearLayout
    private var modelStatus: TextView? = null
    private var preparing = false
    private var importing = false
    private var importDialog: AlertDialog? = null
    private var cancelledImport: AtomicBoolean? = null
    private var page = "home"
    private val documentRequest = 99
    private val playback = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateMini()
            if (page == "studio") {
                val diagnostic = intent?.getStringExtra("message")
                if (diagnostic != null && intent.getStringExtra("bookId").isNullOrEmpty()) {
                    modelStatus?.text = diagnostic
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        darkSystemBars()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 31)
        }
        renderShell()
        navigate("home")
    }

    private fun renderShell() {
        val shell = column().apply { setBackgroundColor(Palette.BG) }
        val scroll = ScrollView(this).apply { isFillViewport = false
            isVerticalScrollBarEnabled = false }
        content = column().apply { setPadding(dp(20), dp(20), dp(20), dp(28)) }
        scroll.addView(content)
        shell.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        mini = column().apply {
            setPadding(dp(12), dp(5), dp(12), dp(5))
            setBackgroundColor(Palette.BG)
        }
        shell.addFull(mini)
        bottomNavigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(9))
            setBackgroundColor(Palette.BG)
        }
        shell.addFull(bottomNavigation)
        setContentView(shell)
        updateMini()
    }

    private fun header(section: String) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val brand = column()
        brand.addView(text("VOX", 31f, Palette.WHITE, true))
        brand.addView(text(section.uppercase(), 11f, Palette.GREEN, true).apply {
            letterSpacing = 0.18f
        })
        row.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(action("＋  Add book", true) { pickBook() },
            LinearLayout.LayoutParams(dp(122), dp(46)))
        content.addFull(row)
        content.gap(this, 28)
    }

    private fun navigate(target: String) {
        page = target
        content.removeAllViews()
        header(when (target) {
            "library" -> "Your library"
            "studio" -> "Voice studio"
            else -> "Listen & discover"
        })
        when (target) {
            "library" -> libraryPage()
            "studio" -> studioPage()
            else -> homePage()
        }
        bottomNavigation.removeAllViews()
        for ((label, dest) in listOf("⌂  Home" to "home",
            "▦  Library" to "library", "♫  Studio" to "studio")) {
            val selected = page == dest
            val button = action(label, false) { navigate(dest) }.apply {
                setTextColor(if (selected) Palette.GREEN else Palette.MUTED)
                background = Palette.shape(if (selected) Palette.PANEL else Palette.BG, dp(12).toFloat())
            }
            bottomNavigation.addView(button, LinearLayout.LayoutParams(0, dp(50), 1f))
        }
        updateMini()
    }

    private fun homePage() {
        content.addView(text("Good listening.", 28f, Palette.WHITE, true))
        content.gap(this, 5)
        content.addView(text("Your books. Your voices. Entirely offline.", 13f, Palette.MUTED))
        content.gap(this, 12)
        lastImportSummary()
        content.gap(this, 24)
        val books = store.all()
        val feature = column().apply {
            background = Palette.gradient(0xFF234B3B.toInt(), 0xFF15261F.toInt(), dp(17).toFloat())
            setPadding(dp(18), dp(20), dp(18), dp(20))
        }
        feature.addView(text("PICK UP WHERE YOU LEFT OFF", 11f, Palette.GREEN, true))
        feature.gap(this, 16)
        if (books.isNotEmpty()) {
            val selectedId = getSharedPreferences("vox_playback", MODE_PRIVATE).getString("book_id", null)
            val current = books.firstOrNull { it.id == selectedId } ?: books.first()
            val body = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            body.addView(bookArt(current, 100, 134),
                LinearLayout.LayoutParams(dp(100), dp(134)))
            val details = column().apply { setPadding(dp(17), 0, 0, 0) }
            details.addView(text(current.title, 20f, Palette.WHITE, true).apply { maxLines = 3 })
            details.gap(this, 5)
            details.addView(text(current.author.ifBlank { "Audiobook & reader" },
                12f, Palette.MUTED))
            details.gap(this, 15)
            details.addView(action("▶  Continue", true) { openBook(current.id) },
                LinearLayout.LayoutParams(-1, dp(46)))
            body.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            feature.addFull(body)
        } else {
            feature.addView(text("Your next great book starts here.", 22f, Palette.WHITE, true))
            feature.gap(this, 10)
            feature.addView(text("Import an EPUB, PDF or DOCX, then choose a narrator.",
                14f, Palette.MUTED))
            feature.gap(this, 15)
            feature.addFull(action("＋  Import a book") { pickBook() })
        }
        content.addFull(feature)
        content.gap(this, 28)
        if (books.isNotEmpty()) {
            content.addView(text("Recently added", 23f, Palette.WHITE, true))
            content.gap(this, 14)
            val strip = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            val cards = LinearLayout(this)
            for (bk in books.take(15)) {
                val tile = column().apply { setOnClickListener { openBook(bk.id) } }
                tile.addView(bookArt(bk, 134, 170), LinearLayout.LayoutParams(dp(134), dp(170)))
                tile.gap(this, 9)
                tile.addView(text(bk.title, 13f, Palette.WHITE, true).apply { maxLines = 2 })
                tile.addView(text(bk.author.ifBlank { "VOX audiobook" },
                    11f, Palette.MUTED).apply { maxLines = 1 })
                cards.addView(tile, LinearLayout.LayoutParams(dp(134), -2).apply { rightMargin = dp(16) })
            }
            strip.addView(cards)
            content.addFull(strip)
            content.gap(this, 28)
        }
        content.addView(text("Made for serious reading", 22f, Palette.WHITE, true))
        content.gap(this, 12)
        val promo = section("")
        promo.addView(text("♫  The Philosophy Studio", 20f, Palette.WHITE, true))
        promo.gap(this, 6)
        promo.addView(text("Choose a British academic narrator, adjust your pace, and turn whole books into private audiobooks.",
            14f, Palette.MUTED))
        promo.gap(this, 12)
        promo.addView(action("Explore voices", false) { navigate("studio") })
        content.addFull(promo)
    }

    private fun libraryPage() {
        content.addView(text("Your Library", 29f, Palette.WHITE, true))
        content.gap(this, 12)
        lastImportSummary()
        content.gap(this, 14)
        val search = EditText(this).apply {
            hint = "⌕  Search books and authors"
            setSingleLine(true)
            textSize = 15f
            setTextColor(Palette.WHITE)
            setHintTextColor(Palette.MUTED)
            background = Palette.shape(Palette.PANEL, dp(12).toFloat())
            setPadding(dp(17), dp(13), dp(15), dp(13))
        }
        content.addFull(search)
        content.gap(this, 12)
        val results = column()
        content.addFull(results)
        fun populate(query: String) {
            results.removeAllViews()
            val books = store.all().filter {
                it.title.contains(query, true) || it.author.contains(query, true)
            }
            results.addView(text(if (query.isBlank()) {
                books.size.toString() + " saved books"
            } else books.size.toString() + " results", 12f, Palette.MUTED))
            results.gap(this, 12)
            if (books.isEmpty()) {
                results.addView(text("No matching books. Import an EPUB, PDF or DOCX to begin.",
                    14f, Palette.MUTED))
            }
            for (bk in books) {
                val line = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(2), dp(9), dp(2), dp(9))
                    setOnClickListener { openBook(bk.id) }
                }
                line.addView(bookArt(bk, 65, 85), LinearLayout.LayoutParams(dp(65), dp(85)))
                val details = column().apply { setPadding(dp(14), 0, dp(4), 0) }
                details.addView(text(bk.title, 16f, Palette.WHITE, true).apply { maxLines = 2 })
                details.gap(this, 5)
                details.addView(text(
                    bk.author.ifBlank { "Book" } + " • " + bk.chapters.size + " sections",
                    12f, Palette.MUTED).apply { maxLines = 2 })
                line.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
                val more = text("⋮", 25f, Palette.MUTED).apply {
                    gravity = Gravity.CENTER
                    setOnClickListener { confirmDelete(bk.id, bk.title) }
                }
                line.addView(more, LinearLayout.LayoutParams(dp(38), dp(55)))
                results.addFull(line)
            }
        }
        populate("")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                populate(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun studioPage() {
        content.addView(text("Narration Studio", 29f, Palette.WHITE, true))
        content.gap(this, 8)
        content.addView(text("Two Kokoro neural models are included in the app. No subscription or model download.",
            14f, Palette.MUTED))
        content.gap(this, 21)
        val card = section("Kokoro · English Studio")
        card.gap(this, 7)
        card.addView(text("28 English voices, including four British male narrators.",
            13f, Palette.MUTED))
        card.gap(this, 12)
        card.addFull(action("Prepare English voices offline") {
            prepareModel(ModelInstaller.ENGLISH)
        })
        card.gap(this, 8)
        card.addFull(action("▶  Test English Kokoro — hear a sample", false) {
            modelStatus?.text = "Starting real Kokoro speech diagnostic…"
            NarrationService.dispatch(this, NarrationService.TEST_VOICE) {
                putExtra("engine", "kokoro-english")
                putExtra("speaker", 26)
            }
        })
        content.addFull(card)
        content.gap(this, 12)
        val latest = section("Kokoro · Latest multilingual")
        latest.gap(this, 7)
        latest.addView(text("103 voices, primarily Mandarin plus three English voices.",
            13f, Palette.MUTED))
        latest.gap(this, 12)
        latest.addFull(action("Prepare latest voices offline", false) {
            prepareModel(ModelInstaller.LATEST)
        })
        latest.gap(this, 8)
        latest.addFull(action("▶  Test Kokoro v1.1 — hear a sample", false) {
            modelStatus?.text = "Starting multilingual Kokoro speech diagnostic…"
            NarrationService.dispatch(this, NarrationService.TEST_VOICE) {
                putExtra("engine", "kokoro-latest")
                putExtra("speaker", 0)
            }
        })
        content.addFull(latest)
        content.gap(this, 14)
        modelStatus = text("Checking included models…", 13f, Palette.MUTED)
        content.addView(modelStatus)
        updateModelStatus()
        content.gap(this, 8)
        content.addView(text("The test generates a real local WAV file and plays it. It does not need a book or Internet. If a test fails, the reason should appear above without closing the library.", 12f, Palette.MUTED))
        content.gap(this, 20)
        content.addView(text("Listening tips", 21f, Palette.WHITE, true))
        content.gap(this, 9)
        content.addView(text("For Hegel and Heidegger, begin with British George at 0.90×. Speech is generated locally and cached for replay. You can select narrator and speed inside each book.",
            14f, Palette.MUTED))
    }

    private fun updateModelStatus() {
        val installer = ModelInstaller(this)
        modelStatus?.text = "English voices: " +
            (if (installer.installed(ModelInstaller.ENGLISH)) "ready" else "not prepared") +
            "\nLatest voices: " +
            (if (installer.installed(ModelInstaller.LATEST)) "ready" else "not prepared") +
            "\nThe first preparation needs additional free storage."
    }
    private fun prepareModel(which: String) {
        if (preparing) return
        preparing = true
        modelStatus?.text = "Preparing Kokoro " + which + " from the integrated APK…"
        modelWorker.execute {
            try {
                ModelInstaller(this).install(which) { progress ->
                    runOnUiThread { modelStatus?.text = progress }
                }
                runOnUiThread { updateModelStatus() }
            } catch (e: Exception) {
                runOnUiThread {
                    modelStatus?.text = "Model preparation failed: " +
                        (e.message ?: "Unknown error") + ". Free storage and try again."
                }
            } finally { runOnUiThread { preparing = false } }
        }
    }

    private fun updateMini() {
        mini.removeAllViews()
        val pref = getSharedPreferences("vox_playback", MODE_PRIVATE)
        val id = pref.getString("book_id", null) ?: return
        val book = store.all().firstOrNull { it.id == id } ?: return
        val holder = LinearLayout(this).apply {
            background = Palette.shape(Palette.SOFT_GREEN, dp(13).toFloat())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(10), dp(8))
        }
        holder.addView(bookArt(book, 47, 47), LinearLayout.LayoutParams(dp(47), dp(47)))
        val labels = column().apply {
            setPadding(dp(10), 0, dp(5), 0)
            setOnClickListener { openBook(book.id) }
        }
        labels.addView(text(book.title, 13f, Palette.WHITE, true).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        labels.addView(text(pref.getString("message", "Ready to listen") ?: "Ready to listen",
            11f, Palette.MUTED).apply { maxLines = 1 })
        holder.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val paused = pref.getBoolean("paused", true)
        holder.addView(action(if (paused) "▶" else "Ⅱ", false) {
            if (paused && !pref.getBoolean("active", false)) {
                NarrationService.dispatch(this, NarrationService.START) {
                    putExtra("bookId", book.id)
                    putExtra("chapter", pref.getInt("chapter", 0))
                    putExtra("engine", pref.getString("engine", "kokoro-english"))
                    putExtra("speaker", pref.getInt("speaker", 26))
                    putExtra("speed", pref.getFloat("speed", 1f))
                }
            } else {
                NarrationService.dispatch(this,
                    if (paused) NarrationService.RESUME else NarrationService.PAUSE)
            }
        }.apply {
            background = Palette.shape(Palette.SOFT_GREEN, dp(30).toFloat())
            textSize = 20f
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        mini.addFull(holder)
    }

    private fun lastImportSummary() {
        val msg = getSharedPreferences("vox_import", MODE_PRIVATE)
            .getString("last_result", null) ?: return
        val failed = getSharedPreferences("vox_import", MODE_PRIVATE)
            .getBoolean("last_failed", false)
        val block = section(if (failed) "Last import needs attention" else "Last import completed")
        block.addView(text(msg, 12f, if (failed) Palette.GOLD else Palette.GREEN))
        content.addFull(block)
    }

    /**
     * Modal progress UI stays visible until the file is fully parsed and saved.
     * The cancel button sets a cooperative flag checked during copy/page parsing.
     */
    private fun beginImport(uri: Uri) {
        if (importing) {
            Toast.makeText(this, "A book is already being imported", Toast.LENGTH_SHORT).show()
            return
        }
        importing = true
        val cancelled = AtomicBoolean(false)
        cancelledImport = cancelled
        val body = column().apply { setPadding(dp(20), dp(18), dp(20), dp(16)) }
        val title = text("Preparing your ebook…", 19f, Palette.WHITE, true)
        body.addFull(title)
        body.gap(this, 12)
        val status = text("Opening selected document", 14f, Palette.MUTED)
        body.addFull(status)
        body.gap(this, 14)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 1
            progressTintList = android.content.res.ColorStateList.valueOf(Palette.GREEN)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Palette.RAISED)
        }
        body.addFull(bar)
        body.gap(this, 9)
        val fraction = text("Preparing local import…", 12f, Palette.MUTED)
        body.addFull(fraction)
        body.gap(this, 15)
        body.addFull(text("Your book remains on this device. Nothing is uploaded.",
            12f, Palette.MUTED))
        body.gap(this, 12)
        val cancelButton = action("Cancel import", false) {
            cancelled.set(true)
            status.text = "Cancelling safely…"
            fraction.text = "Finishing the current page or archive operation"
        }
        body.addFull(cancelButton)
        val dialog = AlertDialog.Builder(this).setView(body).create().apply {
            setCancelable(false)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
        }
        importDialog = dialog
        dialog.show()
        val started = android.os.SystemClock.uptimeMillis()
        ioWorker.execute {
            try {
                val parsed = DocumentParser.import(this, uri) { p ->
                    if (cancelled.get()) throw CancellationException("Import cancelled")
                    runOnUiThread {
                        if (importDialog !== dialog || isFinishing) return@runOnUiThread
                        status.text = p.stage
                        val hasTotal = p.total > 0
                        val stagePct = if (hasTotal) (p.done.toFloat() / p.total)
                            .coerceIn(0f, 1f) else 0f
                        val pct = when (p.stage) {
                            "Copying document" -> 1 + (stagePct * 17).toInt()
                            "Extracting EPUB chapters", "Extracting DOCX paragraphs",
                            "Extracting PDF text", "Reading PDF pages",
                            "Recognizing scanned PDF pages" ->
                                20 + (stagePct * 70).toInt()
                            "Extraction complete" -> 92
                            else -> 19
                        }
                        bar.progress = pct
                        fraction.text = if (hasTotal) {
                            p.done.toString() + " / " + p.total + " · " +
                                (stagePct * 100).toInt() + "% of " + p.stage.lowercase() +
                                "\n" + p.description
                        } else p.description.ifBlank { "Working…" }
                    }
                }
                if (cancelled.get()) throw CancellationException("Import cancelled")
                runOnUiThread {
                    if (importDialog === dialog) {
                        status.text = "Saving to your library…"
                        bar.progress = 95
                        fraction.text = "Keeping imported chapters and book metadata"
                    }
                }
                val saved = store.save(parsed.title, parsed.chapters, parsed.author, parsed.cover)
                val elapsed = ((android.os.SystemClock.uptimeMillis() - started) / 1000)
                val result = saved.title + " · " + saved.chapters.size +
                    " sections · " + saved.wordCount + " words · " + elapsed + " sec"
                getSharedPreferences("vox_import", MODE_PRIVATE).edit()
                    .putString("last_result", result).putBoolean("last_failed", false).apply()
                runOnUiThread {
                    bar.progress = 100
                    finishImportDialog(dialog)
                    navigate("library")
                    AlertDialog.Builder(this)
                        .setTitle("Book imported successfully")
                        .setMessage(result + "\n\nThe text is ready for reading and narration.")
                        .setPositiveButton("Open reader") { _, _ -> openBook(saved.id) }
                        .setNegativeButton("Stay in library", null)
                        .show()
                }
            } catch (ex: Exception) {
                val wasCancelled = cancelled.get() || ex is CancellationException
                val report = if (wasCancelled) "Import cancelled" else
                    (ex.message ?: ex.javaClass.simpleName)
                getSharedPreferences("vox_import", MODE_PRIVATE).edit()
                    .putString("last_result", report).putBoolean("last_failed", !wasCancelled)
                    .apply()
                runOnUiThread {
                    finishImportDialog(dialog)
                    if (::content.isInitialized) navigate(page)
                    AlertDialog.Builder(this)
                        .setTitle(if (wasCancelled) "Import cancelled" else "Unable to import ebook")
                        .setMessage(if (wasCancelled) "No book was added."
                            else report + "\n\nFor scanned PDFs, OCR is required. For encrypted PDFs, remove the password before importing.")
                        .setPositiveButton("OK", null)
                        .setNeutralButton("Try another book") { _, _ -> pickBook() }
                        .show()
                }
            }
        }
    }
    private fun finishImportDialog(dialog: AlertDialog) {
        importing = false
        cancelledImport = null
        if (importDialog === dialog) importDialog = null
        if (dialog.isShowing) dialog.dismiss()
    }

    private fun pickBook() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/epub+zip", "application/pdf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "text/plain", "text/markdown", "text/html", "application/octet-stream"))
        }
        startActivityForResult(intent, documentRequest)
    }
    private fun openBook(id: String) {
        startActivity(Intent(this, ReaderActivity::class.java).putExtra("bookId", id))
    }
    private fun confirmDelete(id: String, title: String) {
        AlertDialog.Builder(this).setTitle("Remove this book?")
            .setMessage(title + "\nIts imported text and cached audio will be deleted.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                store.delete(id)
                navigate("library")
            }.show()
    }

    @Deprecated("Retained for Storage Access Framework compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != documentRequest || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        beginImport(uri)
    }
    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(NarrationService.EVENT)
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(playback, filter, Context.RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(playback, filter)
        }
        if (::mini.isInitialized) updateMini()
    }
    override fun onStop() {
        runCatching { unregisterReceiver(playback) }
        super.onStop()
    }
    override fun onResume() {
        super.onResume()
        if (::content.isInitialized && page == "home") navigate("home")
        if (::mini.isInitialized) updateMini()
    }
    override fun onDestroy() {
        cancelledImport?.set(true)
        importDialog?.dismiss()
        ioWorker.shutdown()
        modelWorker.shutdown()
        super.onDestroy()
    }
}
