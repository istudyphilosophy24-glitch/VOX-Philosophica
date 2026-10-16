# Building VOX PHILOSOPHICA for Android

The native Kotlin project lives at the repository root. To compile the debug APK:

1. Open **Actions** and choose **Build Android APK**.
2. Choose **Run workflow** (or inspect the latest build triggered by a push).
3. Once a build succeeds, download the artifact named `VOX-PHILOSOPHICA-debug-apk`.
4. Extract its ZIP and install `app-debug.apk` on your Android device.

The Gradle build requires JDK 17, Android SDK 35, Android Gradle Plugin 8.6.1, Gradle 8.7, Google Maven, Maven Central and JitPack. Kokoro voice model files are downloaded separately by the Android app.

**Development status:** The Kotlin text chunking and WAV file smoke tests passed locally; compilation and physical-device audio have not yet been validated. Do not treat a queued or failed CI run as a working APK.

The original project specification and limitations are in [README.md](README.md).
