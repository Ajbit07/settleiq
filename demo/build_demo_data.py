#!/usr/bin/env python3
"""
Build a small, hand-checked dataset that exercises one reconciliation outcome
per settlement, so each can be pointed at individually on screen.

Every figure here is computed the way the engine computes it, in integer paise,
from the demo merchant's own rate card. Nothing is fitted after the fact: if a
number below is wrong, the run will disagree with it and the exception will land
somewhere else. That is the point -- this file is a prediction, and `verify.py`
checks it.

Rate card for merchant_3 (see setup.sql):
    upi 0.00%        card 2.00%       wallet 1.80%      netbanking flat 15.00
    GST 18% on fee   TDS 1% (batch)   reserve 5% on card+wallet
    dispute fee 300.00 (+18% GST)     tolerance 100 paise

Settlement timing: a regular batch settling on D draws payments captured D-2
BANKING days, and the banking calendar counts ordinary Saturdays as working
days -- only Sundays and the 2nd/4th Saturday are holidays. So in Dec 2025:

    capture Mon 01 -> settle Wed 03      capture Fri 05 -> settle Mon 08
    capture Tue 02 -> settle Thu 04      capture Mon 08 -> settle Wed 10
    capture Wed 03 -> settle Fri 05      capture Tue 09 -> settle Thu 11
    capture Thu 04 -> settle Sat 06      capture Wed 10 -> settle Fri 12

Saturday the 6th is the FIRST Saturday, hence a working day. Getting this wrong
silently empties a batch's candidate pool: the payments land under a settle date
no settlement claims, membership goes unproven, and the case classifies as
partition_unproven instead of whatever it was meant to show.
"""
import csv, pathlib

OUT = pathlib.Path(__file__).parent / "data"
OUT.mkdir(exist_ok=True)

R = 100                      # paise per rupee
def rs(p):                   # paise -> the "1234.56" form the CSVs use
    return f"{p // R}.{p % R:02d}"

def pct(amount, p):          # HALF_UP percent, the engine's rounding
    return (amount * p + 5000) // 10000        # p in basis points x100

CARD, UPI, WALLET = "card", "upi", "wallet"
FEE_BPS = {UPI: 0, CARD: 200, WALLET: 180}     # percent x100
GST_BPS, RESERVE_BPS = 1800, 500
RESERVE_METHODS = {CARD, WALLET}

INSTANT_BPS = 25                               # 0.25% surcharge on instant payouts

def fee_of(amount, method, instant=False):
    f = pct(amount, FEE_BPS[method])
    if instant:
        f += pct(amount, INSTANT_BPS)
    return f

def gst_of(fee):
    return pct(fee, GST_BPS)

def reserve_of(amount, method, instant=False):
    # An instant payout holds no reserve: FeeModel.reserveHeld short-circuits on
    # the instant flag before it looks at the method.
    if instant or method not in RESERVE_METHODS:
        return 0
    f = fee_of(amount, method, instant)
    return pct(amount - f - gst_of(f), RESERVE_BPS)

payments, orders, settlements, bank = [], [], [], []
refunds, chargebacks, reserves = [], [], []
expect = []                  # what each settlement should classify as

def payment(pid, oid, amount, method, capture):
    payments.append([pid, oid, rs(amount), method, "captured", f"{capture}T11:00:00+05:30"])
    orders.append([oid, f"cust_{pid[-3:]}", rs(amount), f"{capture}T05:30:00"])
    return {"id": pid, "amount": amount, "method": method}

def settle(sid, utr, members, tds, settled, extra_deductions=0, instant=False,
           gross_override=None):
    """Emit the settlement row and return what the engine will predict for it."""
    gross = sum(m["amount"] for m in members) if gross_override is None else gross_override
    fee = sum(fee_of(m["amount"], m["method"], instant) for m in members)
    gst = sum(gst_of(fee_of(m["amount"], m["method"], instant)) for m in members)
    reserve = sum(reserve_of(m["amount"], m["method"], instant) for m in members)
    # The report's own columns must satisfy gross - fees - gst - tds = net or
    # ingestion rejects the row before reconciliation ever sees it.
    net = gross - fee - gst - tds
    settlements.append([sid, utr, rs(gross), rs(fee), rs(gst), rs(tds), rs(net),
                        f"{settled}T18:30:00+05:30", "true" if instant else "false"])
    predicted = gross - fee - gst - tds - reserve - extra_deductions
    return predicted

def credit(bid, value_date, amount, utr, narration=None):
    bank.append([bid, value_date, rs(amount),
                 narration or f"NEFT-{utr}-DEMO-SETTLEMENT"])

# ── S1 · closes to the paise ──────────────────────────────────────────────
# Two UPI captures, zero platform fee, TDS the only deduction. The bank pays
# exactly what the decomposition predicts.
m = [payment("pay_d01", "ord_d01", 1_000_000, UPI, "2025-12-01"),
     payment("pay_d02", "ord_d02",   500_000, UPI, "2025-12-01")]
p = settle("setl_DEMO_0001", "HDFCN520251200001", m, tds=15_000, settled="2025-12-03")
credit("bnk_d01", "2025-12-03", p, "HDFCN520251200001")
expect.append(("setl_DEMO_0001", "no exception", "residue 0 - PROVED"))

# ── S2 · fee variance ─────────────────────────────────────────────────────
# Card volume 100,000.00. The bank pays 250.00 less than the published 2.00%
# card rate predicts: an effective 2.25%. check_rate_card reads that as an
# implied 0.25% excess, inside the 0.02-1.50% band that means "fee variance".
m = [payment("pay_d03", "ord_d03", 6_000_000, CARD, "2025-12-02"),
     payment("pay_d04", "ord_d04", 4_000_000, CARD, "2025-12-02")]
p = settle("setl_DEMO_0002", "HDFCN520251200002", m, tds=100_000, settled="2025-12-04")
credit("bnk_d02", "2025-12-04", p - 25_000, "HDFCN520251200002")
expect.append(("setl_DEMO_0002", "fee_variance", "residue -250.00 on 100,000.00 card volume"))

# ── S3 · refund explains the gap ──────────────────────────────────────────
# A 3,000.00 refund on pay_d05, netted into the payout by the platform AND
# debited again by the bank, so the credit is short by exactly the refund.
#
# netted_flag MUST be true: get_refunds only looks at refunds marked netted
# whose payment is a proven member of the batch. A refund left unflagged is
# invisible to the investigation and the break classifies as unexplained --
# correctly, since nothing on file would tie the two together.
m = [payment("pay_d05", "ord_d05", 2_000_000, UPI, "2025-12-03"),
     payment("pay_d06", "ord_d06",   800_000, UPI, "2025-12-03")]
refunds.append(["rfnd_d01", "pay_d05", rs(300_000), "2025-12-03T14:00:00+05:30", "true"])
p = settle("setl_DEMO_0003", "HDFCN520251200003", m, tds=28_000, settled="2025-12-05",
           extra_deductions=300_000)
credit("bnk_d03", "2025-12-05", p - 300_000, "HDFCN520251200003")
expect.append(("setl_DEMO_0003", "netted_refund", "residue -3,000.00 = refund rfnd_d01"))

# ── S4 · chargeback debited twice ─────────────────────────────────────────
# The dispute and its fee are already netted by the engine; the bank debits the
# same 2,000.00 + 300.00 a second time, so the residue equals the total.
m = [payment("pay_d07", "ord_d07", 5_000_000, CARD, "2025-12-04")]
chargebacks.append(["disp_d01", "pay_d07", rs(200_000), rs(30_000),
                    "2025-12-04T10:00:00+05:30", ""])
cb_deduction = 200_000 + 30_000 + pct(30_000, GST_BPS)     # amount + fee + GST on fee
p = settle("setl_DEMO_0004", "HDFCN520251200004", m, tds=50_000, settled="2025-12-06",
           extra_deductions=cb_deduction)
credit("bnk_d04", "2025-12-06", p - 230_000, "HDFCN520251200004")
expect.append(("setl_DEMO_0004", "chargeback_debit", "residue -2,300.00 = disp_d01 amount + fee"))

# ── S5 / S6 · two batches nothing can separate ────────────────────────────
# Both settle 2025-12-09 and both contain a 25,000.00 card capture, so the
# swap-invariance check (which keys on settle date, amount, method and rate
# tier) flags them against each other.
#
# S5 is an instant (T+0) batch drawing same-day captures; S6 is a regular T+2
# batch drawing 2025-12-05. Separate candidate pools mean the partition solver
# can still PROVE each batch's membership -- otherwise it refuses outright and
# the case classifies as partition_unproven instead, which is a different story.
#
# Both reconcile to the paise. They are held anyway, which is the point: the
# arithmetic is perfect and the engine still will not post.
a1 = payment("pay_d08", "ord_d08", 2_500_000, CARD, "2025-12-08")   # instant, same day
a2 = payment("pay_d09", "ord_d09",   700_000, UPI,  "2025-12-08")
b1 = payment("pay_d10", "ord_d10", 2_500_000, CARD, "2025-12-05")   # regular, T+2
b2 = payment("pay_d11", "ord_d11",   900_000, UPI,  "2025-12-05")
p5 = settle("setl_DEMO_0005", "HDFCN520251200005", [a1, a2], tds=32_000,
            settled="2025-12-08", instant=True)
p6 = settle("setl_DEMO_0006", "HDFCN520251200006", [b1, b2], tds=34_000,
            settled="2025-12-08")
credit("bnk_d05", "2025-12-08", p5, "HDFCN520251200005")
credit("bnk_d06", "2025-12-08", p6, "HDFCN520251200006")
expect.append(("setl_DEMO_0005", "ambiguous_candidates",
               "residue 0 and still held - pay_d08 / pay_d10 are swap-invariant"))
expect.append(("setl_DEMO_0006", "ambiguous_candidates",
               "residue 0 and still held - pay_d08 / pay_d10 are swap-invariant"))


# ── S7 · nothing on file explains it ──────────────────────────────────────
# UPI only, so there is no card volume for a rate-card story, and no refund,
# chargeback or reserve row of the right size. The engine says so.
m = [payment("pay_d12", "ord_d12", 1_200_000, UPI, "2025-12-09")]
p = settle("setl_DEMO_0007", "HDFCN520251200007", m, tds=12_000, settled="2025-12-11")
credit("bnk_d07", "2025-12-11", p - 45_000, "HDFCN520251200007")
expect.append(("setl_DEMO_0007", "unexplained", "residue -450.00, no source document"))

# ── S8 · reserve release ──────────────────────────────────────────────────
# A 5,000.00 reserve released on the settle date, not flagged netted, so the
# credit is short by exactly the released amount.
m = [payment("pay_d13", "ord_d13", 3_000_000, WALLET, "2025-12-10")]
p = settle("setl_DEMO_0008", "HDFCN520251200008", m, tds=30_000, settled="2025-12-12")
reserves.append(["rsv_d01", rs(500_000), "2025-09-12", "2025-12-12",
                 "2025-12-12T09:00:00+05:30", "false"])
credit("bnk_d08", "2025-12-12", p - 500_000, "HDFCN520251200008")
expect.append(("setl_DEMO_0008", "reserve_movement", "residue -5,000.00 = rsv_d01"))

# ── S9 · small enough to post itself ──────────────────────────────────────
# The same kind of break as S2, two orders of magnitude smaller. Every policy
# gate passes: fee_variance is on the auto-post allowlist, confidence is 0.96,
# the batch is not ambiguous, and 60.00 is under BOTH the flat 100.00 ceiling
# and 0.1% of a 200,000.00 batch. So the engine posts it without asking.
#
# Captured Thu the 11th: Fri the 12th is one banking day, Sat the 13th is the
# SECOND Saturday and a holiday, so the second banking day is Mon the 15th.
m = [payment("pay_d14", "ord_d14", 20_000_000, CARD, "2025-12-11")]
p = settle("setl_DEMO_0009", "HDFCN520251200009", m, tds=200_000, settled="2025-12-15")
credit("bnk_d09", "2025-12-15", p - 6_000, "HDFCN520251200009")
expect.append(("setl_DEMO_0009", "fee_variance",
               "residue -60.00 - passes every gate, auto-posted"))

# ── S10 · no bank credit at all ───────────────────────────────────────────
# The settlement reports a payout that never arrived. There is no bank row to
# link, so check_duplicate_credit flags it unmatched: a reversed payout or a
# retry under a new reference are the usual causes.
m = [payment("pay_d15", "ord_d15", 900_000, UPI, "2025-12-15")]
settle("setl_DEMO_0010", "HDFCN520251200010", m, tds=9_000, settled="2025-12-17")
expect.append(("setl_DEMO_0010", "duplicate_credit", "no bank credit links to this settlement"))

# ── S11 · the report claims more than the payments ────────────────────────
# gross reads 7,000.00 but only 5,000.00 of captures mature into this batch, so
# no subset of the pool sums to the reported gross. The solver refuses rather
# than assigning a batch it cannot prove, and the case says membership is
# unproven instead of quietly reconciling against the platform's own figure.
m = [payment("pay_d16", "ord_d16", 500_000, UPI, "2025-12-16")]
p = settle("setl_DEMO_0011", "HDFCN520251200011", m, tds=7_000, settled="2025-12-18",
           gross_override=700_000)
credit("bnk_d11", "2025-12-18", p, "HDFCN520251200011")
expect.append(("setl_DEMO_0011", "partition_unproven", "reported gross exceeds available captures"))

# ── S13 · matched by the model, not by a reference ────────────────────────
# The narration carries NO recognisable reference, so the exact-UTR stage finds
# nothing and the learned pair scorer has to decide the link on amount, date and
# method. The confidence on this case is therefore below 1.00, which is what
# tells you a model made the match rather than an identifier.
#
# Every token here is under 10 characters on purpose: the normaliser's loosest
# UTR pattern needs 10, so nothing in this string can be mistaken for one.
m = [payment("pay_d17", "ord_d17", 1_600_000, UPI, "2025-12-17")]
p = settle("setl_DEMO_0013", "HDFCN520251200013", m, tds=16_000, settled="2025-12-19")
credit("bnk_d13", "2025-12-19", p - 7_500, None, narration="IMPS PAYOUT CREDIT RETAIL")
expect.append(("setl_DEMO_0013", "unexplained",
               "matched by the scorer, not by a reference - confidence below 1.00"))

# ── write ─────────────────────────────────────────────────────────────────
FILES = {
    "payments.csv":    ("payment_id,order_id,amount,method,status,created_at_ist", payments),
    "orders.csv":      ("order_id,customer_id,amount,created_at_utc", orders),
    "settlements.csv": ("settlement_id,utr,gross,fees,gst,tds,net,settled_at,instant", settlements),
    "bank.csv":        ("bank_txn_id,value_date,amount,narration", bank),
    "refunds.csv":     ("refund_id,payment_id,amount,created_at_ist,netted_flag", refunds),
    "chargebacks.csv": ("dispute_id,payment_id,amount,fee,raised_at_ist,reversed_at_ist", chargebacks),
    "reserve.csv":     ("reserve_id,amount,held_on,release_date,released_at_ist,netted_flag", reserves),
}
for name, (header, rows) in FILES.items():
    with open(OUT / name, "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh, lineterminator="\n")
        w.writerow(header.split(","))
        w.writerows(rows)
    print(f"  {name:<18} {len(rows):>3} rows")

# A second copy, numbered and named after the entity each one targets, for
# uploading by hand on the Ingest screen. Generated rather than copied by hand
# so the two sets cannot drift apart.
UPLOAD = pathlib.Path(__file__).parent / "upload"
UPLOAD.mkdir(exist_ok=True)
ORDER = [("1_PAYMENTS", "payments.csv"), ("2_ORDERS", "orders.csv"),
         ("3_SETTLEMENTS", "settlements.csv"), ("4_BANK", "bank.csv"),
         ("5_REFUNDS", "refunds.csv"), ("6_CHARGEBACKS", "chargebacks.csv"),
         ("7_RESERVE", "reserve.csv"),
         ("8_PAYMENTS_with_errors", "payments_with_errors.csv")]
for name, src in ORDER:
    srcp = OUT / src
    if srcp.exists():
        (UPLOAD / f"{name}.csv").write_text(srcp.read_text(encoding="utf-8"), encoding="utf-8")
print(f"upload/ refreshed ({len(ORDER)} files)")

with open(OUT / "expected.txt", "w", encoding="utf-8") as fh:
    for sid, cls, why in expect:
        fh.write(f"{sid}\t{cls}\t{why}\n")
print(f"\n{len(payments)} payments, {len(settlements)} settlements, {len(bank)} bank credits")
print("expected outcomes written to data/expected.txt")
