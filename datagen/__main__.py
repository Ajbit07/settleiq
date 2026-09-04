import argparse, os, sys, time
from .config import PROFILES
from .generate import Generator
from .report import render, sample_narrations


def main(argv=None):
    ap = argparse.ArgumentParser(prog="datagen", description="SettleIQ synthetic data generator")
    ap.add_argument("--profile", default="all", choices=list(PROFILES) + ["all"])
    ap.add_argument("--out", default="data")
    ap.add_argument("--samples", type=int, default=5)
    a = ap.parse_args(argv)

    names = list(PROFILES) if a.profile == "all" else [a.profile]
    all_ok = True
    for name in names:
        t0 = time.time()
        g = Generator(PROFILES[name], os.path.join(a.out, name)).run()
        text, ok = render(g)
        all_ok &= ok
        print(text)
        print(f"  generated in {time.time()-t0:.2f}s -> {os.path.join(a.out, name)}")
        print()
        print("SAMPLE MESSY BANK NARRATIONS")
        print("-" * 78)
        for gt, row in sample_narrations(g, a.samples):
            print(f"  bank_txn_id : {row['bank_txn_id']}   {row['value_date']}   "
                  f"amount {row['amount']}")
            print(f"  narration   : {row['narration']!r}")
            print(f"  true UTR    : {gt['true_utr'] or '(none in source)'}")
            print(f"  corruption  : {gt['corruption_ops']}")
            print()
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
