package io.voxphilosophica

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.common.InputImage
import com.google.android.gms.tasks.Tasks
import org.jsoup.Jsoup
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/** EPUB spine, metadata, TOC, covers; DOCX structural paragraphs; PDF native text. */
object DocumentParser {
    data class Imported(val title: String, val chapters: List<Chapter>,
                        val author: String = "", val cover: ByteArray? = null)
    private data class Entry(val href: String, val mime: String, val properties: String)
    private const val MAX_DOCUMENT = 120L * 1024 * 1024
    data class ImportProgress(val stage: String, val done: Int = 0,
                              val total: Int = 0, val description: String = "")

    fun import(
        context: Context, uri: Uri, onProgress: (ImportProgress) -> Unit = {}
    ): Imported {
        fun update(stage: String, done: Int = 0, total: Int = 0, note: String = "") {
            if (Thread.currentThread().isInterrupted)
                throw java.util.concurrent.CancellationException("Import cancelled")
            onProgress(ImportProgress(stage, done, total, note))
        }
        update("Opening document", note = "Checking the selected file")
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Untitled"
        val ext = name.substringAfterLast('.', "").lowercase()
        val expectedBytes = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE),
            null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
        } ?: -1L
        val file = File.createTempFile("vox-import-", ".bin", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Unable to open selected document" }
                file.outputStream().use { output ->
                    val buf = ByteArray(32_768)
                    var total = 0L
                    while (true) {
                        val count = input.read(buf)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_DOCUMENT) { "Document exceeds 120 MB; try a smaller file" }
                        output.write(buf, 0, count)
                        if (total % (512 * 1024) < count || total == count) {
                            update("Copying document", (total / 1024).toInt(),
                                if (expectedBytes in 1..MAX_DOCUMENT) (expectedBytes / 1024).toInt() else 0,
                                "Copied " + (total / 1048576) + " MB to local storage")
                        }
                    }
                }
            }
            val baseTitle = name.substringBeforeLast('.').ifBlank { name }
            update("Inspecting document", note = "Finding text and sections")
            val parsed = when (ext) {
                "epub" -> extractEpub(file, baseTitle, ::update)
                "docx" -> extractDocx(file, baseTitle, ::update)
                "pdf" -> extractPdf(context, file, baseTitle, ::update)
                "html", "htm", "xhtml" -> Imported(baseTitle,
                    listOf(Chapter("Complete text", htmlToText(file.readText()))))
                "txt", "md" -> Imported(baseTitle, listOf(Chapter("Complete text", file.readText())))
                else -> error("Use EPUB, PDF, DOCX, TXT, Markdown or HTML. Scanned PDFs need OCR.")
            }.also { require(it.chapters.any { ch -> ch.body.isNotBlank() }) {
                "No readable text found. Image-only/scanned PDFs need OCR." }
            }
            update("Extraction complete", parsed.chapters.size, parsed.chapters.size,
                parsed.chapters.size.toString() + " reading sections found")
            return parsed
        } finally { file.delete() }
    }

    private fun readBytes(input: InputStream, max: Int): ByteArray = input.use { source ->
        val buffer = ByteArray(16_384)
        val out = ByteArrayOutputStream()
        while (true) {
            val count = source.read(buffer)
            if (count < 0) break
            require(out.size().toLong() + count <= max) { "Archive entry exceeds limit" }
            out.write(buffer, 0, count)
        }
        out.toByteArray()
    }

    private fun readXml(input: InputStream, max: Int): org.w3c.dom.Document =
        secureXml(String(readBytes(input, max), Charsets.UTF_8))

    private fun secureXml(xml: String): org.w3c.dom.Document {
        require(!Regex("(?i)<!\\s*(DOCTYPE|ENTITY)\\b").containsMatchIn(xml)) {
            "XML DTD/entity declarations are disabled"
        }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { isXIncludeAware = false }
            listOf(
                "http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
                "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false
            ).forEach { (flag, value) -> runCatching { setFeature(flag, value) } }
        }
        return factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw org.xml.sax.SAXException("No external XML entities") }
        }.parse(InputSource(StringReader(xml)))
    }

    /** Retain paragraphs, headings, quotes and list boundaries; omit active content. */
    private fun htmlToText(source: String): String {
        val doc = Jsoup.parse(source)
        doc.select("script, style, noscript, svg, nav, head").remove()
        doc.select("br").forEach { it.before("\n") }
        doc.select("p, h1, h2, h3, h4, h5, h6, li, blockquote, pre, tr, section, article")
            .forEach { element ->
                element.prependText("\n")
                element.appendText("\n")
            }
        return doc.body().wholeText()
            .replace('\u00A0', ' ')
            .replace(Regex("[\\t ]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    /** Resolves relative EPUB resource paths without treating + as space. */
    private fun path(base: String, relative: String): String {
        val raw = relative.substringBefore('#').substringBefore('?')
        require(!raw.startsWith("/") && !raw.contains("://")) { "Invalid EPUB resource URL" }
        val decoded = Uri.decode(raw)
        val result = mutableListOf<String>()
        val joined = if (base.isBlank()) decoded else "$base/$decoded"
        for (segment in joined.replace('\\', '/').split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> require(result.isNotEmpty()) { "Unsafe EPUB resource path" }.also {
                    result.removeAt(result.lastIndex)
                }
                else -> result.add(segment)
            }
        }
        return result.joinToString("/")
    }

    private fun extractEpub(
        file: File, filename: String,
        report: (String, Int, Int, String) -> Unit
    ): Imported = ZipFile(file).use { zip ->
        val container = zip.getEntry("META-INF/container.xml") ?: error("EPUB container.xml missing")
        val rootDoc = readXml(zip.getInputStream(container), 100_000)
        val root = rootDoc.getElementsByTagNameNS("*", "rootfile").item(0) as? Element
            ?: error("EPUB package manifest missing")
        val opfPath = path("", root.getAttribute("full-path"))
        val opfEntry = zip.getEntry(opfPath) ?: error("EPUB package file missing")
        val opf = readXml(zip.getInputStream(opfEntry), 2_000_000)
        val opfBase = opfPath.substringBeforeLast('/', "")
        fun metadata(tag: String): String {
            val nodes = opf.getElementsByTagNameNS("*", tag)
            return (0 until nodes.length).asSequence()
                .map { nodes.item(it).textContent.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
        }
        val bookTitle = metadata("title").takeIf { it.isNotBlank() } ?: filename
        val author = metadata("creator")
        val manifest = mutableMapOf<String, Entry>()
        val items = opf.getElementsByTagNameNS("*", "item")
        for (i in 0 until items.length) {
            val e = items.item(i) as Element
            manifest[e.getAttribute("id")] = Entry(
                path(opfBase, e.getAttribute("href")),
                e.getAttribute("media-type"), e.getAttribute("properties")
            )
        }

        val toc = mutableMapOf<String, String>()
        val nav = manifest.values.firstOrNull { "nav" in it.properties.split(' ') }
        if (nav != null) {
            zip.getEntry(nav.href)?.let { entry ->
                val html = String(readBytes(zip.getInputStream(entry), 1_000_000), Charsets.UTF_8)
                val dom = Jsoup.parse(html)
                val navigation = dom.select("nav").firstOrNull {
                    it.attr("epub:type").contains("toc") || it.attr("role").contains("doc-toc")
                } ?: dom.selectFirst("nav")
                navigation?.select("a[href]")?.forEach { link ->
                    runCatching {
                        val target = path(nav.href.substringBeforeLast('/', ""), link.attr("href"))
                        val label = link.text().trim()
                        if (label.isNotBlank()) toc.putIfAbsent(target, label.take(180))
                    }
                }
            }
        }
        // EPUB2 NCX support when EPUB3 nav is absent.
        if (toc.isEmpty()) {
            val ncx = manifest.values.firstOrNull { it.mime == "application/x-dtbncx+xml" }
            if (ncx != null) {
                zip.getEntry(ncx.href)?.let { entry ->
                    val dom = readXml(zip.getInputStream(entry), 2_000_000)
                    val points = dom.getElementsByTagNameNS("*", "navPoint")
                    for (i in 0 until points.length) {
                        val p = points.item(i) as Element
                        val labels = p.getElementsByTagNameNS("*", "text")
                        val contents = p.getElementsByTagNameNS("*", "content")
                        val source = contents.item(0) as? Element ?: continue
                        val label = labels.item(0)?.textContent?.trim().orEmpty()
                        if (label.isNotBlank()) {
                            runCatching {
                                toc.putIfAbsent(path(ncx.href.substringBeforeLast('/', ""),
                                    source.getAttribute("src")), label.take(180))
                            }
                        }
                    }
                }
            }
        }

        val chapters = mutableListOf<Chapter>()
        val spine = opf.getElementsByTagNameNS("*", "itemref")
        for (i in 0 until spine.length) {
            require(chapters.size <= 4000) { "EPUB exceeds 4000 reading sections" }
            val ref = spine.item(i) as Element
            report("Extracting EPUB chapters", i + 1, spine.length, "Reading chapters in order")
            if (ref.getAttribute("linear").equals("no", ignoreCase = true)) continue
            val item = manifest[ref.getAttribute("idref")] ?: continue
            if (item.properties.split(' ').contains("nav")) continue
            if (!(item.mime.contains("html") || item.href.endsWith(".xhtml", true)
                        || item.href.endsWith(".html", true))) continue
            val entry = zip.getEntry(item.href) ?: continue
            val xhtml = String(readBytes(zip.getInputStream(entry), 5_000_000), Charsets.UTF_8)
            val doc = Jsoup.parse(xhtml)
            val heading = doc.selectFirst("body h1, body h2, body h3")?.text()?.trim().orEmpty()
            val title = toc[item.href] ?: heading.takeIf { it.isNotBlank() }
                ?: doc.title().takeIf { it.isNotBlank() }
                ?: "Section " + (chapters.size + 1)
            val text = htmlToText(xhtml)
            if (text.isNotBlank()) chapters.add(Chapter(title.take(180), text))
        }
        require(chapters.isNotEmpty()) { "No readable EPUB spine sections found" }

        val coverId = opf.getElementsByTagNameNS("*", "meta").let { nodes ->
            (0 until nodes.length).asSequence().mapNotNull { nodes.item(it) as? Element }
                .firstOrNull { it.getAttribute("name").equals("cover", true) }
                ?.getAttribute("content")
        }
        val coverEntry = (manifest[coverId]?.takeIf { it.mime.startsWith("image/") }
            ?: manifest.values.firstOrNull { it.properties.split(' ').contains("cover-image") }
            ?: manifest.values.firstOrNull { it.mime.startsWith("image/") &&
                it.href.substringAfterLast('/').contains("cover", ignoreCase = true) })
        val cover = coverEntry?.let { zip.getEntry(it.href) }?.let { entry ->
            runCatching { readBytes(zip.getInputStream(entry), 3_000_000) }.getOrNull()
        }
        Imported(bookTitle, chapters, author, cover)
    }

    private fun extractDocx(
        file: File, filename: String,
        report: (String, Int, Int, String) -> Unit
    ): Imported = ZipFile(file).use { zip ->
        val entry = zip.getEntry("word/document.xml") ?: error("Invalid DOCX package")
        val doc = readXml(zip.getInputStream(entry), 25_000_000)
        val paragraphs = doc.getElementsByTagNameNS("*", "p")
        val chapters = mutableListOf<Chapter>()
        var title = "Complete document"
        val current = StringBuilder()
        fun flush() {
            if (current.isNotBlank()) {
                chapters.add(Chapter(title, current.toString().trim()))
                current.clear()
            }
        }
        fun collect(node: Node, out: StringBuilder) {
            when (node.localName) {
                "t" -> out.append(node.textContent)
                "tab" -> out.append("    ")
                "br", "cr" -> out.append('\n')
                else -> {
                    val children = node.childNodes
                    for (k in 0 until children.length) collect(children.item(k), out)
                }
            }
        }
        for (i in 0 until paragraphs.length) {
            if (i % 25 == 0 || i == paragraphs.length - 1)
                report("Extracting DOCX paragraphs", i + 1, paragraphs.length,
                    "Finding headings and preserving original text")
            val paragraph = paragraphs.item(i) as Element
            val text = StringBuilder().also { collect(paragraph, it) }.toString().trim()
            if (text.isBlank()) continue
            val style = paragraph.getElementsByTagNameNS("*", "pStyle").item(0) as? Element
            val heading = style?.getAttributeNS(
                "http://schemas.openxmlformats.org/wordprocessingml/2006/main", "val")
                ?.ifBlank { style.getAttribute("w:val") }.orEmpty()
            if (heading.startsWith("heading", true) || heading.equals("Title", true)) {
                flush()
                title = text.take(180)
            } else current.append(text).append("\n\n")
            require(chapters.size < 2000) { "Document has too many sections" }
        }
        flush()
        require(chapters.isNotEmpty()) { "DOCX has no readable paragraphs" }
        Imported(filename, chapters)
    }

    private fun extractPdf(
        context: Context, file: File, filename: String,
        report: (String, Int, Int, String) -> Unit
    ): Imported {
        report("Opening PDF", 0, 0, "Loading page structure")
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(
            file, MemoryUsageSetting.setupMixed(32L * 1024 * 1024)
                .setTempDir(context.cacheDir)
        ).use { pdf ->
            require(!pdf.isEncrypted) { "Password-protected PDF; unlock before importing" }
            require(pdf.numberOfPages <= 1500) { "PDF exceeds 1500 pages" }
            report("Reading PDF pages", 0, pdf.numberOfPages,
                "Found " + pdf.numberOfPages + " pages")
            val parts = mutableListOf<Chapter>()
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            var first = 1
            while (first <= pdf.numberOfPages) {
                val last = (first + 7).coerceAtMost(pdf.numberOfPages)
                stripper.startPage = first
                stripper.endPage = last
                val content = stripper.getText(pdf).replace(Regex("[ \\t]+"), " ")
                    .replace(Regex("\\n{3,}"), "\n\n").trim()
                if (content.isNotBlank()) parts.add(
                    Chapter(if (first == last) "Page $first" else "Pages $first–$last", content)
                )
                report("Extracting PDF text", last, pdf.numberOfPages,
                    "Processed page " + last + " of " + pdf.numberOfPages +
                        " • " + parts.size + " readable sections")
                first = last + 1
            }
            if (parts.isEmpty()) {
                // Bundled ML Kit model; no network download or server OCR required.
                require(pdf.numberOfPages <= 250) {
                    "This scanned PDF has " + pdf.numberOfPages +
                    " pages. Offline OCR is limited to 250 pages per import; split the PDF."
                }
                report("Starting offline OCR", 0, pdf.numberOfPages,
                    "No selectable text detected. Preparing image recognition…")
                val renderer = PDFRenderer(pdf)
                val recognition = TextRecognition.getClient(
                    TextRecognizerOptions.DEFAULT_OPTIONS)
                try {
                    var group = StringBuilder()
                    var groupStart = 1
                    for (pageIndex in 0 until pdf.numberOfPages) {
                        report("Recognizing scanned PDF pages", pageIndex + 1,
                            pdf.numberOfPages,
                            "OCR page " + (pageIndex + 1) + " of " + pdf.numberOfPages)
                        val image = renderer.renderImageWithDPI(pageIndex, 110f)
                        try {
                            val input = InputImage.fromBitmap(image, 0)
                            val text = Tasks.await(recognition.process(input)).text.trim()
                            if (text.isNotBlank()) group.append(text).append("\n\n")
                        } finally {
                            image.recycle()
                        }
                        if ((pageIndex + 1) % 6 == 0 || pageIndex == pdf.numberOfPages - 1) {
                            if (group.isNotBlank()) {
                                parts.add(Chapter(
                                    "OCR pages " + groupStart + "–" + (pageIndex + 1),
                                    group.toString().trim()))
                            }
                            group = StringBuilder()
                            groupStart = pageIndex + 2
                        }
                    }
                } catch (e: Exception) {
                    throw IllegalArgumentException(
                        "Offline OCR could not recognize the scanned PDF: " +
                            (e.message ?: "unknown error"), e)
                } finally {
                    recognition.close()
                }
            }
            require(parts.isNotEmpty()) {
                "No readable text was found in this PDF, even after offline OCR."
            }
            return Imported(filename, parts)
        }
    }
}
