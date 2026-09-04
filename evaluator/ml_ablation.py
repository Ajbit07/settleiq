"""Feature ablation: does the learned scorer actually change decisions?

THE QUESTION THIS ANSWERS

"AUC 1.0000" on the headline merchant proves the data is separable, not that the
model is doing work. The honest test is narrower and harder: on a candidate
graph where amount carries no information, does adding identity evidence change
which settlement gets picked — and does it change it to the RIGHT one?

So this trains the SAME GBDT four times on four feature subsets, ranks each
bank credit's candidates with each model, and compares the top-1 choice against
ground truth the engine never sees.

  amount        amount delta, sign, tolerance, relative delta
  amount+time   + banking-day distance and same-day
  amount+ident  + UTR exact/repair/confidence, trigram, jaro-winkler
  full          all sixteen features

WHAT IS REPORTED, AND WHY THESE

top-1 accuracy is the decision metric: whichever candidate the scorer ranks
first is the one the pipeline would act on. AUC is reported too but is the
weaker claim — a model can rank pairs well in aggregate and still lose the head
of the list, which is the only part that posts money.

`decisions changed` counts credits where a richer subset picks a different
settlement than amount alone. That is the number the phrase "ML is necessary"
actually rests on. If it is zero, the honest conclusion is that ML is not
necessary here, and this script prints exactly that.

Train/test is split by BANK CREDIT, not by candidate pair, so no credit's decoy
can leak into training while its true pair is scored. Seeded and deterministic.
"""
from __future__ import annotations

import csv
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "mlservice"))
from train import train_gbdt, raw_score, sigmoid, auc  # noqa: E402

# Index into Features.NAMES, which is the contract between Java and Python.
NAMES = [
    "abs_amount_delta_log", "signed_delta_sign", "delta_within_tolerance",
    "delta_rel_to_net", "banking_day_delta", "abs_banking_day_delta", "same_day",
    "utr_exact", "utr_repaired", "utr_repair_confidence", "token_overlap",
    "jaro_winkler", "narration_has_any_utr", "settlement_utr_present",
    "is_instant", "amount_rank_gap",
]
SUBSETS = {
    "amount":       [0, 1, 2, 3],
    "amount+time":  [0, 1, 2, 3, 4, 5, 6],
    "amount+ident": [0, 1, 2, 3, 7, 8, 9, 10, 11, 12, 13],
    "full":         list(range(16)),
}


def load_candidates(path: Path):
    rows = []
    with path.open(encoding="utf-8") as f:
        for r in csv.DictReader(f):
            rows.append({
                "bank": r["bank_txn_id"], "settlement": r["settlement_id"],
                "x": [float(r["f_" + n]) for n in NAMES],
            })
    return rows


def load_truth(path: Path):
    with path.open(encoding="utf-8") as f:
        return {r["bank_txn_id"]: r for r in csv.DictReader(f)}


def split_by_credit(banks, holdout=0.4, seed=11):
    """Split on the CREDIT so a decoy never trains against its own true pair."""
    import random
    rng = random.Random(seed)
    ordered = sorted(banks)
    rng.shuffle(ordered)
    cut = int(len(ordered) * (1 - holdout))
    return set(ordered[:cut]), set(ordered[cut:])


def run(cand_path: Path, truth_path: Path, out_path: Path | None = None):
    cands = load_candidates(cand_path)
    truth = load_truth(truth_path)

    by_bank: dict[str, list] = {}
    for c in cands:
        if c["bank"] in truth:
            by_bank.setdefault(c["bank"], []).append(c)
    # A credit with one candidate has nothing to rank; it tests nothing.
    contended = {b: v for b, v in by_bank.items() if len(v) > 1}

    train_b, test_b = split_by_credit(list(contended))
    print(f"candidate graphs        {len(contended)} contended "
          f"({len(by_bank) - len(contended)} single-candidate, excluded)")
    print(f"candidate pairs         {sum(len(v) for v in contended.values())}")
    print(f"train / held-out        {len(train_b)} / {len(test_b)} credits\n")
    if not test_b:
        print("no held-out credits; nothing to report")
        return {}

    def label(c):
        return 1 if truth[c["bank"]]["settlement_id"] == c["settlement"] else 0

    # Belt and braces against the same leak from the other side: shuffle each
    # credit's candidate list with a fixed seed so a score tie can never be
    # resolved by the order the engine happened to emit them in.
    import random as _r
    _shuf = _r.Random(4242)
    for _b in contended:
        _shuf.shuffle(contended[_b])

    results, picks = {}, {}
    for name, idx in SUBSETS.items():
        Xtr = [[c["x"][i] for i in idx] for b in train_b for c in contended[b]]
        ytr = [label(c) for b in train_b for c in contended[b]]
        base, trees, lr = train_gbdt(Xtr, ytr, seed=7)

        correct = wrong = 0
        probs, labels, chosen = [], [], {}
        for b in sorted(test_b):
            scored = []
            for c in contended[b]:
                p = sigmoid(raw_score(base, trees, lr, [c["x"][i] for i in idx]))
                scored.append((p, c))
                probs.append(p)
                labels.append(label(c))
            scored.sort(key=lambda t: -t[0])
            top = scored[0][1]
            chosen[b] = top["settlement"]
            if truth[b]["settlement_id"] == top["settlement"]:
                correct += 1
            else:
                wrong += 1
        picks[name] = chosen
        n = correct + wrong
        results[name] = {
            "features": len(idx), "top1_correct": correct, "top1_wrong": wrong,
            "top1_accuracy": round(100.0 * correct / n, 2) if n else 0.0,
            "auc": round(auc(probs, labels), 4),
        }

    base_picks = picks["amount"]
    for name in SUBSETS:
        changed = sum(1 for b, s in picks[name].items() if base_picks.get(b) != s)
        fixed = sum(1 for b, s in picks[name].items()
                    if base_picks.get(b) != s and truth[b]["settlement_id"] == s)
        broke = sum(1 for b, s in picks[name].items()
                    if base_picks.get(b) != s and truth[b]["settlement_id"] != s
                    and truth[b]["settlement_id"] == base_picks.get(b))
        results[name].update({"decisions_changed_vs_amount": changed,
                              "changed_to_correct": fixed, "changed_to_wrong": broke})

    hdr = f"{'subset':<14}{'feat':>5}{'top-1 correct':>15}{'accuracy':>10}{'AUC':>8}{'changed':>9}{'fixed':>7}{'broke':>7}"
    print(hdr)
    print("-" * len(hdr))
    for name, r in results.items():
        print(f"{name:<14}{r['features']:>5}{r['top1_correct']:>10}/{r['top1_correct']+r['top1_wrong']:<4}"
              f"{r['top1_accuracy']:>9.2f}%{r['auc']:>8.4f}"
              f"{r['decisions_changed_vs_amount']:>9}{r['changed_to_correct']:>7}{r['changed_to_wrong']:>7}")

    # Per-shape, because the amount trap is the case that matters and averaging
    # it into the others hides whether it was solved.
    print("\nby contention shape (held-out credits)")
    shapes = sorted({truth[b]["shape"] for b in test_b})
    print(f"  {'shape':<14}{'n':>4}" + "".join(f"{s:>15}" for s in SUBSETS))
    for sh in shapes:
        bs = [b for b in test_b if truth[b]["shape"] == sh]
        line = f"  {sh:<14}{len(bs):>4}"
        for name in SUBSETS:
            ok = sum(1 for b in bs if picks[name][b] == truth[b]["settlement_id"])
            line += f"{ok:>10}/{len(bs):<4}"
        print(line)

    verdict = _verdict(results)
    print(f"\nVERDICT  {verdict}")
    payload = {"subsets": results, "verdict": verdict,
               "contended_graphs": len(contended), "held_out_credits": len(test_b)}
    if out_path:
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
        print(f"written  {out_path}")
    return payload


def _verdict(results: dict) -> str:
    """State plainly whether the extra features earned their place."""
    amt, full = results["amount"], results["full"]
    gain = full["top1_accuracy"] - amt["top1_accuracy"]
    changed = full["decisions_changed_vs_amount"]
    if changed == 0:
        return ("the full scorer picks the SAME settlement as amount alone on every held-out "
                "credit. On this data the extra features change no decision and ML is not "
                "necessary. Reported as measured.")
    if gain <= 0:
        return (f"the full scorer changes {changed} decision(s) but does not improve top-1 "
                f"accuracy ({amt['top1_accuracy']:.2f}% -> {full['top1_accuracy']:.2f}%). "
                "The extra features are not earning their place on this data.")
    return (f"the full scorer changes {changed} decision(s) against amount alone, "
            f"{full['changed_to_correct']} of them to the correct settlement, lifting top-1 "
            f"accuracy {amt['top1_accuracy']:.2f}% -> {full['top1_accuracy']:.2f}%.")


if __name__ == "__main__":
    cand = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("reports/adversarial/candidates.csv")
    tru = Path(sys.argv[2]) if len(sys.argv) > 2 else Path(
        "data/adversarial/ground_truth/ground_truth_bank_links.csv")
    out = Path(sys.argv[3]) if len(sys.argv) > 3 else Path("reports/metrics/ml_ablation.json")
    print("=" * 78)
    print("  ML FEATURE ABLATION — adversarial candidate graph")
    print("=" * 78)
    run(cand, tru, out)
