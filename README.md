# REACH — smartphone spatial assistant (hackathon MVP)

Chest-mounted Samsung Galaxy A15 5G guides a visually impaired user's hand to a
requested beverage using voice → Claude intent → local detection → MediaPipe
hand tracking → deterministic spatial guidance → TTS.

**Claude is called once per utterance. The real-time loop is 100% on-device.**

```
Voice → Claude (backend proxy, once) → target locked
Camera frame → EfficientDet candidates + HSV beverage color (own thread, ~3 Hz, stale results dropped)
            → MediaPipe fingertips (all five exposed; the index tip drives guidance)
            → SpatialEngine (smoothing + hysteresis + confirm + throttle), 2D only today
            → TTS: "Move right." / "Lined up. Reach forward slowly."  (never "reached" without depth)
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
| **V6 voice** | Tap big button, speak | Transcript appears top of screen | Type-free demo: keep offline mode phrases exact ("find my coke", "guide me") |

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
  covering the bottle." when the fingertip is over where it was). A re-lock is announced ("Found the bottle again.").
- Hand missing > 0.5 s (4 s right after "guide me") → "I can't see your hand."
- Speech recognition fails → RESET + retry; exact phrases work offline.

## Guidance geometry (`spatial/SpatialEngine.kt`)

- **Axes:** normalized coordinates of the upright 480×640 CameraX analysis frame, the one bitmap both the
  detector and the hand landmarker run on. x 0→1 left→right, y 0→1 top→bottom. Chest-mounted rear camera:
  image right = user's right, image up = up (phone upright).
- **Error** = locked box centre − index fingertip (landmark 8). EMA-smoothed (α 0.5 per frame).
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
  embedded labels, and has **no `can` class**: a soda can is only found if the model
  happens to call it a bottle/cup/vase. The allowlist lives in `SupportedClasses`
  (`grounding/Detection.kt`); anything else is logged as `rejected` and ignored.
- Beverage identity (coke/pepsi/sprite/monster) is an HSV color guess on top of those
  containers, demo-curated and unverified on real cans.
- Open-vocabulary grounding would slot in behind `ObjectGrounder` later.
- Not a safety/medical device; final contact is performed by the user.

## Security

- API key lives only in the backend shell env. `.gitignore` blocks `.env`.
- The APK contains only a LAN backend URL.
