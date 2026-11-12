# REACH — Complete Setup Guide (Humans & AI Agents)

This guide covers end-to-end setup on a fresh computer (**Windows**, **macOS**, or **Linux**) and Android device—including offline vision/voice models, wireless ADB debugging, BLE haptic band pairing, and optional FastAPI backend configuration.

---

## 1. Prerequisites

| Component | Version / Requirement | Notes |
|---|---|---|
| **Java JDK** | **JDK 17** | Required by Android Gradle Plugin 8.5.2 & Gradle 8.7 |
| **Android SDK** | Platform `android-34`, Build-Tools `34.0.0`, `platform-tools` (`adb`) | `compileSdk = 34`, `minSdk = 26` |
| **Android Device** | Android 8.0+ (API 26+), ARCore-compatible optional | Xiaomi/Samsung/Pixel tested (`arm64-v8a` & `armeabi-v7a`) |
| **Python** *(optional)* | Python 3.10+ | Only needed for `backend/` cloud verification or `tools/` scripts |

### Quick Windows Toolchain Install (Scoop)
```powershell
scoop install openjdk17 android-clt
sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"
```

Set environment variables in PowerShell (or User Environment Variables):
```powershell
$env:JAVA_HOME = "$env:USERPROFILE\scoop\apps\openjdk17\current"
$env:ANDROID_HOME = "$env:USERPROFILE\scoop\apps\android-clt\current"
```

---

## 2. Clone & Fetch Models

```bash
git clone <YOUR_REPO_URL> Reach
cd Reach
```

All required models are tracked cleanly (each `< 24 MB`), or you can verify / re-download them anytime:

- **Windows (PowerShell)**:
  ```powershell
  .\scripts\download_models.ps1
  ```
- **macOS / Linux (bash)**:
  ```bash
  ./scripts/download_models.sh
  ```

Expected contents inside `android/app/src/main/assets/`:
1. `yolo11n.onnx` (~10.7 MB) — Primary ONNX Runtime COCO-80 object detector
2. `efficientdet_lite2_int8.tflite` (~7.5 MB) — MediaPipe fallback detector
3. `hand_landmarker.task` (~7.8 MB) — MediaPipe 21-point hand landmark tracker
4. `mobilenet_v3_small.tflite` (~4.1 MB) — Registered-object identity embedder
5. `model-en-us/` — Offline Vosk small US English speech & wake-word model (`vosk-model-small-en-us-0.15`)

---

## 3. Build & Run Unit Tests

The Android app works **100% offline** with `-PbackendUrl=http://127.0.0.1:8000` (local fallback):

```powershell
cd android
.\gradlew.bat testDebugUnitTest assembleDebug -PbackendUrl=http://127.0.0.1:8000
```

*(Replace `.\gradlew.bat` with `./gradlew` on macOS/Linux.)*

Output APK:
`android/app/build/outputs/apk/debug/app-debug.apk`

---

## 4. Deploy to an Android Phone

### Option A: USB Debugging
1. Phone **Settings -> About phone -> tap Build number 7 times** to unlock Developer Options.
2. Enable **USB debugging** (and on Xiaomi/MIUI/HyperOS, enable **USB debugging (Security settings)** & **Install via USB**).
3. Connect USB and install:
   ```bash
   adb devices
   adb install -r android/app/build/outputs/apk/debug/app-debug.apk
   ```

### Option B: Wireless Debugging (Android 11+)
1. Ensure phone and PC are on the **same Wi-Fi network**.
2. On phone: **Settings -> Developer options -> Wireless debugging** -> turn ON.
3. Tap **Pair device with pairing code** to note `<IP>:<PAIR_PORT>` and the 6-digit `<PAIR_CODE>`, plus check the main Wireless debugging screen for the connect `<IP>:<CONNECT_PORT>`.
4. **On Windows**, run `wireless-debug.ps1`:
   ```powershell
   .\wireless-debug.ps1 -PairIpPort "192.168.1.7:38255" -PairCode "719904" -IpPort "192.168.1.7:5555" -InstallApk
   ```
5. **On macOS / Linux**:
   ```bash
   adb pair 192.168.1.7:38255 719904
   adb connect 192.168.1.7:5555
   adb install -r android/app/build/outputs/apk/debug/app-debug.apk
   ```

> Tip: Running `adb tcpip 5555` once while plugged via USB keeps the connect port fixed at `5555` across Wi-Fi reconnects until reboot.

---

## 5. First-Launch Usage & Voice Commands

Grant **Camera** and **Microphone** (plus **Nearby devices / Bluetooth** if using the BLE wristband) when prompted.

- **Hands-free wake word**: Say **`"reach"`** at any time (offline Vosk listens continuously with zero notification chimes).
- **Find an object**: Say **`"find the bottle"`**, **`"find the laptop"`**, **`"find my water bottle"`**, or **`"help me find the laptop"`**.
  - If the object is not in view yet, REACH waits up to 60s and prompts you to pan slowly.
- **Start guidance**: Say **`"guide me"`** or **`"yes"`** (or tap **Start Guidance**).
  - You can use either hand or keep both hands visible; REACH locks smoothly onto your active hand.
  - When lined up, REACH says **"Lined up. Reach forward slowly, and say got it when you reach it."**
- **Confirm reached**: Say **`"got it"`**, **`"reached"`**, or **`"I got it"`** (or tap **Reached**).
- **Cancel / Describe**: Say **`"stop"`**, **`"cancel"`**, or **`"what do you see"`**.

---

## 6. Optional Modules

### BLE Haptic Wristband (`firmware/reach_haptic_band/`)
- Flash `firmware/reach_haptic_band/reach_haptic_band.ino` to your ESP32 / Arduino BLE board with two vibration motors.
- In REACH, tap **Band Setup** (or connect during onboarding) to verify Left/Right motor direction pulses.
- If no wristband is connected, REACH automatically delivers gentle vibration pulses through the phone's built-in vibrator plus full voice guidance.

### Optional Cloud Backend (`backend/`)
Only needed if you want Claude API backup labeling when offline confidence is low:
```bash
cd backend
python -m venv .venv
source .venv/bin/activate   # or .\.venv\Scripts\Activate.ps1 on Windows
pip install -r requirements.txt
cp .env.example .env        # set ANTHROPIC_API_KEY
uvicorn server:app --host 0.0.0.0 --port 8000
```
Then build Android pointing `-PbackendUrl=http://<YOUR_PC_LAN_IP>:8000`.
