plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "io.voxphilosophica"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.voxphilosophica"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
}
dependencies {
    implementation("com.github.k2-fsa:sherpa-onnx:v1.13.8") {
        exclude(group = "com.github.k2-fsa.sherpa-onnx", module = "sherpa-onnx-jvm")
    }
    implementation("org.apache.commons:commons-compress:1.26.2")
}
