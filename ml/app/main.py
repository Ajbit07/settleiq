"""SettleIQ ML service.

Two jobs, deliberately narrow:

  /parse-narration   rule-based bank-narration parsing with CHARACTER SPANS,
                     so every extracted field can be traced to the substring it
                     came from.
  /score             calibrated pair probabilities from the pinned model.

What this service does NOT do is decide anything. It returns numbers; the Java
engine decides. It is also not in the reconciliation hot path -- the engine
loads the exported model and scores inline, so a run is reproducible without
this service being up. This exists for interactive use, retraining and
inspection.
"""
import json
import math
import os
import re
from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

MODEL_PATH = Path(os.environ.get("MODEL_PATH", "/app/model/model.json"))
VERSION = os.environ.get("SERVICE_VERSION", "1.0.0")

app = FastAPI(
    title="SettleIQ ML service",
    version=VERSION,
    description="Narration parsing and calibrated pair scoring. Advisory only: "
                "no output of this service becomes a number in the ledger.",
)

_model: dict[str, Any] | None = None


def model() -> dict[str, Any]:
    global _model
    if _model is None:
        if not MODEL_PATH.exists():
            raise HTTPException(503, f"model artifact not present at {MODEL_PATH}")
        _model = json.loads(MODEL_PATH.read_text(encoding="utf-8"))
    return _model


# ------------------------------------------------------------------ health
@app.get("/health/live")
def live() -> dict[str, str]:
    return {"status": "UP"}


@app.get("/health/ready")
def ready() -> dict[str, Any]:
    ok = MODEL_PATH.exists()
    return {"status": "UP" if ok else "DOWN", "model_present": ok,
            "model_path": str(MODEL_PATH)}


@app.get("/model")
def model_info() -> dict[str, Any]:
    m = model()
    return {"version": m.get("version"), "trees": len(m.get("trees", [])),
            "features": m.get("features", []),
            "calibration_points": len(m.get("isotonic", {}).get("x", []))}


# ------------------------------------------------------- narration parsing
UTR_PATTERNS = [
    ("neft_rtgs_strict", re.compile(r"\b([A-Z]{4}[NR]\d{1,2}\d{8}\d{4,8})\b")),
    ("neft_rtgs_despaced", re.compile(r"([A-Z]{4}\s?[NR]\s?\d\s?\d{6,18})")),
    ("imps_upi_12digit", re.compile(r"\b(\d{12})\b")),
    ("numeric_long", re.compile(r"\b(\d{9,18})\b")),
    ("alnum_block", re.compile(r"\b([A-Z0-9]{14,22})\b")),
    ("mixed_numeric_damaged", re.compile(r"\b([A-Z0-9]{10,13})\b")),
]
REF_PATTERN = re.compile(r"\b((?:rfnd|disp|pay|setl|rsrv)_[0-9a-zA-Z]+)\b", re.I)
RAILS = {"NEFT": "neft", "IMPS": "imps", "RTGS": "rtgs", "UPI": "upi_payout", "CMS": "corp"}


class NarrationRequest(BaseModel):
    narration: str = Field(min_length=1, max_length=2000)


@app.post("/parse-narration")
def parse_narration(req: NarrationRequest) -> dict[str, Any]:
    """Every field carries the span that produced it. A claim without a span is
    not returned, because an auditor cannot check it."""
    canonical = re.sub(r"\s+", " ", req.narration.upper()).strip()
    rail = next((v for k, v in RAILS.items() if canonical.startswith(k)), None)

    spans, seen = [], set()
    for rule, pat in UTR_PATTERNS:
        for m in pat.finditer(canonical):
            val = m.group(1).replace(" ", "")
            if rule == "mixed_numeric_damaged":
                digits = sum(c.isdigit() for c in val)
                if digits < 8 or digits == len(val):
                    continue
            if val in seen:
                continue
            seen.add(val)
            spans.append({"field": "utr", "value": val, "rule": rule,
                          "start": m.start(1), "end": m.end(1)})

    refs = [{"field": "reference", "value": m.group(1).lower(), "rule": "entity_token",
             "start": m.start(1), "end": m.end(1)}
            for m in REF_PATTERN.finditer(req.narration)]

    return {"canonical": canonical, "rail": rail,
            "utr_candidates": [s["value"] for s in spans],
            "reference_tokens": [r["value"] for r in refs],
            "provenance": spans + refs}


# ---------------------------------------------------------------- scoring
def _sigmoid(z: float) -> float:
    if z >= 0:
        return 1.0 / (1.0 + math.exp(-z))
    e = math.exp(z)
    return e / (1.0 + e)


def _tree_eval(t: dict[str, Any], x: list[float]) -> float:
    n = 0
    while t["left"][n] >= 0:
        n = int(t["left"][n]) if x[int(t["feature"][n])] <= t["threshold"][n] \
            else int(t["right"][n])
    return t["value"][n]


def _isotonic(m: dict[str, Any], p: float) -> float:
    xs, ys = m["isotonic"]["x"], m["isotonic"]["y"]
    if not xs:
        return p
    if p <= xs[0]:
        return ys[0]
    if p >= xs[-1]:
        return ys[-1]
    lo, hi = 0, len(xs) - 1
    while hi - lo > 1:
        mid = (lo + hi) // 2
        if xs[mid] <= p:
            lo = mid
        else:
            hi = mid
    t = (p - xs[lo]) / max(xs[hi] - xs[lo], 1e-12)
    return ys[lo] + t * (ys[hi] - ys[lo])


class ScoreRequest(BaseModel):
    features: list[list[float]] = Field(min_length=1, max_length=5000)


@app.post("/score")
def score(req: ScoreRequest) -> dict[str, Any]:
    m = model()
    n_features = len(m["features"])
    out = []
    for row in req.features:
        if len(row) != n_features:
            raise HTTPException(422, f"expected {n_features} features, got {len(row)}")
        raw = m["bias"] + sum(_tree_eval(t, row) for t in m["trees"])
        out.append(round(_isotonic(m, _sigmoid(raw)), 6))
    return {"model_version": m["version"], "probabilities": out}
