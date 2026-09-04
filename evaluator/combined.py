"""One command, one report, across every dataset SettleIQ is evaluated on.

WHY A COMBINED REPORT

The individual benchmarks each answer one question well and none of them answers
the question a reviewer actually asks first, which is: across everything you
run, how many times did this system move money it could not justify?

So this runs the real pipeline over every dataset, collects what each one
produced, and leads with that number. It computes nothing itself — every figure
is read back from an artifact the engine wrote — and it deliberately does not
average across datasets, because a baseline built to be easy and an adversarial
set built to be hard have no meaningful mean.

The datasets are regenerated from fixed seeds before each run, so the report is
reproducible from a clean checkout.

    python evaluator/combined.py            (or: make benchmark)
"""
from __future__ import annotations

import csv
import json
import os
import subprocess
import sys
import time
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "reports" / "combined"
METRICS = ROOT / "reports" / "metrics"

# name -> (data dir, what it is for)
DATASETS = [
    ("baseline",    "data/merchant_a",     "the headline merchant-month"),
    ("ood",         "data/merchant_b_ood", "different method mix, B2B tickets"),
    ("adversarial", "data/adversarial",    "candidate graphs where amount is uninformative"),
    ("hard_netting","data/hard_netting",   "partitions the ordered-block hypothesis cannot solve"),
]


def sh(cmd: list[str], env_extra: dict | None = None, **kw) -> subprocess.CompletedProcess:
    env = os.environ.copy()
    if env_extra:
        env.update(env_extra)
    return subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, env=env, **kw)


# The benchmark runs the engine with the LLM planner OFF, deliberately.
#
# A benchmark whose tool ordering depends on a sampled model is not a benchmark:
# the numbers move between runs, and a regression in the deterministic engine
# would be indistinguishable from the model having a different day. It is also
# ~40x slower, because every planned step becomes a network round trip.
#
# The LLM is evidenced separately, by LlmProof, on one exception — which is the
# right shape for that claim anyway. "The model investigated this case" is a
# demonstration; "the engine posts nothing it cannot prove" is a measurement.
DETERMINISTIC = {"SETTLEIQ_LLM_PROVIDER": "off"}


def regenerate() -> dict:
    """Rebuild the seeded fixtures. The baseline datasets are left untouched."""
    seeds = {}
    for script, target, seed in (
        ("datagen/adversarial.py", "data/adversarial", 20251103),
        ("datagen/hard_netting.py", "data/hard_netting", "deterministic (no rng)"),
    ):
        r = sh([sys.executable, script, target])
        if r.returncode != 0:
            print(f"  ! {script} failed:\n{r.stderr[:400]}")
        seeds[target] = seed
    return seeds


def run_engine(name: str, data_dir: str) -> dict:
    """Run the real pipeline. Returns the summary the engine itself wrote."""
    out = OUT / name
    out.mkdir(parents=True, exist_ok=True)
    audit = out / "audit.jsonl"
    if audit.exists():
        audit.unlink()          # a fresh chain per dataset, so counts are per-run
    cmd = ["java", "-cp", "engine/out", "com.settleiq.Main",
           "--merchant", data_dir, "--preset", "full",
           "--out", str(out), "--audit", str(audit),
           "--dump-candidates", str(out / "candidates.csv")]
    t0 = time.time()
    r = sh(cmd, DETERMINISTIC)
    wall = int((time.time() - t0) * 1000)
    if r.returncode != 0:
        return {"error": r.stderr[-500:] or r.stdout[-500:], "wall_ms": wall}

    summary = json.loads((out / "full" / "summary.json").read_text(encoding="utf-8"))
    summary["_stdout"] = r.stdout
    summary["_wall_ms"] = wall

    # Exception mix and refusal reasons come from the emitted rows, not stdout.
    ex_path = out / "full" / "exceptions.csv"
    rows = list(csv.DictReader(ex_path.open(encoding="utf-8"))) if ex_path.exists() else []
    summary["_types"] = dict(Counter(r_["type"] for r_ in rows))
    summary["_verdicts"] = dict(Counter(r_["verdict"] for r_ in rows))
    summary["_agent_steps"] = sum(
        len([s for s in (r_.get("agent_steps") or "").split(">")
             if s and s not in ("classify", "propose_resolution", "policy_check")])
        for r_ in rows)
    summary["_multi_step"] = sum(
        1 for r_ in rows
        if len([s for s in (r_.get("agent_steps") or "").split(">")
                if s and s not in ("classify", "propose_resolution", "policy_check")]) > 1)

    # Solver and attribution outcomes are printed by the engine as telemetry.
    for line in r.stdout.splitlines():
        if line.startswith("netting-solver:"):
            summary["_solver"] = line.split(":", 1)[1].strip()
        if line.startswith("attribution:"):
            summary["_attribution"] = line.split(":", 1)[1].strip()
        if line.startswith("llm: provider="):
            summary["_llm_status"] = line.split("status=", 1)[1].strip()
        if line.startswith("llm: investigations_with_llm="):
            summary["_llm"] = line.split("llm: ", 1)[1].strip()
    return summary


def run_llm_proof() -> dict:
    """One real investigation, so the LLM claim rests on an observation.

    Returns whatever actually happened, including "no model reachable" — a
    benchmark that silently omits this section when the model is down would let
    the absence of evidence read as evidence.
    """
    r = sh(["java", "-cp", "engine/out", "com.settleiq.LlmProof",
            "--merchant", "data/merchant_a", "--type", "fee_variance",
            "--audit", str(OUT / "llm_proof_audit.jsonl")])
    out = r.stdout
    keep, lines = ("llm_used", "llm_model", "llm_calls", "tools_chosen_by_llm",
                   "classification", "policy verdict", "llm status"), []
    for ln in out.splitlines():
        t = ln.strip()
        if any(t.startswith(k) for k in keep):
            lines.append(" ".join(t.split()))
    if r.returncode == 2 or not lines:
        lines = ["LLM CONFIGURATION REQUIRED - no reachable model.",
                 "every trace records llm_used=false; the pipeline is unaffected."]
    (OUT).mkdir(parents=True, exist_ok=True)
    (OUT / "llm_proof.txt").write_text(out, encoding="utf-8")
    return {"lines": lines, "exit": r.returncode, "raw": out}


def main() -> int:
    print("=" * 78)
    print("  SETTLEIQ — COMBINED BENCHMARK")
    print("=" * 78)
    print(f"  generated  {time.strftime('%Y-%m-%d %H:%M:%S')}")

    r = sh(["git", "rev-parse", "--short", "HEAD"])
    print(f"  commit     {r.stdout.strip() or 'not a git repository'}")

    print("\nregenerating seeded fixtures (baseline datasets untouched)")
    seeds = regenerate()
    for k, v in seeds.items():
        print(f"  {k:<22} seed={v}")

    results = {}
    print("\nrunning the real pipeline over each dataset")
    for name, data_dir, note in DATASETS:
        if not (ROOT / data_dir).is_dir():
            print(f"  {name:<14} SKIPPED — {data_dir} not present")
            continue
        s = run_engine(name, data_dir)
        results[name] = s
        if "error" in s:
            print(f"  {name:<14} FAILED — {s['error'][:120]}")
        else:
            print(f"  {name:<14} {s['payments']:>6} payments  "
                  f"{s['exceptions']:>3} exceptions  {s['_wall_ms']:>6} ms   ({note})")

    ok = {k: v for k, v in results.items() if "error" not in v}

    # ---------------------------------------------------------------- headline
    total_auto = sum(v.get("auto_posted", 0) for v in ok.values())
    total_esc = sum(v.get("escalated", 0) for v in ok.values())
    # An unprovable item that was auto-posted would appear as an AUTO_POST
    # verdict on a refusal-class exception. Counted from the emitted rows, not
    # asserted from the design.
    unprovable_posted = 0
    for name in ok:
        ex = OUT / name / "full" / "exceptions.csv"
        if not ex.exists():
            continue
        for row in csv.DictReader(ex.open(encoding="utf-8")):
            if row["verdict"] == "AUTO_POST" and row["type"] in (
                    "ambiguous_candidates", "attribution_ambiguous", "partition_unproven"):
                unprovable_posted += 1

    print("\n" + "=" * 78)
    print(f"  UNSAFE AUTO-POSTS : {unprovable_posted}")
    print("  (an item the engine could not prove, posted anyway. target 0)")
    print("=" * 78)

    # ---------------------------------------------------------------- per set
    print("\nPER DATASET")
    h = (f"  {'dataset':<14}{'records':>9}{'links':>8}{'credits':>9}{'excep':>7}"
         f"{'posted':>8}{'held':>6}{'residue':>14}")
    print(h); print("  " + "-" * (len(h) - 2))
    for name, v in ok.items():
        recs = v["payments"] + v["settlements"] + v["bank_rows"]
        print(f"  {name:<14}{recs:>9,}{v['payment_links']:>8,}"
              f"{v['bank_matched']:>4}/{v['settlements']:<4}{v['exceptions']:>7}"
              f"{v['auto_posted']:>8}{v['escalated']:>6}"
              f"{v['residue_abs_paise']/100:>14,.2f}")

    print("\nSAFETY — every refusal path, counted from emitted rows")
    print(f"  {'dataset':<14}{'ambiguous':>11}{'attribution':>13}{'partition':>11}{'unexplained':>13}")
    for name, v in ok.items():
        t = v.get("_types", {})
        print(f"  {name:<14}{t.get('ambiguous_candidates',0):>11}"
              f"{t.get('attribution_ambiguous',0):>13}"
              f"{t.get('partition_unproven',0):>11}{t.get('unexplained',0):>13}")

    print("\nNETTING — partition solver outcomes")
    for name, v in ok.items():
        if v.get("_solver"):
            print(f"  {name:<14}{v['_solver']}")
        else:
            print(f"  {name:<14}H1 resolved every date-group; the DP was not reached")

    print("\nATTRIBUTION — adjustments the engine declined to place")
    for name, v in ok.items():
        print(f"  {name:<14}{v.get('_attribution', 'none refused')}")

    print("\nAGENT")
    print(f"  {'dataset':<14}{'investigations':>15}{'tool calls':>12}{'multi-step':>12}")
    for name, v in ok.items():
        print(f"  {name:<14}{v['exceptions']:>15}{v.get('_agent_steps',0):>12}"
              f"{v.get('_multi_step',0):>12}")

    print("\nLLM INVESTIGATION")
    print("  the dataset runs above are deterministic by design (planner off), so")
    print("  the model is evidenced separately, on one real exception:")
    llm = run_llm_proof()
    for line in llm["lines"]:
        print(f"    {line}")
    print(f"  full transcript: reports/combined/llm_proof.txt")

    # ---------------------------------------------------------------- ML
    print("\nML FEATURE ABLATION")
    abl_path = METRICS / "ml_ablation.json"
    if abl_path.exists():
        abl = json.loads(abl_path.read_text(encoding="utf-8"))
        print(f"  {'subset':<14}{'top-1':>10}{'AUC':>9}{'changed':>10}{'fixed':>8}")
        for k, s in abl["subsets"].items():
            print(f"  {k:<14}{s['top1_accuracy']:>9.2f}%{s['auc']:>9.4f}"
                  f"{s['decisions_changed_vs_amount']:>10}{s['changed_to_correct']:>8}")
        print(f"\n  {abl['verdict']}")
    else:
        print("  not present — run: python evaluator/ml_ablation.py")

    payload = {
        "generated": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "unsafe_auto_posts": unprovable_posted,
        "totals": {"auto_posted": total_auto, "escalated": total_esc},
        "datasets": {k: {kk: vv for kk, vv in v.items() if not kk.startswith("_stdout")}
                     for k, v in ok.items()},
        "ml_ablation": json.loads(abl_path.read_text(encoding="utf-8"))
                       if abl_path.exists() else None,
        "llm_proof": llm,
        "note": ("engine runs are executed with the LLM planner disabled so the "
                 "measurement is reproducible; the LLM is evidenced separately "
                 "by LlmProof on one real exception"),
    }
    METRICS.mkdir(parents=True, exist_ok=True)
    (METRICS / "combined.json").write_text(json.dumps(payload, indent=2, default=str),
                                           encoding="utf-8")
    print(f"\nwritten  reports/metrics/combined.json")
    return 0 if unprovable_posted == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
