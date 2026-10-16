package io.voxphilosophica

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val importer = Executors.newSingleThreadExecutor()
    private val modelWorker = Executors.newSingleThreadExecutor()
    private lateinit var root: LinearLayout
    private lateinit var library: LinearLayout
    private lateinit var modelStatus: TextView
    private lateinit var message: TextView
    private var downloading = false
    private val REQUEST_DOCUMENT = 99
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        darkSystemBars()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 31)
        render()
    }
    private fun render() {
        val scroller = ScrollView(this).apply { setBackgroundColor(Palette.BG); isFillViewport = true }
        root = column().apply { setPadding(dp(22), dp(34), dp(22), dp(30)) }
        scroller.addView(root); setContentView(scroller)
        root.addView(text("VOX", 40f, Palette.GOLD, true))
        root.addView(text("P H I L O S O P H I C A", 14f, Palette.MUTED))
        root.gap(this, 6)
        root.addView(text("A private listening library for the serious reader.", 15f, Palette.WHITE))
        root.gap(this, 24)
        val importCard = section("THE LIBRARY")
        importCard.gap(this, 12)
        importCard.addFull(action("+  Import EPUB, DOCX or text") {
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                    "application/epub+zip", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "text/plain", "text/markdown", "text/html", "application/octet-stream"))
            }
            startActivityForResult(pick, REQUEST_DOCUMENT)
        })
        importCard.gap(this, 9)
        importCard.addView(text("Original text remains on your phone. EPUB sections form an ordered listening queue.", 12f, Palette.MUTED))
        importCard.gap(this, 12)
        message = text("Ready to import a book.", 13f, Palette.GREEN)
        importCard.addView(message)
        root.addFull(importCard)
        root.gap(this, 16)

        val modelCard = section("INTEGRATED KOKORO STUDIO")
        modelCard.gap(this, 11)
        modelStatus = text("Checking built-in narration models…", 14f, Palette.WHITE)
        modelCard.addView(modelStatus)
        modelCard.gap(this, 10)
        modelCard.addFull(action("Prepare included voice models · offline", false) {
            prepareIntegratedModels()
        })
        modelCard.gap(this, 9)
        modelCard.addView(text("Two quantized Kokoro neural models are already inside this APK. No GitHub download, API key, or subscription is required. Preparation copies them into private storage for speech synthesis.", 12f, Palette.MUTED))
        modelCard.gap(this, 8)
        modelCard.addView(text("Kokoro v1.0: 28 English voices, including four British male narrators. Kokoro v1.1: 103 voices, principally Chinese with three English voices. First use needs additional free storage.", 12f, Palette.MUTED))
        root.addFull(modelCard)
        root.gap(this, 19)
        root.addView(text("YOUR BOOKS", 15f, Palette.GOLD, true))
        root.gap(this, 10)
        library = column()
        root.addFull(library)
        updateModel()
        refreshBooks()
        prepareIntegratedModels()
    }
    private fun updateModel() {
        val installer = ModelInstaller(this)
        val english = installer.installed(ModelInstaller.ENGLISH)
        val latest = installer.installed(ModelInstaller.LATEST)
        modelStatus.text = "English audiobook engine: " + (if (english) "READY" else "preparing") +
            "\nLatest multilingual engine: " + (if (latest) "READY" else "preparing") +
            "\nBoth models included in APK; speech runs without internet."
        modelStatus.setTextColor(if (english) Palette.GREEN else Palette.MUTED)
    }
    private fun prepareIntegratedModels() {
        if (downloading) return
        if (ModelInstaller(this).installed(ModelInstaller.ENGLISH) &&
            ModelInstaller(this).installed(ModelInstaller.LATEST)) {
            updateModel()
            return
        }
        downloading = true
        modelStatus.text = "Unpacking built-in Kokoro models · this can take a few minutes…"
        modelWorker.execute {
            try {
                val installer = ModelInstaller(this)
                installer.install(ModelInstaller.ENGLISH) { status ->
                    runOnUiThread { modelStatus.text = status }
                }
                installer.install(ModelInstaller.LATEST) { status ->
                    runOnUiThread { modelStatus.text = status }
                }
                runOnUiThread { updateModel() }
            } catch (error: Exception) {
                runOnUiThread {
                    updateModel()
                    modelStatus.text = modelStatus.text.toString() +
                        "\nModel preparation error: " + (error.message ?: "unknown error") +
                        ". Free storage and tap Prepare to retry."
                }
            } finally {
                runOnUiThread { downloading = false }
            }
        }
    }
    private fun refreshBooks() {
        library.removeAllViews()
        val books = BookStore(this).all()
        if (books.isEmpty()) {
            library.addView(text("Your library is empty. Import a book to begin.", 14f, Palette.MUTED))
            return
        }
        for (bk in books) {
            val card = section(bk.title)
            card.gap(this, 6)
            card.addView(text("${bk.chapters.size} sections  ·  ${bk.wordCount} words", 12f, Palette.MUTED))
            card.gap(this, 10)
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(action("Open reader") {
                startActivity(Intent(this, ReaderActivity::class.java).putExtra("bookId", bk.id))
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            buttons.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
            buttons.addView(action("Delete", false) {
                AlertDialog.Builder(this).setTitle("Remove ${bk.title}?")
                    .setMessage("The imported book and its generated audio cache will be deleted.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Remove") { _, _ -> BookStore(this).delete(bk.id); refreshBooks() }.show()
            }, LinearLayout.LayoutParams(dp(96), dp(48)))
            card.addFull(buttons)
            library.addFull(card)
            library.gap(this, 10)
        }
    }
    @Deprecated("Modern activity results can replace this when integrating AppCompat")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQUEST_DOCUMENT -> {
                message.text = "Importing document…"
                importer.execute {
                    try {
                        val parsed = DocumentParser.import(this, uri)
                        val book = BookStore(this).save(parsed.title, parsed.chapters)
                        runOnUiThread {
                            message.text = "Imported ${book.title} successfully."
                            refreshBooks()
                        }
                    } catch (error: Exception) {
                        runOnUiThread { message.text = "Import failed: ${error.message}" }
                    }
                }
            }

        }
    }
    override fun onDestroy() { importer.shutdown(); modelWorker.shutdown(); super.onDestroy() }
}
