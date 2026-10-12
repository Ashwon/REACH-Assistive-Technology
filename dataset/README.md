# REACH dataset, evaluation and training

One schema, one annotation workflow, one evaluation harness and one training pipeline for **all** supported
categories. Nothing here is per-object code: adding a category means adding it to `categories.json` and
labelling data for it.

## Supported categories (`categories.json`)

`categories.json` is the single list. The app (`SupportedClasses`), the evaluator and the training split all
read or are tested against it (`CategorySpecTest`). `model_labels` says which labels of the **current** detector
(EfficientDet-Lite0, COCO, embedded `labels.txt` verified byte-for-byte) count as each category.

| category | current model | evidence so far |
|---|---|---|
| bottle (incl. water bottles) | native COCO `bottle` | no labelled bottle frames yet |
| laptop | native COCO `laptop` | A15 seed: a black laptop lid seen from behind missed in 30/30 frames (model called it `backpack` 0.22–0.30) |
| **laptop bag** | **none**: no COCO class | needs custom training; the app answers "I can't recognise laptop bags yet" |
| backpack, handbag | native COCO | no labelled bag frames yet; COCO detectors are known to be weak on bags |
| cup, wine glass, bowl, vase, cell phone, remote, mouse, keyboard, book, scissors, clock, banana, apple, orange, teddy bear, chair, person | native COCO | seed: phone on the floor missed 30/30; person lying down called `bed` in 16/30 frames |

`user hand` / `other hand` (ids 101/102) are for hand-identity evaluation only, never detector training.

## Layout of one dataset (`dataset/<name>/`)

```
images/            frames, 480x640 upright (what the app's models see); JPEG q95
annotations.json   COCO: images[], annotations[] (bbox = [x, y, w, h] in pixels, category_id from categories.json,
                   optional "track_id" on the annotation or in its "attributes": same id = same physical object
                   across a sequence), categories[]
images.csv         one row per image:
                   file_name, session, sequence, frame_ms, lighting (bright|normal|dim|dark), synthetic (0|1),
                   labeled (all | cat|cat|...: categories annotated EXHAUSTIVELY in this image; detections of other
                   categories there are ignored, neither credited nor counted as false positives),
                   target (category the user asks for in this sequence, optional), reach_ms (when the user
                   touches it, optional; enables the reached/speech check)
predictions/       written by tools/eval_on_device.sh, one predictions_<model>.jsonl per model
```

`session` = one recording session (place + time). Splits are made **by session** (`tools/split_dataset.py`):
consecutive frames are near-duplicates, so a frame-level split would leak test data into training.

## Workflow

1. **Record** on the A15 (debug build): `python3 -I tools/capture_session.py dataset/<name> --session kitchen-dim-1 --frames 60 --lighting dim`
   Frames are the app's own analysis frames. Record short sequences (2–5 s) so stability and locking can be measured.
2. **Annotate** in CVAT or Label Studio. Create the labels from `categories.json` (plus `user hand`, `other hand`),
   add a text attribute `track_id` for sequences, export **COCO 1.0** as `annotations.json`. Rules: tight box on the
   visible extent; an object less than ~25 % visible is not boxed and its category is removed from that image's
   `labeled` set; if you cannot tell what something is, do the same rather than guess.
3. **Evaluate** every model in one run on the phone: `tools/eval_on_device.sh dataset/<name> efficientdet_lite0.tflite models/candidates/x.tflite`
   (`LOOPS=10` adds sustained-latency loops). Report: `android/app/build/reports/vision-eval/report.md` + `.json`.
4. **Train** (only when the data below exists): `tools/split_dataset.py` → `tools/train_detector.py` (MediaPipe Model
   Maker; exports a `.tflite` the app loads unchanged) → step 3 on the **test** split.

## What the report measures

Detection at IoU 0.5, at three levels, all on the same frames:
- `model`: every raw detection ≥ 0.35, i.e. what the app surfaced before temporal confirmation existed;
- `app`: what the app now offers as a target (plausible box, seen in ≥ 3 detector runs; `CandidateTracker`);
- `sure`: what the app names without hedging (no class conflict, not low light, smoothed score ≥ 0.6).

Per category precision / recall / false positives per negative image (e.g. false laptops on laptop-free frames),
AP50, what the model called each annotated object (confusion), per-object stability (label flips, app id switches),
target locking (frames a lock points at something else, re-acquisitions), hand identity (frames guided by an
"other hand" box; must be 0), reached/speech (directions spoken after REACHED; must be 0), and on-device latency /
memory / thermal state. App-level numbers come from running the app's own code over the recorded output.

Unit tests prove the logic; only this report, on enough real labelled frames, says anything about accuracy.

## Data still required (none of this exists yet)

Minimum to make accuracy claims (per category, held-out **test** split, ≥ 5 sessions in ≥ 3 different rooms):
≥ 50 annotated instances → recall known to about ±12 %. To fine-tune (train split): ≥ 300 instances per category,
≥ 500 for `laptop bag`, across ≥ 20 sessions. Every session should cover:

- distance 0.3–2 m, sizes small to frame-filling, several angles; laptops open, closed, from behind; bottles upright and lying;
- bright, normal, dim, dark (lights off, curtains) and back-lit scenes; plain and cluttered backgrounds;
- look-alikes side by side: bottle / vase / cup; laptop / tablet / TV / monitor / keyboard; backpack / handbag / laptop bag / suitcase;
- the reaching hand partially covering the target; several instances of one category; several categories next to each other;
- **negatives** (images with no instance, `labeled` = all): dark empty regions, black rectangles (TV off, doorway, black cushion,
  shadow under a desk) — the laptop false-positive case;
- hands: the user alone, the user plus another person's hand, hands crossing, the confirmation gesture, and one full reach with
  `target` + `reach_ms` set.

## Acceptance criteria for replacing the detector

The shipped `efficientdet_lite0.tflite` stays the fallback. A candidate replaces it only if, on the held-out test split:
1. `app` recall on bottle, laptop, backpack, handbag, cup is ≥ the current model's + 0.10, and no category drops by more than 0.05;
2. laptop false positives per negative image at the `app` level are ≤ the current model's and ≤ 0.02;
3. on the A15 with the hand landmarker running, detector p90 ≤ 350 ms and no thermal throttling after `LOOPS=10`;
4. the logic gates stay at zero: wrong-target frames, other-hand frames, directions after reached.

## `seed/`: a pipeline smoke test, not a benchmark

130 images: 90 real A15 frames (2 sequences in one room, captured 2026-10-11) + 40 **synthetic** dark frames
(`tools/make_dark_frames.py`: black, sensor noise, the wall frames underexposed ×0.25/×0.12/×0.06). Boxes were drawn by
inspecting full-resolution frames: a person lying on the floor, the black laptop they hold (lid to the camera) and a phone on
the floor (boxes checked on frames 0/15/29; the camera barely moved). Book / backpack / handbag are not exhaustively labelled
in the wall frames (red folders and papers of unclear type). One scene, one room, three objects: the numbers it produces show
that the harness works and reproduce known failure cases; they are not accuracy estimates.
