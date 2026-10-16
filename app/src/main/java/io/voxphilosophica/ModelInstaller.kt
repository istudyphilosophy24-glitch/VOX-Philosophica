package io.voxphilosophica

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Installs Kokoro exclusively from APK assets. No model network request is made.
 * Archives are unpacked once into private storage for ONNX Runtime.
 */
class ModelInstaller(private val context: Context) {
    companion object {
        const val ENGLISH = "v1_0"
        const val LATEST = "v1_1"
        private val lock = Any()

        fun assetName(which: String): String = when (which) {
            ENGLISH -> "kokoro-v1_0.tar.bz2"
            LATEST -> "kokoro-v1_1.tar.bz2"
            else -> error("Unrecognized Kokoro model")
        }
        fun folderName(which: String): String = when (which) {
            ENGLISH -> "kokoro-int8-multi-lang-v1_0"
            LATEST -> "kokoro-int8-multi-lang-v1_1"
            else -> error("Unrecognized Kokoro model")
        }
    }

    val modelDir: File get() = modelDir(ENGLISH)
    fun modelDir(which: String): File = File(context.filesDir, "models/" + folderName(which))
    val installed: Boolean get() = installed(ENGLISH)
    fun installed(which: String): Boolean {
        val dir = modelDir(which)
        return File(dir, "model.int8.onnx").length() > 40_000_000L &&
            File(dir, "voices.bin").length() > 500_000L &&
            File(dir, "tokens.txt").length() > 0L &&
            File(dir, "espeak-ng-data").isDirectory &&
            File(dir, "lexicon-us-en.txt").length() > 1000L
    }

    fun embedded(which: String): Boolean =
        context.assets.list("")?.contains(assetName(which)) == true

    fun install(onProgress: (String) -> Unit) = install(ENGLISH, onProgress)

    /** Blocking method: invoke on background thread, never on the main thread. */
    fun install(which: String, onProgress: (String) -> Unit) {
        synchronized(lock) {
            // The playback service runs in :tts; coordinate installations across processes.
            val lockFile = File(context.filesDir, "models/.install.lock")
            lockFile.parentFile?.mkdirs()
            java.io.RandomAccessFile(lockFile, "rw").channel.use { channel ->
            channel.lock().use {
            if (installed(which)) return
            val asset = assetName(which)
            require(embedded(which)) { "Integrated Kokoro model not found in APK" }
            val dest = modelDir(which)
            dest.parentFile?.mkdirs()
            val staging = File(dest.parentFile, dest.name + ".staging")
            staging.deleteRecursively()
            staging.mkdirs()
            try {
                onProgress("Preparing integrated Kokoro " + which + " · no internet needed…")
                var expanded = 0L
                var lastReported = 0L
                var entries = 0
                val stat = android.os.StatFs(context.filesDir.absolutePath)
                require(stat.availableBytes > 260L * 1024 * 1024) {
                    "Insufficient free storage to prepare Kokoro. Free at least 300 MB."
                }
                val root = staging.canonicalPath + File.separator
                BZip2CompressorInputStream(
                    BufferedInputStream(context.assets.open(asset), 128 * 1024)
                ).use { decompressed ->
                    TarArchiveInputStream(decompressed).use { tar ->
                        var entry = tar.nextTarEntry
                        val prefix = folderName(which) + "/"
                        while (entry != null) {
                            var normalized = entry.name.removePrefix("./")
                            if (normalized.startsWith(prefix)) {
                                normalized = normalized.removePrefix(prefix)
                                if (normalized.isNotBlank()) {
                                    val target = File(staging, normalized).canonicalFile
                                    require(target.path.startsWith(root)) { "Unsafe archive entry" }
                                    require(!entry.isSymbolicLink && !entry.isLink) { "Links forbidden" }
                                    if (entry.isDirectory) target.mkdirs()
                                    else if (entry.isFile) {
                                        require(entry.size in 0..(400L * 1024 * 1024)) {
                                            "Unexpected model file size"
                                        }
                                        expanded += entry.size
                                        entries++
                                        if (expanded - lastReported > 12L * 1024 * 1024) {
                                            onProgress("Unpacking Kokoro " + which + ": " +
                                                (expanded / 1048576) + " MB extracted…")
                                            lastReported = expanded
                                        }
                                        require(expanded < 750L * 1024 * 1024 && entries <= 4000) {
                                            "Kokoro archive exceeds unpacking limit"
                                        }
                                        target.parentFile?.mkdirs()
                                        FileOutputStream(target).buffered().use { output ->
                                            tar.copyTo(output)
                                        }
                                    }
                                }
                            }
                            entry = tar.nextTarEntry
                        }
                    }
                }
                require(File(staging, "model.int8.onnx").length() > 40_000_000L &&
                    File(staging, "voices.bin").length() > 500_000L &&
                    File(staging, "tokens.txt").length() > 0 &&
                    File(staging, "lexicon-us-en.txt").length() > 1000 &&
                    File(staging, "espeak-ng-data").isDirectory) {
                    "Built-in model archive is incomplete"
                }
                if (dest.exists()) dest.deleteRecursively()
                check(staging.renameTo(dest)) { "Unable to activate Kokoro model" }
                onProgress("Kokoro " + which + " ready. Voices are fully offline.")
            } finally {
                staging.deleteRecursively()
            }
            }
            }
        }
    }
}
