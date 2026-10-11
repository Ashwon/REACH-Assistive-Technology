"""Run: cd backend && python3 -m pytest -q   (no network, no API key needed)"""
from types import SimpleNamespace

from fastapi.testclient import TestClient

import server

c = TestClient(server.app)
BODY = {"utterance": "find my coke",
        "candidates": [{"id": 0, "label": "bottle", "beverage_guess": "coke", "cx": 0.3, "cy": 0.5, "score": 0.9}]}


def fake_claude(text):
    reply = SimpleNamespace(content=[SimpleNamespace(type="text", text=text)])
    return SimpleNamespace(create=lambda **kw: reply)


def test_health():
    assert c.get("/health").json()["ok"] is True


def test_interpret_success_strips_code_fence(monkeypatch):
    raw = '```json\n{"intent":"REACH_OBJECT","target":"coke","candidate_id":0,"confidence":0.9,' \
          '"needs_clarification":false,"question":"ok"}\n```'
    monkeypatch.setattr(server.client, "messages", fake_claude(raw))
    r = c.post("/interpret", json=BODY)
    assert r.status_code == 200 and r.json()["candidate_id"] == 0 and r.json()["target"] == "coke"


def test_interpret_failure_is_502_without_leaking_error(monkeypatch):
    def boom(**kw): raise RuntimeError("secret-ish detail")
    monkeypatch.setattr(server.client, "messages", SimpleNamespace(create=boom))
    r = c.post("/interpret", json=BODY)
    assert r.status_code == 502 and "secret" not in r.text


def test_interpret_rejects_oversized_input():
    assert c.post("/interpret", json={**BODY, "candidates": BODY["candidates"] * 9}).status_code == 422
