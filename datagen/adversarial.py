"""Adversarial matching fixture: amount alone must not identify the settlement.

WHY THIS EXISTS

On the headline merchant a bank credit equals its settlement net almost exactly,
so |amount delta| separates positives from negatives on its own. The scorer
reports AUC 1.0000 over 122 candidate pairs, which measures the difficulty of
the data, not the intelligence of the model. Any claim that the learned scorer
earns its place is unsupported by that dataset.

This fixture removes the shortcut. Every contended bank credit has TWO
settlements in its window whose nets are identical to the paise, so the amount
feature carries exactly zero information about which one is correct. The only
thing that separates them is identity evidence in the narration.

THREE CONTENTION SHAPES

  clean_tie   both candidates delta=0; the true one's UTR appears verbatim in
              the narration. Amount is uninformative; identity decides.

  repair_tie  both candidates delta=0; the true one's UTR appears CORRUPTED
              (transposed and truncated, the way a real bank feed mangles it),
              so only UTR repair recovers it.

  amount_trap the decoy is the BETTER amount match (delta=0) and the true
              settlement is off by one rupee, because a genuine fee variance
              moved it. Ranking by amount picks the decoy — a false match.
              Only identity evidence gets this right.

The third shape is the one that matters. The first two show amount is useless;
the third shows amount is actively misleading, which is what makes a
feature-aware scorer necessary rather than merely tidy.

Ground truth is written to ground_truth/ and is NEVER read by the engine.
"""
from __future__ import annotations

import csv
import json
import random
from datetime import date, datetime, timedelta
from pathlib import Path

IST = "+05:30"
DECLARED = {date(2025, 10, 21), date(2025, 11, 5), date(2025, 11, 14),
            date(2025, 11, 25), date(2025, 12, 25)}


def _is_holiday(d: date) -> bool:
    if d.weekday() == 6:
        return True
    if d.weekday() == 5 and (d.day - 1) // 7 + 1 in (2, 4):
        return True
    return d in DECLARED


def _bd(d: date, n: int) -> date:
    out = d
    while _is_holiday(out):
        out += timedelta(days=1)
    for _ in range(n):
        out += timedelta(days=1)
        while _is_holiday(out):
            out += timedelta(days=1)
    return out


def _utr(rng: random.Random) -> str:
    return "".join(rng.choice("0123456789") for _ in range(12))


def _corrupt(utr: str, rng: random.Random) -> str:
    """Mangle a UTR past deterministic repair but not past recognition.

    The engine repairs by exact match, unique-prefix truncation and character
    confusion. A transposition EARLY in the string defeats all three: the prefix
    no longer matches, and transposition is not a confusion. What survives is
    fuzzy similarity — jaro-winkler and trigram overlap — which is exactly the
    evidence the learned scorer is supposed to weigh.

    So this is the regime where the scorer has to earn its place: too damaged
    for deterministic identity, too similar to be noise.
    """
    s = list(utr)
    i = rng.randrange(1, 4)                 # early transposition kills the prefix
    s[i], s[i + 1] = s[i + 1], s[i]
    j = rng.randrange(len(utr) - 4, len(utr) - 1)
    s[j], s[j + 1] = s[j + 1], s[j]         # and a second, late
    return "".join(s)[: len(utr) - 2]       # feed truncates too


def build(out_dir: Path, cases: int = 60, seed: int = 20251103,
          merchant_id: str = "mrch_ADVERSA") -> dict:
    rng = random.Random(seed)
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "observable").mkdir(exist_ok=True)
    (out_dir / "config").mkdir(exist_ok=True)
    (out_dir / "ground_truth").mkdir(exist_ok=True)

    payments, orders, settlements, bank = [], [], [], []
    truth = []
    pid = sid = bid = 0
    day = date(2025, 11, 3)
    shapes = ["clean_tie", "repair_tie", "amount_trap"]

    for n in range(cases):
        shape = shapes[n % len(shapes)]
        capture = day
        settle = _bd(capture, 2)

        # Two settlements on one date with IDENTICAL nets. Their payment sets
        # differ, so the netting solver can still partition them — the tie is in
        # the bank->settlement match, which is what this fixture is about.
        net = rng.randrange(20_000, 900_000) * 100        # whole rupees, in paise
        splits = [(0.6, 0.4), (0.7, 0.3)]
        pair = []
        for k in range(2):
            sid += 1
            a, b_ = splits[k]
            amounts = [int(net * a) // 100 * 100, net - int(net * a) // 100 * 100]
            for j, amt in enumerate(amounts):
                pid += 1
                ts = datetime.combine(capture, datetime.min.time()) + timedelta(
                    hours=6, minutes=k * 90, seconds=j * 11)
                payments.append({
                    "payment_id": f"pay_{pid:014d}", "order_id": f"order_{pid:010d}",
                    "amount": f"{amt // 100}.{amt % 100:02d}", "method": "upi",
                    "status": "captured", "created_at_ist": ts.isoformat() + IST})
                orders.append({
                    "order_id": f"order_{pid:010d}", "customer_id": f"cust_{pid % 89:06d}",
                    "amount": f"{amt // 100}.{amt % 100:02d}",
                    "created_at_utc": ts.isoformat() + "+00:00"})
            u = _utr(rng)
            settled_at = datetime.combine(settle, datetime.min.time()) + timedelta(
                hours=18, minutes=k)
            pair.append({"sid": f"setl_ADV_{sid:05d}", "utr": u, "net": net,
                         "settled_at": settled_at})

        # WHICH of the pair is the true one must be random. Fixing it at index 0
        # leaks the answer through ordering: candidates with identical features
        # score identically, a stable sort keeps construction order, and every
        # ranker then "solves" the tie by picking the first — scoring 100% on a
        # graph where amount carries no information at all. That artifact is
        # exactly what this fixture exists to rule out.
        if rng.random() < 0.5:
            pair = [pair[1], pair[0]]
        true_s, decoy_s = pair[0], pair[1]

        # The amount trap: the true settlement is a rupee light because a fee
        # variance moved it, while the decoy matches the credit exactly. Amount
        # now points at the WRONG settlement.
        credit_amount = true_s["net"]
        if shape == "amount_trap":
            true_s["net"] = true_s["net"] - 100     # true settlement is Rs 1 short
            # credit still equals the decoy's net exactly

        for s in pair:
            settlements.append({
                "settlement_id": s["sid"], "utr": s["utr"],
                "gross": f"{s['net'] // 100}.{s['net'] % 100:02d}",
                "fees": "0.00", "gst": "0.00", "tds": "0.00",
                "net": f"{s['net'] // 100}.{s['net'] % 100:02d}",
                "settled_at": s["settled_at"].isoformat() + IST, "instant": "false"})

        # One credit, carrying a DAMAGED reference to the true settlement. The
        # reference is always damaged: a verbatim UTR is matched deterministically
        # by stage 1 and never reaches the scorer, so a fixture that leaves it
        # intact tests the exact-match path and nothing else.
        shown = _corrupt(true_s["utr"], rng)
        bid += 1
        bank.append({
            "bank_txn_id": f"bnk_{bid:012d}", "value_date": settle.isoformat(),
            "amount": f"{credit_amount // 100}.{credit_amount % 100:02d}",
            "narration": f"NEFT/{shown}/PAYOUT/ADVERSARIAL BENCH PVT LTD"})
        truth.append({"bank_txn_id": f"bnk_{bid:012d}", "settlement_id": true_s["sid"],
                      "decoy_settlement_id": decoy_s["sid"], "shape": shape,
                      "credit_paise": credit_amount, "true_net_paise": true_s["net"],
                      "decoy_net_paise": decoy_s["net"]})

        # The decoy is deliberately NOT paid in this window. Giving it its own
        # credit would let stage 1 match it on its verbatim UTR, taking it out
        # of the free pool — and the contention the fixture exists to create
        # would quietly evaporate before the scorer ever saw it.

        day = _bd(day, 3)

    def write(name: str, rows: list[dict], fields: list[str], sub="observable") -> None:
        with (out_dir / sub / name).open("w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=fields)
            w.writeheader()
            w.writerows(rows)

    write("payments.csv", payments,
          ["payment_id", "order_id", "amount", "method", "status", "created_at_ist"])
    write("orders.csv", orders, ["order_id", "customer_id", "amount", "created_at_utc"])
    write("settlements.csv", settlements,
          ["settlement_id", "utr", "gross", "fees", "gst", "tds", "net", "settled_at", "instant"])
    write("bank_statement.csv", bank, ["bank_txn_id", "value_date", "amount", "narration"])
    write("refunds.csv", [], ["refund_id", "payment_id", "amount", "created_at_ist", "netted_flag"])
    write("chargebacks.csv", [],
          ["dispute_id", "payment_id", "amount", "fee", "raised_at_ist", "reversed_at_ist"])
    write("reserve_ledger.csv", [],
          ["reserve_id", "amount", "held_on", "release_date", "released_at_ist", "netted_flag"])

    # Hidden from the engine. Read only by the ablation harness.
    write("ground_truth_bank_links.csv", truth,
          ["bank_txn_id", "settlement_id", "decoy_settlement_id", "shape",
           "credit_paise", "true_net_paise", "decoy_net_paise"], sub="ground_truth")

    (out_dir / "config" / "rate_card_published.json").write_text(json.dumps({
        "merchant_id": merchant_id, "name": "Adversarial Matching Bench",
        "gst_rate_pct": "0.0", "tds_rate_pct": "0.0", "reserve_rate_pct": "0.0",
        "reserve_methods": [], "reserve_hold_days": 90,
        "instant_surcharge_pct": "0.0", "dispute_fee": "0.00", "tolerance_paise": 100,
        "methods": {m: [{"effective_from": "2025-11-01", "pct": "0.00", "flat_paise": 0}]
                    for m in ("upi", "card", "netbanking", "wallet")},
    }, indent=2), encoding="utf-8")

    return {"cases": cases, "payments": len(payments), "settlements": len(settlements),
            "bank_credits": len(bank), "ground_truth_links": len(truth)}


if __name__ == "__main__":
    import sys
    target = Path(sys.argv[1] if len(sys.argv) > 1 else "data/adversarial")
    stats = build(target)
    print(f"adversarial matching fixture -> {target}")
    for k, v in stats.items():
        print(f"  {k}: {v}")
