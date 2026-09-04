"""Pair scorer: gradient-boosted trees + isotonic calibration, zero dependencies.

Trained on candidate feature vectors DUMPED BY THE JAVA ENGINE, so the features
seen at training time are byte-for-byte the ones seen at serving time. The model
exports to plain JSON and is evaluated inside Java; there is no scoring service
in the request path.

That choice is deliberate. A reconciliation decision has to be reproducible
years later during an audit, which means the model is a pinned, versioned
artifact recorded in the audit ledger -- not a live endpoint whose weights may
have moved since.

Splits: fitted on the training-pool merchants and merchant_a/train, calibrated
on merchant_a/dev, and never fitted on merchant_a/test.
"""
import csv
import json
import math
import os
import random
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

MODEL_VERSION = "settleiq-pairscorer-1.0.0"
TRAIN_MERCHANTS = ["train_t1", "train_t2", "train_t3"]
TUNE_MERCHANT = "merchant_a"


def load(path):
    if not os.path.exists(path):
        return []
    with open(path, newline="", encoding="utf-8") as fh:
        return list(csv.DictReader(fh))


# --------------------------------------------------------------------- dataset
def build(merchants, include_splits=None):
    X, y, groups, meta = [], [], [], []
    for m in merchants:
        cand = load(f"reports/{m}/candidates.csv")
        links = load(f"data/{m}/ground_truth/ground_truth_links.csv")
        if not cand:
            continue
        # bank_txn <-> settlement truth, and each settlement's split
        true_bank, split_of = {}, {}
        for l in links:
            true_bank[l["settlement_id"]] = l["bank_txn_id"]
            split_of[l["settlement_id"]] = l["split"]
        feat_cols = [c for c in cand[0] if c.startswith("f_")]
        for r in cand:
            sp = split_of.get(r["settlement_id"])
            if include_splits and sp not in include_splits:
                continue
            X.append([float(r[c]) for c in feat_cols])
            y.append(1 if true_bank.get(r["settlement_id"]) == r["bank_txn_id"] else 0)
            groups.append(f"{m}:{r['bank_txn_id']}")
            meta.append((m, r["bank_txn_id"], r["settlement_id"], sp))
    return X, y, groups, meta, [c[2:] for c in (feat_cols if X else [])]


# ------------------------------------------------------------------ GBDT parts
class Tree:
    __slots__ = ("feat", "thr", "left", "right", "val")

    def __init__(self):
        self.feat, self.thr, self.left, self.right, self.val = [], [], [], [], []

    def _node(self):
        self.feat.append(-1)
        self.thr.append(0.0)
        self.left.append(-1)
        self.right.append(-1)
        self.val.append(0.0)
        return len(self.feat) - 1

    def predict(self, x):
        n = 0
        while self.left[n] >= 0:
            n = self.left[n] if x[self.feat[n]] <= self.thr[n] else self.right[n]
        return self.val[n]


def build_tree(X, grad, hess, idx, depth, max_depth, lam, min_child):
    t = Tree()

    def rec(rows, d):
        n = t._node()
        G = sum(grad[i] for i in rows)
        H = sum(hess[i] for i in rows)
        t.val[n] = -G / (H + lam)
        if d >= max_depth or len(rows) < 2 * min_child:
            return n
        best = (0.0, -1, 0.0)
        parent = G * G / (H + lam)
        for f in range(len(X[0])):
            vals = sorted({X[i][f] for i in rows})
            if len(vals) < 2:
                continue
            # candidate thresholds at midpoints, capped for speed
            step = max(1, len(vals) // 24)
            cands = [(vals[k] + vals[k + 1]) / 2 for k in range(0, len(vals) - 1, step)]
            for thr in cands:
                gl = hl = nl = 0.0
                for i in rows:
                    if X[i][f] <= thr:
                        gl += grad[i]
                        hl += hess[i]
                        nl += 1
                nr = len(rows) - nl
                if nl < min_child or nr < min_child:
                    continue
                gr, hr = G - gl, H - hl
                gain = gl * gl / (hl + lam) + gr * gr / (hr + lam) - parent
                if gain > best[0]:
                    best = (gain, f, thr)
        if best[1] < 0:
            return n
        _, f, thr = best
        lrows = [i for i in rows if X[i][f] <= thr]
        rrows = [i for i in rows if X[i][f] > thr]
        t.feat[n], t.thr[n] = f, thr
        t.left[n] = rec(lrows, d + 1)
        t.right[n] = rec(rrows, d + 1)
        return n

    rec(idx, 0)
    return t


def sigmoid(z):
    if z >= 0:
        return 1.0 / (1.0 + math.exp(-z))
    e = math.exp(z)
    return e / (1.0 + e)


def train_gbdt(X, y, n_trees=90, lr=0.12, max_depth=3, lam=1.0, min_child=4, seed=7):
    rng = random.Random(seed)
    pos = sum(y)
    base = math.log(max(pos, 1) / max(len(y) - pos, 1))
    scores = [base] * len(X)
    trees = []
    idx_all = list(range(len(X)))
    for _ in range(n_trees):
        p = [sigmoid(s) for s in scores]
        grad = [p[i] - y[i] for i in idx_all]
        hess = [max(p[i] * (1 - p[i]), 1e-6) for i in idx_all]
        rows = idx_all if len(idx_all) < 400 else rng.sample(idx_all, int(0.85 * len(idx_all)))
        t = build_tree(X, grad, hess, rows, 0, max_depth, lam, min_child)
        for i in idx_all:
            scores[i] += lr * t.predict(X[i])
        trees.append(t)
    return base, trees, lr


def raw_score(base, trees, lr, x):
    return base + lr * sum(t.predict(x) for t in trees)


# ------------------------------------------------------- isotonic calibration
def isotonic(pairs):
    """Pool-adjacent-violators. pairs = [(score, label)] sorted by score."""
    pairs = sorted(pairs)
    ys = [float(l) for _, l in pairs]
    ws = [1.0] * len(ys)
    xs = [s for s, _ in pairs]
    i = 0
    while i < len(ys) - 1:
        if ys[i] <= ys[i + 1]:
            i += 1
            continue
        tw = ws[i] + ws[i + 1]
        ty = (ys[i] * ws[i] + ys[i + 1] * ws[i + 1]) / tw
        ys[i:i + 2] = [ty]
        ws[i:i + 2] = [tw]
        xs[i:i + 2] = [xs[i]]
        if i > 0:
            i -= 1
    # thin to a monotone step function
    out_x, out_y = [], []
    for x, yv in zip(xs, ys):
        if out_y and abs(out_y[-1] - yv) < 1e-9:
            continue
        out_x.append(x)
        out_y.append(min(max(yv, 1e-6), 1 - 1e-6))
    if len(out_x) < 2:
        out_x = [0.0, 1.0]
        out_y = [0.01, 0.99]
    return out_x, out_y


def apply_iso(ix, iy, p):
    if p <= ix[0]:
        return iy[0]
    if p >= ix[-1]:
        return iy[-1]
    lo, hi = 0, len(ix) - 1
    while hi - lo > 1:
        mid = (lo + hi) // 2
        if ix[mid] <= p:
            lo = mid
        else:
            hi = mid
    t = (p - ix[lo]) / max(ix[hi] - ix[lo], 1e-12)
    return iy[lo] + t * (iy[hi] - iy[lo])


# ------------------------------------------------------------------- metrics
def ece(probs, labels, bins=10):
    """Expected calibration error, plus the reliability table."""
    tot = len(probs)
    table, e = [], 0.0
    for b in range(bins):
        lo, hi = b / bins, (b + 1) / bins
        sel = [i for i in range(tot) if (lo < probs[i] <= hi) or (b == 0 and probs[i] <= hi)]
        if not sel:
            table.append({"bin": f"{lo:.1f}-{hi:.1f}", "n": 0, "conf": 0.0, "acc": 0.0})
            continue
        conf = sum(probs[i] for i in sel) / len(sel)
        acc = sum(labels[i] for i in sel) / len(sel)
        e += len(sel) / tot * abs(acc - conf)
        table.append({"bin": f"{lo:.1f}-{hi:.1f}", "n": len(sel),
                      "conf": round(conf, 4), "acc": round(acc, 4)})
    return e, table


def auc(probs, labels):
    pos = [p for p, l in zip(probs, labels) if l == 1]
    neg = [p for p, l in zip(probs, labels) if l == 0]
    if not pos or not neg:
        return 0.0
    order = sorted(range(len(probs)), key=lambda i: probs[i])
    ranks = {}
    i = 0
    while i < len(order):
        j = i
        while j + 1 < len(order) and probs[order[j + 1]] == probs[order[i]]:
            j += 1
        r = (i + j) / 2 + 1
        for k in range(i, j + 1):
            ranks[order[k]] = r
        i = j + 1
    rsum = sum(ranks[i] for i in range(len(probs)) if labels[i] == 1)
    return (rsum - len(pos) * (len(pos) + 1) / 2) / (len(pos) * len(neg))


def main():
    Xtr, ytr, _, _, names = build(TRAIN_MERCHANTS)
    Xa, ya, _, meta_a, _ = build([TUNE_MERCHANT])
    Xtr2 = Xtr + [x for x, m in zip(Xa, meta_a) if m[3] == "train"]
    ytr2 = ytr + [v for v, m in zip(ya, meta_a) if m[3] == "train"]

    Xdev = [x for x, m in zip(Xa, meta_a) if m[3] == "dev"]
    ydev = [v for v, m in zip(ya, meta_a) if m[3] == "dev"]
    Xte = [x for x, m in zip(Xa, meta_a) if m[3] == "test"]
    yte = [v for v, m in zip(ya, meta_a) if m[3] == "test"]

    print(f"train pairs {len(Xtr2)} (pos {sum(ytr2)})   "
          f"dev {len(Xdev)} (pos {sum(ydev)})   test {len(Xte)} (pos {sum(yte)})")
    if not Xtr2:
        print("no training data; run the engine with --dump-candidates first")
        return 1

    base, trees, lr = train_gbdt(Xtr2, ytr2)

    # calibrate on DEV only
    cal_src = list(zip([sigmoid(raw_score(base, trees, lr, x)) for x in Xdev], ydev)) \
        if Xdev else list(zip([sigmoid(raw_score(base, trees, lr, x)) for x in Xtr2], ytr2))
    ix, iy = isotonic(cal_src)

    def prob(x):
        return apply_iso(ix, iy, sigmoid(raw_score(base, trees, lr, x)))

    report = {"version": MODEL_VERSION, "features": names,
              "n_train": len(Xtr2), "n_pos_train": sum(ytr2)}
    for label, Xs, ys in (("train", Xtr2, ytr2), ("dev", Xdev, ydev), ("test", Xte, yte)):
        if not Xs:
            continue
        ps = [prob(x) for x in Xs]
        e, table = ece(ps, ys)
        report[label] = {"n": len(Xs), "pos": sum(ys), "auc": round(auc(ps, ys), 4),
                         "ece": round(e, 4), "reliability": table}
        print(f"  {label:<6} n={len(Xs):<5} pos={sum(ys):<4} AUC={auc(ps, ys):.4f} ECE={e:.4f}")

    model = {
        "version": MODEL_VERSION,
        "bias": base,
        "learning_rate": lr,
        "features": names,
        "trees": [{"feature": [float(v) for v in t.feat],
                   "threshold": t.thr,
                   "left": [float(v) for v in t.left],
                   "right": [float(v) for v in t.right],
                   "value": [lr * v for v in t.val]} for t in trees],
        "isotonic": {"x": ix, "y": iy},
    }
    os.makedirs("mlservice", exist_ok=True)
    with open("mlservice/model.json", "w", encoding="utf-8") as fh:
        json.dump(model, fh)
    os.makedirs("reports/metrics", exist_ok=True)
    with open("reports/metrics/calibration.json", "w", encoding="utf-8") as fh:
        json.dump(report, fh, indent=2)
    print(f"wrote mlservice/model.json ({len(trees)} trees) and "
          f"reports/metrics/calibration.json")
    return 0


if __name__ == "__main__":
    sys.exit(main())
