# AGENTS.md — AI Assistant Instructions for REACH

This file gives coding agents (Antigravity, Claude Code, Cursor, Codex, Gemini CLI) the exact architecture, build commands, device-deployment workflow, and invariants for **REACH** so you can set it up and iterate safely on a new machine or Android phone.

## 1. Repository Layout

- `android/` — Android (Kotlin, `compileSdk = 34`, `minSdk = 26`, Gradle 8.7) app:
  - `app/src/main/java/com/reach/app/grounding/`:
    - `Yolo11Grounder.kt` — primary on-device detector running `yolo11n.onnx` via `onnxruntime-android:1.20.0` (`createObjectGrounder(context)` falls back cleanly to `LocalDetectorGrounder`).
    - `GrounderFilters.kt` — pure-Kotlin anchor + post-NMS false-positive suppression (prevents rectangular objects such as books, monitors/TVs, cell phones, standalone keyboards, and desks from being mislabeled as `laptop`).
    - `ObjectGrounder.kt` — `ObjectGrounder` interface, `Candidate`, `LocalDetectorGrounder` (`efficientdet_lite2_int8.tflite` MediaPipe fallback), `ColorClassifier`, `lookOf`, `safeCrop`.
    - `Detection.kt` — `SupportedClasses.DETECTED` (`bottle`, `laptop`), `Box`, `CandidateTracker`, `TargetLock`, `DetectorGate`, `RecentCandidates`.
  - `app/src/main/java/com/reach/app/hand/` — MediaPipe `hand_landmarker.task` (21-point landmarks) + `HandSelector` (single-hand or two-hand auto-enrollment & identity continuity).
  - `app/src/main/java/com/reach/app/spatial/` — `SpatialEngine`, hysteresis, `TurnMonitor`, `DepthChecker`, and `GuidanceThrottle`.
  - `app/src/main/java/com/reach/app/speech/` — `VoiceInput` (continuous offline `vosk-android:0.3.47` via `AudioRecord` so Android speech chimes never fire) + `Speaker` (`TextToSpeech` with `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`).
  - `app/src/main/java/com/reach/app/haptic/` — BLE GATT controller (`BleBand`, `BandSession`, `BandSetup`, `HapticLanguage`, `HapticPlayer`) plus on-phone `VibrationEffect` fallback when no wristband is paired.
  - `app/src/main/java/com/reach/app/ReachCore.kt` & `ReachController.kt` — deterministic state machine, camera analyzer routing, wake-word / voice-confirmation handling (`"reach"`, `"find the bottle"`, `"got it"`, `"stop"`).
- `android/app/src/main/assets/` — bundled offline models (`yolo11n.onnx`, `efficientdet_lite2_int8.tflite`, `hand_landmarker.task`, `mobilenet_v3_small.tflite`, `model-en-us/`).
- `scripts/download_models.ps1` & `scripts/download_models.sh` — idempotent scripts to download or refresh all vision and Vosk assets.
- `wireless-debug.ps1` — Windows helper to pair/connect Android 11+ Wireless Debugging and `pm install` the debug APK.
- `backend/` — optional FastAPI server (`server.py`) for cloud-assisted scene/label verification (`ANTHROPIC_API_KEY`). Offline mode works 100% without it using `-PbackendUrl=http://127.0.0.1:8000`.
- `firmware/reach_haptic_band/` — Arduino/ESP32 BLE dual-motor wristband sketch.
- `dataset/` & `tools/` — COCO category spec (`dataset/categories.json`) and evaluation scripts.

## 2. Exact Commands (Run Instead of Guessing)

### Environment Variables (Windows example)
Set `JAVA_HOME` (JDK 17) and `ANDROID_HOME` (Android SDK Platform 34 + Build-Tools 34.0.0 + Platform-Tools):
```powershell
$env:JAVA_HOME = "$env:USERPROFILE\scoop\apps\openjdk17\current"
$env:ANDROID_HOME = "$env:USERPROFILE\scoop\apps\android-clt\current"
```

### Step 1: Verify / Download On-Device Models
- Windows: `.\scripts\download_models.ps1`
- macOS/Linux: `./scripts/download_models.sh`

### Step 2: Run All Unit Tests & Build Debug APK
```powershell
cd android
.\gradlew.bat testDebugUnitTest assembleDebug -PbackendUrl=http://127.0.0.1:8000
```
*(Use `./gradlew` on macOS/Linux.)*

### Step 3: Deploy to Phone (USB or Wireless ADB)
- **USB**:
  ```powershell
  adb install -r android/app/build/outputs/apk/debug/app-debug.apk
  ```
- **Windows Wireless Debugging (`wireless-debug.ps1`)**:
  ```powershell
  # First-time pairing (only needed once per phone-PC pair, pass pairing IP:PORT + 6-digit code):
  .\wireless-debug.ps1 -PairIpPort "<PHONE_IP>:<PAIR_PORT>" -PairCode "<6_DIGIT_CODE>" -IpPort "<PHONE_IP>:<DEBUG_PORT>" -InstallApk
  # Subsequent connects & installs:
  .\wireless-debug.ps1 -IpPort "<PHONE_IP>:5555" -InstallApk
  ```
  > Note on Xiaomi/MIUI/HyperOS: If `adb install -r` fails with `INSTALL_FAILED_USER_RESTRICTED`, either enable **Install via USB** + **USB debugging (Security settings)** or use `adb push` + `pm install -r` (which `wireless-debug.ps1` already automates).

### Step 4: Live Verification via Logcat
Always use `adb logcat -d -s REACH:I` (non-blocking dump) and keep `adb connect` in the same shell invocation when adb runs statelessly:
```powershell
adb logcat -c
adb shell am start -n com.reach.app/.MainActivity
Start-Sleep -Seconds 4
adb logcat -d -s REACH:I
```
Confirm `Yolo11Grounder ready model=yolo11n.onnx` and inspect `groundMs=` latency.

## 3. Non-Negotiable Code Invariants

1. **Supported classes (`SupportedClasses.DETECTED`)**: Only `"bottle"` and `"laptop"` (`dataset/categories.json` must match; checked by `CategorySpecTest`). Never blindly remap `"tv"` or `"keyboard"` to `"laptop"`.
2. **Never auto-reach from 2D overlap**: `SpatialEngine(allow2dReach = false)` and `depthReachedValidated = false` until a physical sensor validates depth. Reach completion relies on explicit confirmation (`"got it"`, `"reached"`, etc., `confirmReached()`).
3. **Always silent offline speech recognition**: Keep `VoiceInput` using Vosk `AudioRecord` rather than Android's system `SpeechRecognizer` intent, which triggers disruptive chime beeps.
4. **Haptics compatibility**: Any guidance changes must keep both BLE wristband motor commands (`HapticCue`) and phone fallback vibration working cleanly.
5. **Run unit tests before every commit/push**: `testDebugUnitTest` includes `GrounderFiltersTest`, `GuidanceThrottleNavTest`, `SpatialEngineTest`, `ReachCoreTest`, `DetectionTest`, `CategorySpecTest`, `VisionEvalTest`, `ConversationTest`, `TutorialTest`, and `ReachStatusTest`.
