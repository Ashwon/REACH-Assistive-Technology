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
        ndk {
            // Package ARM ABIs for real phones (excludes x86/x86_64 emulator libs to keep APK size small).
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        // Backend URL is injected here, NOT an API key. Key lives only on the backend.
        // Override per build: ./gradlew assembleDebug -PbackendUrl=http://<mac-ip>:8000
        val backendUrl = (project.findProperty("backendUrl") as String?) ?: "http://192.168.1.5:8000"
        buildConfigField("String", "BACKEND_URL", "\"$backendUrl\"")
    }
    buildFeatures { buildConfig = true; viewBinding = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// Unit tests read the shared dataset spec (../dataset). The vision evaluator also takes optional inputs:
//   ./gradlew testDebugUnitTest --tests '*VisionEvalRun*' -PevalDataset=<dir> -PevalPredictions=<predictions.jsonl>
tasks.withType<Test>().configureEach {
    systemProperty("reach.dataset", rootProject.file("../dataset").absolutePath)
    systemProperty("reach.eval.dataset", (project.findProperty("evalDataset") as String?) ?: "")
    systemProperty("reach.eval.predictions", (project.findProperty("evalPredictions") as String?) ?: "")
    systemProperty("reach.eval.out", layout.buildDirectory.dir("reports/vision-eval").get().asFile.absolutePath)
    outputs.upToDateWhen { false } // evaluation inputs live outside the project; always rerun
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

    // V6: Offline silent wake-word & speech command recognition (no system notification beeps)
    implementation("com.alphacephei:vosk-android:0.3.47")

    // V7: High-accuracy YOLO11n ONNX object detection runtime
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.code.gson:gson:2.10.1") // reads COCO annotations / predictions in the evaluator
}
