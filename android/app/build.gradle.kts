plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.reach.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.reach.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        // Backend URL is injected here, NOT an API key. Key lives only on the backend.
        buildConfigField("String", "BACKEND_URL", "\"http://10.0.2.2:8000\"") // change to your laptop LAN IP
    }
    buildFeatures { buildConfig = true; viewBinding = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // V0: CameraX
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // V1 + V2: MediaPipe Tasks (hand landmarker + object detector)
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // V3: ARCore (isolated depth check)
    implementation("com.google.ar:core:1.44.0")

    // V5: backend HTTP
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
