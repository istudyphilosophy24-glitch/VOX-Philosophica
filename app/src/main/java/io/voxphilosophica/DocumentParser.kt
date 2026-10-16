package io.voxphilosophica

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Html
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.net.URLDecoder
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

object DocumentParser {
    data class Imported(val title: String, val chapters: List<Chapter>)
    fun import(context: Context, uri: Uri): Imported {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Untitled"
        val suffix = name.substringAfterLast('.', "").lowercase()
        val temp = File.createTempFile("vox-import-", ".file", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Unable to open document" }
                temp.outputStream().use { output ->
                    val buf = ByteArray(32_768)
                    var written = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        written += n
                        require(written <= 40L * 1024 * 1024) { "Document exceeds 40 MB limit" }
                        output.write(buf, 0, n)
                    }
                }
            }
            val chapters = when (suffix) {
                "txt", "md" -> listOf(Chapter("Complete text", temp.readText()))
                "html", "htm" -> listOf(Chapter("Complete text", htmlToText(temp.readText())))
                "docx" -> extractDocx(temp)
                "epub" -> extractEpub(temp)
                else -> error("Supported formats: EPUB, DOCX, TXT, MD, HTML. PDF text extraction is not yet supported.")
            }
            return Imported(name.substringBeforeLast('.').ifBlank { name }, chapters.filter { it.body.isNotBlank() })
        } finally { temp.delete() }
    }
    private fun readLimited(input: InputStream, limit: Int = 3_000_000): String = input.use { source ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16_384)
        while (true) {
            val n = source.read(buffer)
            if (n < 0) break
            require(output.size() + n <= limit) { "EPUB or DOCX section exceeds safety limit" }
            output.write(buffer, 0, n)
        }
        output.toString("UTF-8")
    }
    /**
     * Android's XML parser does not implement all Apache Xerces feature URIs.
     * Enforce the DTD/ENTITY prohibition ourselves and additionally disable external
     * resolution. Unsupported optional parser hardening flags must not break imports.
     */
    private fun secureXml(xml: String): org.w3c.dom.Document {
        require(!Regex("(?i)<!\\s*(DOCTYPE|ENTITY)\\b").containsMatchIn(xml)) {
            "XML documents containing DTD or entity declarations are not supported"
        }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { isXIncludeAware = false }
            for ((feature, enabled) in arrayOf(
                "http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
                "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false
            )) {
                runCatching { setFeature(feature, enabled) }
            }
        }
        return factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ ->
                throw org.xml.sax.SAXException("External XML entities are prohibited")
            }
        }.parse(InputSource(StringReader(xml)))
    }
    private fun htmlToText(content: String): String {
        // Remove script/style before HTML-to-text conversion.
        val cleaned = content.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), "")
        return Html.fromHtml(cleaned, Html.FROM_HTML_MODE_LEGACY).toString()
            .replace('\u00a0', ' ').replace(Regex("[ \t]+"), " ")
            .replace(Regex("\n{3,}"), "\n\n").trim()
    }
    private fun extractDocx(file: File): List<Chapter> = ZipFile(file).use { archive ->
        val xml = archive.getEntry("word/document.xml") ?: error("Not a valid DOCX file")
        val doc = secureXml(readLimited(archive.getInputStream(xml), 12_000_000))
        val paragraphs = doc.getElementsByTagNameNS("*", "p")
        val text = buildString {
            for (i in 0 until paragraphs.length) {
                val p = paragraphs.item(i) as Element
                val words = p.getElementsByTagNameNS("*", "t")
                for (j in 0 until words.length) append(words.item(j).textContent)
                append("\n")
            }
        }.trim()
        listOf(Chapter("Complete text", text))
    }
    private fun extractEpub(file: File): List<Chapter> = ZipFile(file).use { archive ->
        val container = archive.getEntry("META-INF/container.xml") ?: error("Not a valid EPUB")
        val doc = secureXml(readLimited(archive.getInputStream(container), 100_000))
        val root = doc.getElementsByTagNameNS("*", "rootfile").item(0) as? Element
            ?: error("EPUB rootfile missing")
        val opfPath = root.getAttribute("full-path")
        val opf = archive.getEntry(opfPath) ?: error("EPUB package missing")
        val packageDoc = secureXml(readLimited(archive.getInputStream(opf), 2_000_000))
        val base = opfPath.substringBeforeLast('/', "")
        val manifest = mutableMapOf<String, String>()
        val items = packageDoc.getElementsByTagNameNS("*", "item")
        for (i in 0 until items.length) {
            val el = items.item(i) as Element
            manifest[el.getAttribute("id")] = el.getAttribute("href").substringBefore('#')
        }
        val chapters = mutableListOf<Chapter>()
        val refs = packageDoc.getElementsByTagNameNS("*", "itemref")
        for (i in 0 until refs.length) {
            val ref = refs.item(i) as Element
            val href = manifest[ref.getAttribute("idref")] ?: continue
            val filepath = (if (base.isEmpty()) href else "$base/$href")
                .split('/').fold(mutableListOf<String>()) { acc, part ->
                    when (part) { ".", "" -> Unit; ".." -> if (acc.isNotEmpty()) acc.removeAt(acc.lastIndex) else Unit
                        else -> acc.add(URLDecoder.decode(part, "UTF-8")) }; acc
                }.joinToString("/")
            val entry = archive.getEntry(filepath) ?: continue
            val html = readLimited(archive.getInputStream(entry), 3_000_000)
            val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]*>"), "")?.trim()?.take(90)
                ?.takeIf { it.isNotBlank() } ?: "Section ${chapters.size + 1}"
            val text = htmlToText(html)
            if (text.isNotBlank()) chapters.add(Chapter(title, text))
            if (chapters.size > 5000) error("EPUB has too many sections")
        }
        if (chapters.isEmpty()) error("EPUB contains no readable text")
        chapters
    }
}
