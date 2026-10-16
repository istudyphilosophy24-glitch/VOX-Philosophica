package io.voxphilosophica

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

/** Downloads the official Sherpa-ONNX Kokoro package on explicit user request. */
class ModelInstaller(private val context: Context) {
    companion object {
        const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-en-v0_19.tar.bz2"
        const val MODEL_FOLDER = "kokoro-int8-en-v0_19"
    }
    val modelDir: File get() = File(context.filesDir, "models/$MODEL_FOLDER")
    val installed: Boolean get() = File(modelDir, "model.int8.onnx").length() > 20_000_000 &&
        File(modelDir, "voices.bin").length() > 500_000 &&
        File(modelDir, "tokens.txt").isFile &&
        File(modelDir, "espeak-ng-data").isDirectory

    /** Must be called on a background thread. */
    fun install(onProgress: (String) -> Unit) {
        if (installed) { onProgress("Kokoro is already installed"); return }
        val workDir = File(context.filesDir, "models").apply { mkdirs() }
        val archive = File(context.cacheDir, "kokoro-model-download.part")
        val destination = File(workDir, "$MODEL_FOLDER.partial")
        val oldFolder = modelDir
        destination.deleteRecursively()
        destination.mkdirs()
        try {
            onProgress("Downloading Kokoro. This may require hundreds of MB and several minutes…")
            val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 25_000; readTimeout = 35_000; instanceFollowRedirects = true
                setRequestProperty("User-Agent", "VOX-Philosophica/0.1")
            }
            try {
                connection.connect()
                require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
                val maxBytes = 550L * 1024 * 1024
                var bytes = 0L
                connection.inputStream.buffered().use { input ->
                    archive.outputStream().buffered().use { output ->
                        val buffer = ByteArray(65536)
                        var lastUpdate = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            bytes += n
                            require(bytes <= maxBytes) { "Model archive exceeds permitted size" }
                            output.write(buffer, 0, n)
                            if (bytes - lastUpdate > 10L * 1024 * 1024) {
                                onProgress("Downloaded ${bytes / (1024 * 1024)} MB…")
                                lastUpdate = bytes
                            }
                        }
                    }
                }
            } finally { connection.disconnect() }
            onProgress("Unpacking model securely…")
            TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)))).use { tar ->
                var entry = tar.nextTarEntry
                var expanded = 0L
                while (entry != null) {
                    val prefix = "$MODEL_FOLDER/"
                    if (entry.name.startsWith(prefix)) {
                        val relative = entry.name.removePrefix(prefix)
                        if (relative.isNotBlank()) {
                            val target = File(destination, relative).canonicalFile
                            require(target.path.startsWith(destination.canonicalPath + File.separator)) { "Invalid model archive path" }
                            if (entry.isDirectory) target.mkdirs()
                            else if (entry.isFile) {
                                require(entry.size in 0..(380L * 1024 * 1024)) { "Unexpected model file size" }
                                expanded += entry.size
                                require(expanded <= 700L * 1024 * 1024) { "Model package unexpectedly large" }
                                target.parentFile?.mkdirs()
                                target.outputStream().buffered().use { output -> tar.copyTo(output) }
                            }
                        }
                    }
                    entry = tar.nextTarEntry
                }
            }
            require(File(destination, "model.int8.onnx").length() > 20_000_000) { "Kokoro model missing" }
            require(File(destination, "voices.bin").length() > 500_000) { "Kokoro voices missing" }
            require(File(destination, "tokens.txt").isFile) { "Kokoro token file missing" }
            require(File(destination, "espeak-ng-data").isDirectory) { "Kokoro phonemizer missing" }
            oldFolder.deleteRecursively()
            require(destination.renameTo(oldFolder)) { "Unable to activate installed model" }
            onProgress("Kokoro installed. Ready for fully offline generation.")
        } catch (e: Exception) {
            destination.deleteRecursively()
            throw e
        } finally { archive.delete() }
    }
}
