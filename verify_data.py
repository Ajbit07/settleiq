"""Independent integrity check. Reads ONLY the written CSVs -- never the
in-memory generator -- so it catches serialisation bugs too."""
import csv, json, os, sys
from collections import defaultdict
from decimal import Decimal

def load(p):
    with open(p, newline="", encoding="utf-8") as fh:
        return list(csv.DictReader(fh))

def paise(s):
    return int((Decimal(s) * 100).to_integral_value())

def check(root):
    fails = []
    obs, gt = os.path.join(root, "observable"), os.path.join(root, "ground_truth")
    bank = {r["bank_txn_id"]: r for r in load(os.path.join(obs, "bank_statement.csv"))}
    pay = {r["payment_id"]: r for r in load(os.path.join(obs, "payments.csv"))}
    setl = {r["settlement_id"]: r for r in load(os.path.join(obs, "settlements.csv"))}
    decomp = load(os.path.join(gt, "ground_truth_decomposition.csv"))
    links = load(os.path.join(gt, "ground_truth_links.csv"))
    amb = load(os.path.join(gt, "ground_truth_ambiguity.csv"))

    # 1. every decomposition sums exactly to its bank credit
    by_txn = defaultdict(list)
    for r in decomp:
        by_txn[r["bank_txn_id"]].append(r)
    bad = 0
    for txn, rows in by_txn.items():
        total = sum(paise(r["amount"]) for r in rows if r["component"] != "TOTAL_bank_credit")
        stated = [paise(r["amount"]) for r in rows if r["component"] == "TOTAL_bank_credit"][0]
        if total != stated or paise(bank[txn]["amount"]) != stated:
            bad += 1
    fails.append(("decomposition sums to bank credit, to the paise",
                  f"{len(by_txn)-bad}/{len(by_txn)} exact", bad == 0))

    # 2. gross in ground-truth links reconciles to observable settlements.gross
    g = defaultdict(int)
    for l in links:
        g[l["settlement_id"]] += paise(l["amount"])
    mism = [s for s in g if paise(setl[s]["gross"]) != g[s]]
    fails.append(("sum(linked payments) == settlements.gross",
                  f"{len(g)-len(mism)}/{len(g)} batches", not mism))

    # 3. no payment linked to two settlements
    seen, dup = set(), 0
    for l in links:
        if l["payment_id"] in seen:
            dup += 1
        seen.add(l["payment_id"])
    fails.append(("no payment appears in two batches", f"{dup} duplicates", dup == 0))

    # 4. only captured payments settle
    nf = sum(1 for l in links if pay[l["payment_id"]]["status"] != "captured")
    fails.append(("failed payments never settle", f"{nf} violations", nf == 0))

    # 5. ambiguity traps really are swap-invariant
    bad_amb = 0
    for a in amb:
        pa, pb = pay[a["payment_id_a"]], pay[a["payment_id_b"]]
        if not (pa["amount"] == pb["amount"] and pa["method"] == pb["method"]
                and pa["created_at_ist"][:10] == pb["created_at_ist"][:10]
                and a["settlement_id_a"] != a["settlement_id_b"]
                and setl[a["settlement_id_a"]]["settled_at"][:10]
                    == setl[a["settlement_id_b"]]["settled_at"][:10]):
            bad_amb += 1
    fails.append(("ambiguity traps are genuinely swap-invariant",
                  f"{len(amb)-bad_amb}/{len(amb)} valid", bad_amb == 0))

    # 6. THE LEAK TEST: no ground-truth column may appear in observable files
    # A table's own primary key is observable. A leak is a FOREIGN key that
    # would hand the engine a linkage it is supposed to reconstruct.
    forbidden = {
        "payments.csv":       {"settlement_id", "bank_txn_id", "utr", "fee", "gst", "tds"},
        "orders.csv":         {"settlement_id", "bank_txn_id", "payment_id"},
        "refunds.csv":        {"settlement_id", "bank_txn_id"},
        "chargebacks.csv":    {"settlement_id", "bank_txn_id"},
        "settlements.csv":    {"bank_txn_id", "payment_id"},
        "bank_statement.csv": {"settlement_id", "payment_id", "utr", "refund_id",
                               "dispute_id", "reserve_id"},
        "reserve_ledger.csv": {"settlement_id", "bank_txn_id", "payment_id"},
    }
    leaked = []
    for fn in os.listdir(obs):
        cols = set(load(os.path.join(obs, fn))[0].keys())
        for c in sorted(cols & forbidden.get(fn, set())):
            leaked.append(f"{fn}:{c}")
        for c in sorted(cols):
            if c.startswith("_") or c in ("split", "ambiguous"):
                leaked.append(f"{fn}:{c}")
    fails.append(("no ground-truth linkage leaks into observable/",
                  ", ".join(leaked) or "clean", not leaked))

    # 7. published rate card must NOT contain the mid-month change
    rc = json.load(open(os.path.join(root, "config", "rate_card_published.json"), encoding="utf-8"))
    tiers = sum(len(v) for v in rc["methods"].values())
    fails.append(("published rate card hides the mid-month change",
                  f"{tiers} tiers, one per method", tiers == len(rc["methods"])))

    # 8. settlements.net is the REPORTED net; bank credit may differ by drift
    diff = sum(1 for s in setl
               if any(paise(bank[t]["amount"]) != paise(setl[s]["net"])
                      for t in by_txn if by_txn[t][0]["settlement_id"] == s
                      and bank[t]["amount"]))
    fails.append(("bank credit deviates from reported net (drift is real)",
                  f"{diff} batches differ", diff > 0))
    return fails

ok = True
for root in sys.argv[1:]:
    print(f"\nINTEGRITY  {root}")
    print("-" * 74)
    for label, detail, passed in check(root):
        ok &= passed
        print(f"  [{'PASS' if passed else 'FAIL'}] {label:<48} {detail}")
sys.exit(0 if ok else 1)
