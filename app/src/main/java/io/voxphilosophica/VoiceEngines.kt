package io.voxphilosophica

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

interface SpeechEngine {
    fun synthesize(text: String, speaker: Int, speed: Float, output: File)
    fun close()
}

/** Standard Android engine: voice quality and offline voices depend on installed TTS service. */
class SystemSpeechEngine(context: Context) : SpeechEngine {
    private val initialized = CountDownLatch(1)
    private val result = AtomicReference<Int>(TextToSpeech.ERROR)
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) {
        result.set(it); initialized.countDown()
    }
    override fun synthesize(text: String, speaker: Int, speed: Float, output: File) {
        require(initialized.await(20, TimeUnit.SECONDS)) { "Android TTS initialization timed out" }
        require(result.get() == TextToSpeech.SUCCESS) { "No Android TTS engine available" }
        val candidate = tts.voices?.filter {
            !it.isNetworkConnectionRequired && it.locale.language == Locale.ENGLISH.language
        }?.sortedBy { it.name }
        val selected = candidate?.getOrNull(speaker.coerceIn(0, (candidate.size - 1).coerceAtLeast(0)))
        require(selected != null) { "No offline English TTS voice is installed. Download one in Android Text-to-Speech settings." }
        tts.voice = selected
        tts.setSpeechRate(speed)
        val done = CountDownLatch(1)
        val error = AtomicReference<String?>(null)
        val id = UUID.randomUUID().toString()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == id) done.countDown() }
            override fun onError(utteranceId: String?) {
                if (utteranceId == id) { error.set("System TTS failed"); done.countDown() }
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == id) { error.set("System TTS error $errorCode"); done.countDown() }
            }
        })
        require(tts.synthesizeToFile(text, null, output, id) == TextToSpeech.SUCCESS) { "Android TTS rejected text" }
        require(done.await(90, TimeUnit.SECONDS)) { "Android TTS generation timed out" }
        require(error.get() == null) { error.get() ?: "TTS error" }
        require(output.length() > 44) { "No audio generated" }
    }
    override fun close() { tts.stop(); tts.shutdown() }
}

/** Native local model. All data stays on device. Uses sherpa-onnx's official Android AAR. */
class KokoroSpeechEngine(context: Context) : SpeechEngine {
    private val engine: OfflineTts
    init {
        val dir = ModelInstaller(context).modelDir
        require(ModelInstaller(context).installed) { "Kokoro model not installed" }
        engine = OfflineTts(config = OfflineTtsConfig(model = OfflineTtsModelConfig(
            kokoro = OfflineTtsKokoroModelConfig(
                model = File(dir, "model.int8.onnx").absolutePath,
                voices = File(dir, "voices.bin").absolutePath,
                tokens = File(dir, "tokens.txt").absolutePath,
                dataDir = File(dir, "espeak-ng-data").absolutePath
            ), numThreads = 2, debug = false, provider = "cpu"
        )))
    }
    override fun synthesize(text: String, speaker: Int, speed: Float, output: File) {
        val numSpeakers = engine.numSpeakers().coerceAtLeast(1)
        val generated = engine.generate(text, sid = speaker.coerceIn(0, numSpeakers - 1), speed = speed)
        require(generated.samples.isNotEmpty()) { "Kokoro generated no audio" }
        WavWriter.save(output, generated.samples, generated.sampleRate)
    }
    override fun close() { engine.release() }
}

