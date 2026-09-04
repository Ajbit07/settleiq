"""Generator self-report: distributions, edge-case census, sample narrations.

This is a QA artifact. If an edge case the spec demands is not present at the
required multiplicity, the census below prints FAIL and `make generate` exits
non-zero. A generator that silently stops producing an edge case would quietly
inflate every downstream metric.
"""
from collections import Counter, defaultdict
from datetime import datetime

from .banking_calendar import IST, UTC, is_bank_holiday
from .money import fmt_inr, lakh


def _bar(n, total, width=34):
    if total == 0:
        return ""
    return "#" * max(1, round(width * n / total)) if n else ""


def census(g):
    """Returns list of (label, count, required, ok)."""
    p = g.p
    batches = [g.batches[k] for k in sorted(g.batches)]

    # multi-day batches: T+2 collapse across weekends/holidays
    multiday = sum(1 for b in batches
                   if len({x["_ts"].date() for x in b["payments"]}) > 1)
    # holiday-shifted: settle date is more than 2 calendar days after capture
    shifted = 0
    for b in batches:
        if b["instant"]:
            continue
        for x in b["payments"]:
            if (b["settle_date"] - x["_ts"].date()).days > 2:
                shifted += 1
                break
    netted_rf = sum(1 for r in g.refunds if r["netted_flag"])
    sep_rf = len(g.refunds) - netted_rf
    cb_rev = sum(1 for c in g.chargebacks if "_reversal_settlement" in c)
    cb_sep = sum(1 for c in g.chargebacks if not c["netted"])
    res_netted = sum(1 for e in g.reserve_ledger if e["netted_flag"])
    res_sep = len(g.reserve_ledger) - res_netted
    collisions = sum(1 for r in g.refunds if r["_collision"])
    instant = sum(1 for b in batches if b["instant"])
    drift = sum(1 for b in batches if b["drift"])
    unexpl = sum(1 for b in batches if b["unexplained"])
    # IST/UTC day disagreement
    utc_off = 0
    order_by_id = {o["order_id"]: o for o in g.orders}
    for pay in g.payments:
        o = order_by_id.get(pay["order_id"])
        if not o:
            continue
        if datetime.fromisoformat(o["created_at_utc"]).date() != pay["_ts"].date():
            utc_off += 1
    # mid-month rate change exposure
    rate_change = 0
    if p.rate_card_change:
        m, _, eff = p.rate_card_change
        rate_change = sum(1 for x in g.payments
                          if x["method"] == m and x["status"] == "captured"
                          and x["_ts"].date() >= eff)
    dup = len(g.dup_keys)
    bare = sum(1 for b in batches if b["rail"] == "bare")

    return [
        ("T+2 shifted by weekend/holiday (batches)", shifted, 5),
        ("batch spanning multiple capture days", multiday, 3),
        ("refunds netted into payout", netted_rf, 10),
        ("refunds as separate bank debit", sep_rf, 10),
        ("chargeback debit with later reversal", cb_rev, 3),
        ("chargeback as standalone bank debit", cb_sep, 3),
        ("reserve released netted into payout", res_netted, 5),
        ("reserve released as standalone credit", res_sep, 2),
        ("partial refund w/ amount collision", collisions, 5),
        ("AMBIGUITY TRAP pairs (must refuse)", len(g.gt_ambiguity), p.ambiguity_pairs),
        ("instant settlement batches", instant, 1),
        ("failed payout retried under new UTR", dup, 1),
        ("paise rounding drift batches", drift, 4),
        ("IST/UTC off-by-one-day payments", utc_off, 50),
        ("genuinely unexplainable residues", unexpl, 1),
        ("mid-month rate change affected payments", rate_change, 1 if p.rate_card_change else 0),
        ("narration with NO utr at all", bare, 1),
    ]


def render(g):
    p = g.p
    L = []
    A = L.append
    batches = [g.batches[k] for k in sorted(g.batches)]
    cap = [x for x in g.payments if x["status"] == "captured"]
    gmv = sum(x["amount_paise"] for x in cap)
    net_total = sum(b["net"] for b in batches)

    A("=" * 78)
    A(f"  SettleIQ datagen  |  {p.merchant_id}  {p.name}")
    A(f"  period {p.period_start} .. {p.period_end}   seed={p.seed}")
    A("=" * 78)
    A("")
    A("VOLUME")
    A(f"  orders                {len(g.orders):>10,}")
    A(f"  payments              {len(g.payments):>10,}   captured {len(cap):,}"
      f"  failed {len(g.payments)-len(cap):,}")
    A(f"  refunds               {len(g.refunds):>10,}")
    A(f"  chargebacks           {len(g.chargebacks):>10,}")
    A(f"  settlement batches    {len(batches):>10,}")
    A(f"  bank statement rows   {len(g.bank_rows):>10,}   "
      f"(payout credits {sum(1 for r in g.gt_narration if r['kind']=='payout_credit'):,})")
    A(f"  ground-truth links    {len(g.gt_links):>10,}")
    A("")
    A("MONEY")
    A(f"  captured GMV          {fmt_inr(gmv):>18}   ({lakh(gmv)})")
    A(f"  total bank credited   {fmt_inr(net_total):>18}   ({lakh(net_total)})")
    A(f"  gap to explain        {fmt_inr(gmv-net_total):>18}   "
      f"({(gmv-net_total)*100.0/gmv:.2f}% of GMV)")
    A("")
    comp = defaultdict(int)
    for b in batches:
        comp["platform fee"] -= b["fee"]
        comp["GST on fee (18%)"] -= b["gst"]
        comp["TDS 194-O (1%)"] -= b["tds"]
        comp["refunds netted"] -= b["refund_netted"]
        comp["chargeback debits"] -= b["chargeback"]
        comp["dispute fee + GST"] -= b["dispute_fee"] + b["dispute_gst"]
        comp["chargeback reversals"] += b["reversal"]
        comp["rolling reserve held"] -= b["reserve_held"]
        comp["reserve released"] += b["reserve_released"]
        comp["rounding drift"] += b["drift"]
        comp["unexplained"] += b["unexplained"]
    A("  GAP DECOMPOSITION (ground truth, exact to the paise)")
    for k in ["platform fee", "GST on fee (18%)", "TDS 194-O (1%)", "refunds netted",
              "chargeback debits", "dispute fee + GST", "chargeback reversals",
              "rolling reserve held", "reserve released", "rounding drift", "unexplained"]:
        A(f"    {k:<24}{fmt_inr(comp[k]):>16}")
    A(f"    {'-'*24}{'-'*16}")
    A(f"    {'sum of components':<24}{fmt_inr(sum(comp.values())):>16}"
      f"   (== -(gap): {sum(comp.values()) == -(gmv-net_total)})")
    A("")
    A("METHOD MIX (captured)")
    mc = Counter(x["method"] for x in cap)
    mv = defaultdict(int)
    for x in cap:
        mv[x["method"]] += x["amount_paise"]
    for m, n in mc.most_common():
        A(f"  {m:<12}{n:>6,} txn  {n*100.0/len(cap):>5.1f}%  "
          f"{fmt_inr(mv[m]):>15}  {_bar(n, len(cap))}")
    A("")
    A("TICKET SIZE (captured)")
    buckets = [(0, 50000, "< Rs 500"), (50000, 100000, "Rs 500-1k"),
               (100000, 250000, "Rs 1k-2.5k"), (250000, 500000, "Rs 2.5k-5k"),
               (500000, 1500000, "Rs 5k-15k"), (1500000, 10**12, ">= Rs 15k")]
    for lo, hi, label in buckets:
        n = sum(1 for x in cap if lo <= x["amount_paise"] < hi)
        A(f"  {label:<12}{n:>6,}  {n*100.0/len(cap):>5.1f}%  {_bar(n, len(cap))}")
    amts = sorted(x["amount_paise"] for x in cap)
    q = lambda f: amts[int(f * (len(amts) - 1))]
    A(f"  min {fmt_inr(amts[0])}   p50 {fmt_inr(q(.5))}   p90 {fmt_inr(q(.9))}"
      f"   p99 {fmt_inr(q(.99))}   max {fmt_inr(amts[-1])}")
    A(f"  mean ticket {fmt_inr(gmv // len(cap))}")
    A("")
    A("BATCH SIZE (payments per settlement batch)")
    sizes = sorted(len(b["payments"]) for b in batches)
    A(f"  min {sizes[0]}   p50 {sizes[len(sizes)//2]}   p90 {sizes[int(.9*(len(sizes)-1))]}"
      f"   max {sizes[-1]}   mean {sum(sizes)/len(sizes):.1f}")
    A("  -> subset-sum search space per credit is set by this distribution")
    A("")
    A("SETTLEMENT CALENDAR (capture days collapsing onto one settle date)")
    by_settle = defaultdict(set)
    for b in batches:
        if b["instant"]:
            continue
        for x in b["payments"]:
            by_settle[b["settle_date"]].add(x["_ts"].date())
    for d in sorted(by_settle)[:12]:
        src = sorted(by_settle[d])
        A(f"  {d} {d.strftime('%a')}  <- {len(src)} capture day(s): "
          f"{', '.join(str(s) for s in src)}")
    A(f"  ... {len(by_settle)} settle dates total")
    A("")
    A("SPLITS (by settlement batch and time, never by row)")
    sp = Counter(b["split"] for b in batches)
    spv = defaultdict(int)
    for b in batches:
        spv[b["split"]] += b["gross"]
    for s in ["train", "dev", "test"]:
        A(f"  {s:<7}{sp[s]:>4} batches   gross {fmt_inr(spv[s]):>16}   "
          f"{spv[s]*100.0/sum(spv.values()):>5.1f}% of gross")
    A("")
    A("UTR RECOVERABILITY (bank rows carrying a payout credit)")
    ops = Counter()
    payout = [r for r in g.gt_narration if r["kind"].startswith("payout")]
    # "clean" == the true UTR survives verbatim in the narration, checked
    # literally. Inferring it from which operators fired overstates the damage:
    # a 38-char field truncation only breaks the UTR if the UTR sat past col 38.
    clean = sum(1 for r in payout if r["utr_survives_verbatim"])
    for r in payout:
        for x in r["corruption_ops"].split("|"):
            if x:
                ops[x.split(":")[0]] += 1
    A(f"  exact-UTR extractable  {clean}/{len(payout)}  "
      f"({clean*100.0/len(payout):.1f}%)  <- Stage 1 ceiling")
    A(f"  needs repair or netting {len(payout)-clean}/{len(payout)}  "
      f"({(len(payout)-clean)*100.0/len(payout):.1f}%)")
    for k, v in ops.most_common():
        A(f"    {k:<24}{v:>5}")
    A("")
    A("EDGE-CASE CENSUS")
    ok_all = True
    for label, n, req in census(g):
        ok = n >= req
        ok_all &= ok
        A(f"  [{'OK ' if ok else 'FAIL'}] {label:<44}{n:>6}  (min {req})")
    A("")
    return "\n".join(L), ok_all


def sample_narrations(g, n=5, kinds=None):
    """Pick the most instructive dirty narrations, one per corruption family."""
    byid = {r["bank_txn_id"]: r for r in g.bank_rows}
    want = ["truncate_utr", "charswap_utr", "split_utr", "no_utr_in_source",
            "field_truncated_38", "drop_utr", "retry_new_utr"]
    picked, seen = [], set()
    for fam in want:
        for gt in g.gt_narration:
            if gt["bank_txn_id"] in seen:
                continue
            fams = [x.split(":")[0] for x in gt["corruption_ops"].split("|") if x]
            if fam in fams:
                picked.append((gt, byid[gt["bank_txn_id"]]))
                seen.add(gt["bank_txn_id"])
                break
        if len(picked) >= n:
            break
    return picked
