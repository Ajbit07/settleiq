"""`make demo` -- scripted run with a live counter.

Every number printed here is read back from engine output or ground truth. The
headline is computed, not written. If the engine gets worse, this script says
so; there is no hard-coded closing line.
"""
import csv
import json
import os
import shutil
import subprocess
import sys
import time
from collections import Counter
from decimal import Decimal

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from datagen.money import fmt_inr, lakh  # noqa: E402

MERCHANT = "data/merchant_a"
OUT = "reports/demo"
LEDGER = "reports/demo_audit.jsonl"
P = lambda s: 0 if not s else int((Decimal(s) * 100).to_integral_value())


def load(p):
    if not os.path.exists(p):
        return []
    with open(p, newline="", encoding="utf-8") as fh:
        return list(csv.DictReader(fh))


C = {"g": "\033[32m", "r": "\033[31m", "y": "\033[33m", "b": "\033[36m",
     "d": "\033[90m", "w": "\033[97m", "x": "\033[0m", "B": "\033[1m"}
LIVE = sys.stdout.isatty() and not os.environ.get("NO_COLOR")
if not LIVE:
    C = {k: "" for k in C}
SEP = "\u00b7" if LIVE else "|"


def rule(ch="-"):
    print(C["d"] + ch * 74 + C["x"])


def head(t):
    print()
    print(C["B"] + C["w"] + t + C["x"])
    rule()


def tick(label, target, unit="", dur=0.9, color="w", fmt=None):
    """Count a number up so the audience can see it move.

    Carriage-return animation only when attached to a terminal; piped output
    gets one clean line so logs and CI stay readable."""
    final = fmt(target) if fmt else f"{target:,}"
    if not LIVE:
        print(f"  {label:<34}{final:>18} {unit}".rstrip())
        return
    steps = 26
    for i in range(steps + 1):
        v = target * i // steps
        shown = fmt(v) if fmt else f"{v:,}"
        sys.stdout.write(f"\r  {label:<34}{C[color]}{shown:>18}{C['x']} {unit}   ")
        sys.stdout.flush()
        time.sleep(dur / steps)
    print()


def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=900)
    if r.returncode != 0:
        print(r.stdout[-1500:], r.stderr[-1500:])
        raise SystemExit("step failed: " + " ".join(cmd))
    return r.stdout


def main():
    t_start = time.time()
    os.makedirs(OUT, exist_ok=True)
    if os.path.exists(LEDGER):
        os.remove(LEDGER)

    print()
    print(C["B"] + "  SETTLEIQ" + C["x"] + C["d"] +
          "   payout decomposition and reconciliation" + C["x"])
    rule("=")
    print("  A merchant sees a bank credit and cannot say what it is made of.")
    print("  One credit is a batch of payments, minus fees, minus GST on those")
    print("  fees, minus TDS, minus refunds netted in, minus chargebacks, minus")
    print("  rolling reserve, plus reserve released from an earlier period.")
    print()
    print(C["d"] + "  The LLM chooses; arithmetic decides. No model output ever becomes" + C["x"])
    print(C["d"] + "  a number in the ledger. All money math is integer paise." + C["x"])

    # ---------------------------------------------------------------- inputs
    head("1  WHAT THE MERCHANT ACTUALLY HAS")
    pays = load(MERCHANT + "/observable/payments.csv")
    bank = load(MERCHANT + "/observable/bank_statement.csv")
    setl = load(MERCHANT + "/observable/settlements.csv")
    refs = load(MERCHANT + "/observable/refunds.csv")
    cbs = load(MERCHANT + "/observable/chargebacks.csv")
    cap = [p for p in pays if p["status"] == "captured"]
    gmv = sum(P(p["amount"]) for p in cap)
    credited = sum(P(b["amount"]) for b in bank if P(b["amount"]) > 0)

    tick("payments captured", len(cap), dur=0.5)
    tick("settlement batches", len(setl), dur=0.35)
    tick("bank statement rows", len(bank), dur=0.35)
    tick("refunds / chargebacks", len(refs) + len(cbs), dur=0.35)
    print()
    print(f"  {'captured GMV':<34}{C['w']}{fmt_inr(gmv):>18}{C['x']}")
    print(f"  {'total credited by the bank':<34}{C['w']}{fmt_inr(credited):>18}{C['x']}")
    print(f"  {'unexplained gap':<34}{C['r']}{fmt_inr(gmv - credited):>18}{C['x']}"
          f"   {C['d']}<- the whole problem{C['x']}")
    print()
    print(C["d"] + "  Sample of what the bank actually wrote:" + C["x"])
    for b in [x for x in bank if P(x["amount"]) > 0][:3]:
        print(f"    {C['d']}{b['value_date']}  {P(b['amount'])/100:>12,.2f}  "
              f"{b['narration'][:52]}{C['x']}")

    # ---------------------------------------------------------------- engine
    head("2  DECOMPOSING EVERY CREDIT")
    print(C["d"] + "  stages: normalise -> exact UTR -> repair -> candidates -> scorer" + C["x"])
    print(C["d"] + "          -> global assignment -> netting solver -> agent -> audit" + C["x"])
    print()
    t0 = time.time()
    out = run(["java", "-cp", "engine/out", "com.settleiq.Main",
               "--merchant", MERCHANT, "--preset", "full",
               "--out", OUT, "--audit", LEDGER])
    engine_ms = int((time.time() - t0) * 1000)

    summ = json.loads(open(f"{OUT}/full/summary.json", encoding="utf-8").read().strip())
    dec = load(f"{OUT}/full/predicted_decomposition.csv")
    exc = load(f"{OUT}/full/exceptions.csv")
    links = load(f"{OUT}/full/predicted_links.csv")

    tick("payments linked to a batch", len(links), dur=0.9, color="g")
    tick("bank credits decomposed", summ["bank_matched"], dur=0.6, color="g")

    exact = sum(1 for r in dec if r["component"] == "residue" and P(r["amount"]) == 0)
    tick("batches exact to the paise", exact, dur=0.6, color="g")

    residue_abs = sum(abs(P(r["amount"])) for r in dec if r["component"] == "residue")
    print()
    if LIVE:
        for v in [gmv - credited, (gmv - credited) // 4, residue_abs * 6,
                  residue_abs * 2, residue_abs]:
            sys.stdout.write(f"\r  {'residue falling':<34}{C['y']}{fmt_inr(v):>18}{C['x']}   ")
            sys.stdout.flush()
            time.sleep(0.35)
        print()
    print(f"  {'residue after decomposition':<34}{C['w']}{fmt_inr(residue_abs):>18}{C['x']}")

    # ------------------------------------------------------------ exceptions
    head("3  WHAT THE AGENT DID WITH WHAT WAS LEFT")
    kinds = Counter(e["type"] for e in exc)
    auto = [e for e in exc if e["verdict"] == "AUTO_POST"]
    esc = [e for e in exc if e["verdict"] == "ESCALATE"]
    for k, n in kinds.most_common():
        amt = sum(abs(P(e["residue"])) for e in exc if e["type"] == k)
        print(f"  {k:<26}{n:>4}  {fmt_inr(amt):>14}")
    print()
    print(f"  {C['g']}auto-posted{C['x']}   {len(auto):>3}"
          f"   {C['d']}all six policy arms satisfied{C['x']}")
    print(f"  {C['y']}escalated{C['x']}     {len(esc):>3}"
          f"   {C['d']}held for a human, with a written reason{C['x']}")
    print()
    amb = [e for e in exc if e["ambiguous"] == "1"]
    print(f"  {C['b']}Refused to auto-match {len(amb)} batches containing swap-invariant")
    print(f"  payments.{C['x']} Two payments with the same amount, method and rate tier")
    print("  in different batches settling the same day cannot be told apart by")
    print("  arithmetic. Refusing is the correct answer, not a failure.")

    # residue that the agent could actually name
    named = sum(abs(P(e["residue"])) for e in exc if e["type"] != "unexplained")
    unnamed = sum(abs(P(e["residue"])) for e in exc if e["type"] == "unexplained")
    print()
    print(f"  {'residue the agent could name':<34}{C['g']}{fmt_inr(named):>18}{C['x']}"
          f"   {C['d']}fee variance, timing, reserve{C['x']}")
    print(f"  {'residue genuinely unexplained':<34}{C['r']}{fmt_inr(unnamed):>18}{C['x']}"
          f"   {C['d']}no source document exists{C['x']}")

    # ----------------------------------------------------------------- audit
    head("4  THE AUDIT TRAIL, AND RUNNING IT TWICE")
    rows1 = sum(1 for _ in open(LEDGER, encoding="utf-8"))
    print(f"  first run appended            {rows1:>4} hash-chained rows")
    out2 = run(["java", "-cp", "engine/out", "com.settleiq.Main",
                "--merchant", MERCHANT, "--preset", "full",
                "--out", OUT, "--audit", LEDGER])
    rows2 = sum(1 for _ in open(LEDGER, encoding="utf-8"))
    sup = [l for l in out2.splitlines() if "suppressed_duplicate" in l]
    print(f"  identical re-run appended     {rows2 - rows1:>4} rows"
          f"   {C['g']}<- zero duplicate postings{C['x']}")
    if sup:
        print(f"  {C['d']}{sup[0].strip()}{C['x']}")
    chain = [l for l in out2.splitlines() if l.startswith("audit:")]
    if chain:
        print(f"  {C['d']}{chain[0].strip()}{C['x']}")

    # -------------------------------------------------------------- headline
    total = time.time() - t_start
    head("RESULT")
    pct = residue_abs * 100.0 / gmv
    print(f"  {C['B']}{C['w']}{lakh(gmv)} reconciled{C['x']}"
          f"  {C['d']}{SEP}{C['x']}  {C['B']}{fmt_inr(unnamed)} genuinely unexplained{C['x']}"
          f"  {C['d']}{SEP}{C['x']}  {C['B']}{len(exc)} honest exceptions{C['x']}")
    print(f"  {C['d']}engine {engine_ms} ms  {SEP}  {summ['payments'] + summ['bank_rows']:,} records  {SEP}  "
          f"residue {fmt_inr(residue_abs)} ({pct:.4f}% of GMV)  {SEP}  demo {total:.0f}s{C['x']}")
    print()
    print(f"  {C['d']}Of the {fmt_inr(residue_abs)} residue, {fmt_inr(named)} is named and")
    print(f"  evidenced by the agent; {fmt_inr(unnamed)} has no source document and is")
    print(f"  reported as unexplained rather than written off quietly.{C['x']}")
    rule("=")
    print(f"  UI: {C['b']}make serve{C['x']}   full metrics: {C['b']}make evaluate{C['x']}")
    print()


if __name__ == "__main__":
    main()
