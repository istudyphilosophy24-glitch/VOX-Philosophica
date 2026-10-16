package io.voxphilosophica

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Write mono signed-16 PCM RIFF/WAV without needing audio-encoding libraries. */
object WavWriter {
    fun save(file: File, samples: FloatArray, sampleRate: Int) {
        require(samples.size < 100_000_000 && sampleRate in 8000..192000)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out ->
            val bytes = samples.size * 2
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII)); header.putInt(36 + bytes)
            header.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); header.putInt(16)
            header.putShort(1); header.putShort(1); header.putInt(sampleRate)
            header.putInt(sampleRate * 2); header.putShort(2); header.putShort(16)
            header.put("data".toByteArray(Charsets.US_ASCII)); header.putInt(bytes)
            out.write(header.array())
            val chunk = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in samples) {
                if (chunk.remaining() < 2) { out.write(chunk.array(), 0, chunk.position()); chunk.clear() }
                chunk.putShort((sample.coerceIn(-1.0f, 1.0f) * 32767f).toInt().toShort())
            }
            out.write(chunk.array(), 0, chunk.position())
        }
    }
}
