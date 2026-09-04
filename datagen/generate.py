"""SettleIQ synthetic merchant-month generator.

Emits OBSERVABLE sources (what a merchant actually has) and a HIDDEN ground
truth (what the evaluator alone may read). The engine must never open any file
under ground_truth/.

Every rupee is an int of paise. The decomposition identity that must hold
exactly, per bank credit:

    gross - fee - gst - tds - refund_netted - chargeback - dispute_fee
          - dispute_gst + chargeback_reversal - reserve_held + reserve_released
          + drift + unexplained  ==  bank_credit_amount
"""
import csv
import json
import os
import random
from collections import Counter, defaultdict
from datetime import date, datetime, timedelta

from .banking_calendar import IST, add_banking_days, iso, to_utc
from .config import (CHARGEBACK_REVERSAL_SHARE, DISPUTE_FEE_PAISE, GST_RATE,
                     INSTANT_SURCHARGE, RESERVE_RATE, TDS_RATE, split_for)
from .money import fmt, pct_of
from . import narration as N

RESERVE_METHODS = {"card", "wallet"}


def _dt(d, h, m, s):
    return datetime(d.year, d.month, d.day, h, m, s, tzinfo=IST)


def _hour(rng):
    """Daytime-weighted, with a deliberate late-night tail. 00:00-05:29 IST is
    the PREVIOUS calendar day in UTC, which is where the off-by-one bugs live."""
    r = rng.random()
    if r < 0.09:
        return rng.randint(0, 5)
    if r < 0.30:
        return rng.randint(6, 11)
    if r < 0.72:
        return rng.randint(12, 18)
    return rng.randint(19, 23)


def _amount(rng, bands):
    r, acc = rng.random(), 0.0
    v = None
    for w, lo, hi in bands:
        acc += w
        if r <= acc:
            v = rng.randint(lo, hi)
            break
    if v is None:
        v = rng.randint(bands[-1][1], bands[-1][2])
    style = rng.random()
    if style < 0.30:
        v = (v // 100) * 100
    elif style < 0.45:
        v = (v // 100) * 100 + 99
    return max(v, 100)


def _quota(rng, n, share, min_selected=0, min_unselected=0):
    """Choose an EXACT number of indices, not a per-item coin flip.

    Coin flips make edge-case counts vary run to run; at n=14 a 30% branch can
    yield 1. Downstream metrics would then move for reasons unrelated to the
    engine. Quotas keep every proportion reproducible and let us guarantee a
    floor on both sides of the split.
    """
    k = round(share * n)
    k = max(k, min_selected)
    k = min(k, n - min_unselected)
    k = max(0, min(k, n))
    return set(rng.sample(range(n), k))


def _method(rng, mix):
    r, acc = rng.random(), 0.0
    for m, w in sorted(mix.items()):
        acc += w
        if r <= acc:
            return m
    return sorted(mix)[-1]


class Generator:
    def __init__(self, profile, outdir):
        self.p = profile
        self.out = outdir
        self.rng = random.Random(profile.seed)
        self.payments, self.orders, self.refunds = [], [], []
        self.chargebacks, self.reserve_ledger = [], []
        self.batches = {}
        self.bank_rows, self.gt_decomp, self.gt_links = [], [], []
        self.gt_narration, self.gt_ambiguity, self.gt_exceptions = [], [], []
        self.dup_keys = set()
        self._seq = Counter()

    def _id(self, prefix, width=14):
        self._seq[prefix] += 1
        return "%s_%0*d" % (prefix, width - len(prefix) - 1, self._seq[prefix])

    # ---------------------------------------------------------------- payments
    def gen_payments(self):
        p, rng = self.p, self.rng
        days = (p.period_end - p.period_start).days + 1
        for _ in range(p.n_payments):
            d = p.period_start + timedelta(days=rng.randrange(days))
            ts = _dt(d, _hour(rng), rng.randrange(60), rng.randrange(60))
            method = _method(rng, p.method_mix)
            amt = _amount(rng, p.amount_bands)
            failed = rng.random() < p.failure_rate
            oid = self._id("order", 16)
            self.orders.append({
                "order_id": oid,
                "customer_id": "cust_%04d" % rng.randrange(1, 900),
                "amount": fmt(amt),
                "created_at_utc": iso(to_utc(ts - timedelta(seconds=rng.randrange(30, 900)))),
            })
            self.payments.append({
                "payment_id": self._id("pay", 18),
                "order_id": oid,
                "amount_paise": amt,
                "method": method,
                "status": "failed" if failed else "captured",
                "created_at_ist": iso(ts),
                "_ts": ts,
                "instant": False,
                "tds_eligible": (not failed) and rng.random() < p.tds_eligible_share,
                "_amb": False,
            })
        # Instant settlement is an intraday SWEEP: on an opted-in day the
        # platform settles everything captured before a cut-off. That makes the
        # instant batch a contiguous time window -- realistic, and recoverable.
        cap = [x for x in self.payments if x["status"] == "captured"]
        days_avail = sorted({x["_ts"].date() for x in cap})
        instant_days = set(rng.sample(days_avail, min(p.instant_batches, len(days_avail))))
        for x in cap:
            if x["_ts"].date() in instant_days and x["_ts"].hour < 12:
                x["instant"] = True

    def _cycle_of(self, ts):
        """Adjustments enter the same cycle machinery as payments: the cycle is
        a function of time-of-day. This is what makes batch attribution
        DERIVABLE by the engine instead of a coin flip it could never recover."""
        return 1 + (ts.hour * self.p.cycles_per_day) // 24

    def _batch_on(self, by_date, sd, ts, dates):
        key = sd if sd in by_date else min(dates, key=lambda d: abs((d - sd).days))
        want = self._cycle_of(ts)
        pool = by_date[key]
        for b in pool:
            if b["cycle"] == want:
                return b, key
        return min(pool, key=lambda b: abs(b["cycle"] - want)), key

    # --------------------------------------------------------- batch assembly
    def _batch_key(self, pay):
        d = pay["_ts"].date()
        if pay["instant"]:
            return (d, 0)
        cycle = 1 + (pay["_ts"].hour * self.p.cycles_per_day) // 24
        return (add_banking_days(d, 2), cycle)

    def build_batches(self):
        for pay in self.payments:
            if pay["status"] != "captured":
                continue
            key = self._batch_key(pay)
            b = self.batches.setdefault(key, {
                "settle_date": key[0], "cycle": key[1], "instant": key[1] == 0,
                "payments": [], "refunds": [], "chargebacks": [],
                "reversals": [], "reserve_released": [],
            })
            b["payments"].append(pay)
        for i, key in enumerate(sorted(self.batches), start=1):
            b = self.batches[key]
            # Full merchant id, not a 6-character tail. The tail was an artifact
            # of ids that happened to end in a 6-char random suffix; on any other
            # naming it slices mid-word and produces things like "setl_hant_1".
            b["settlement_id"] = "setl_%s_%04d" % (self.p.merchant_id, i)
            b["split"] = split_for(b["settle_date"])

    def _by_settle_date(self, exclude_instant=True):
        out = defaultdict(list)
        for b in self.batches.values():
            if exclude_instant and b["instant"]:
                continue
            out[b["settle_date"]].append(b)
        for k in out:
            out[k].sort(key=lambda x: x["settlement_id"])
        return out

    def inject_ambiguity_traps(self):
        """A CYCLE-BOUNDARY RACE.

        Two payments with identical amount, method and capture timestamp that
        the platform split across CONSECUTIVE settlement cycles. Real cuts are
        made on an internal sequence number, not on the wall clock, so two
        payments captured in the same second genuinely can land either side of
        one.

        The pair is indistinguishable on every observable field, and swapping
        them leaves both batch grosses AND both fee totals unchanged (same
        amount, same method, same rate tier). No arithmetic can separate them.

        A correct system still produces an assignment -- it has to put them
        somewhere -- but must REFUSE to auto-post it, because it cannot show its
        work. Refusing here is the behaviour under test.
        """
        rng = self.rng
        by_date = self._by_settle_date()
        pairable = sorted([d for d, bs in by_date.items() if len(bs) >= 2])
        rng.shuffle(pairable)
        made = 0
        for d in pairable:
            if made >= self.p.ambiguity_pairs:
                break
            # consecutive cycles only: the pair has to straddle ONE cut
            pool = sorted(by_date[d], key=lambda b: b["cycle"])
            adj = [(pool[i], pool[i + 1]) for i in range(len(pool) - 1)
                   if pool[i + 1]["cycle"] == pool[i]["cycle"] + 1]
            if not adj:
                continue
            b1, b2 = rng.choice(adj)
            src = rng.choice(b1["payments"])
            # stamp both at the last second of b1s cycle window, so the pair
            # sits exactly on the cut
            hours_per_cycle = 24 // self.p.cycles_per_day
            end_hour = b1["cycle"] * hours_per_cycle - 1
            ts = _dt(src["_ts"].date(), max(0, end_hour), 59, 59)
            ids = []
            for b in (b1, b2):
                oid = self._id("order", 16)
                pay = {
                    "payment_id": self._id("pay", 18), "order_id": oid,
                    "amount_paise": src["amount_paise"], "method": src["method"],
                    "status": "captured", "created_at_ist": iso(ts),
                    "_ts": ts, "instant": False,
                    "tds_eligible": src["tds_eligible"], "_amb": True,
                }
                self.orders.append({
                    "order_id": oid, "customer_id": "cust_%04d" % rng.randrange(1, 900),
                    "amount": fmt(pay["amount_paise"]),
                    "created_at_utc": iso(to_utc(ts - timedelta(seconds=120))),
                })
                self.payments.append(pay)
                b["payments"].append(pay)
                ids.append(pay["payment_id"])
            self.gt_ambiguity.append({
                "payment_id_a": ids[0], "payment_id_b": ids[1],
                "settlement_id_a": b1["settlement_id"], "settlement_id_b": b2["settlement_id"],
                "amount": fmt(src["amount_paise"]), "method": src["method"],
                "capture_date_ist": ts.date().isoformat(),
                "reason": "identical amount+method+timestamp split across consecutive "
                          "cycles; swap-invariant in gross and in fee",
            })
            made += 1

    # ---------------------------------------------------------------- refunds
    def gen_refunds(self):
        p, rng = self.p, self.rng
        cap = [x for x in self.payments if x["status"] == "captured" and not x["_amb"]]
        pool = [x["amount_paise"] for x in cap]
        chosen = rng.sample(cap, min(p.refund_count, len(cap)))
        by_date = self._by_settle_date()
        dates = sorted(by_date)
        netted_idx = _quota(rng, len(chosen), 0.65, min_unselected=12)
        for i_ref, pay in enumerate(chosen):
            partial = rng.random() < 0.45
            collision = False
            if partial and rng.random() < 0.35:
                amt = min(rng.choice(pool), pay["amount_paise"])
                collision = True
            elif partial:
                amt = max(100, int(pay["amount_paise"] * rng.uniform(0.15, 0.8)))
            else:
                amt = pay["amount_paise"]
            rd = min(pay["_ts"].date() + timedelta(days=rng.randint(1, 12)), p.period_end)
            rts = _dt(rd, _hour(rng), rng.randrange(60), rng.randrange(60))
            netted = i_ref in netted_idx
            sd = add_banking_days(rd, 2)
            r = {
                "refund_id": self._id("rfnd", 17), "payment_id": pay["payment_id"],
                "amount_paise": amt, "created_at_ist": iso(rts),
                "netted_flag": 1 if netted else 0,
                "_settle_date": sd, "_collision": collision,
            }
            if netted:
                b, key = self._batch_on(by_date, sd, rts, dates)
                b["refunds"].append(r)
                r["_settle_date"] = key
            self.refunds.append(r)

    # ------------------------------------------------------------ chargebacks
    def gen_chargebacks(self):
        p, rng = self.p, self.rng
        cap = [x for x in self.payments if x["status"] == "captured"
               and x["method"] in ("card", "wallet") and not x["_amb"]]
        chosen = rng.sample(cap, min(p.chargeback_count, len(cap)))
        by_date = self._by_settle_date()
        dates = sorted(by_date)
        netted_idx = _quota(rng, len(chosen), 0.70, min_unselected=3)
        rev_idx = _quota(rng, len(chosen), CHARGEBACK_REVERSAL_SHARE, min_selected=3)
        for i_cb, pay in enumerate(chosen):
            cd = min(pay["_ts"].date() + timedelta(days=rng.randint(3, 20)), p.period_end)
            target = add_banking_days(cd, 2)
            sd = min(dates, key=lambda d: (abs((d - target).days), d))
            cb_ts = _dt(cd, _hour(rng), rng.randrange(60), rng.randrange(60))
            b, sd = self._batch_on(by_date, sd, cb_ts, dates)
            cb = {
                "dispute_id": self._id("disp", 17), "payment_id": pay["payment_id"],
                "amount_paise": pay["amount_paise"], "fee_paise": DISPUTE_FEE_PAISE,
                "raised_at_ist": iso(cb_ts),
                "netted": i_cb in netted_idx, "_settlement": b["settlement_id"],
                "reversed_at_ist": "",
            }
            b["chargebacks"].append(cb)
            if i_cb in rev_idx:
                later = [d for d in dates if d > sd]
                if later:
                    rd = later[min(len(later) - 1, rng.randint(3, 9))]
                    rev_ts = _dt(rd, _hour(rng), rng.randrange(60), rng.randrange(60))
                    rb, rd = self._batch_on(by_date, rd, rev_ts, dates)
                    rb["reversals"].append(cb)
                    cb["_reversal_settlement"] = rd.isoformat()
                    cb["reversed_at_ist"] = iso(rev_ts)
            self.chargebacks.append(cb)

        # A chargeback raised near the end of the window has no later
        # settlement run to be represented into, so the reversal quota can come
        # up short. Top up from chargebacks that DO have a later run.
        #
        # This repair pass consumes RNG only when it is actually needed, so
        # profiles that already met the quota keep a byte-identical stream.
        made = sum(1 for c in self.chargebacks if "_reversal_settlement" in c)
        if made < 3:
            eligible = []
            for c in self.chargebacks:
                if "_reversal_settlement" in c:
                    continue
                raised = datetime.fromisoformat(c["raised_at_ist"]).date()
                target = add_banking_days(raised, 2)
                sd = min(dates, key=lambda d: (abs((d - target).days), d))
                later = [d for d in dates if d > sd]
                if later:
                    eligible.append((c, later))
            for c, later in eligible:
                if made >= 3:
                    break
                rd = later[min(len(later) - 1, rng.randint(3, 9))]
                rev_ts = _dt(rd, _hour(rng), rng.randrange(60), rng.randrange(60))
                rb, rd = self._batch_on(by_date, rd, rev_ts, dates)
                rb["reversals"].append(c)
                c["_reversal_settlement"] = rd.isoformat()
                c["reversed_at_ist"] = iso(rev_ts)
                made += 1

    # ------------------------------------------------- rolling reserve ledger
    def gen_reserve(self):
        """Holds created in-window mature 90 days out, i.e. OUTSIDE the window.
        So every in-window RELEASE comes from a seeded prior-period hold. That
        asymmetry is exactly what makes merchants think money went missing."""
        p, rng = self.p, self.rng
        by_date = self._by_settle_date()
        dates = sorted(by_date)
        n_entries = max(12, len(dates))
        netted_idx = _quota(rng, n_entries, 0.70, min_unselected=3)
        for i in range(n_entries):
            rel = dates[rng.randrange(len(dates))]
            amt = rng.randint(150000, 1800000)
            netted = i in netted_idx
            rel_ts = _dt(rel, _hour(rng), rng.randrange(60), rng.randrange(60))
            e = {
                "reserve_id": "rsrv_%s_%04d" % (p.merchant_id, i + 1),
                "amount_paise": amt, "amount": fmt(amt),
                "held_on": (rel - timedelta(days=90)).isoformat(),
                "release_date": rel.isoformat(), "released_at_ist": iso(rel_ts),
                "netted_flag": 1 if netted else 0, "_rel": rel,
            }
            if netted:
                b, _ = self._batch_on(by_date, rel, rel_ts, dates)
                b["reserve_released"].append(e)
            self.reserve_ledger.append(e)

    # ------------------------------------------------------ money computation
    def compute(self):
        p = self.p
        for key in sorted(self.batches):
            b = self.batches[key]
            gross = fee = gst = tds = reserve_base = 0
            for pay in sorted(b["payments"], key=lambda x: x["payment_id"]):
                tier = p.rate_for(pay["method"], pay["_ts"].date())
                f = pct_of(pay["amount_paise"], tier.pct) + tier.flat_paise
                if pay["instant"]:
                    f += pct_of(pay["amount_paise"], INSTANT_SURCHARGE)
                g = pct_of(f, GST_RATE)
                t = pct_of(pay["amount_paise"], TDS_RATE) if pay["tds_eligible"] else 0
                pay["_fee"], pay["_gst"], pay["_tds"] = f, g, t
                gross += pay["amount_paise"]
                fee += f
                gst += g
                tds += t
                if pay["method"] in RESERVE_METHODS:
                    reserve_base += pay["amount_paise"] - f - g
            rf = sum(r["amount_paise"] for r in b["refunds"])
            cb = sum(c["amount_paise"] for c in b["chargebacks"] if c["netted"])
            dfee = sum(c["fee_paise"] for c in b["chargebacks"])
            dgst = pct_of(dfee, GST_RATE)
            rev = sum(c["amount_paise"] for c in b["reversals"])
            hold = 0 if b["instant"] else pct_of(reserve_base, RESERVE_RATE)
            rel = sum(e["amount_paise"] for e in b["reserve_released"])
            b.update(gross=gross, fee=fee, gst=gst, tds=tds, refund_netted=rf,
                     chargeback=cb, dispute_fee=dfee, dispute_gst=dgst, reversal=rev,
                     reserve_held=hold, reserve_released=rel, drift=0, unexplained=0)
            b["net"] = (gross - fee - gst - tds - rf - cb - dfee - dgst + rev
                        - hold + rel)

    def inject_residues(self):
        p, rng = self.p, self.rng
        keys = [k for k in sorted(self.batches) if not self.batches[k]["instant"]]
        for k in rng.sample(keys, min(p.drift_batches, len(keys))):
            b = self.batches[k]
            b["drift"] = rng.choice([-5, -3, -2, -1, 1, 2, 3, 4, 5])
            b["net"] += b["drift"]
        dev_keys = [k for k in keys if self.batches[k]["split"] == "dev"] or keys
        for k in rng.sample(dev_keys, min(p.unexplained_batches, len(dev_keys))):
            b = self.batches[k]
            b["unexplained"] = -rng.randint(180000, 320000)
            b["net"] += b["unexplained"]
            self.gt_exceptions.append({
                "settlement_id": b["settlement_id"], "exception_type": "unexplained",
                "amount": fmt(b["unexplained"]), "genuinely_unresolvable": 1,
                "note": "no source document exists for this residue, by construction",
            })
        self.dup_keys = set(rng.sample([k for k in keys if self.batches[k]["net"] > 0],
                                       min(p.duplicate_payout_batches, len(keys))))

    # -------------------------------------------------------------- bank rows
    def _bank(self, d, amount, text, utr, ops, kind, ref=None):
        row = {
            "bank_txn_id": self._id("bnk", 16), "value_date": d.isoformat(),
            "amount": fmt(amount), "narration": text,
        }
        self.bank_rows.append(row)
        self.gt_narration.append({
            "bank_txn_id": row["bank_txn_id"], "true_utr": utr or "",
            "corruption_ops": "|".join(ops), "kind": kind, "reference_entity": ref or "",
            "utr_survives_verbatim": 1 if (utr and utr in text) else 0,
        })
        return row

    def emit_bank(self):
        p, rng = self.p, self.rng

        def payee():
            return rng.choice(N.PAYEE_VARIANTS)

        for key in sorted(self.batches):
            b = self.batches[key]
            rail = rng.choices(p.narration_styles, weights=p.narration_style_weights)[0]
            utr = N.make_utr(rng, p.bank_code, rail, b["settle_date"])
            b["utr"], b["rail"] = utr, rail
            tmpl = N.TEMPLATES[rail]
            clean = tmpl.format(utr=utr, payee=payee(), bank=p.bank_code)
            if rail == "bare":
                dirty, ops = clean, ["no_utr_in_source"]
            else:
                dirty, ops = N.corrupt(rng, clean, utr, p.corruption_aggressiveness)

            if key in self.dup_keys:
                # Failed payout retried under a NEW utr -> duplicate-credit risk.
                # settlements.csv carries only the retry UTR.
                utr1 = N.make_utr(rng, p.bank_code, rail, b["settle_date"])
                self._bank(b["settle_date"], b["net"],
                           tmpl.format(utr=utr1, payee=payee(), bank=p.bank_code),
                           utr1, ["failed_payout_original"], "payout_credit_reversed",
                           b["settlement_id"])
                rdt = add_banking_days(b["settle_date"], 1)
                self._bank(rdt, -b["net"],
                           rng.choice(N.PAYOUT_RETURN_TEMPLATES).format(utr=utr1),
                           utr1, ["payout_return"], "payout_return", b["settlement_id"])
                row = self._bank(rdt, b["net"], dirty, utr, ops + ["retry_new_utr"],
                                 "payout_credit", b["settlement_id"])
            else:
                row = self._bank(b["settle_date"], b["net"], dirty, utr, ops,
                                 "payout_credit", b["settlement_id"])
            b["bank_txn_id"] = row["bank_txn_id"]

        for r in self.refunds:
            if r["netted_flag"]:
                continue
            t = rng.choice(N.REFUND_TEMPLATES).format(
                utr=N.make_utr(rng, p.bank_code, "neft", r["_settle_date"]),
                ref=r["refund_id"], payee=payee())
            dirty, ops = N.corrupt(rng, t, None, p.corruption_aggressiveness)
            self._bank(r["_settle_date"], -r["amount_paise"], dirty, None, ops,
                       "refund_debit", r["refund_id"])

        for c in self.chargebacks:
            if c["netted"]:
                continue
            d = datetime.fromisoformat(c["raised_at_ist"]).date()
            t = rng.choice(N.CHARGEBACK_TEMPLATES).format(
                ref=c["dispute_id"], payee=payee(), bank=p.bank_code)
            dirty, ops = N.corrupt(rng, t, None, p.corruption_aggressiveness)
            self._bank(add_banking_days(d, 1), -c["amount_paise"], dirty, None, ops,
                       "chargeback_debit", c["dispute_id"])

        for e in self.reserve_ledger:
            if e["netted_flag"]:
                continue
            u = N.make_utr(rng, p.bank_code, "neft", e["_rel"])
            t = rng.choice(N.RESERVE_TEMPLATES).format(utr=u, payee=payee())
            dirty, ops = N.corrupt(rng, t, u, p.corruption_aggressiveness)
            self._bank(e["_rel"], e["amount_paise"], dirty, u, ops,
                       "reserve_release_credit", e["reserve_id"])

        self.bank_rows.sort(key=lambda r: (r["value_date"], r["bank_txn_id"]))

    # ----------------------------------------------------------- ground truth
    def build_ground_truth(self):
        for key in sorted(self.batches):
            b = self.batches[key]
            for pay in sorted(b["payments"], key=lambda x: x["payment_id"]):
                self.gt_links.append({
                    "payment_id": pay["payment_id"], "settlement_id": b["settlement_id"],
                    "bank_txn_id": b["bank_txn_id"], "amount": fmt(pay["amount_paise"]),
                    "method": pay["method"], "fee": fmt(pay["_fee"]),
                    "gst": fmt(pay["_gst"]), "tds": fmt(pay["_tds"]),
                    "settle_date": b["settle_date"].isoformat(), "split": b["split"],
                    "ambiguous": 1 if pay["_amb"] else 0,
                })
            comps = [
                ("gross", b["gross"]), ("platform_fee", -b["fee"]),
                ("gst_on_fee", -b["gst"]), ("tds_194o", -b["tds"]),
                ("refund_netted", -b["refund_netted"]),
                ("chargeback_debit", -b["chargeback"]), ("dispute_fee", -b["dispute_fee"]),
                ("dispute_fee_gst", -b["dispute_gst"]),
                ("chargeback_reversal", b["reversal"]),
                ("reserve_held", -b["reserve_held"]),
                ("reserve_released", b["reserve_released"]),
                ("rounding_drift", b["drift"]), ("unexplained", b["unexplained"]),
            ]
            total = sum(v for _, v in comps)
            assert total == b["net"], (b["settlement_id"], total, b["net"])
            for name, v in comps:
                if v == 0 and name != "gross":
                    continue
                self.gt_decomp.append({
                    "bank_txn_id": b["bank_txn_id"], "settlement_id": b["settlement_id"],
                    "component": name, "amount": fmt(v), "split": b["split"],
                })
            self.gt_decomp.append({
                "bank_txn_id": b["bank_txn_id"], "settlement_id": b["settlement_id"],
                "component": "TOTAL_bank_credit", "amount": fmt(b["net"]),
                "split": b["split"],
            })

    # ------------------------------------------------------------------ write
    def _write(self, path, rows, cols):
        full = os.path.join(self.out, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w", newline="", encoding="utf-8") as fh:
            w = csv.DictWriter(fh, fieldnames=cols, extrasaction="ignore")
            w.writeheader()
            w.writerows(rows)
        return full

    def write(self):
        p, rng = self.p, self.rng
        self._write("observable/orders.csv", self.orders,
                    ["order_id", "customer_id", "amount", "created_at_utc"])
        self._write("observable/payments.csv",
                    [dict(x, amount=fmt(x["amount_paise"])) for x in self.payments],
                    ["payment_id", "order_id", "amount", "method", "status", "created_at_ist"])
        self._write("observable/refunds.csv",
                    [dict(r, amount=fmt(r["amount_paise"])) for r in self.refunds],
                    ["refund_id", "payment_id", "amount", "created_at_ist", "netted_flag"])
        self._write("observable/chargebacks.csv",
                    [dict(c, amount=fmt(c["amount_paise"]), fee=fmt(c["fee_paise"]))
                     for c in self.chargebacks],
                    ["dispute_id", "payment_id", "amount", "fee", "raised_at_ist",
                     "reversed_at_ist"])

        setl = []
        for key in sorted(self.batches):
            b = self.batches[key]
            utr = "" if rng.random() < 0.03 else b["utr"]
            setl.append({
                "settlement_id": b["settlement_id"], "utr": utr,
                "gross": fmt(b["gross"]), "fees": fmt(b["fee"]), "gst": fmt(b["gst"]),
                "tds": fmt(b["tds"]),
                "net": fmt(b["net"] - b["drift"] - b["unexplained"]),
                "settled_at": iso(_dt(b["settle_date"], 18, 30, 0)),
                "instant": 1 if b["instant"] else 0,
            })
        self._write("observable/settlements.csv", setl,
                    ["settlement_id", "utr", "gross", "fees", "gst", "tds", "net",
                     "settled_at", "instant"])
        self._write("observable/bank_statement.csv", self.bank_rows,
                    ["bank_txn_id", "value_date", "amount", "narration"])

        self._write("ground_truth/ground_truth_links.csv", self.gt_links,
                    ["payment_id", "settlement_id", "bank_txn_id", "amount", "method",
                     "fee", "gst", "tds", "settle_date", "split", "ambiguous"])
        self._write("ground_truth/ground_truth_decomposition.csv", self.gt_decomp,
                    ["bank_txn_id", "settlement_id", "component", "amount", "split"])
        self._write("ground_truth/ground_truth_narration.csv", self.gt_narration,
                    ["bank_txn_id", "true_utr", "corruption_ops", "kind", "reference_entity",
                     "utr_survives_verbatim"])
        self._write("ground_truth/ground_truth_ambiguity.csv", self.gt_ambiguity,
                    ["payment_id_a", "payment_id_b", "settlement_id_a", "settlement_id_b",
                     "amount", "method", "capture_date_ist", "reason"])
        self._write("ground_truth/ground_truth_exceptions.csv", self.gt_exceptions,
                    ["settlement_id", "exception_type", "amount",
                     "genuinely_unresolvable", "note"])
        # The rolling-reserve statement IS something a merchant receives. Hiding
        # it would make reserve movements unexplainable by construction, which
        # would be a flaw in the benchmark rather than a hard test.
        self._write("observable/reserve_ledger.csv", self.reserve_ledger,
                    ["reserve_id", "amount", "held_on", "release_date",
                     "released_at_ist", "netted_flag"])
        self._write("ground_truth/splits.csv",
                    [{"settlement_id": self.batches[k]["settlement_id"],
                      "settle_date": self.batches[k]["settle_date"].isoformat(),
                      "cycle": self.batches[k]["cycle"],
                      "split": self.batches[k]["split"]} for k in sorted(self.batches)],
                    ["settlement_id", "settle_date", "cycle", "split"])

        os.makedirs(os.path.join(self.out, "config"), exist_ok=True)
        with open(os.path.join(self.out, "config", "rate_card_published.json"), "w",
                  encoding="utf-8") as fh:
            json.dump({
                "merchant_id": p.merchant_id, "name": p.name,
                "gst_rate_pct": str(GST_RATE), "tds_rate_pct": str(TDS_RATE),
                "reserve_rate_pct": str(RESERVE_RATE),
                "reserve_methods": sorted(RESERVE_METHODS), "reserve_hold_days": 90,
                "instant_surcharge_pct": str(INSTANT_SURCHARGE),
                "dispute_fee": fmt(DISPUTE_FEE_PAISE), "tolerance_paise": 100,
                "methods": p.published_rate_card(),
            }, fh, indent=2)

    def run(self):
        self.gen_payments()
        self.build_batches()
        self.inject_ambiguity_traps()
        self.gen_refunds()
        self.gen_chargebacks()
        self.gen_reserve()
        self.compute()
        self.inject_residues()
        self.emit_bank()
        self.build_ground_truth()
        self.write()
        return self
