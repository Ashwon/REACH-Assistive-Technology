# REACH — smartphone spatial assistant (hackathon MVP)

Chest-mounted Samsung Galaxy A15 5G guides a visually impaired user's hand to a
requested everyday object (bottle, laptop, book, backpack, handbag, cup) using voice → Claude intent → local detection → MediaPipe
hand tracking → deterministic spatial guidance → TTS.

**Claude is called once per utterance. The real-time loop is 100% on-device.**

```
Voice → Claude (backend proxy, once) → target locked
Camera frame → EfficientDet candidates + HSV beverage color (own thread, ~3 Hz, stale results dropped)
              → CandidateTracker evidence: seen in ≥ 3 runs, class conflicts, plausible box, low light
            → MediaPipe hands (up to 2) → HandSelector: the user's LEFT and RIGHT hands, each enrolled once by a gesture
            → ReachCore state machine (one lock for every transition and every spoken cue)
            → SpatialEngine (smoothing + hysteresis + confirm + throttle): 2D alignment, plus distance in depth mode
            → TTS: "Move right." / "Lined up. Reach forward slowly."  (never "reached" from 2D overlap)
Rotation sensor (game rotation vector) → TurnMonitor: turned away from where the target was → pause, guide back
Utterance → Conversation (local intents + short-lived task memory) → Claude only for new object requests
ReachCore → HapticCue (Haptic Language v1.0, one per frame) → HapticPlayer (priority, timing) → BleBand (BLE NUS) → ESP32-S3 band
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
   a typed utterance: `adb shell "am start -f 0x20000000 -n com.reach.app/.MainActivity --es utterance 'can you help me find my bottle'"`
   (the inner single quotes matter). Unit tests: `cd android && ./gradlew testDebugUnitTest`.

## Milestone test plan (run in this order)

| Milestone | Test on A15 | Success | Fallback |
|---|---|---|---|
| **V0 camera** | Launch app | Full-screen ultrawide preview; log `cameras id=2 f=1.4mm fov=75°, id=0 f=3.98mm fov=52°` then `camera bound id=2` | Pick camera 0 in the settings sheet |
| **V1 hand** | Hold hand ~40 cm in front | Big cyan dot rides your index fingertip, small dots on the other four tips (`adb logcat | grep "REACH   : tips"`) | Increase lighting; drop `MinHandDetectionConfidence` to 0.3 |
| **V2 grounding** | A bottle/cup/laptop/book on a table, ~50–80 cm | Yellow boxes labeled `#id label score` (drink containers add a color guess) | Check `adb logcat | grep "REACH   : kept"` for what the detector reports (`near=` lists supported objects scoring 0.20–0.35); tune `SupportedClasses` / `ColorClassifier` |
| **V3 depth** | Settings → Camera → "Main camera + depth (ARCore)" | `arcore setup … agree=true`, `arcore tracking=TRACKING depth=yes`; chip "Depth camera on" | Any failure falls back to the ultrawide with a spoken reason; the old DEPTH check screen is still in settings |
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

- Wide camera mode, or depth mode without a valid measurement → 2D, direction-only guidance, announced as such
  ("Distance unavailable. Direction only." once per guidance); never "reached" from 2D.
- Claude/backend/internet fails → offline keyword target selection; the guidance
  loop never touches the network.
- Target not detected → its identity is kept (see Target through occlusion); directions pause the moment its box is
  older than 1 s or the camera has turned > 15° since it was seen. Hidden past the grace period → TRACKING_LOST with
  "The bottle is blocked from view. Move your hand or the camera slightly…" (or "…I can't see the bottle anymore…");
  when it is re-found REACH says so once, and guidance resumes only after "guide me".
- Enrolled hand missing > 0.5 s → "I can't see your right hand." It keeps its identity for 15 s; only if it cannot be
  recovered safely (gone too long, two candidates, crossed with a same-labelled hand) does REACH ask for the gesture
  with that hand again. Guidance is never handed to another person's hand.
- Speech recognition fails → RESET + retry; exact phrases work offline.

## Hand identity (`hand/HandSelector.kt`)

**Enrolment, once.** At start-up (and via the hand button, "set up my hands", or settings) REACH asks for the left hand,
then the right: "Raise your left hand, then close it into a fist." The hand seen **open after the prompt and then held
as a fist for 300 ms** becomes `USER_LEFT_HAND` (then `USER_RIGHT_HAND`). The answer window (20 s) starts when the prompt
has been spoken, not when a long introduction began. The already-enrolled hand never answers for the other side, and a
hand MediaPipe confidently labels the same as the other enrolled hand is refused ("That looks like your other hand").
Two hands answering at once → asked again. Left/right come from the user, never from MediaPipe: its handedness label
assumes a mirrored selfie image; on this rear camera it is not mapped to a side. The label each hand had **at enrolment**
is stored and only ever compared with itself.

**Tracking.** Every visible hand has its own track (palm predicted from recent motion, size continuity, handedness veto;
continuously seen tracks claim their hands first). Per side: `TRACKED`, `MISSING` (out of view, identity kept for
`Config.graceMs` = 15 s), `UNCERTAIN` (another hand within ~0.6 hand-lengths: tracks could swap), `NEEDS_RECOVERY`
(identity cannot be recovered safely; that side only makes the gesture again). One side's trouble never resets the other.
A missing hand comes back
- within 1 s by continuity: within 0.15 of its predicted palm, size ratio 0.75–1.33, MediaPipe confidence ≥ 0.6, label
  not contradicting, and the only such hand; or
- within the grace period on a **new** track that was born after the hand was last seen (a hand in view at the same time
  as the user's can never be it), has been labelled like the enrolled hand in every confident (≥ 0.8) sighting, is
  0.6–1.6× its last size, and has been followed for 300 ms — and is the only hand that qualifies.
Two candidates → `NEEDS_RECOVERY`. After crossing, `UNCERTAIN` resolves to `TRACKED` only if the two hands were told
apart by confident, different labels (the user's own two hands); otherwise recovery. Two frames with the same timestamp
no longer corrupt the motion prediction (velocity needs ≥ 20 ms between sightings).

**Limits (not biometric).** If the user's hand is out of view and another person's hand of the same side and size enters
alone, it can pass the return gates; hence the bounded grace period, pausing on any doubt, and the recovery gesture.
Settings → Diagnostics shows both enrolled hands: state, track id, confidence, label, missing time, reason. The fist
reading (fingertips nearer the wrist than their middle joints) is unit-tested on synthetic landmarks and still needs
checking on the A15 at several distances. Cost of two hand slots on the A15: ~74–96 ms per frame median, ~9–11 fps overall
(measured 2026-10-13, no hands in view).

**Which hand is guided.** Voice directions follow one enrolled hand: the one the user named ("guide my left hand"), else
the right, else the left. In depth mode, if the hand comparison (`HapticPolicy`, 3D distances only) says the other hand is
reliably closer, guidance switches to it and says so (unless the user named a hand). That comparison never drives a motor.

## Guidance states (`ReachCore.kt`)

`IDLE → SEARCHING → TARGET_LOCKED → GUIDING → REACHED`, plus `TRACKING_LOST` (target). One lock covers every transition
and every spoken guidance line. Every transition bumps a session number; detector results and Claude interpretations
carry the session they started in, so a late result cannot re-lock, redirect or restart guidance after reached or
cancel. Changing camera (lens or mode) clears the target, drops in-flight results and asks for the hand again.
**Reached** needs evidence: the user saying "got it" / tapping **I've got it**, or depth contact once the fingertip-depth
estimate has passed validation (`SpatialEngine.DEPTH_REACHED_VALIDATED`, currently `false`, see Depth). Then one "Bottle reached." is spoken (the speaker flushes its queue) and no
cue is spoken again until the user starts a new search or says guide me. A 2D overlap never counts as reached.

## Target through occlusion (`grounding/Detection.kt` TargetLock)

The locked object keeps its identity (class, tracker id, last box, motion, colours) while it is not detected, and the
status says why: `OCCLUDED` (a hand is over its last box; grace 8 s), `TEMPORARILY_MISSING` (not detected, nothing over
it; 2.5 s from the last sighting or the last cover), `LOST` (past the grace; identity still remembered). A retained target
is not a retained position: its box drives directions only while < 1 s old and while the camera has not turned > 15°
since. Re-finding it under a new tracker id needs the same class, a settled undisputed detection, its predicted position
or box overlap (dropped only after a turn back), similar colours (histogram intersection ≥ 0.5) when known, never an object
that was in view **together** with the target, and exactly one such candidate. "I can't see the bottle. Hold still." and
"Your hand is covering the bottle…" are status lines (at most every 6 s), never per frame; "Found the bottle again" is said
only when the loss was announced.

## Several of one object, and saved objects (`talk/Conversation.kt`, `grounding/ObjectMemory.kt`)

Every settled detection has its own tracker id (`CandidateTracker`); a request is never resolved to the first one. "Find
my laptop" with two laptops in view is asked about locally: "I can help. I see two laptops. Do you want the one on the
left or the right?" Answers and descriptions in the request are resolved from what the camera sees now: "the one on the
right", "the black one" (only if that option's pixels clearly have more black than the others), "the one next to my bottle"
(only if exactly one bottle is in view and one laptop is clearly nearer it: ≤ 0.6× the other's distance). Anything else is
asked again ("I see 2 bottles too, so that doesn't tell me which…"). If Claude picks one of several objects of a class
that nothing local singles out, the pick is discarded and the user is asked.

**Saved objects (optional).** "Remember this laptop as my work laptop" saves the locked target, or the one settled laptop
in view: its crop (`files/objects/*.jpg`) and an image embedding (MediaPipe ImageEmbedder, MobileNet-V3 small, 4 MB,
`scripts/download_models.sh`) in `files/objects/objects.tsv`, on the phone only. Saying it again from another side adds a
photo; "forget my work laptop" deletes it. "Find my work laptop" (or "find my laptop" when one laptop is saved) compares
each laptop in view with the photos: it is called the user's only if its similarity is ≥ 0.80 **and** ≥ 0.08 above every
other laptop's ("The laptop on the right looks like your work laptop from its saved photo."). Otherwise REACH asks the
user to choose ("I can't tell which laptop is your work laptop from its photo…"; one laptop: "…Say yes to use it
anyway"), and the chosen object is never called theirs. A class, a colour, or being the only one in view is never taken
as identity. The embedding runs only for a request, a registration or a check (17.6 ms p50 / 35 ms p90 per crop on the
A15, 2026-10-13), never per frame and never over the network.

**Identity is not a tracker id.** The saved name is bound to the lock for the tracker id it was matched on. When the target
is re-found under a new id (after an occlusion, a turn, a detector gap), directions pause ("Checking this is your work
laptop. Hold still.", the band gets one HOLD) and the photo is compared again on each detector run while the target
is in view. Match on that track → "This still looks like your work laptop. Continuing."; another laptop matching better,
or 3 unsure checks in a row → the target is dropped and the user is told why.

**Not validated.** The 0.80 / 0.08 thresholds are starting values. Unlabelled A15 seed frames gave 0.82–0.98 for the same
spot in consecutive frames and ≤ 0.24 between different scenes, so expect true objects seen from another side or in other
light to fall below 0.80 (REACH then asks). How well the embedding tells two similar laptops or bottles apart is unknown
until `IdentityEval` runs on instance-labelled photos (`dataset/README.md`). An image embedding cannot tell two identical
products apart at all.

## Turning away (`spatial/Heading.kt`, `spatial/HeadingSensor.kt`)

Measured on the A15: LSM6DSL gyroscope + accelerometer, Samsung Game Rotation Vector (type 15) at 20 ms. The camera's
heading comes from the rotation matrix (the rear camera's -Z axis; undefined, and reported as unknown, when it points at
the floor or ceiling). The target's bearing is the heading when it was last detected plus its offset in the image (the
bound camera's measured field of view; ARCore's own intrinsics in depth mode). AWAY = filtered heading > 60° from that
bearing for 0.7 s; back = within 25° for 0.5 s. Then: "You may have turned away from the bottle. Turn back toward your
previous direction, to your left." Directions pause, the band gets one HOLD (then SEARCH while looking, REACQUIRED when
found), the task and target identity are kept. Facing back: "Hold there. I'm looking for the bottle." The target may then be re-found anywhere in view (only
if it is the only candidate); not re-found within 5 s → "I can't find the bottle from here. Slowly scan the area." "Where
was it?" answers from what is known: what the camera sees now, or "When I last saw the bottle, it was to the right…".
Phone rotation means body rotation only when chest-mounted (Settings → "Phone is chest-mounted"; off = "the camera" wording).

## Conversation (`talk/Conversation.kt`)

Deterministic, offline-capable intents: new object request, follow-up reference, clarification answer, cancel, retry /
continue, completion, hand setup / "guide my left hand", help, "where was it". Short-lived task memory (5 min): requested
category, the options offered (left to right), the selected target id, colour words used. "Can you help me find my
bottle?" → interpreted (Claude when the backend is reachable, else offline); two bottles → "I can help. I see two bottles.
Do you want the one on the left or the right?" → "The blue one." is resolved locally, only if one option's pixels clearly
have more blue than the others ("The bottle on the left looks the most blue. Found the bottle…"); otherwise it asks again.
"The other bag", "that bottle", "continue", "try again" work the same way. Cancel or a new object resets the memory.
Claude is never in the per-frame loop and never decides directions.

## Vibration band (`haptic/`, `BandSetupActivity.kt`, `firmware/`)

**Hardware and protocol (unchanged).** ESP32-S3, two motors, BLE name `REACH_Haptic_Device`, Nordic UART Service
`6E400001-…`, RX `6E400002-…` (phone → band), TX `6E400003-…` (band → phone). One command per write:
`MOTOR,POWER,DURATION_MS` (`L`/`R`/`B`, 0–255, ms). STOP is `B,0,0`. On the A15 (2026-10-13) the band's RX advertised
properties `0x8` (write with response only), so the app writes with response; it picks the write type from RX's
properties, not from an assumption.

**Haptic Language v1.0 (`HapticLanguage.kt`, all timings and powers in one table; prototype values, not validated with users).**
Gaps between pulses are timed on the phone, one pulse per write, so a band that blocks during a pulse can never block a
whole pattern, and a STOP is never stuck behind one.

| Cue | Pattern | Played when (`ReachCore.bandCue`) | Repeat |
|---|---|---|---|
| H1 MOVE_LEFT / H2 MOVE_RIGHT | L (or R) 180 × 100 ms, 100 ms gap, again | guiding, target tracked, guided hand usable, engine cue "Move left/right" | every 1.2 s |
| H3 FORWARD | L,R,L,R 150 × 100 ms, 50 ms gaps | engine cue "Move closer" **and** valid depth this frame **and** fingertip lined up now | every 1.5 s |
| H4 NEAR | both 150 × 80 ms | engine "Almost there" / "at the target" with valid depth this frame and lined up; cadence from the smoothed forward gap: > 10 cm 800 ms, 5–10 cm 400 ms, ≤ 5 cm 200 ms | per band |
| H5 REACHED | both 200 × 250 ms, 120 ms gaps, ×3 | mode REACHED only: depth contact held ≥ 500 ms with valid depth throughout (once `DEPTH_REACHED_VALIDATED`), or the user's own "I've got it" | once |
| H6 HOLD | both 180 × 400 ms | a pause: turned away, guided hand uncertain / needs confirming / being enrolled, identity check, "Stop", "Too far, pull back" | once per pause |
| H7 TARGET_LOST | both 200 × 70 ms ×3, 130 ms gaps | mode TRACKING_LOST | once |
| H8 SEARCH | L 140 × 120 ms, 600 ms, R 140 × 120 ms | mode SEARCHING (request being interpreted); turned back and looking | every 2.5 s |
| H9 REACQUIRED | both 170 × 100 ms ×2, 150 ms gap | after "Found the bottle again" (once in TRACKING_LOST; for 1.5 s in GUIDING, then directions resume) | once |

No vibration: idle, target locked but not guiding, target briefly hidden, hand out of view, "Move up/down", 2D "Lined up.
Reach forward slowly." (2D has no forward evidence), and any frame whose depth is missing or out of range. The band's cue
is computed every frame from the same state the voice uses; the voice keeps saying everything.

**Arbitration (`HapticPlayer.kt`).** Priority HOLD > REACHED > TARGET_LOST (= REACQUIRED) > MOVE_LEFT/RIGHT > FORWARD >
NEAR > SEARCH. One pattern owns the motors. The same cue requested every frame never replays; a repeating cue never
restarts sooner than its interval, a one-shot plays once per entry and not again within 1.5 s. Higher or equal priority
pre-empts at once (STOP if a pulse may still be running); a lower one waits for a running one-shot to finish but a
repeating pattern that is no longer wanted is cut. A new pulse never starts while the last one may still be running,
and a different pattern starts only after 150 ms of quiet. `stop()` (Stop/Cancel, app in background, disconnect) drops
everything and sends STOP; a one-shot cut by it is not replayed after a reconnect.

**BLE client (`BleBand.kt` on Android, `BandSession.kt` pure).** Scans for the name or the service (12 s), connects
(12 s timeout), discovers the service, checks RX/TX, enables TX notifications, then writes STOP first. Every write is
serialized (one in flight; 1.5 s write timeout drops the link); STOP jumps the queue and clears it; a queued pulse older
than 300 ms is dropped. A disconnect cancels guidance and drops the queue; up to 3 reconnects 2 s apart, then "Connection
failed" with Retry. All of it runs on one `reach-band` thread; the camera pipeline only posts to it. Motor state is
"stop sent" when a STOP was written and "off" only when the band itself says `OFF` on TX (patched firmware only). A band
disconnect while REACH is in use is spoken ("Vibration band disconnected. I'll keep guiding by voice.").

**Startup and setup (`BandSetupActivity.kt`, `BandSetup.kt`).** Every launch (before the camera): welcome to REACH →
the band and its nine patterns → connect (scanning / found / connecting / ready / failed + retry, or "Continue with voice
only") → left motor test → right motor test → practise all nine
patterns (labelled "Practice only · not live guidance"; plays straight to the band, never through ReachCore) → confirm
("Continue to REACH" / "Test again" / "Continue with voice only"). Whether the introduction opens is decided per launch,
never from a stored preference (`BandSetup.showIntro`): a new main screen or a new process starts at step 1; going to the
background and back, or Android recreating a screen inside the same process, does not. Back goes one step back (on step 1
it leaves REACH); the current step and answers survive recreation. Every step is spoken and on screen; "Stop vibration" is
on every step. A motor counts only from the user's answer ("Yes, I felt it" / "I didn't feel it" / "I felt it on my
other side"), never from a successful write; the motors are never swapped in software. Setup is "done" only when both
motors were felt and the user chose to continue; the band's address and the language version are stored in the `reach`
SharedPreferences. Live haptics run only while that same band is connected; any failed self-test turns them off. Voice
only → the band is not used for live guidance this session (Settings → Motor self-test sets it up later). A new language version
opens the practice step again. Settings also has Connect / reconnect, Review haptic instructions and Stop vibration.

**Firmware (`firmware/reach_haptic_band/reach_haptic_band.ino`): a patch for review, NOT flashed.** The working sketch
was not on this machine, so this is a full replacement keeping the same name, UUIDs and command format: pulses run from
`loop()` with `millis()` (no `delay()` in the BLE path), power 0 = STOP (clears the queue, motors off at once), strict
parsing (`L/R/B`, 0–255, 0–1000 ms, nothing trailing; `ERR …` on TX), 8-pulse queue with stale pulses dropped, motors off
on reset and on disconnect, `OK …` / `OFF` notifications. Motor GPIOs are deliberately left as an `#error` until copied
from the working sketch. Android works with either firmware; only "motors off (reported by band)", instant STOP and the
disconnect-off guarantee need the patch.

**Verified so far.** Unit tests (JVM): payloads, all nine patterns and timings, near bands, priority / pre-emption /
repeat limits / no-overlap (randomised), STOP and cancel, stale and invalid depth suppression, disconnect / reconnect,
setup steps and persistence. On the A15 (2026-10-13): the band was found and connected and RX reported write-with-response.
**Not yet verified:** that each motor vibrates, on the correct side, with these patterns; that users can tell them apart.

**Physical test sequence (band on the wrist, phone beside it).**
1. Serial Bluetooth Terminal first (no app): `L,180,100` → left only; `R,180,100` → right only; `B,200,250` → both;
   `B,0,0` → accepted without error (note the reply, if any); `L,180,5000` → note whether the unpatched firmware accepts it.
2. REACH → Settings → Motor self-test: the left pulse comes after "This is the left motor"; answer honestly. Repeat right.
   If either is on the wrong side, choose "I felt it on my other side" and fix the band or the wiring, not the app.
3. Practice step: play each of the nine patterns twice; write down which ones you could not tell apart.
4. Live, 2D: find a bottle, Start Guidance, hand to its left → two right pulses repeating; hand over it → no vibration;
   "I've got it" → three long buzzes once. Cover the camera until "I can't see the bottle anymore" → three short buzzes.
5. Live, depth mode: lined up, hand ~30 cm short → alternating pulses; move in → taps speeding up; cover the depth
   sensor / move the hand off-axis → taps stop at once.
6. Stop Guidance during a pattern → it stops within one pulse; switch the band off while guiding → "Vibration band
   disconnected" is spoken and guidance continues by voice; switch it on → "Vibration band connected" and the current
   cue resumes (no replayed "reached").

## Introduction

Every launch: the welcome step of the setup screen, then "REACH ready." on the camera screen. The help button / "help" /
Settings → "Replay introduction" speaks the full introduction. It states the real current state: the vibration sentences (the main patterns, forward / near only in depth
mode) are said only while live haptics are on; until both hands are enrolled it says they will be tracked once shown.

## Object evidence (`grounding/`)

A detection is offered as a target only after the tracker has seen it in ≥ 3 detector runs (~1 s) with a plausible box
(near-whole-frame boxes are rejected: in dim A15 frames every junk box covered ≥ 0.73 of the frame). A spot the model
also called something else within the last 1.5 s (IoU ≥ 0.5, any class, e.g. "person" one frame and "bed" the next) is
a conflict: REACH says "I can't tell if that's a person or a bed." Wording follows the evidence: "Found the laptop" only
for a settled, unconflicted detection with smoothed score ≥ 0.6 in a lit frame (mean luma ≥ 45); otherwise "I might see
the laptop, but I'm not sure." Repeating the same detection makes it stable, never "verified". A box whose pixels are dark
and featureless (mean luma < 40, std < 10) needs a strong score (≥ 0.6) to be kept at all, and is then never "sure": a
weak "laptop" on shadow is dropped.

**Supported objects** (`SupportedClasses.DETECTED`, `dataset/categories.json`): bottle, laptop, book, backpack, handbag,
cup; "bag" means backpack or handbag. Every other COCO class is still detected but never offered; it is tracked only to
flag class conflicts. Asking for anything else gets a fixed local answer and is never mapped to a supported class (Claude
is not asked): "I can't recognise a phone yet. I can find a bottle, laptop, book, backpack, handbag, or cup." There is no
laptop-bag class: "I can't tell a laptop bag from other bags. Ask for a backpack or a handbag…". People and scissors are
refused outright. A lock is refused when the detection's class is not what was asked for (e.g. Claude picked a handbag for
"bottle"), when it is weak (< 0.6) in a low-light frame or on a dark box ("It's too dark for me to tell if that's a
laptop…"), unsettled, or disputed. The locked box keeps the time of the frame it was measured in, so a slow interpretation
cannot make an old box guide as if it were fresh.

## Evaluation (`dataset/README.md`, `tools/`)

Batch evaluation replaces scenario-by-scenario phone testing. `tools/eval_on_device.sh dataset/<name> <models…>` runs
every labelled frame through the real models on the phone, then scores them on the JVM with the app's own tracking,
evidence, locking, hand-selection and state-machine code: per-class precision/recall, false laptops per negative image,
confusion, stability, target switches, other-hand frames, directions after reached, latency, memory, thermal.
`dataset/categories.json` is the one category list; `tools/split_dataset.py` + `tools/train_detector.py` are the
fine-tuning path (not run yet: there is no training data). Manual phone testing is for final integration checks only.

## Screen (`MainActivity`, `ui/ReachStatus.kt`, `ui/OverlayView.kt`)

Camera first, portrait, edge to edge, dark high-contrast theme, Material 3:
- **Full-screen camera:** the real preview (CameraX `fillCenter`, or in depth mode the exact analysed bitmap with
  `centerCrop`) behind the system bars. The 3:4 frame is cropped at the sides **on screen only**; the models always see
  the whole frame. `OverlayView` maps frame coordinates with the same fill transform from the actual frame size in the
  snapshot (unit-tested in `FillCenterTest`), drawing boxes, the locked target (green, "Target ·"; dashed amber "Target · hidden"
  at its last box while it is not detected), both enrolled hands (all 21 landmarks, fingertips, an L / R tag; the guided
  hand's index tip ringed), other hands dimmed, the fingertip→target line while guiding, an "L?"/"R?" ring where a
  missing enrolled hand was last seen, and the measured gap at the fingertip in depth mode. Labels of boxes cut off at an edge stay on screen.
- **Top overlay (inset from status bar/cutout):** status pill (Starting / Camera ready / Searching / Detected / Guiding /
  Paused · target covered / hidden / turned away / hand uncertain, Target lost, Reached), object chip (in view / covered /
  briefly hidden / out of view, still locked), hand chip ("Left: tracked · Right: out of view 2s" or the enrolment step),
  distance chip (per hand, estimates, depth mode only), turn chip (only while turned away) and the vibration chip (the cue
  being sent, or why haptics are off: not set up / skipped / not connected / motor test needed), and the hand, help,
  speech and settings buttons.
- **Bottom overlay (inset from nav bar and keyboard):** the current instruction (same words as the voice), "Direction
  only" or "Direction + distance · estimate" while guiding, last spoken line, the request field with mic, and only the
  actions that apply (Find → Cancel / Start Guidance → I've got it / Stop Guidance).
- **Settings sheet:** camera choice (the cameras the phone actually exposes, with their measured field of view, plus
  depth mode), voice settings, replay introduction, set up both hands again, "Phone is chest-mounted", the vibration band
  (link state, connect / reconnect, motor self-test, review haptic instructions, stop vibration), the old ARCore check
  screen, and live diagnostics (camera, frame size, fps, both enrolled hands, heading sensor and turn state, band cue and
  link status with write counts, closer-hand comparison, depth state and reason).
- Status wording is derived only from real state and is unit-tested. Camera permission refused, camera busy and
  model-load failure each replace the live view with a plain-language message.

## Camera modes and depth (`camera/`, `spatial/Depth.kt`)

Measured on the Galaxy A15 5G (SM-A156E, Android 16) with `adb shell dumpsys media.camera` and the app's logs:
- Apps can open camera `0` (main, 3.98 mm, 52° across in portrait) and `2` (ultrawide, 1.40 mm, 5 MP, fixed focus, 75°
  across in portrait / 92° on the long side). `zoomRatioRange` is 1–4 on every camera, so there is no 0.5× zoom ratio:
  the ultrawide is a separate camera id. **Wide mode** (default) binds the widest camera CameraX reports.
- **Depth mode** = an ARCore session as the frame source (ARCore picks the main camera itself; it cannot use the
  ultrawide). Depth is supported on this phone. ARCore CPU image 640×480 (sensor orientation 90°, rotated to an upright
  480×640 analysis frame), intrinsics f = 487.6/487.4 px, c = 318.7/241.0; GPU texture 1920×1080; depth map 160×90
  (follows the texture). CPU image → depth map = x unchanged, y × 1.3333 − 0.1667 (the depth map covers only the central
  75% of the image's short side = the middle 75% of the upright frame's width). That mapping came from ARCore's
  `transformCoordinates2d` (NaN until `setDisplayGeometry` was called) and from the two images' intrinsics independently;
  they agree within 0.1%, and depth is withheld on any frame where they don't. Depth/frame timestamp skew 3–43 ms;
  over 200 ms is rejected. Depth needs ARCore to be tracking, which needs texture and some camera motion.
- **Measurement:** target reference point = centre of the locked box; its Z = median depth over the box's central 30%,
  taken only while no hand overlaps the box and held ≤ 5 s. Fingertip Z = an **estimate** from the hand's own geometry
  (MediaPipe world landmarks vs. image landmarks, weak perspective, ARCore intrinsics), because motion-stereo depth on a
  moving fingertip is unreliable (ARCore's value at the tip is logged as `arTipZ` for comparison only). Both are
  unprojected with the same intrinsics into one camera frame (X right, Y down, Z forward, metres) for the 3D distance.
  Hand-size variation makes the estimate ~±15%; an occluded or out-of-frame tip is refused, not guessed.
- **Guidance:** alignment (left/right/up/down) is always the 2D line-of-sight error; distance cues only once lined up:
  "Move closer" (gap > 15 cm), "Almost there. Slowly." (5–15 cm), "Too far. Pull back a little." (past by > 8 cm), zones
  with 3 cm hysteresis. At the target, until validated: "You should be at the bottle. Feel for it, and tap I've got it."
- **Validation still to do on the phone (needed before `DEPTH_REACHED_VALIDATED = true`):** object at a tape-measured
  30–80 cm, fingertip held still at ≥ 5 measured distances; from the `REACH` log lines read `targetZ` and `tipZest`. Pass
  = each within ±5 cm of the tape and the gap within ±5 cm at every position. Until then distance cues are experimental
  and completion stays user-confirmed.

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
- **Depth:** see Camera modes and depth.

## Honest limits (say these out loud in the pitch)

- No centimetre accuracy claim. Distance exists only in depth mode, is an estimate (fingertip ~±15%), and has not yet
  been validated against a tape measure on the phone.
- The detector is EfficientDet-Lite0 (COCO). It can only report the COCO names in its
  embedded labels, and has **no `can` or `laptop bag` class**: a soda can is only found if the model
  happens to call it a bottle or cup. Supported categories: `dataset/categories.json` (kept in sync with
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
