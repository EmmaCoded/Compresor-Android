plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "app.compresor"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.compresor"
        minSdk = 21
        targetSdk = 35
        versionCode = 2
        versionName = "0.2"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    // One APK per phone type (32 and 64 bit), each with its own ffmpeg.
    splits { abi { isEnable = true; reset(); include("arm64-v8a", "armeabi-v7a"); if (project.hasProperty("emulator")) include("x86_64"); isUniversalApk = false } }
    // ffmpeg ships as libffmpeg.so: Android extracts it to the native library folder, from where it can be executed.
    packaging { jniLibs { useLegacyPackaging = true } }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    val bom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(bom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
