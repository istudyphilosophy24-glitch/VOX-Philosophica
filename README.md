# VOX PHILOSOPHICA 0.3.0 · Integrated Kokoro Edition

A native Android audiobook reader with **two Kokoro neural text-to-speech models embedded in one APK**. Designed for offline philosophical and academic reading.

## Audio models included at build time

- **Kokoro INT8 v1.0**: 53 total speakers, of which 28 speak English. Includes four British male narrator choices. Default: British George (speaker 26).
- **Kokoro INT8 v1.1-zh**: 103 speakers; 3 English voices and 100 Chinese voices. This newer variant does **not** replace the richer English voice inventory of v1.0.
- Both archives are fetched by GitHub Actions directly from Sherpa-ONNX upstream releases, verified against pinned SHA-256 digests, then packaged unmodified into `assets/`.
- App unpacks the archives once to private storage at first run. No model download, internet connection, API account, or per-character charges are needed on the phone.

## What is optimized

- Quantized INT8 model files reduce model storage and CPU inference demands versus the FP32 variants.
- Arm64-native APK target for modern 64-bit Android phones.
- Accent-aware Kokoro pronunciation lexicon selection (British/US when provided in the archive).
- Source-preserving punctuation normalization for narration, sentence/chapter chunking, and per-voice WAV caching.
- Native foreground playback with headset/notification controls.
- Earlier Android EPUB/DOCX import XML compatibility fix remains included.

**Neural network weights have NOT been fine-tuned.** Improved voice choices, quantized deployment, lexicon selection, and narration processing are engineering improvements, not evidence of retraining or demonstrably superior subjective listening quality. Training would require a separate licensed speech corpus, GPU resources, evaluation and model conversion pipeline.

## Build and verify

In GitHub Actions run **Build Integrated Kokoro APK** on the main branch. The pipeline:

1. Installs Android SDK and Gradle
2. Downloads and SHA-256-verifies the two official model archives
3. Compiles the Android debug APK and checks both are embedded
4. Publishes artifact `VOX-PHILOSOPHICA-0.3.0-INTEGRATED-ARM64`

A standalone APK extracted from that artifact contains all data necessary for offline inference. The binary is large because the model weights are embedded; first use needs additional storage to unpack both models. The APK is targeted at **arm64-v8a** Android devices and is not a universal 32-bit release.

## Physical-device checklist

After sideloading, **test on the target device**: import a licensed EPUB; wait for Kokoro model preparation; open the reader; select *Kokoro Studio* and British George; start narration; switch to *Kokoro Latest* and verify English voice playback; lock screen and test media controls; play the same passage twice to verify WAV caching.

GitHub successful compilation and APK asset validation alone **do not prove** that both neural engines work continuously or at real-time speed on a specific phone.

## Data and licensing

Book texts and cached narration remain on-device. Models are sourced from the official [sherpa-onnx model releases](https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models); their upstream licenses and embedded component licenses apply, including eSpeak NG phonemization resources. This repository contains application source, not third-party copyrighted books.
