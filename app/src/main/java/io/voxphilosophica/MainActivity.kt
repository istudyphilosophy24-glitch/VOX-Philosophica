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
    private lateinit var root: LinearLayout
    private lateinit var library: LinearLayout
    private lateinit var modelStatus: TextView
    private lateinit var message: TextView
    private var downloading = false
    private val REQUEST_DOCUMENT = 99
    private val REQUEST_KOKORO_ARCHIVE = 100
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

        val modelCard = section("OFFLINE VOICE ENGINE")
        modelCard.gap(this, 11)
        modelStatus = text("Checking Kokoro model…", 14f, Palette.WHITE)
        modelCard.addView(modelStatus)
        modelCard.gap(this, 10)
        modelCard.addFull(action("Install Kokoro · one-time large download", false) {
            if (downloading) return@action
            AlertDialog.Builder(this).setTitle("Download Kokoro?")
                .setMessage("Downloads a large open-weight speech model from the official Sherpa-ONNX GitHub release. Wi-Fi and hundreds of MB of free storage are recommended. Android's installed speech engine works without it.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Download") { _, _ -> downloadModel() }.show()
        })
        modelCard.gap(this, 9)
        modelCard.addFull(action("Import Kokoro archive from Downloads", false) {
            if (downloading) return@action
            val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(picker, REQUEST_KOKORO_ARCHIVE)
        })
        modelCard.gap(this, 9)
        modelCard.addFull(action("Download archive using browser", false) {
            val browser = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(ModelInstaller.MODEL_URL))
            try { startActivity(browser) }
            catch (_: android.content.ActivityNotFoundException) {
                modelStatus.text = "No web browser found. You can use the automatic installer instead."
            }
        })
        modelCard.gap(this, 9)
        modelCard.addView(text("If the in-app download fails, use the browser download and then Import Kokoro archive. A partial in-app download is saved so Retry can resume it.", 12f, Palette.MUTED))
        modelCard.gap(this, 9)
        modelCard.addView(text("Kokoro synthesis is local, with no pay-per-hour charge. The voice model is not included in the APK.", 12f, Palette.MUTED))
        root.addFull(modelCard)
        root.gap(this, 19)
        root.addView(text("YOUR BOOKS", 15f, Palette.GOLD, true))
        root.gap(this, 10)
        library = column()
        root.addFull(library)
        updateModel()
        refreshBooks()
    }
    private fun updateModel() {
        modelStatus.text = if (ModelInstaller(this).installed) "● Kokoro installed — local narration is available"
        else "○ Kokoro not installed — Android speech engine is available"
        modelStatus.setTextColor(if (ModelInstaller(this).installed) Palette.GREEN else Palette.MUTED)
    }
    private fun downloadModel() {
        downloading = true
        message.text = "Downloading voice engine…"
        importer.execute {
            try {
                ModelInstaller(this).install { progress -> runOnUiThread { modelStatus.text = progress } }
                runOnUiThread { updateModel(); message.text = "Kokoro installed. Select it inside a book." }
            } catch (error: Exception) {
                runOnUiThread { modelStatus.text = "Install failed: ${error.message}" }
            } finally { runOnUiThread { downloading = false } }
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
            REQUEST_KOKORO_ARCHIVE -> {
                if (downloading) return
                downloading = true
                modelStatus.text = "Opening selected Kokoro archive…"
                importer.execute {
                    try {
                        val input = contentResolver.openInputStream(uri)
                            ?: error("Could not open the selected file")
                        ModelInstaller(this).installFromStream(input) { progress ->
                            runOnUiThread { modelStatus.text = progress }
                        }
                        runOnUiThread { updateModel(); message.text = "Kokoro installed from downloaded archive." }
                    } catch (error: Exception) {
                        runOnUiThread { modelStatus.text = "Archive import failed: ${error.message}" }
                    } finally {
                        runOnUiThread { downloading = false }
                    }
                }
            }
        }
    }
    override fun onDestroy() { importer.shutdown(); super.onDestroy() }
}
