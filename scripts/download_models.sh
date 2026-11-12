#!/usr/bin/env bash
# Fetches on-device vision and offline speech models into android/app/src/main/assets/.
set -euo pipefail

ASSETS="$(cd "$(dirname "$0")/.." && pwd)/android/app/src/main/assets"
mkdir -p "$ASSETS"

download() {
  local name="$1"
  local url="$2"
  local dest="$ASSETS/$name"
  if [ -s "$dest" ] && [ "${1:-}" != "--force" ]; then
    echo "[OK] $name already present"
    return
  fi
  echo "[GET] $name..."
  curl -fL "$url" -o "$dest.tmp"
  mv "$dest.tmp" "$dest"
}

download "yolo11n.onnx" \
  "https://huggingface.co/aaurelions/yolo11n.onnx/resolve/main/yolo11n.onnx"

download "efficientdet_lite2_int8.tflite" \
  "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite2/int8/latest/efficientdet_lite2.tflite"

download "hand_landmarker.task" \
  "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task"

download "mobilenet_v3_small.tflite" \
  "https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_small/float32/latest/mobilenet_v3_small.tflite"

if [ ! -s "$ASSETS/model-en-us/am/final.mdl" ]; then
  echo "[GET] vosk-model-small-en-us-0.15.zip..."
  TMP_ZIP="$ASSETS/vosk-model-small-en-us-0.15.zip"
  TMP_DIR="$ASSETS/_vosk_tmp"
  curl -fL "https://alphacephei.com/kaldi/models/vosk-model-small-en-us-0.15.zip" -o "$TMP_ZIP"
  rm -rf "$TMP_DIR" "$ASSETS/model-en-us"
  mkdir -p "$TMP_DIR"
  unzip -q "$TMP_ZIP" -d "$TMP_DIR"
  mv "$TMP_DIR"/vosk-model-small-en-us-* "$ASSETS/model-en-us"
  rm -rf "$TMP_DIR" "$TMP_ZIP"
fi

echo "Done. Assets ready in $ASSETS:"
ls -lh "$ASSETS"
