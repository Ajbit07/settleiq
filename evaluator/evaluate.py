"""SettleIQ evaluation harness.

Reads the hidden ground truth and the engine's predictions and reports the
metrics that decide whether this system is safe to point at real money.

The headline number is NOT match rate. It is false-match rate among links the
policy engine was willing to auto-post, because a false match is the only error
here that silently moves money to the wrong place.
"""
import csv
import json
import os
import sys
from collections import Counter, defaultdict
from decimal import Decimal

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from datagen.money import fmt_inr, lakh  # noqa: E402


def load(path):
    if not os.path.exists(path):
        return []
    with open(path, newline="", encoding="utf-8") as fh:
        return list(csv.DictReader(fh))


def P(s):
    if s is None or s == "":
        return 0
    return int((Decimal(s) * 100).to_integral_value())


# ---------------------------------------------------------------- core metrics
def link_metrics(truth, pred, amounts, ambiguous_pred):
    """Precision / recall / F1 on payment -> settlement links.

    Reported BOTH by row count and weighted by amount. Row-count F1 flatters a
    system that gets thousands of small UPI payments right and loses the few
    large ones; amount-weighted F1 is what the merchant actually feels.
    """
    tp = fp = fn = 0
    tp_amt = fp_amt = fn_amt = 0
    for pid, true_setl in truth.items():
        got = pred.get(pid)
        amt = amounts.get(pid, 0)
        if got is None:
            fn += 1
            fn_amt += amt
        elif got == true_setl:
            tp += 1
            tp_amt += amt
        else:
            fp += 1
            fp_amt += amt
    for pid in pred:
        if pid not in truth:
            fp += 1
            fp_amt += amounts.get(pid, 0)

    def prf(tp_, fp_, fn_):
        p = tp_ / (tp_ + fp_) if tp_ + fp_ else 0.0
        r = tp_ / (tp_ + fn_) if tp_ + fn_ else 0.0
        f = 2 * p * r / (p + r) if p + r else 0.0
        return p, r, f

    return {
        "row": prf(tp, fp, fn),
        "amt": prf(tp_amt, fp_amt, fn_amt),
        "tp": tp, "fp": fp, "fn": fn,
        "tp_amt": tp_amt, "fp_amt": fp_amt, "fn_amt": fn_amt,
    }


def evaluate(root, pred_dir, preset):
    gt = root + "/ground_truth"
    obs = root + "/observable"

    links = load(gt + "/ground_truth_links.csv")
    amb_pairs = load(gt + "/ground_truth_ambiguity.csv")
    gt_exc = load(gt + "/ground_truth_exceptions.csv")
    decomp_t = load(gt + "/ground_truth_decomposition.csv")
    payments = load(obs + "/payments.csv")

    truth = {l["payment_id"]: l["settlement_id"] for l in links}
    split_of = {l["payment_id"]: l["split"] for l in links}
    amounts = {p["payment_id"]: P(p["amount"]) for p in payments}
    gmv = sum(amounts[p["payment_id"]] for p in payments if p["status"] == "captured")

    # Genuinely ambiguous means SWAP-INVARIANT: two payments with the same
    # amount, method and rate tier, in different batches settling the same day.
    # Swapping them changes no batch gross and no fee total, so no arithmetic
    # can separate them and refusing to auto-post is the correct behaviour.
    #
    # The planted traps are a subset of this set. Scoring only against the
    # planted ones would punish the engine for correctly refusing the natural
    # collisions the generator produced by accident -- which are equally real.
    planted = set()
    for a in amb_pairs:
        planted.add(a["payment_id_a"])
        planted.add(a["payment_id_b"])

    RATE_CHANGE = "2025-11-16"
    cap_date = {p["payment_id"]: p["created_at_ist"][:10] for p in payments}
    settle_date = {l["settlement_id"]: l["settle_date"] for l in links}
    grouped = defaultdict(lambda: defaultdict(list))
    for l in links:
        tier = "post" if cap_date.get(l["payment_id"], "") >= RATE_CHANGE else "pre"
        k = (settle_date[l["settlement_id"]], l["amount"], l["method"], tier)
        grouped[k][l["settlement_id"]].append(l["payment_id"])
    genuinely_ambiguous = set()
    for by_batch in grouped.values():
        if len(by_batch) > 1:
            for v in by_batch.values():
                genuinely_ambiguous.update(v)

    pred_rows = load(pred_dir + "/predicted_links.csv")
    pred = {r["payment_id"]: r["settlement_id"] for r in pred_rows}
    ambiguous_pred = {r["payment_id"] for r in pred_rows if r["ambiguous"] == "1"}

    summary = {}
    sj = pred_dir + "/summary.json"
    if os.path.exists(sj):
        summary = json.loads(open(sj, encoding="utf-8").read().strip())

    out = {"preset": preset, "summary": summary}
    out["overall"] = link_metrics(truth, pred, amounts, ambiguous_pred)
    for sp in ("train", "dev", "test"):
        t = {k: v for k, v in truth.items() if split_of.get(k) == sp}
        p = {k: v for k, v in pred.items() if split_of.get(k) == sp}
        out[sp] = link_metrics(t, p, amounts, ambiguous_pred)

    # ---- the money metric: false matches among AUTO-POSTABLE links ----------
    auto = {k: v for k, v in pred.items() if k not in ambiguous_pred}
    auto_fp = sum(1 for k, v in auto.items() if truth.get(k) != v)
    auto_fp_amt = sum(amounts.get(k, 0) for k, v in auto.items() if truth.get(k) != v)
    out["auto_posted"] = len(auto)
    out["auto_false_match_rate"] = auto_fp / len(auto) if auto else 0.0
    out["auto_false_match_amt"] = auto_fp_amt
    out["auto_resolution_rate"] = len(auto) / len(truth) if truth else 0.0

    # ---- exception precision: is refusing these justified? ------------------
    refused = ambiguous_pred & set(truth)
    justified = sum(1 for p in refused if p in genuinely_ambiguous)
    out["refused"] = len(refused)
    out["refused_justified"] = justified
    out["exception_precision"] = justified / len(refused) if refused else 0.0
    out["trap_recall"] = (len(genuinely_ambiguous & ambiguous_pred) / len(genuinely_ambiguous)
                          if genuinely_ambiguous else 0.0)
    out["traps_total"] = len(genuinely_ambiguous)
    out["planted_total"] = len(planted)
    out["planted_caught"] = len(planted & ambiguous_pred)
    out["ambiguous_missed"] = len(genuinely_ambiguous - ambiguous_pred)

    # ---- residue -------------------------------------------------------------
    pred_dec = load(pred_dir + "/predicted_decomposition.csv")
    residue = sum(P(r["amount"]) for r in pred_dec if r["component"] == "residue")
    abs_residue = sum(abs(P(r["amount"])) for r in pred_dec if r["component"] == "residue")
    out["residue_net"] = residue
    out["residue_abs"] = abs_residue
    out["residue_pct_gmv"] = abs_residue * 100.0 / gmv if gmv else 0.0
    out["gmv"] = gmv

    # truly-unexplainable floor: what the generator made unattributable
    out["irreducible"] = sum(abs(P(e["amount"])) for e in gt_exc
                             if e["genuinely_unresolvable"] == "1")

    # per-batch residue buckets, so "unexplained" is not one blended number
    buckets = Counter()
    for r in pred_dec:
        if r["component"] != "residue":
            continue
        v = abs(P(r["amount"]))
        if v == 0:
            buckets["exact_to_the_paise"] += 1
        elif v <= 100:
            buckets["within_Rs_1"] += 1
        elif v <= 10000:
            buckets["Rs_1_to_100"] += 1
        else:
            buckets["over_Rs_100"] += 1
    out["residue_buckets"] = dict(buckets)

    # ---- cost of error -------------------------------------------------------
    #
    # Match rate is not a cost. These two errors are, and they are not
    # symmetric: a FALSE match silently moves money to the wrong place and is
    # found weeks later during a reconciliation review, costing a full manual
    # unwind. A MISSED match sits visibly in a queue and costs an analyst a few
    # minutes. Pricing them separately is what justifies a system that refuses.
    #
    # Rates are assumptions, stated here rather than buried, so a reader can
    # substitute their own.
    UNWIND_HOURS_PER_FALSE_MATCH = 2.5      # trace, reverse, re-post, sign off
    ANALYST_MINUTES_PER_MISS = 6.0          # open, eyeball, resolve from queue
    ANALYST_RATE_PER_HOUR = 1200_00         # paise (Rs 1,200/hr fully loaded)

    fm = sum(1 for k, v in auto.items() if truth.get(k) != v)
    missed = out["overall"]["fn"] + len(refused)
    false_cost = round(fm * UNWIND_HOURS_PER_FALSE_MATCH * ANALYST_RATE_PER_HOUR)
    miss_cost = round(missed * (ANALYST_MINUTES_PER_MISS / 60.0) * ANALYST_RATE_PER_HOUR)
    out["cost_of_error"] = {
        "false_matches": fm,
        "false_match_cost_paise": false_cost,
        "missed_or_refused": missed,
        "missed_cost_paise": miss_cost,
        "total_paise": false_cost + miss_cost,
        "assumptions": {
            "unwind_hours_per_false_match": UNWIND_HOURS_PER_FALSE_MATCH,
            "analyst_minutes_per_miss": ANALYST_MINUTES_PER_MISS,
            "analyst_rate_per_hour": "1200.00",
        },
        # What a policy that auto-posted everything, refusing nothing, would cost.
        "cost_if_nothing_refused_paise": round(
            (fm + len(refused & set(genuinely_ambiguous)) // 2)
            * UNWIND_HOURS_PER_FALSE_MATCH * ANALYST_RATE_PER_HOUR),
    }

    # ---- bank credit coverage ------------------------------------------------
    bank_true = {}
    for l in links:
        bank_true[l["settlement_id"]] = l["bank_txn_id"]
    bl = load(pred_dir + "/predicted_bank_links.csv")
    bank_pred = {r["settlement_id"]: r["bank_txn_id"] for r in bl}
    if not bank_pred:
        bank_pred = {r["settlement_id"]: r["bank_txn_id"]
                     for r in pred_rows if r["bank_txn_id"]}
    bok = sum(1 for s, b in bank_pred.items() if bank_true.get(s) == b)
    out["bank_links_correct"] = bok
    out["bank_links_wrong"] = len(bank_pred) - bok
    out["bank_links_predicted"] = len(bank_pred)
    out["bank_links_total"] = len(bank_true)
    return out


def fmt_row(m):
    rp, rr, rf = m["overall"]["row"]
    ap, ar, af = m["overall"]["amt"]
    s = m.get("summary", {})
    return (f"{m['preset']:<14} {rf*100:>6.2f} {af*100:>7.2f} "
            f"{m['auto_false_match_rate']*100:>7.3f} "
            f"{fmt_inr(m['residue_abs']):>13} {m['residue_pct_gmv']:>6.3f} "
            f"{s.get('wall_ms', 0):>7}")


def render(m):
    L = []
    A = L.append
    s = m.get("summary", {})
    A("=" * 78)
    A(f"  EVALUATION  preset={m['preset']}   model={s.get('model_version','?')}")
    A("=" * 78)
    A("")
    A("PAYMENT -> SETTLEMENT LINKS")
    A(f"  {'split':<8}{'rows':>7}{'prec':>8}{'recall':>8}{'F1':>8}   "
      f"{'amt-prec':>9}{'amt-rec':>9}{'amt-F1':>8}")
    for sp in ("train", "dev", "test", "overall"):
        d = m[sp]
        rp, rr, rf = d["row"]
        ap, ar, af = d["amt"]
        n = d["tp"] + d["fn"]
        A(f"  {sp:<8}{n:>7,}{rp*100:>8.2f}{rr*100:>8.2f}{rf*100:>8.2f}   "
          f"{ap*100:>9.2f}{ar*100:>9.2f}{af*100:>8.2f}")
    A("")
    A("THE MONEY METRIC")
    A(f"  auto-postable links            {m['auto_posted']:>10,}")
    A(f"  false matches among them       {m['auto_false_match_rate']*100:>10.3f} %"
      f"   (target < 0.500 %)")
    A(f"  value mis-assigned             {fmt_inr(m['auto_false_match_amt']):>10}")
    A(f"  auto-resolution rate           {m['auto_resolution_rate']*100:>10.2f} %")
    A("")
    A("REFUSALS  (refusing an easy item is a failure, not a virtue)")
    A(f"  items refused                  {m['refused']:>10,}")
    A(f"  of which genuinely ambiguous   {m['refused_justified']:>10,}")
    A(f"  exception precision            {m['exception_precision']*100:>10.2f} %")
    A(f"  swap-invariant recall          {m['trap_recall']*100:>10.2f} %"
      f"   ({m['traps_total']} genuinely ambiguous)")
    A(f"  planted traps caught           {m['planted_caught']}/{m['planted_total']}")
    A(f"  ambiguous items MISSED         {m['ambiguous_missed']:>10,}"
      f"   (these would be false matches)")
    A("")
    A("RESIDUE")
    A(f"  GMV                            {fmt_inr(m['gmv']):>16}")
    A(f"  unexplained (abs)              {fmt_inr(m['residue_abs']):>16}"
      f"   {m['residue_pct_gmv']:.4f} % of GMV")
    A(f"  net residue                    {fmt_inr(m['residue_net']):>16}")
    A(f"  irreducible floor (by design)  {fmt_inr(m['irreducible']):>16}")
    A(f"  per-batch: {m['residue_buckets']}")
    A("")
    A("COST OF ERROR  (assumptions stated, not buried)")
    c = m["cost_of_error"]
    A(f"  false matches                  {c['false_matches']:>10,}"
      f"   x {c['assumptions']['unwind_hours_per_false_match']}h unwind"
      f"  = {fmt_inr(c['false_match_cost_paise']):>12}")
    A(f"  missed or refused              {c['missed_or_refused']:>10,}"
      f"   x {c['assumptions']['analyst_minutes_per_miss']}min triage"
      f" = {fmt_inr(c['missed_cost_paise']):>12}")
    A(f"  {'total operational cost':<30}{'':>10}   {'':>18}"
      f"  {fmt_inr(c['total_paise']):>12}")
    A(f"  counterfactual: a policy that refused nothing and auto-posted the")
    A(f"  swap-invariant pairs would cost "
      f"{fmt_inr(c['cost_if_nothing_refused_paise'])} in unwinds alone.")
    A("")
    A("BANK CREDIT LINKING")
    A(f"  correct {m['bank_links_correct']}/{m['bank_links_total']} settlements"
      f"   wrong {m['bank_links_wrong']}   predicted {m['bank_links_predicted']}")
    A("")
    A("THROUGHPUT")
    wall = s.get("wall_ms", 0)
    recs = s.get("payments", 0) + s.get("bank_rows", 0)
    A(f"  wall {wall} ms   {recs:,} records   "
      f"{recs * 1000.0 / wall if wall else 0:,.0f} records/sec")
    if "timings" in s:
        for k, v in s["timings"].items():
            A(f"    {k:<22}{v:>6} ms")
    A("")
    return "\n".join(L)


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "data/merchant_a"
    preset = sys.argv[2] if len(sys.argv) > 2 else "full"
    pred = sys.argv[3] if len(sys.argv) > 3 else f"reports/{preset}"
    m = evaluate(root, pred, preset)
    print(render(m))
    os.makedirs("reports/metrics", exist_ok=True)
    with open(f"reports/metrics/{preset}.json", "w", encoding="utf-8") as fh:
        json.dump(m, fh, indent=2, default=str)


if __name__ == "__main__":
    main()
