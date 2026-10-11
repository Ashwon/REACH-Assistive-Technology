#!/usr/bin/env bash
# Downloads the two MediaPipe model files into the Android assets folder.
set -e
ASSETS="$(dirname "$0")/../android/app/src/main/assets"
mkdir -p "$ASSETS"

echo "Downloading hand_landmarker.task ..."
curl -L -o "$ASSETS/hand_landmarker.task" \
  "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task"

echo "Downloading efficientdet_lite0.tflite ..."
curl -L -o "$ASSETS/efficientdet_lite0.tflite" \
  "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float16/latest/efficientdet_lite0.tflite"

ls -la "$ASSETS"
echo "Done. Rebuild the app."
