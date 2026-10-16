package io.voxphilosophica

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class Chapter(val title: String, val body: String)
data class Book(
    val id: String, val title: String, val chapters: List<Chapter>,
    val importedAt: Long, val author: String = ""
) {
    val wordCount: Int get() = chapters.sumOf { ch ->
        ch.body.split(Regex("\\s+")).count { it.isNotBlank() }
    }
}
class BookStore(private val context: Context) {
    private val dir = File(context.filesDir, "library").apply { mkdirs() }
    private val coverDir = File(context.filesDir, "covers").apply { mkdirs() }
    fun all(): List<Book> = dir.listFiles { file -> file.extension == "json" }
        ?.mapNotNull { runCatching { load(it.nameWithoutExtension) }.getOrNull() }
        ?.sortedByDescending { it.importedAt } ?: emptyList()

    fun cover(id: String): File? =
        File(coverDir, "$id.jpg").takeIf { it.isFile && it.length() > 100L }

    fun save(title: String, chapters: List<Chapter>, author: String = "", cover: ByteArray? = null): Book {
        val clean = chapters.filter { it.body.isNotBlank() }
        require(clean.isNotEmpty()) { "The document contains no extractable text" }
        val id = java.util.UUID.randomUUID().toString()
        val book = Book(id, title.take(180), clean, System.currentTimeMillis(), author.take(160))
        val metadata = JSONObject().put("id", id).put("title", book.title)
            .put("author", book.author).put("importedAt", book.importedAt)
        val parts = JSONArray()
        clean.forEach { parts.put(JSONObject().put("title", it.title).put("body", it.body)) }
        metadata.put("chapters", parts)
        // A huge source image should not be decoded at its original resolution.
        if (cover != null && cover.size in 256..3_000_000) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(cover, 0, cover.size, bounds)
                if (bounds.outWidth in 1..10000 && bounds.outHeight in 1..10000) {
                    var sample = 1
                    while (bounds.outWidth / sample > 640 || bounds.outHeight / sample > 900) sample *= 2
                    val bmp = BitmapFactory.decodeByteArray(cover, 0, cover.size,
                        BitmapFactory.Options().apply { inSampleSize = sample })
                    if (bmp != null) {
                        File(coverDir, "$id.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 84, it) }
                        bmp.recycle()
                    }
                }
            }
        }
        File(dir, "$id.json").writeText(metadata.toString())
        return book
    }
    fun load(id: String): Book {
        require(Regex("[a-f0-9-]{36}").matches(id)) { "Invalid book identifier" }
        val root = JSONObject(File(dir, "$id.json").readText())
        val chapters = root.getJSONArray("chapters")
        return Book(id, root.getString("title"),
            (0 until chapters.length()).map {
                val obj = chapters.getJSONObject(it)
                Chapter(obj.getString("title"), obj.getString("body"))
            }, root.getLong("importedAt"), root.optString("author", ""))
    }
    fun delete(id: String) {
        require(Regex("[a-f0-9-]{36}").matches(id))
        File(dir, "$id.json").delete()
        File(coverDir, "$id.jpg").delete()
        File(context.filesDir, "audio/$id").deleteRecursively()
    }
}
fun sha256(str: String): String = MessageDigest.getInstance("SHA-256")
    .digest(str.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
