"""Ablation: turn each component off for real and measure what breaks.

Every row runs the actual engine with that stage disabled. Nothing here is
estimated. If a component does not earn its place the table says so, and that
is a result worth having rather than one to hide.
"""
import json
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from datagen.money import fmt_inr  # noqa: E402
from evaluator.evaluate import evaluate  # noqa: E402

STEPS = [
    ("deterministic", "deterministic only", "exact UTR match, no repair, no netting"),
    ("repair", "+ UTR repair", "prefix / confusion repair, refuses on ambiguity"),
    ("scorer", "+ pair scorer", "calibrated GBDT over engineered features"),
    ("global", "+ global assignment", "Hungarian, one-to-one, never greedy"),
    ("netting", "+ netting solver", "reconstructs batch membership"),
    ("full", "+ exception agent", "classify, evidence, policy, audit"),
]


def run(merchant, preset, out_root):
    audit = f"{out_root}/audit_{preset}.jsonl"
    if os.path.exists(audit):
        os.remove(audit)
    t0 = time.time()
    r = subprocess.run(
        ["java", "-cp", "engine/out", "com.settleiq.Main",
         "--merchant", merchant, "--preset", preset,
         "--out", out_root, "--audit", audit],
        capture_output=True, text=True, timeout=900)
    wall = time.time() - t0
    if r.returncode != 0:
        print(r.stdout[-2000:])
        print(r.stderr[-2000:])
        raise SystemExit(f"engine failed on preset {preset}")
    return wall, r.stdout


def main():
    merchant = sys.argv[1] if len(sys.argv) > 1 else "data/merchant_a"
    out_root = "reports/ablation"
    os.makedirs(out_root, exist_ok=True)

    rows = []
    for preset, label, note in STEPS:
        wall, stdout = run(merchant, preset, out_root)
        m = evaluate(merchant, f"{out_root}/{preset}", preset)
        s = m.get("summary", {})
        rows.append({
            "preset": preset, "label": label, "note": note,
            "row_f1": m["overall"]["row"][2] * 100,
            "amt_f1": m["overall"]["amt"][2] * 100,
            "links": m["overall"]["tp"] + m["overall"]["fp"],
            "false_match": m["auto_false_match_rate"] * 100,
            "residue": m["residue_abs"],
            "residue_pct": m["residue_pct_gmv"],
            "bank_ok": m["bank_links_correct"],
            "bank_bad": m["bank_links_wrong"],
            "bank_tot": m["bank_links_total"],
            "exc_prec": m["exception_precision"] * 100,
            "missed_amb": m["ambiguous_missed"],
            "auto": s.get("auto_posted", 0),
            "esc": s.get("escalated", 0),
            "ms": s.get("wall_ms", int(wall * 1000)),
        })

    w = ("=" * 118)
    print(w)
    print("  ABLATION -- each row is a real run with that stage switched off")
    print(w)
    hdr = (f"{'configuration':<22}{'bank ok':>9}{'bad':>5}{'links':>8}{'amt F1':>8}"
           f"{'false':>8}{'residue':>14}{'%GMV':>8}{'auto':>6}{'esc':>5}{'ms':>7}")
    print(hdr)
    print("-" * 118)
    for r in rows:
        print(f"{r['label']:<22}{r['bank_ok']:>5}/{r['bank_tot']:<3}{r['bank_bad']:>5}"
              f"{r['links']:>8,}{r['amt_f1']:>8.2f}{r['false_match']:>8.3f}"
              f"{fmt_inr(r['residue']):>14}{r['residue_pct']:>8.3f}"
              f"{r['auto']:>6}{r['esc']:>5}{r['ms']:>7}")
    print("-" * 118)
    print("  bank = settlements correctly linked to their bank credit")
    print("  links = payment->settlement links produced;  false = false-match rate "
          "among auto-postable links")
    print("  residue = unexplained rupees, absolute, summed over every batch")
    print()

    # honest deltas
    print("WHAT EACH COMPONENT BOUGHT")
    for i in range(1, len(rows)):
        a, b = rows[i - 1], rows[i]
        d_bank = b["bank_ok"] - a["bank_ok"]
        d_bad = b["bank_bad"] - a["bank_bad"]
        d_res = a["residue"] - b["residue"]
        d_f1 = b["amt_f1"] - a["amt_f1"]
        verdict = []
        if d_bank:
            verdict.append(f"{d_bank:+d} correct bank links")
        if d_bad:
            verdict.append(f"{d_bad:+d} WRONG bank links")
        if b["auto"] or b["esc"]:
            if not a["auto"] and not a["esc"]:
                verdict.append(f"routes {b['auto']} auto-post / {b['esc']} escalate "
                               f"with an audit trail")
        if abs(d_res) >= 100:
            verdict.append(f"{fmt_inr(-d_res)} residue")
        if abs(d_f1) >= 0.01:
            verdict.append(f"{d_f1:+.2f} amt-F1")
        if not verdict:
            verdict.append("NO MEASURABLE CHANGE on this dataset")
        print(f"  {b['label']:<22} {'; '.join(verdict)}")
    print()

    with open("reports/metrics/ablation.json", "w", encoding="utf-8") as fh:
        json.dump(rows, fh, indent=2)
    print("wrote reports/metrics/ablation.json")


if __name__ == "__main__":
    main()
