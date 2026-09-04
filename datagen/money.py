"""Money primitives.

RULE: currency is an integer number of paise everywhere. Never float/double.
Decimal is used only as an intermediate for rate arithmetic, always with an
explicit rounding mode. Java (BigDecimal, RoundingMode.HALF_UP) mirrors this.
"""
from decimal import Decimal, ROUND_HALF_UP

ONE = Decimal(1)
HUNDRED = Decimal(100)


def rupees(value) -> int:
    """'1234.56' | Decimal | int rupees -> paise (int)."""
    d = Decimal(str(value))
    return int((d * HUNDRED).quantize(ONE, rounding=ROUND_HALF_UP))


def fmt(paise: int) -> str:
    """paise -> '1234.56' (always 2dp, sign preserved)."""
    sign = "-" if paise < 0 else ""
    p = abs(int(paise))
    return f"{sign}{p // 100}.{p % 100:02d}"


def fmt_inr(paise: int) -> str:
    """paise -> '12,34,567.89' Indian digit grouping, for reports."""
    sign = "-" if paise < 0 else ""
    p = abs(int(paise))
    whole, frac = divmod(p, 100)
    s = str(whole)
    if len(s) > 3:
        head, tail = s[:-3], s[-3:]
        parts = []
        while len(head) > 2:
            parts.insert(0, head[-2:])
            head = head[:-2]
        if head:
            parts.insert(0, head)
        s = ",".join(parts + [tail])
    return f"{sign}{s}.{frac:02d}"


def lakh(paise: int) -> str:
    return f"{Decimal(paise) / Decimal(10000000):.2f}L"


def pct_of(paise: int, pct: Decimal) -> int:
    """percentage of an amount, HALF_UP to whole paise."""
    return int((Decimal(paise) * pct / HUNDRED).quantize(ONE, rounding=ROUND_HALF_UP))
