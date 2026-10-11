# REACH — smartphone spatial assistant (hackathon MVP)

Chest-mounted Samsung Galaxy A15 5G guides a visually impaired user's hand to a
requested beverage using voice → Claude intent → local detection → MediaPipe
hand tracking → deterministic spatial guidance → TTS.

**Claude is called once per utterance. The real-time loop is 100% on-device.**

```
Voice → Claude (backend proxy, once) → target locked
Camera frame → EfficientDet candidates + HSV beverage color
            → MediaPipe index fingertip
            → SpatialEngine (hysteresis + throttle)
            → TTS: "Move right." / "Reach forward slowly." / "Target reached."
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
3. **App**: open `android/` in Android Studio. Edit `BACKEND_URL` in
   `app/build.gradle.kts` to `http://<laptop-ip>:8000`. Build → Run on the A15.
   Grant camera + mic permissions.

## Milestone test plan (run in this order)

| Milestone | Test on A15 | Success | Fallback |
|---|---|---|---|
| **V0 camera** | Launch app | Live rear preview, no stutter after 2 min | Lower analysis res to 480×360 in `CameraXSource` |
| **V1 hand** | Hold hand ~40 cm in front | Cyan dot rides your index fingertip | Increase lighting; drop `MinHandDetectionConfidence` to 0.3 |
| **V2 grounding** | 4 cans on a plain table, ~50–80 cm | Yellow boxes labeled coke/pepsi/sprite/monster | Tune `ColorClassifier` thresholds live; raise cans on a box for a clean background |
| **V3 depth** | Press DEPTH TEST button | "Depth API supported: true" + plausible mm at center | If false/fails: ship Level 1 (2D). Demo unaffected. |
| **V4 spatial** | Lock target (below), move hand around | Cues change L/R/U/D without chatter | Widen `ENTER_X/EXIT_X` gap |
| **V5 Claude** | "Find my Coke" with backend up | TTS: "Found the coke, NN percent sure." Two red cans → clarification question | Kill backend → app auto-switches to offline keyword mode |
| **V6 voice** | Tap big button, speak | Transcript appears top of screen | Type-free demo: keep offline mode phrases exact ("find my coke", "guide me") |

## Demo script (2 min)

1. Cans on table: Coke, Pepsi, Sprite, Monster. Phone chest-mounted, ~60 cm away.
2. Tap **TAP + SPEAK** → "**Find my Coke.**"
   → "Found the coke, 90 percent sure. Say guide me when ready."
3. Tap → "**Guide me.**" → move hand in from the side.
   → "Move right." … "Move right." … "Reach forward slowly." … "Target reached."
4. **Trust moment** (judges love this): place Coke and a second red can →
   "Find my Coke" → Claude asks "I see two similar red cans — the one on your
   left?" This is the differentiator vs. describe-only tools.
5. **Resilience moment**: kill the backend, repeat step 2 →
   "Offline mode. Targeting the coke." Guidance still works.

## Graceful degradation (built in)

- ARCore depth fails → 2D guidance (hand-in-box + hand-size-growth proximity proxy).
- Claude/backend/internet fails → offline keyword target selection; the guidance
  loop never touches the network.
- Detector misses → target box held briefly, then "I lost tracking. Hold still."
- Speech recognition fails → RESET + retry; exact phrases work offline.

## Honest limits (say these out loud in the pitch)

- No centimeter accuracy claim; proximity is a heuristic in Level 1.
- HSV color classing is demo-curated for 4 beverages; open-vocabulary grounding
  slots in behind `ObjectGrounder` later (that's why the interface exists).
- Not a safety/medical device; final contact is performed by the user.

## Security

- API key lives only in the backend shell env. `.gitignore` blocks `.env`.
- The APK contains only a LAN backend URL.
