"""Indian banking calendar + IST/UTC handling.

RBI convention modelled here:
  - Sundays are bank holidays
  - 2nd and 4th Saturdays are bank holidays (1st/3rd/5th Saturdays are working)
  - plus a declared holiday list

The declared list below is a CONFIGURED calendar for the synthetic period.
It is part of the noise model, not a claim about any real bank's calendar.
"""
from datetime import date, datetime, timedelta, timezone

IST = timezone(timedelta(hours=5, minutes=30))
UTC = timezone.utc

# Configured holiday calendar for the synthetic window (Oct-Dec 2025).
DECLARED_HOLIDAYS = {
    date(2025, 10, 21),   # festival cluster (pre-window, affects reserve releases)
    date(2025, 11, 5),    # Guru Nanak Jayanti
    date(2025, 11, 14),   # configured regional holiday -> forces a Fri holiday shift
    date(2025, 11, 25),   # configured regional holiday -> forces a Tue holiday shift
    date(2025, 12, 25),   # Christmas
}


def nth_saturday(d: date) -> int:
    """For a Saturday, which Saturday of the month it is (1-based)."""
    return (d.day - 1) // 7 + 1


def is_bank_holiday(d: date) -> bool:
    if d.weekday() == 6:                      # Sunday
        return True
    if d.weekday() == 5 and nth_saturday(d) in (2, 4):
        return True
    return d in DECLARED_HOLIDAYS


def is_banking_day(d: date) -> bool:
    return not is_bank_holiday(d)


def next_banking_day(d: date) -> date:
    cur = d + timedelta(days=1)
    while is_bank_holiday(cur):
        cur += timedelta(days=1)
    return cur


def add_banking_days(d: date, n: int) -> date:
    """T+n where n counts banking days. T+0 rolls forward if T is a holiday."""
    cur = d
    while is_bank_holiday(cur):
        cur += timedelta(days=1)
    for _ in range(n):
        cur = next_banking_day(cur)
    return cur


def banking_days_between(a: date, b: date) -> int:
    """Signed count of banking days from a to b (feature for the pair scorer)."""
    if a == b:
        return 0
    sign, lo, hi = (1, a, b) if b > a else (-1, b, a)
    n, cur = 0, lo
    while cur < hi:
        cur += timedelta(days=1)
        if is_banking_day(cur):
            n += 1
    return sign * n


def to_ist(dt_utc: datetime) -> datetime:
    return dt_utc.astimezone(IST)


def to_utc(dt_ist: datetime) -> datetime:
    return dt_ist.astimezone(UTC)


def iso(dt: datetime) -> str:
    return dt.isoformat(timespec="seconds")
