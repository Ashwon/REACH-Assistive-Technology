# REACH — smartphone spatial assistant (hackathon MVP)

Chest-mounted Samsung Galaxy A15 5G guides a visually impaired user's hand to a
requested beverage using voice → Claude intent → local detection → MediaPipe
hand tracking → deterministic spatial guidance → TTS.

**Claude is called once per utterance. The real-time loop is 100% on-device.**

```
Voice → Claude (backend proxy, once) → target locked
Camera frame → EfficientDet candidates + HSV beverage color (own thread, ~3 Hz, stale results dropped)
              → CandidateTracker evidence: seen in ≥ 3 runs, class conflicts, plausible box, low light
            → MediaPipe hands (up to 2) → HandSelector: the user's hand, chosen by a deliberate gesture
            → ReachCore state machine (one lock for every transition and every spoken cue)
            → SpatialEngine (smoothing + hysteresis + confirm + throttle), 2D only today
            → TTS: "Move right." / "Lined up. Reach forward slowly."  (never "reached" from 2D overlap)
```

## Setup (15 min)

1. **Models** (required before first build):
   ```bash
   ./scripts/download_models.sh
   ```
2. **Backend** (laptop, same Wi-Fi as phone; Python 3.10+):
   ```bash
   cd backend
   pip install -r requirements.txt
   export ANTHROPIC_API_KEY=sk-ant-...        # NEVER commit this
   uvicorn server:app --host 0.0.0.0 --port 8000
   ```
   Verify: open `http://<laptop-ip>:8000/health` from the phone browser.
3. **App** (Wi-Fi: phone and Mac on the same network):
   ```bash
   cd android
   ./gradlew assembleDebug -PbackendUrl=http://<mac-lan-ip>:8000   # default http://192.168.1.5:8000
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
   No Wi-Fi on the phone? Over USB use `adb reverse tcp:8000 tcp:8000` and build with
   `-PbackendUrl=http://127.0.0.1:8000`. Backend tests: `cd backend && python3 -m pytest -q`.
   Debug builds log pipeline state under the `REACH` tag (`adb logcat | grep "REACH   :"`) and accept
   a typed utterance: `adb shell "am start -f 0x20000000 -n com.reach.app/.MainActivity --es utterance 'find the person'"`
   (the inner single quotes matter). Unit tests: `cd android && ./gradlew testDebugUnitTest`.

## Milestone test plan (run in this order)

| Milestone | Test on A15 | Success | Fallback |
|---|---|---|---|
| **V0 camera** | Launch app | Live rear preview, no stutter after 2 min | Lower analysis res to 480×360 in `CameraXSource` |
| **V1 hand** | Hold hand ~40 cm in front | Big cyan dot rides your index fingertip, small dots on the other four tips (`adb logcat | grep "REACH   : tips"`) | Increase lighting; drop `MinHandDetectionConfidence` to 0.3 |
| **V2 grounding** | A bottle/cup/phone/chair on a table, ~50–80 cm | Yellow boxes labeled `#id label score` (drink containers add a color guess) | Check `adb logcat | grep "REACH   : kept"` for what the detector reports (`near=` lists supported objects scoring 0.20–0.35); tune `SupportedClasses` / `ColorClassifier` |
| **V3 depth** | Press DEPTH | "Depth API supported: true" + plausible mm at center; `depthcheck` log lines give ARCore's image size, intrinsics, depth size | Informational only: depth is not in the guidance loop (see below) |
| **V4 spatial** | Lock target (below), "guide me", move hand around | Cues change L/R/U/D without chatter, then "Lined up. Reach forward slowly." | Thresholds: `exitX/enterX/exitY/enterY` in `SpatialEngine` |
| **V5 Claude** | "Find my Coke" with backend up | TTS: "Found the coke, NN percent sure." Two red cans → clarification question | Kill backend → app auto-switches to offline keyword mode |
| **V6 voice** | Tap **Find an Object** with the field empty (or the mic icon), speak | Transcript appears in the field and is submitted | Type the request instead; offline phrases: "find my coke", "guide me" |

## Demo script (2 min)

1. Cans on table: Coke, Pepsi, Sprite, Monster. Phone chest-mounted, ~60 cm away.
2. Tap **TAP + SPEAK** → "**Find my Coke.**"
   → "Found the coke, 90 percent sure. Say guide me when ready."
3. Tap → "**Guide me.**" → "Guiding by direction only, not distance." → move hand in from the side.
   → "Move right." … "Move down." … "Lined up. Reach forward slowly." The user feels for the can;
   the app does not claim contact (no depth).
4. **Trust moment** (judges love this): place Coke and a second red can →
   "Find my Coke" → Claude asks "I see two similar red cans — the one on your
   left?" This is the differentiator vs. describe-only tools.
5. **Resilience moment**: kill the backend, repeat step 2 →
   "Offline mode. Targeting the coke." Guidance still works.

## Graceful degradation (built in)

- No depth in the loop → 2D, direction-only guidance, announced as such; never "reached".
- Claude/backend/internet fails → offline keyword target selection; the guidance
  loop never touches the network.
- Detector misses → target box held 1.5 s, then "I can't see the bottle. Hold still." (or "Your hand is
  covering the bottle…" when the fingertip is over where it was). A re-lock is announced ("Found the bottle again.").
  Still gone 4 s later (and not covered by the hand) → TRACKING_LOST: "I lost the bottle. Say guide me to try again…";
  nothing resumes until the user says guide me or searches again.
- Hand missing > 0.5 s → "I can't see your hand." Missing > 2 s, or another hand comes close enough that the two
  could be confused → "I lost your hand. Guidance paused." and the user confirms their hand again (gesture).
  Guidance is never handed to another hand.
- Speech recognition fails → RESET + retry; exact phrases work offline.

## Hand identity (`hand/HandSelector.kt`)

Before a task the user tells REACH which hand is theirs. At start-up (and via the hand button, or by saying "my hand")
REACH says "Raise the hand you'll reach with, then close it into a fist." The hand seen **open after the prompt and then
held as a fist for 300 ms** is selected ("Got your hand."). Other hands in view are ignored unless they make the same
gesture; then nobody is selected and the user is asked again. Nearest hand, first detection, handedness and MediaPipe's
list order are never used: MediaPipe gives hands no identity and no stable order, and with one hand slot it would not
even report a second hand. After selection every visible hand has its own track (palm + size continuity; recently seen
tracks claim their hands first), so a bystander's hand stays on its own track. All five fingertips and the guidance
point come from that one hand. Search never selects a hand; "guide me" without a selected hand asks for the gesture
first and gives no direction until it is done.

Limits: this is not biometric identification. Someone else opening and closing their hand on cue at the same moment
makes it ambiguous (REACH says so); someone doing it *instead of* the user would be taken as the user. The fist reading
(fingertips nearer the wrist than their middle joints) is unit-tested on synthetic landmarks and still needs checking
on the A15 at several distances. Cost of two hand slots on the A15: hand landmarker ~93 ms (no hand) / ~130 ms (one
hand) median per frame vs ~60–105 ms with one slot; ~8–10 fps overall.

## Guidance states (`ReachCore.kt`)

`IDLE → SEARCHING → TARGET_LOCKED → GUIDING → REACHED`, plus `TRACKING_LOST` (target). One lock covers every transition
and every spoken guidance line. Every transition bumps a session number; detector results and Claude interpretations
carry the session they started in, so a late result cannot re-lock, redirect or restart guidance after reached or
cancel. **Reached** needs evidence: depth-verified contact (implemented, not fed: no depth in this pipeline) or the user
saying "got it" / tapping **I've got it**. Then one "Bottle reached." is spoken (the speaker flushes its queue) and no
cue is spoken again until the user starts a new search or says guide me. A 2D overlap never counts as reached.

## Object evidence (`grounding/`)

A detection is offered as a target only after the tracker has seen it in ≥ 3 detector runs (~1 s) with a plausible box
(near-whole-frame boxes are rejected: in dim A15 frames every junk box covered ≥ 0.73 of the frame). A spot the model
also called something else within the last 1.5 s (IoU ≥ 0.5, any class, e.g. "person" one frame and "bed" the next) is
a conflict: REACH says "I can't tell if that's a person or a bed." Wording follows the evidence: "Found the laptop" only
for a settled, unconflicted detection with smoothed score ≥ 0.6 in a lit frame (mean luma ≥ 45); otherwise "I might see
the laptop, but I'm not sure." Repeating the same detection makes it stable, never "verified". Asking for a category the
model has no class for ("laptop bag") gets "I can't recognise laptop bags yet" instead of matching "laptop".

## Evaluation (`dataset/README.md`, `tools/`)

Batch evaluation replaces scenario-by-scenario phone testing. `tools/eval_on_device.sh dataset/<name> <models…>` runs
every labelled frame through the real models on the phone, then scores them on the JVM with the app's own tracking,
evidence, locking, hand-selection and state-machine code: per-class precision/recall, false laptops per negative image,
confusion, stability, target switches, other-hand frames, directions after reached, latency, memory, thermal.
`dataset/categories.json` is the one category list; `tools/split_dataset.py` + `tools/train_detector.py` are the
fine-tuning path (not run yet: there is no training data). Manual phone testing is for final integration checks only.

## Screen (`MainActivity`, `ui/ReachStatus.kt`, `ui/OverlayView.kt`)

One portrait screen, dark high-contrast theme, Material 3 (already a dependency):
- **Top:** REACH + tagline; speech on/off (persisted) and a settings sheet (speech switch, guidance volume
  10–100 %, test voice, the ARCore depth check).
- **Camera card:** the real CameraX preview (letterboxed, so the whole analysis frame is visible) with the
  detector's boxes, the locked target, all five fingertips (index ringed, a too-far hand dimmed) and, while
  guiding, the fingertip→target line. Status pill + "Seeing …" / hand chips come from `ReachSnapshot`.
- **Guidance card:** the engine's current cue in the same words the voice uses (`Guidance.phrase`),
  "Direction only · no distance" while guiding, and the last phrase actually sent to TTS.
- **Request:** text field with a mic icon; **Find an Object** submits typed text or, if empty, listens.
  The actions follow the controller's real mode: Find → (Searching…, Cancel) → Start Guidance / Cancel →
  I've got it / Stop Guidance; after a lost target, Start Guidance is the retry. The hand button (top bar)
  re-runs hand selection.
- Status wording is derived only from real state and is unit-tested: "Guiding" exists only while guiding with the
  user's hand in view, "Reached" only in REACHED (depth contact or the user's confirmation; never from 2D overlap).
- Camera permission refused, camera busy (no frames for 3 s) and model-load failure each replace the live
  view with a plain-language message instead of closing the app.

## Guidance geometry (`spatial/SpatialEngine.kt`)

- **Axes:** normalized coordinates of the upright 480×640 CameraX analysis frame, the one bitmap both the
  detector and the hand landmarker run on. x 0→1 left→right, y 0→1 top→bottom. Chest-mounted rear camera:
  image right = user's right, image up = up (phone upright).
- **Error** = locked box centre − index fingertip (landmark 8) of the selected hand (see Hand identity).
  EMA-smoothed (α 0.5 per frame).
- **Thresholds (box-relative, frame units):** lined up horizontally when |ex| < max(0.03, 0.35·w),
  correct again when |ex| > that + max(0.03, 0.25·w); vertically |ey| < max(0.03, 0.30·h), re-enter at
  + max(0.04, 0.15·h). Horizontal is corrected first.
- **Stability:** a changed decision must hold 300 ms before it is spoken; changed cues ≥ 1.1 s apart,
  repeats ≥ 2.6 s apart; "Stop." / "reached" skip the gap.
- **What 2D can and cannot say:** "lined up" = the fingertip is on the camera's line of sight to the
  object. It carries no distance, so 2D guidance ends at "Lined up. Reach forward slowly." Hand size is not
  used: a hand reaching forward moves *away* from a chest camera and shrinks, so the old
  "hand grew → reached" proxy was backwards (measured: 0.26 → 0.10 during a reach).
- **Depth path (implemented, not fed):** given metric depth for both the object and the fingertip from one
  depth map registered to this frame (valid range 0.15–3 m), the engine says "Move closer" (gap > 6 cm),
  "Stop." (within 3 cm, or fingertip past the object) and "<object> reached" after 500 ms of continuous
  aligned contact. Any invalid depth sample restarts that hold.
- **Why ARCore depth is not wired in:** ARCore opens the camera itself and CameraX already owns it in the
  main screen; the two cannot stream at once. ARCore's depth map is aligned to ARCore's own camera image
  (its own resolution, crop and intrinsics, see the `depthcheck` log), not to our CameraX frame, so sampling
  it at our box/fingertip coordinates would mix coordinate systems. Doing it properly means making ARCore
  the single frame source for both models. On phones without a depth sensor ARCore estimates depth from
  device motion, which is weakest on a moving hand, so fingertip depth would still need validating.

## Honest limits (say these out loud in the pitch)

- No centimeter accuracy claim; proximity is a heuristic in Level 1.
- The detector is EfficientDet-Lite0 (COCO). It can only report the COCO names in its
  embedded labels, and has **no `can` or `laptop bag` class**: a soda can is only found if the model
  happens to call it a bottle/cup/vase. Supported categories: `dataset/categories.json` (kept in sync with
  `SupportedClasses` by a test). Measured on recorded A15 frames: a black laptop seen from behind was never
  called a laptop (30/30 frames; "backpack" at 0.22–0.30), a phone on the floor was missed 30/30, a person
  lying down was "bed" in 16/30 frames, train wheels painted on a wall were "scissors" (0.36–0.46). These are
  model limits; the app's job is to not state them as fact (see Object evidence). Better accuracy needs the
  labelled data and the acceptance test in `dataset/README.md`.
- Beverage identity (coke/pepsi/sprite/monster) is an HSV color guess on top of those
  containers, demo-curated and unverified on real cans.
- Open-vocabulary grounding would slot in behind `ObjectGrounder` later.
- Not a safety/medical device; final contact is performed by the user.

## Security

- API key lives only in the backend shell env. `.gitignore` blocks `.env`.
- The APK contains only a LAN backend URL.
