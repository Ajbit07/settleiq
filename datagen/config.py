"""Rate cards, merchant profiles, generator knobs.

CRITICAL SPLIT OF KNOWLEDGE
---------------------------
RATE_CARD_TRUE      : used by the generator to produce the money. Ground truth.
published_rate_card : written to disk for the engine/ML service to consume.

The published card deliberately OMITS the mid-month card-rate change. The
engine is never told the rate moved; it must surface the drift as a
`fee_variance` exception. Handing the model the generator's parameters would
make the benchmark meaningless.
"""
from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal

GST_RATE = Decimal("18.0")          # on platform fee
TDS_RATE = Decimal("1.0")           # u/s 194-O on eligible gross
RESERVE_RATE = Decimal("5.0")       # rolling reserve
RESERVE_HOLD_DAYS = 90
INSTANT_SURCHARGE = Decimal("0.25")  # extra pct on instant settlement
DISPUTE_FEE_PAISE = 30000           # Rs 300 flat per chargeback
CHARGEBACK_REVERSAL_SHARE = 0.35


@dataclass(frozen=True)
class RateTier:
    effective_from: date
    pct: Decimal            # percentage of gross
    flat_paise: int         # flat fee per transaction


@dataclass
class MerchantProfile:
    merchant_id: str
    name: str
    seed: int
    n_payments: int
    period_start: date
    period_end: date
    method_mix: dict            # method -> weight
    amount_bands: list          # (weight, lo_paise, hi_paise)
    failure_rate: float
    refund_count: int
    chargeback_count: int
    cycles_per_day: int
    instant_batches: int
    tds_eligible_share: float
    bank_code: str
    narration_styles: list
    narration_style_weights: list
    corruption_aggressiveness: float
    rate_card_true: dict
    rate_card_change: tuple = None   # (method, new_pct, effective_from) hidden from engine
    ambiguity_pairs: int = 6
    drift_batches: int = 8
    duplicate_payout_batches: int = 1
    unexplained_batches: int = 1

    def published_rate_card(self) -> dict:
        """What the engine is allowed to see: base tiers only, no mid-month change."""
        out = {}
        for method, tiers in self.rate_card_true.items():
            base = min(tiers, key=lambda t: t.effective_from)
            out[method] = [{
                "effective_from": base.effective_from.isoformat(),
                "pct": str(base.pct),
                "flat_paise": base.flat_paise,
            }]
        return out

    def rate_for(self, method: str, on: date) -> RateTier:
        tiers = sorted(self.rate_card_true[method], key=lambda t: t.effective_from)
        chosen = tiers[0]
        for t in tiers:
            if on >= t.effective_from:
                chosen = t
        return chosen


P0 = date(2025, 11, 1)
P1 = date(2025, 11, 30)

_BASE_CARD = {
    "upi":        [RateTier(P0, Decimal("0.00"), 0)],
    "card":       [RateTier(P0, Decimal("2.00"), 0),
                   RateTier(date(2025, 11, 16), Decimal("2.30"), 0)],   # HIDDEN change
    "netbanking": [RateTier(P0, Decimal("0.00"), 1500)],                # Rs 15 flat
    "wallet":     [RateTier(P0, Decimal("1.80"), 0)],
}

# Out-of-distribution merchant: different mix, different card rate, no mid-month change.
_OOD_CARD = {
    "upi":        [RateTier(P0, Decimal("0.00"), 0)],
    "card":       [RateTier(P0, Decimal("1.75"), 0)],
    "netbanking": [RateTier(P0, Decimal("0.00"), 1200)],                # Rs 12 flat
    "wallet":     [RateTier(P0, Decimal("2.10"), 0)],
}

MERCHANT_A = MerchantProfile(
    merchant_id="merchant_1",
    name="Kirana Direct Retail Pvt Ltd",
    seed=20251101,
    n_payments=5000,
    period_start=P0,
    period_end=P1,
    method_mix={"upi": 0.52, "card": 0.28, "netbanking": 0.09, "wallet": 0.11},
    amount_bands=[
        (0.78, 9900, 108000),        # Rs    99 - Rs  1,080
        (0.18, 108000, 360000),      # Rs 1,080 - Rs  3,600
        (0.04, 360000, 1750000),     # Rs 3,600 - Rs 17,500
    ],
    failure_rate=0.05,
    refund_count=300,
    chargeback_count=40,
    cycles_per_day=4,
    instant_batches=4,
    tds_eligible_share=0.55,
    bank_code="HDFC",
    narration_styles=["neft", "imps", "rtgs", "upi_payout", "bare"],
    narration_style_weights=[0.34, 0.24, 0.24, 0.12, 0.06],
    corruption_aggressiveness=0.95,
    rate_card_true=_BASE_CARD,
    rate_card_change=("card", Decimal("2.30"), date(2025, 11, 16)),
)

MERCHANT_B_OOD = MerchantProfile(
    merchant_id="merchant_2",
    name="Vaayu Logistics Solutions LLP",
    seed=771103,
    n_payments=1800,
    period_start=P0,
    period_end=P1,
    # B2B-ish: netbanking heavy, low UPI, fat tickets
    method_mix={"upi": 0.14, "card": 0.22, "netbanking": 0.46, "wallet": 0.18},
    amount_bands=[
        (0.30, 200000, 900000),      # Rs  2,000 - Rs  9,000
        (0.48, 900000, 4500000),     # Rs  9,000 - Rs 45,000
        (0.22, 4500000, 25000000),   # Rs 45,000 - Rs 2,50,000
    ],
    failure_rate=0.08,
    refund_count=90,
    chargeback_count=14,
    cycles_per_day=2,
    instant_batches=2,
    tds_eligible_share=0.85,
    bank_code="ICIC",
    narration_styles=["neft", "rtgs", "corp", "bare"],
    narration_style_weights=[0.30, 0.28, 0.34, 0.08],
    corruption_aggressiveness=1.25,
    rate_card_true=_OOD_CARD,
    rate_card_change=None,
    ambiguity_pairs=3,
    drift_batches=4,
    duplicate_payout_batches=1,
    unexplained_batches=1,
)

PROFILES = {"merchant_a": MERCHANT_A, "merchant_b_ood": MERCHANT_B_OOD}

# Time+batch split boundaries, by SETTLEMENT date. Never split by row.
SPLITS = [
    ("train", date(2025, 11, 1), date(2025, 11, 18)),
    ("dev",   date(2025, 11, 19), date(2025, 11, 24)),
    ("test",  date(2025, 11, 25), date(2025, 12, 31)),
]


def split_for(settle_date: date) -> str:
    for name, lo, hi in SPLITS:
        if lo <= settle_date <= hi:
            return name
    return "test"


# ---------------------------------------------------------------------------
# TRAINING POOL
#
# Extra merchant-months drawn from the same generative process as MERCHANT_A
# but with different seeds. The pair scorer is fitted on these so that
# merchant_a/dev is free for threshold tuning and merchant_a/test is touched
# exactly once, at the end. Training on merchant_a/train alone would give the
# model only a few dozen hard pairs to learn from.
# ---------------------------------------------------------------------------
import dataclasses as _dc

def _reseed(base, mid, seed, n=None):
    return _dc.replace(base, merchant_id=mid, seed=seed,
                       n_payments=n or base.n_payments)

TRAIN_POOL = {
    "train_t1": _reseed(MERCHANT_A, "mrch_T1RNGA", 5150011, 4200),
    "train_t2": _reseed(MERCHANT_A, "mrch_T2RNGB", 8820247, 4600),
    "train_t3": _reseed(MERCHANT_B_OOD, "mrch_T3RNGC", 3310992, 1500),
}
PROFILES.update(TRAIN_POOL)
