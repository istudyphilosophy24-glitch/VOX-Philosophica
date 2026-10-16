# VOX PHILOSOPHICA · Android 0.1.0

**A native, offline-first Android audiobook reader and synthesis application.**

## Implementation status (read first)

This is an **implemented Android source project**, **not a verified APK**. The authoring environment lacks the Android SDK, Gradle runtime, Maven access, and model downloads, so no Android compilation or device testing was possible in this session. The build workflow is supplied, but its success is not represented as verified. The Kotlin text-chunking and WAV-format smoke tests were run successfully using the installed Kotlin compiler.

## What is implemented

- Native Android UI, dark reading theme, private book library and removal.
- Android Storage Access Framework imports EPUB, DOCX, HTML, TXT and Markdown.
- EPUB spine-based section ordering, DOCX paragraph extraction and plain text import.
- Section picker and scrollable original text viewer.
- English-language text chunking with no generative rewriting.
- Two TTS backends: Android's offline-installed system TTS voices and Sherpa-ONNX Kokoro int8, after installing the model.
- Explicit download of a Kokoro model from the upstream GitHub release; archive extraction takes place on-device. Approximately hundreds of MB of storage required.
- Per-book/per-speaker/per-speed generated WAV cache; repeated listening doesn't regenerate existing chunks.
- Android foreground playback, MediaSession, notification and headset controls for previous/pause/next/stop, background audio and sequential playback of EPUB sections.
- One-time synthesis and repeated listening without per-character cloud fees (local generation only).
- GitHub Actions configuration for debug APK compilation and artifact upload.

## Not yet implemented

- **No compiled APK verified** from this environment.
- No direct PDF text extraction (convert PDFs to EPUB/TXT outside the app for now).
- No Chatterbox model integration, voice cloning, expressiveness controls, paid cloud fallback or pronunciation dictionary yet.
- No precise word-level time alignment, export UI or speed-preserving time stretch. Speed is synthesis speed; cached WAV is regenerated when speed changes.
- No confirmed physical-device performance benchmarks or formal blind listening comparison with ElevenReader.

## Building a debug APK

**Prerequisites:** JDK 17, Android SDK Platform 35, Android Gradle Plugin 8.6.1 / Gradle 8.7, internet access to Maven Central, Google Maven and JitPack.

1. Open this directory in a recent Android Studio version, allow dependency resolution, run `:app:assembleDebug`.
2. APK path: `app/build/outputs/apk/debug/app-debug.apk`.
3. Alternatively, push the source project to a new GitHub repository; Actions -> **Build Android APK** -> Run workflow. Download artifact `VOX-PHILOSOPHICA-debug-apk`, unzip, and install the `app-debug.apk` inside.
4. Android 8.0+ supported (`minSdk 26`); Android 13+ asks for notification permission. On Android 14+, the foreground service uses the required media playback service type.

The workflow uses `gradle :app:assembleDebug` installed by `gradle/actions/setup-gradle`; there is deliberately no fake or broken Gradle-wrapper `.jar` in the archive.

## First use

1. Launch the app and import an EPUB, DOCX or plain-text book.
2. Open it and choose **Android offline voice**. You must have an English voice installed locally in your Android speech engine (Settings > Text-to-speech output); the app does **not** silently fall back to a network voice.
3. To use Kokoro, return to the library and choose **Install Kokoro** over Wi-Fi. This explicitly downloads the official English int8 model archive, extracts it to private app storage, and checks required files.
4. Reopen your book, choose **Kokoro local neural voice**, select a voice (speaker IDs 0–10) and speed, and press **Narrate**. Generated WAV audio will persist between sessions.

### Known device considerations

Kokoro synthesis is CPU-intensive; it may be slower than realtime and can require substantial RAM on budget hardware, even with 8-bit weights. On-device download/install requires storage much greater than the compressed file. Background audio playback works after chunk generation; producing the *next* uncached chunk can cause a pause. Pre-generation/prefetch and physical device testing are required before promising uninterrupted narration.

## Third-party model and license

This project references the Sherpa-ONNX Android AAR at `com.github.k2-fsa:sherpa-onnx:v1.13.8` from JitPack, and downloads `kokoro-int8-en-v0_19.tar.bz2` from the official upstream project. Their separate licenses apply. The large model weights, native binaries, copyrighted books, and other users' voices are **not** redistributed in this ZIP. Users must have rights to the documents they import.

Useful upstream references:
- https://github.com/k2-fsa/sherpa-onnx
- https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models
- https://k2-fsa.github.io/sherpa/onnx/android/build-sherpa-onnx.html
- https://github.com/hexgrad/kokoro

## Verification already completed

```shell
tests/run-local-smoke-tests.sh
# Passed chunking integrity tests for 5 cases.
# Passed WAV integrity test (1 s, 24 kHz, signed 16-bit mono).
```

Build and hardware validation remain essential before use as a production audiobook app.
