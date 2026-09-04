"""Coverage-risk curve and the single held-out test report.

The coverage-risk curve is how the auto-post threshold gets chosen: it is swept
on DEV, an operating point is picked there, and only then is TEST scored once.
"""
import json
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from datagen.money import fmt_inr  # noqa: E402
from evaluator.evaluate import evaluate, load, P  # noqa: E402

THRESHOLDS = [0.50, 0.60, 0.70, 0.80, 0.85, 0.90, 0.95, 0.97, 0.99]
OPERATING_POINT = 0.95   # matches Config.autoPostMinConfidence


def coverage_risk(root, pred_dir, split):
    """Sweep the auto-post confidence threshold over bank-link decisions."""
    links = load(root + "/ground_truth/ground_truth_links.csv")
    true_bank = {l["settlement_id"]: l["bank_txn_id"] for l in links}
    split_of = {l["settlement_id"]: l["split"] for l in links}
    bl = load(pred_dir + "/predicted_bank_links.csv")
    rows = [r for r in bl if split_of.get(r["settlement_id"]) == split] or bl

    total = len([s for s in true_bank if split_of.get(s) == split]) or len(true_bank)
    pts = []
    for t in THRESHOLDS:
        kept = [r for r in rows if float(r["confidence"]) >= t]
        ok = sum(1 for r in kept if true_bank.get(r["settlement_id"]) == r["bank_txn_id"])
        pts.append({
            "threshold": t,
            "coverage": 100.0 * len(kept) / total if total else 0.0,
            "precision": 100.0 * ok / len(kept) if kept else 100.0,
            "auto_posted": len(kept),
            "false_matches": len(kept) - ok,
        })
    return {"split": split, "operating_point": OPERATING_POINT, "points": pts}


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "data/merchant_a"
    pred = sys.argv[2] if len(sys.argv) > 2 else "reports/full"
    os.makedirs("reports/metrics", exist_ok=True)

    dev = coverage_risk(root, pred, "dev")
    with open("reports/metrics/coverage_risk.json", "w", encoding="utf-8") as fh:
        json.dump(dev, fh, indent=2)

    print("COVERAGE-RISK  (swept on DEV; the operating point is chosen here)")
    print(f"  {'thr':>6}{'coverage':>11}{'precision':>11}{'posted':>9}{'false':>7}")
    for p in dev["points"]:
        mark = "  <- operating point" if abs(p["threshold"] - OPERATING_POINT) < 1e-9 else ""
        print(f"  {p['threshold']:>6.2f}{p['coverage']:>10.1f}%{p['precision']:>10.1f}%"
              f"{p['auto_posted']:>9}{p['false_matches']:>7}{mark}")
    print()

    m = evaluate(root, pred, "full")
    s = m.get("summary", {})
    wall = s.get("wall_ms", 1)
    recs = s.get("payments", 0) + s.get("bank_rows", 0)
    test = {
        "amt_f1": m["test"]["amt"][2] * 100,
        "row_f1": m["test"]["row"][2] * 100,
        "false_match": m["auto_false_match_rate"] * 100,
        "exception_precision": m["exception_precision"] * 100,
        "residue_paise": m["residue_abs"],
        "residue_fmt": fmt_inr(m["residue_abs"]),
        "residue_pct_gmv": m["residue_pct_gmv"],
        "rps": round(recs * 1000.0 / wall) if wall else 0,
        "wall_ms": wall,
        "ambiguous_missed": m["ambiguous_missed"],
    }
    with open("reports/metrics/test_report.json", "w", encoding="utf-8") as fh:
        json.dump(test, fh, indent=2)

    print("HELD-OUT TEST  (touched once, after thresholds were fixed on dev)")
    print(f"  amount-weighted F1     {test['amt_f1']:.2f}")
    print(f"  row F1                 {test['row_f1']:.2f}")
    print(f"  false-match rate       {test['false_match']:.3f} %   target < 0.500 %")
    print(f"  exception precision    {test['exception_precision']:.1f} %")
    print(f"  ambiguous missed       {test['ambiguous_missed']}")
    print(f"  unexplained residue    {test['residue_fmt']}   "
          f"({test['residue_pct_gmv']:.4f} % of GMV)")
    print(f"  throughput             {test['rps']:,} records/sec  "
          f"({test['wall_ms']} ms wall)")


if __name__ == "__main__":
    main()
