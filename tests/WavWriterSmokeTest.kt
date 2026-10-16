import io.voxphilosophica.WavWriter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

fun main() {
    val file = File.createTempFile("vox-audio-", ".wav")
    try {
        val samples = FloatArray(24000) { (it % 1200) / 1200f * 2 - 1 }
        WavWriter.save(file, samples, 24000)
        val bytes = file.readBytes()
        check(bytes.size == 44 + samples.size * 2)
        check(String(bytes, 0, 4) == "RIFF")
        check(String(bytes, 8, 4) == "WAVE")
        check(String(bytes, 36, 4) == "data")
        check(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(24) == 24000)
        check(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(40) == 48000)
        println("Passed WAV integrity test (1 s, 24 kHz, signed 16-bit mono).")
    } finally { file.delete() }
}
