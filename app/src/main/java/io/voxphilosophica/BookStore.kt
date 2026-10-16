package io.voxphilosophica

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

// Chapter texts live in private app storage. No private books are uploaded.
data class Chapter(val title: String, val body: String)
data class Book(val id: String, val title: String, val chapters: List<Chapter>, val importedAt: Long) {
    val wordCount: Int get() = chapters.sumOf { it.body.split(Regex("\\s+")).count { w -> w.isNotEmpty() } }
}
class BookStore(private val context: Context) {
    private val dir = File(context.filesDir, "library").apply { mkdirs() }
    fun all(): List<Book> = dir.listFiles { file -> file.extension == "json" }
        ?.mapNotNull { runCatching { load(it.nameWithoutExtension) }.getOrNull() }
        ?.sortedByDescending { it.importedAt } ?: emptyList()
    fun save(title: String, chapters: List<Chapter>): Book {
        val cleanChapters = chapters.filter { it.body.isNotBlank() }
        require(cleanChapters.isNotEmpty()) { "No readable text found in this document" }
        val book = Book(java.util.UUID.randomUUID().toString(), title.take(140), cleanChapters, System.currentTimeMillis())
        val root = JSONObject().put("id", book.id).put("title", book.title).put("importedAt", book.importedAt)
        val parts = JSONArray()
        book.chapters.forEach { parts.put(JSONObject().put("title", it.title).put("body", it.body)) }
        root.put("chapters", parts)
        File(dir, "${book.id}.json").writeText(root.toString())
        return book
    }
    fun load(id: String): Book {
        require(Regex("[a-f0-9-]{36}").matches(id)) { "Invalid book identifier" }
        val root = JSONObject(File(dir, "$id.json").readText())
        val chapters = root.getJSONArray("chapters")
        return Book(id, root.getString("title"), (0 until chapters.length()).map {
            val obj = chapters.getJSONObject(it)
            Chapter(obj.getString("title"), obj.getString("body"))
        }, root.getLong("importedAt"))
    }
    fun delete(id: String) {
        require(Regex("[a-f0-9-]{36}").matches(id))
        File(dir, "$id.json").delete()
        File(context.filesDir, "audio/$id").deleteRecursively()
    }
}
fun sha256(str: String): String = MessageDigest.getInstance("SHA-256")
    .digest(str.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
