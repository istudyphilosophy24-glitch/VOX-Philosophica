package io.voxphilosophica

import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfImportProgressTest {
    @Test
    fun selectablePdfHasTextAndReportsCompletedPages() {
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)
        val file = File(context.cacheDir, "vox-import-progress-regression.pdf")
        PDDocument().use { pdf ->
            val page = PDPage()
            pdf.addPage(page)
            PDPageContentStream(pdf, page).use { canvas ->
                canvas.beginText()
                canvas.setFont(PDType1Font.HELVETICA, 14f)
                canvas.newLineAtOffset(72f, 710f)
                canvas.showText("Phenomenology examines the appearance of meaning.")
                canvas.endText()
            }
            pdf.save(file)
        }
        val events = mutableListOf<DocumentParser.ImportProgress>()
        val imported = DocumentParser.import(context, Uri.fromFile(file)) {
            events.add(it)
        }
        assertTrue(imported.chapters.isNotEmpty())
        assertTrue(imported.chapters[0].body.contains("Phenomenology examines"))
        assertTrue(events.any {
            it.stage == "Extracting PDF text" && it.done == 1 && it.total == 1
        })
        assertEquals("Extraction complete", events.last().stage)
        file.delete()
    }
}
