"""
REACH backend — the ONLY place the Anthropic API key exists.
Run on your laptop, phone connects over the same Wi-Fi.

  export ANTHROPIC_API_KEY=sk-ant-...
  pip install -r requirements.txt
  uvicorn server:app --host 0.0.0.0 --port 8000

Then set BACKEND_URL in android/app/build.gradle.kts to http://<laptop-LAN-IP>:8000
"""

import json
import logging
import os

import anthropic
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

log = logging.getLogger("reach")

app = FastAPI()
client = anthropic.Anthropic()  # reads ANTHROPIC_API_KEY from env

MODEL = os.environ.get("REACH_MODEL", "claude-sonnet-4-6")

SYSTEM = """You are the target-selection brain of REACH, an assistive app that
guides a visually impaired user's hand to an object. You receive the user's
spoken request plus candidate objects detected in the camera frame (each has an
id, a detector label, a color-based beverage guess, image-center coordinates,
and usually a small thumbnail image).

Respond with ONLY a JSON object, no markdown, in exactly this schema:
{
  "intent": "REACH_OBJECT" | "DESCRIBE" | "UNKNOWN",
  "target": "<short lowercase object name, e.g. coke>",
  "candidate_id": <int, id of the chosen candidate, or -1>,
  "confidence": <float 0..1>,
  "needs_clarification": <bool>,
  "question": "<one short spoken sentence: a clarification question if needed,
                otherwise a brief confirmation>"
}

Rules:
- The user is blind. Keep any spoken text short, concrete, and spatial
  ("the one on your left"), never visual-jargon.
- If two or more candidates plausibly match, set needs_clarification=true and
  ask ONE disambiguating question referencing left/right position.
- If no candidate matches, candidate_id=-1, needs_clarification=true, and say
  you cannot see it and suggest panning slowly.
- NEVER invent confidence. If thumbnails are ambiguous or missing, confidence
  must be low (<0.5) and you must say you are unsure.
- Use the thumbnails to verify the beverage guess; the color heuristic can be
  wrong."""


class CandidateIn(BaseModel):
    id: int
    label: str
    beverage_guess: str
    cx: float
    cy: float
    score: float
    thumb_b64: str | None = Field(None, max_length=60_000)  # ~96x128 JPEG is ~10 KB


class InterpretIn(BaseModel):
    utterance: str = Field(max_length=500)
    candidates: list[CandidateIn] = Field(max_length=8)


@app.get("/health")
def health():
    return {"ok": True, "model": MODEL}


@app.post("/interpret")
def interpret(body: InterpretIn):
    content: list[dict] = []
    lines = [f'User said: "{body.utterance}"', "Candidates:"]
    for c in body.candidates:
        pos = "left" if c.cx < 0.4 else "right" if c.cx > 0.6 else "center"
        lines.append(
            f"- id={c.id} label={c.label} beverage_guess={c.beverage_guess} "
            f"position={pos} (cx={c.cx:.2f}, cy={c.cy:.2f}) det_score={c.score:.2f}"
        )
    if not body.candidates:
        lines.append("(none detected)")
    content.append({"type": "text", "text": "\n".join(lines)})

    for c in body.candidates:
        if c.thumb_b64:
            content.append({"type": "text", "text": f"Thumbnail for candidate id={c.id}:"})
            content.append(
                {
                    "type": "image",
                    "source": {
                        "type": "base64",
                        "media_type": "image/jpeg",
                        "data": c.thumb_b64,
                    },
                }
            )

    try:
        msg = client.messages.create(
            model=MODEL,
            max_tokens=300,
            system=SYSTEM,
            messages=[{"role": "user", "content": content}],
        )
        raw = "".join(b.text for b in msg.content if b.type == "text").strip()
        raw = raw.removeprefix("```json").removeprefix("```").removesuffix("```").strip()
        out = json.loads(raw)
        # Minimal schema hardening so the app never crashes on a weird reply.
        return {
            "intent": out.get("intent", "UNKNOWN"),
            "target": out.get("target", ""),
            "candidate_id": int(out.get("candidate_id", -1)),
            "confidence": float(out.get("confidence", 0.0)),
            "needs_clarification": bool(out.get("needs_clarification", False)),
            "question": out.get("question", ""),
        }
    except Exception:
        # Non-2xx on purpose: the app only falls back to offline keyword mode on HTTP errors.
        log.exception("interpret failed")
        raise HTTPException(status_code=502, detail="reasoning service unavailable")
