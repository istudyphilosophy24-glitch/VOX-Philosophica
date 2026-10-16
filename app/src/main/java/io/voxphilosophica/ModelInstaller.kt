package io.voxphilosophica

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Local Kokoro installation with resumable download and independent archive import. */
class ModelInstaller(private val context: Context) {
    companion object {
        const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-en-v0_19.tar.bz2"
        const val MODEL_FOLDER = "kokoro-int8-en-v0_19"
        private const val MAX_ARCHIVE = 550L * 1024 * 1024
        private const val MAX_UNPACKED = 900L * 1024 * 1024
    }

    val modelDir: File get() = File(context.filesDir, "models/$MODEL_FOLDER")
    private val downloadFile: File get() = File(context.filesDir, "models/.downloads/kokoro-int8-en-v0_19.tar.bz2.part")

    val installed: Boolean get() = validModel(modelDir)
    private fun validModel(dir: File): Boolean =
        File(dir, "model.int8.onnx").length() > 20_000_000 &&
        File(dir, "voices.bin").length() > 500_000 &&
        File(dir, "tokens.txt").length() > 0 &&
        File(dir, "espeak-ng-data").isDirectory

    /** Called on a worker thread. Resumes the partial archive on connection errors. */
    fun install(onProgress: (String) -> Unit) {
        if (installed) { onProgress("Kokoro is already installed."); return }
        val file = downloadFile
        file.parentFile?.mkdirs()
        try {
            downloadWithRetries(file, onProgress)
            unpack(file, onProgress)
            file.delete()
        } catch (e: Exception) {
            // Retain partially downloaded bytes so the next tap can resume.
            throw IOException("${e.message ?: "Model installation failed"}. Retry, or download the archive in a browser and choose Import Kokoro archive.", e)
        }
    }

    /** Alternative for phones/networks that interrupt large GitHub app downloads. */
    fun installFromStream(source: InputStream, onProgress: (String) -> Unit) {
        if (installed) { source.close(); onProgress("Kokoro is already installed."); return }
        val staging = File(context.filesDir, "models/.downloads/manual-kokoro.tar.bz2")
        staging.parentFile?.mkdirs()
        try {
            onProgress("Copying selected model archive…")
            var bytes = 0L
            source.use { input ->
                FileOutputStream(staging).buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        bytes += count
                        require(bytes <= MAX_ARCHIVE) { "Selected archive is larger than 550 MB" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            require(bytes > 1_000_000) { "Selected archive is too small" }
            unpack(staging, onProgress)
        } finally {
            staging.delete()
        }
    }

    private fun downloadWithRetries(file: File, onProgress: (String) -> Unit) {
        var lastFailure: Exception? = null
        for (attempt in 1..5) {
            try {
                val existing = file.length()
                require(existing <= MAX_ARCHIVE) { "Partial download exceeds 550 MB; clear app data to reset." }
                onProgress(if (existing > 0L) "Resuming at ${existing / 1048576} MB (attempt $attempt/5)…"
                    else "Downloading Kokoro (attempt $attempt/5)…")
                var connection: HttpURLConnection? = null
                try {
                    connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 30_000
                        readTimeout = 60_000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Android; VOX-Philosophica)")
                        setRequestProperty("Accept-Encoding", "identity")
                        if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
                    }
                    val code = connection.responseCode
                    if (code == 416 && existing > 0L) {
                        val length = connection.getHeaderField("Content-Range")?.substringAfter("*/")?.toLongOrNull()
                        if (length == existing) return
                        file.delete()
                        throw IOException("Server rejected partial download; restarting.")
                    }
                    require(code == 200 || code == 206) { "Model server returned HTTP $code" }
                    val append = code == 206 && existing > 0L
                    if (code == 206) {
                        val from = connection.getHeaderField("Content-Range")
                            ?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull()
                        require(from == existing) { "Server returned a mismatched partial response" }
                    }
                    val start = if (append) existing else 0L
                    val length = connection.contentLengthLong
                    var total = start
                    var reported = total
                    connection.inputStream.buffered().use { input ->
                        FileOutputStream(file, append).buffered().use { output ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                total += n
                                require(total <= MAX_ARCHIVE) { "Model package is larger than expected" }
                                output.write(buffer, 0, n)
                                if (total - reported >= 5L * 1024 * 1024) {
                                    onProgress("Downloaded ${total / 1048576} MB…")
                                    reported = total
                                }
                            }
                        }
                    }
                    if (length > 0 && total - start != length)
                        throw IOException("Connection ended before all model bytes arrived")
                    require(total > 1_000_000) { "Downloaded file is unexpectedly small" }
                    onProgress("Download complete (${total / 1048576} MB).")
                    return
                } finally {
                    connection?.disconnect()
                }
            } catch (e: Exception) {
                lastFailure = e
                if (attempt == 5) break
                onProgress("Connection interrupted (${e.message ?: "network error"}). Retrying…")
                Thread.sleep((attempt * 1500L).coerceAtMost(6000L))
            }
        }
        throw IOException("Could not finish the model download after five attempts: ${lastFailure?.message}", lastFailure)
    }

    private fun unpack(archive: File, onProgress: (String) -> Unit) {
        val directory = File(context.filesDir, "models").apply { mkdirs() }
        val staging = File(directory, "$MODEL_FOLDER.partial")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            onProgress("Unpacking and validating Kokoro…")
            var expanded = 0L
            var fileCount = 0
            TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)))).use { tar ->
                var entry = tar.nextTarEntry
                val root = staging.canonicalPath + File.separator
                while (entry != null) {
                    val relative = entry.name.removePrefix("./")
                    if (relative.startsWith("$MODEL_FOLDER/")) {
                        val path = relative.removePrefix("$MODEL_FOLDER/")
                        if (path.isNotBlank()) {
                            val output = File(staging, path).canonicalFile
                            require(output.path.startsWith(root)) { "Invalid model archive path" }
                            require(!entry.isSymbolicLink && !entry.isLink) { "Archive links are not allowed" }
                            if (entry.isDirectory) {
                                output.mkdirs()
                            } else if (entry.isFile) {
                                require(entry.size in 0..(400L * 1024 * 1024)) { "Model file too large" }
                                expanded += entry.size
                                fileCount++
                                require(expanded <= MAX_UNPACKED && fileCount <= 3000) { "Model archive exceeds limits" }
                                output.parentFile?.mkdirs()
                                FileOutputStream(output).buffered().use { dest -> tar.copyTo(dest) }
                            }
                        }
                    }
                    entry = tar.nextTarEntry
                }
            }
            require(validModel(staging)) { "Archive is incomplete or is not the English Kokoro INT8 model" }
            val live = modelDir
            // Preserve a verified existing installation rather than replacing it with bad files.
            if (live.exists()) {
                val backup = File(directory, "$MODEL_FOLDER.backup")
                backup.deleteRecursively()
                require(live.renameTo(backup)) { "Unable to prepare model replacement" }
                if (!staging.renameTo(live)) {
                    backup.renameTo(live)
                    throw IOException("Unable to activate model")
                }
                backup.deleteRecursively()
            } else {
                require(staging.renameTo(live)) { "Unable to activate model" }
            }
            onProgress("Kokoro installed — you can narrate offline.")
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
    }
}
