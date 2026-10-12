#!/usr/bin/env python3
"""Fine-tune a mobile object detector on the REACH categories with MediaPipe Model Maker. NOT RUN YET:
there is no labelled training data so far (dataset/README.md lists what is needed).

  python3 -m venv .venv-train && .venv-train/bin/pip install mediapipe-model-maker   # Linux/Colab; heavy (TensorFlow)
  python3 tools/split_dataset.py dataset/<name> build/split
  .venv-train/bin/python tools/train_detector.py build/split build/model --epochs 50

The export is a .tflite with label metadata that MediaPipe's ObjectDetector (the app's inference interface)
loads unchanged. It is only a CANDIDATE: run tools/eval_on_device.sh on the held-out test split with it and
with efficientdet_lite0.tflite, and replace the shipped model only if it passes the criteria in
dataset/README.md. The validation numbers printed here are not a real-world accuracy claim.
"""
import argparse, os

ap = argparse.ArgumentParser()
ap.add_argument('split_dir'); ap.add_argument('export_dir')
ap.add_argument('--model', default='MOBILENET_MULTI_AVG', help='a mediapipe_model_maker SupportedModels name')
ap.add_argument('--epochs', type=int, default=50); ap.add_argument('--batch', type=int, default=16)
ap.add_argument('--lr', type=float, default=0.3); ap.add_argument('--qat', action='store_true', help='int8 quantization-aware step')
a = ap.parse_args()

from mediapipe_model_maker import object_detector, quantization  # noqa: E402  (heavy import, after arg checks)

load = lambda s: object_detector.Dataset.from_coco_folder(os.path.join(a.split_dir, s), cache_dir=os.path.join(a.export_dir, 'cache', s))
train, val = load('train'), load('validation')
options = object_detector.ObjectDetectorOptions(
    supported_model=getattr(object_detector.SupportedModels, a.model),
    hparams=object_detector.HParams(export_dir=a.export_dir, epochs=a.epochs, batch_size=a.batch, learning_rate=a.lr))
model = object_detector.ObjectDetector.create(train_data=train, validation_data=val, options=options)
loss, metrics = model.evaluate(val, batch_size=a.batch)
print('validation loss', loss); print('validation COCO metrics', metrics)
if a.qat:
    model.quantization_aware_training(train, val, qat_hparams=object_detector.QATHParams(learning_rate=0.3, batch_size=a.batch, epochs=10))
    model.export_model('reach_detector_int8.tflite')
else:
    model.export_model('reach_detector.tflite',
                       quantization_config=quantization.QuantizationConfig.for_float16())
print('exported to', a.export_dir, '- now evaluate it on the TEST split with tools/eval_on_device.sh')
