#!/usr/bin/env python3
"""
Check what the engine actually decided against what build_demo_data.py predicted.

The dataset is only useful for a demo if you can point at a settlement and say
what it will show BEFORE clicking it. This is the thing that keeps that true: if
a rate card changes or a deduction rule moves, this fails loudly rather than the
demo quietly showing the wrong story.
"""
import json, pathlib, sys, urllib.request

HERE = pathlib.Path(__file__).parent
API = "http://localhost:8088/api/v1"
M = "merchant_3"

run = (HERE / ".last_run").read_text().strip() if (HERE / ".last_run").exists() else None
if not run:
    sys.exit("no demo/.last_run - run demo/load.sh first")

def get(path):
    return json.load(urllib.request.urlopen(API + path))

credits = get(f"/credits?merchantId={M}&runId={run}")
excs = {e["settlement_id"]: e for e in get(f"/exceptions?merchantId={M}&runId={run}")}
byid = {c["settlement_id"]: c for c in credits}

expected = []
for line in (HERE / "data" / "expected.txt").read_text().splitlines():
    sid, cls, why = line.split("\t")
    expected.append((sid, cls, why))

rupees = lambda p: f"{'-' if p < 0 else ''}{abs(p)//100:,}.{abs(p)%100:02d}"
ok = True
print(f"  {'settlement':<20}{'residue':>12}  {'expected':<22}{'actual':<22}{'verdict'}")
print("  " + "-" * 88)
for sid, want, why in expected:
    c = byid.get(sid)
    e = excs.get(sid)
    got = e["exception_type"] if e else "no exception"
    res = c["residue"] if c else 0
    mark = "ok " if got == want else "XX "
    if got != want:
        ok = False
    verdict = e["verdict"] if e else "-"
    print(f"{mark}{sid:<20}{rupees(res):>12}  {want:<22}{got:<22}{verdict}")
    if got != want:
        print(f"     expected because: {why}")

# Exceptions the dataset did not predict. The unattributable refund is one of
# these by design: it has no settlement of its own, so it cannot be listed in
# expected.txt, but it must still be visible here rather than silently ignored.
extra = [e for sid, e in excs.items() if sid not in {x[0] for x in expected}]
if extra:
    print()
    print("  additional exceptions (not tied to a settlement in the plan):")
    for e in extra:
        print(f"     {e['settlement_id']:<20}{e['exception_type']:<22}{e['verdict']}")

print()
matched = sum(1 for c in credits if c.get("bank_txn_id"))
print(f"  {len(credits)} credits, {matched} matched to a bank row, {len(excs)} exceptions")
if not ok:
    print("\n  MISMATCH - the dataset and the engine disagree. Fix the data, not the engine.")
    sys.exit(1)
print("\n  every settlement classified as designed")
