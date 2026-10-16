plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "io.voxphilosophica"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.voxphilosophica"
        minSdk = 26
        targetSdk = 34
        ndk { abiFilters += listOf("arm64-v8a") }
        versionCode = 6
        versionName = "0.5.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += listOf("bz2") }
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    implementation("com.github.k2-fsa:sherpa-onnx:v1.13.8") {
        exclude(group = "com.github.k2-fsa.sherpa-onnx", module = "sherpa-onnx-jvm")
    }
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
