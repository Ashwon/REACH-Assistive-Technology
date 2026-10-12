#!/usr/bin/env bash
# Batch-evaluate one or more detector models on a labelled dataset, on the phone, in one run.
#   tools/eval_on_device.sh dataset/seed                                   # current model
#   tools/eval_on_device.sh dataset/seed efficientdet_lite0.tflite models/efficientdet_lite2.tflite
#   LOOPS=10 tools/eval_on_device.sh dataset/seed                          # + sustained-latency loops
# Needs: a debug build installed (DebugFrames), adb with one device. Models that are not APK assets are pushed
# into the app's files dir, so candidates need no rebuild. Output: <dataset>/predictions/*.jsonl and
# android/app/build/reports/vision-eval/report.{md,json}.
set -euo pipefail
DS=$(cd "$1" && pwd); shift
MODELS=("${@:-efficientdet_lite0.tflite}")
LOOPS=${LOOPS:-1}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
PKG=com.reach.app
mkdir -p "$DS/predictions"

echo "pushing $(ls "$DS/images" | wc -l | tr -d ' ') images"
adb shell rm -rf /data/local/tmp/reach_eval && adb push "$DS/images" /data/local/tmp/reach_eval >/dev/null
adb shell chmod -R a+r /data/local/tmp/reach_eval
adb shell run-as $PKG sh -c "'rm -rf files/eval_images; mkdir -p files/eval_images; cp /data/local/tmp/reach_eval/* files/eval_images/'"
adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 3

PREDS=()
for m in "${MODELS[@]}"; do
  name=$(basename "$m")
  if [ -f "$m" ]; then # a candidate file, not an APK asset
    adb push "$m" /data/local/tmp/$name >/dev/null && adb shell chmod a+r /data/local/tmp/$name
    adb shell run-as $PKG cp /data/local/tmp/$name files/$name
  fi
  adb logcat -c
  adb shell am start -f 0x20000000 -n $PKG/.MainActivity --es replay eval_images --es model "$name" --ei loops "$LOOPS" >/dev/null
  echo -n "replaying with $name "
  until adb logcat -d -s REACH-REPLAY:* | grep -qE "replay (done|failed)"; do echo -n .; sleep 5; done; echo
  adb logcat -d -s REACH-REPLAY:* | grep -E "replay failed" && exit 1
  out="$DS/predictions/predictions_${name%.*}.jsonl"
  adb exec-out run-as $PKG cat "files/eval/predictions_${name%.*}.jsonl" > "$out"
  echo "  -> $out ($(tail -1 "$out"))"
  PREDS+=("$out")
done

(cd "$ROOT/android" && ./gradlew testDebugUnitTest --offline -q --tests '*VisionEvalRun*' \
  -PevalDataset="$DS" -PevalPredictions="$(IFS=,; echo "${PREDS[*]}")") >/dev/null
cat "$ROOT/android/app/build/reports/vision-eval/report.md"
