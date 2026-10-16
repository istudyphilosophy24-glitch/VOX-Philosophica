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

/**
 * INT8 Kokoro models bundled inside the APK. No external download is involved.
 * Only one native model is loaded into memory at a time.
 */
class KokoroSpeechEngine(
    context: Context,
    which: String = ModelInstaller.ENGLISH,
    voiceId: Int = 26
) : SpeechEngine {
    private val engine: OfflineTts

    init {
        val installer = ModelInstaller(context)
        require(installer.installed(which)) {
            "Bundled model was not prepared. Reopen the library and prepare integrated voices."
        }
        val dir = installer.modelDir(which)
        // Match the upstream Kokoro examples and the CI inference smoke test.
        // The British accent is selected by speaker ID, not by changing the main
        // phonemizer lexicon for a pretrained English voice.
        val englishLexicon = File(dir, "lexicon-us-en.txt")
        val chineseLexicon = File(dir, "lexicon-zh.txt")
        require(englishLexicon.isFile && chineseLexicon.isFile) {
            "Kokoro pronunciation dictionaries are incomplete"
        }
        val lexicons = listOf(englishLexicon, chineseLexicon)
            .filter { it.isFile }.joinToString(",") { it.absolutePath }
        engine = OfflineTts(
            config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = File(dir, "model.int8.onnx").absolutePath,
                        voices = File(dir, "voices.bin").absolutePath,
                        tokens = File(dir, "tokens.txt").absolutePath,
                        dataDir = File(dir, "espeak-ng-data").absolutePath,
                        lexicon = lexicons
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                )
            )
        )
    }

    override fun synthesize(text: String, speaker: Int, speed: Float, output: File) {
        val numSpeakers = engine.numSpeakers()
        require(numSpeakers > 0) { "Kokoro initialized without any available speakers" }
        val generated = engine.generate(
            NarrationDirector.forSpeech(text),
            sid = speaker.coerceIn(0, numSpeakers - 1),
            speed = speed
        )
        require(generated.samples.isNotEmpty() && generated.sampleRate > 8000) {
            "Kokoro produced no usable speech samples"
        }
        WavWriter.save(output, generated.samples, generated.sampleRate)
    }

    override fun close() { engine.release() }
}
