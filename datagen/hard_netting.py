"""Hard-netting fixtures: date-groups the ordered-block hypothesis cannot solve.

WHY THIS EXISTS

On the headline merchant, H1 (a settlement cycle is a contiguous window over an
ordering key) resolves all 25 date-groups, so the subset-sum fallback never runs
end to end. The solver's safety properties were therefore only ever exercised by
unit tests calling it directly. That is a real gap: a solver that is correct in
isolation and unreachable in practice proves nothing about the pipeline.

These fixtures are constructed so that, for every ordering key the engine is
willing to hypothesise, NO prefix of the pool sums to a batch gross. H1 is
refuted on its own terms and the partition problem reaches the DP.

Nothing here is a mock. They are ordinary CSVs in the ordinary layout, read by
the ordinary loader and reconciled by the ordinary pipeline. What makes them
fixtures rather than a demo path is only that the amounts were chosen rather
than sampled.

Fees are zero throughout, so gross == net and the bank credit equals the batch
gross exactly. That is deliberate: it removes decomposition from the picture so
a residue can only come from the partition, which is the thing under test.
"""
from __future__ import annotations

import csv
import json
from datetime import date, datetime, timedelta
from pathlib import Path

IST = "+05:30"


# Mirrors BankingCalendar exactly. Getting this wrong does not fail loudly: the
# settlement date simply lands where no payment pool exists and the scenario
# quietly reconciles nothing. It already did, on the two declared holidays below.
DECLARED = {date(2025, 10, 21), date(2025, 11, 5), date(2025, 11, 14),
            date(2025, 11, 25), date(2025, 12, 25)}


def _is_holiday(d: date) -> bool:
    if d.weekday() == 6:                       # Sunday
        return True
    if d.weekday() == 5 and (d.day - 1) // 7 + 1 in (2, 4):
        return True                            # 2nd and 4th Saturday only
    return d in DECLARED


def _wd(d: date, n: int) -> date:
    """n banking days after d, matching BankingCalendar.addBankingDays."""
    out = d
    while _is_holiday(out):
        out += timedelta(days=1)
    for _ in range(n):
        out += timedelta(days=1)
        while _is_holiday(out):
            out += timedelta(days=1)
    return out


class Scenario:
    """One settlement date, its batches, and the payments that fund them.

    `amounts` are in rupees and are listed IN CAPTURE ORDER — the order the
    engine will sort them into when it tries the ordered-block hypothesis.
    `batches` are the batch grosses on that date.
    """

    def __init__(self, key: str, note: str, amounts: list[int], batches: list[int],
                 expect: str, pool_closes: bool = True):
        self.key, self.note = key, note
        self.amounts, self.batches, self.expect = amounts, batches, expect
        # A partial-settlement scenario deliberately does NOT close: more was
        # captured than settled. H1 rejects it on totals before it ever tries an
        # ordering, which is the correct behaviour and the thing being tested.
        self.pool_closes = pool_closes

    def check_h1_is_refuted(self) -> None:
        """Fail loudly if this scenario would not actually reach the DP.

        Without this a fixture can silently degrade into an easy case and the
        benchmark quietly stops testing what it claims to test. This assertion
        has already caught one such mistake.
        """
        if not self.pool_closes:
            assert sum(self.amounts) != sum(self.batches), (
                f"{self.key}: declared as partial but the pool closes exactly")
            return
        assert sum(self.amounts) == sum(self.batches), (
            f"{self.key}: pool {sum(self.amounts)} != batches {sum(self.batches)}; "
            "H1 rejects on totals before it ever tries an ordering")
        run, prefixes = 0, set()
        for a in self.amounts:
            run += a
            prefixes.add(run)
        for b in self.batches:
            assert b not in prefixes, (
                f"{self.key}: prefix sums {sorted(prefixes)} contain batch gross {b}, "
                "so H1 would solve this and the DP would never run")


def scenarios() -> list[Scenario]:
    return [
        # ── SCENARIO 1 — hard but provable ───────────────────────────────────
        # Prefixes are 100/350/650/1000; neither 450 nor 550 appears, so H1 is
        # refuted. Exactly one subset makes 450 ({100,350}), so the DP can
        # prove the partition and the batch reconciles.
        Scenario("unique", "H1 refuted, exactly one partition exists",
                 [100, 250, 300, 350], [450, 550], "H2 solves, unique"),

        # ── SCENARIO 4 — ambiguous partition ─────────────────────────────────
        # Prefixes are 100/300/600/1000; 500 never appears, so H1 is refuted.
        # But {100,400} and {200,300} BOTH make 500. Two arithmetically perfect
        # explanations, nothing to choose between them: the solver must refuse
        # rather than take the first one it finds.
        Scenario("ambiguous", "two different partitions both sum exactly",
                 [100, 200, 300, 400], [500, 500], "refuse: AMBIGUOUS_MULTIPLE_PARTITIONS"),

        # ── SCENARIO 3 — partial settlement ──────────────────────────────────
        # The pool exceeds what settled: 1,000 captured against 600 of batches.
        # H1 rejects on totals. The engine must show the settled amount and
        # leave the rest outstanding rather than forcing the pool to close.
        Scenario("partial", "only part of the captured pool has settled",
                 [100, 250, 300, 350], [220, 380], "partial: 400 captured but unsettled",
                 pool_closes=False),

        # ── SCENARIO 5 — verified search bound ───────────────────────────────
        # 401 payments exceeds the 400-item bound the DP is verified for. The
        # engine must say the search space is larger than the bound, which is a
        # different answer from "no combination exists".
        # Prefixes are the multiples of 7 up to 2,800, plus 2,811. Neither 1,402
        # nor 1,409 is a multiple of 7, so H1 is refuted and the DP is reached —
        # where 401 items immediately exceed the 400-item bound.
        Scenario("bound", "pool exceeds the verified 400-item DP bound",
                 [7] * 400 + [11], [1402, 1409], "refuse: BOUND_ITEMS"),
    ]


def build(out_dir: Path, merchant_id: str = "mrch_HARDNET") -> dict:
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "observable").mkdir(exist_ok=True)
    (out_dir / "config").mkdir(exist_ok=True)

    payments, settlements, bank, orders = [], [], [], []
    manifest = []
    pid = sid = bid = 0
    # A Monday, so T+2 banking days never crosses a weekend inside a scenario.
    day = date(2025, 11, 3)

    for sc in scenarios():
        sc.check_h1_is_refuted()
        capture = day
        settle = _wd(capture, 2)

        # Capture order IS the order the engine will sort by, so the times are
        # assigned in list order and spaced far enough apart to be unambiguous.
        for i, rupees in enumerate(sc.amounts):
            pid += 1
            ts = datetime.combine(capture, datetime.min.time()) + timedelta(
                hours=6, seconds=i * 7)
            payments.append({
                "payment_id": f"pay_{pid:014d}",
                "order_id": f"order_{pid:010d}",
                "amount": f"{rupees}.00",
                "method": "upi",
                "status": "captured",
                "created_at_ist": ts.isoformat() + IST,
            })
            orders.append({
                "order_id": f"order_{pid:010d}",
                "customer_id": f"cust_{pid % 97:06d}",
                "amount": f"{rupees}.00",
                "created_at_utc": ts.isoformat() + "+00:00",
            })

        for j, gross in enumerate(sc.batches):
            sid += 1
            bid += 1
            utr = f"UTR{sc.key.upper()[:4]}{sid:08d}"
            settled_at = datetime.combine(settle, datetime.min.time()) + timedelta(
                hours=18, minutes=j)
            # Zero fees: net == gross, so the credit equals the batch exactly.
            settlements.append({
                "settlement_id": f"setl_HARDNET_{sid:04d}",
                "utr": utr,
                "gross": f"{gross}.00", "fees": "0.00", "gst": "0.00",
                "tds": "0.00", "net": f"{gross}.00",
                "settled_at": settled_at.isoformat() + IST,
                "instant": "false",
            })
            bank.append({
                "bank_txn_id": f"bnk_{bid:012d}",
                "value_date": settle.isoformat(),
                "amount": f"{gross}.00",
                "narration": f"NEFT/{utr}/SETTLEMENT/HARD NETTING FIXTURE",
            })

        manifest.append({
            "scenario": sc.key, "note": sc.note, "settle_date": settle.isoformat(),
            "pool_size": len(sc.amounts), "pool_total": sum(sc.amounts),
            "batches": sc.batches, "expected": sc.expect,
        })
        day = _wd(day, 5)

    def write(name: str, rows: list[dict], fields: list[str]) -> None:
        with (out_dir / "observable" / name).open("w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=fields)
            w.writeheader()
            w.writerows(rows)

    write("payments.csv", payments,
          ["payment_id", "order_id", "amount", "method", "status", "created_at_ist"])
    write("orders.csv", orders, ["order_id", "customer_id", "amount", "created_at_utc"])
    write("settlements.csv", settlements,
          ["settlement_id", "utr", "gross", "fees", "gst", "tds", "net", "settled_at", "instant"])
    write("bank_statement.csv", bank, ["bank_txn_id", "value_date", "amount", "narration"])
    # No adjustments: this fixture isolates the partition problem.
    write("refunds.csv", [], ["refund_id", "payment_id", "amount", "created_at_ist", "netted_flag"])
    write("chargebacks.csv", [],
          ["dispute_id", "payment_id", "amount", "fee", "raised_at_ist", "reversed_at_ist"])
    write("reserve_ledger.csv", [],
          ["reserve_id", "amount", "held_on", "release_date", "released_at_ist", "netted_flag"])

    (out_dir / "config" / "rate_card_published.json").write_text(json.dumps({
        "merchant_id": merchant_id,
        "name": "Hard Netting Fixture",
        "gst_rate_pct": "0.0", "tds_rate_pct": "0.0", "reserve_rate_pct": "0.0",
        "reserve_methods": [], "reserve_hold_days": 90,
        "instant_surcharge_pct": "0.0", "dispute_fee": "0.00", "tolerance_paise": 100,
        "methods": {m: [{"effective_from": "2025-11-01", "pct": "0.00", "flat_paise": 0}]
                    for m in ("upi", "card", "netbanking", "wallet")},
    }, indent=2), encoding="utf-8")

    (out_dir / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    return {"payments": len(payments), "settlements": len(settlements),
            "scenarios": len(manifest)}


if __name__ == "__main__":
    import sys
    target = Path(sys.argv[1] if len(sys.argv) > 1 else "data/hard_netting")
    stats = build(target)
    print(f"hard-netting fixture -> {target}")
    for k, v in stats.items():
        print(f"  {k}: {v}")
    for m in json.loads((target / "manifest.json").read_text(encoding="utf-8")):
        print(f"  {m['scenario']:<10} pool={m['pool_size']:>3} "
              f"total={m['pool_total']:<6} batches={m['batches']}  -> {m['expected']}")
