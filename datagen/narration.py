"""Bank narration synthesis + corruption.

Real bank statements are the dirtiest surface in the whole pipeline. This module
emits narrations from several templates and then applies a recorded sequence of
corruption operators. Every corruption is logged so the evaluator can score
UTR-repair accuracy per operator instead of as one blended number.
"""
import random
import string

DIGITS = "0123456789"
ALNUM = string.ascii_uppercase + DIGITS

# Bank OCR / keying confusions, applied to reference tokens only.
CONFUSIONS = {"O": "0", "0": "O", "I": "1", "1": "I", "S": "5", "5": "S", "B": "8", "8": "B", "Z": "2", "2": "Z"}


def make_utr(rng: random.Random, bank_code: str, rail: str, d) -> str:
    """Rail-appropriate UTR shapes."""
    if rail == "neft":
        return f"{bank_code}N{rng.randint(1,9)}{d:%Y%m%d}{''.join(rng.choice(DIGITS) for _ in range(8))}"
    if rail == "rtgs":
        return f"{bank_code}R{rng.randint(1,9)}{d:%Y%m%d}{''.join(rng.choice(DIGITS) for _ in range(6))}"
    if rail == "imps":
        return "".join(rng.choice(DIGITS) for _ in range(12))
    if rail == "upi_payout":
        return "".join(rng.choice(DIGITS) for _ in range(12))
    return "".join(rng.choice(ALNUM) for _ in range(16))


PAYEE_VARIANTS = [
    "RAZORPAY SOFTWARE PVT LTD", "RAZORPAY SOFTWARE PRIVATE LIMITED",
    "RAZORPAYSOFTWAREP", "RZPY SOFTWARE", "RAZORPAY SOFT PVT L",
    "RAZORPAYSOF", "RZP SETTLEMENT",
]

TEMPLATES = {
    "neft":       "NEFT-{utr}-{payee}-{bank}-SETTLEMENT",
    "imps":       "IMPS/{utr}/{payee}/PAYOUT",
    "rtgs":       "RTGS CR {utr} {payee} SETTLMNT",
    "upi_payout": "UPI/{utr}/PAYOUT/{payee}",
    "corp":       "CMS-CR/{bank}/{utr}/{payee}/MERCHANT SETTLEMENT",
    "bare":       "{payee} SETTLEMENT CREDIT",       # UTR genuinely absent
}

REFUND_TEMPLATES = [
    "NEFT-DR-{utr}-REFUND-{ref}",
    "DEBIT/{ref}/REFUND ADJ/{payee}",
    "REFUND RECOVERY {ref} {payee}",
]
CHARGEBACK_TEMPLATES = [
    "CHARGEBACK DR {ref} {payee}",
    "DISPUTE DEBIT/{ref}/{bank}",
    "CB-DEBIT-{ref}-CARD NETWORK",
]
REVERSAL_TEMPLATES = [
    "CHARGEBACK REVERSAL CR {ref} {payee}",
    "CB-REV/{ref}/REPRESENTMENT WON",
]
RESERVE_TEMPLATES = [
    "RESERVE RELEASE CR {utr} {payee}",
    "ROLLING RESERVE REL/{utr}/{payee}",
]
PAYOUT_RETURN_TEMPLATES = [
    "NEFT RETURN DR {utr} BENEFICIARY A/C CLOSED",
    "PAYOUT REVERSAL/{utr}/RETURN-R03",
]


def _confuse(rng, token, k):
    chars = list(token)
    idxs = [i for i, c in enumerate(chars) if c in CONFUSIONS]
    rng.shuffle(idxs)
    for i in idxs[:k]:
        chars[i] = CONFUSIONS[chars[i]]
    return "".join(chars)


def corrupt(rng: random.Random, text: str, utr: str, aggressiveness: float):
    """Apply corruption operators. Returns (text, ops_applied)."""
    ops = []
    if utr and utr in text:
        r = rng.random()
        if r < 0.16 * aggressiveness:
            k = rng.randint(2, 5)
            text = text.replace(utr, utr[:-k], 1)
            ops.append(f"truncate_utr:{k}")
        elif r < 0.30 * aggressiveness:
            k = rng.randint(1, 3)
            text = text.replace(utr, _confuse(rng, utr, k), 1)
            ops.append(f"charswap_utr:{k}")
        elif r < 0.36 * aggressiveness:
            text = text.replace(utr, "", 1)
            ops.append("drop_utr")
        elif r < 0.42 * aggressiveness:
            head = utr[: rng.randint(4, 8)]
            text = text.replace(utr, head + " " + utr[len(head):], 1)
            ops.append("split_utr")

    if rng.random() < 0.22 * aggressiveness:
        text = text.replace("-", rng.choice(["/", " ", "--"]))
        ops.append("separator_swap")
    if rng.random() < 0.18 * aggressiveness:
        text = text + "//" + "".join(rng.choice(DIGITS) for _ in range(rng.randint(3, 6)))
        ops.append("junk_suffix")
    if rng.random() < 0.14 * aggressiveness:
        text = "  " + text.replace(" ", "  ", rng.randint(1, 3))
        ops.append("whitespace_noise")
    if rng.random() < 0.10 * aggressiveness:
        text = text.lower() if rng.random() < 0.5 else text.title()
        ops.append("case_flip")
    if rng.random() < 0.12 * aggressiveness:
        text = text[:38] if len(text) > 38 else text
        ops.append("field_truncated_38")
    return text.strip(), ops
