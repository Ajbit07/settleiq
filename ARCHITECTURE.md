# Architecture

How SettleIQ is put together, and why each boundary is where it is.

The short version: **the model investigates, arithmetic decides, policy controls,
the ledger records.** Everything below is that sentence, enforced in code.

---

## 1. The problem

A merchant reconciling with a payment gateway holds three records that disagree:

| Source | Says | Arrives as |
|---|---|---|
| Payment gateway | which payments were captured | transaction report |
| Gateway again | what it settled and what it deducted | settlement/payout report |
| The merchant's bank | what actually landed | bank statement |

Plus refunds, chargebacks and reserve movements, each on its own schedule.

Nobody is lying. The gateway nets a refund the bank also debits; a chargeback is
raised after the batch closed; a rolling reserve is withheld and never appears in
the settlement report at all. The gap between "what the report says you earned"
and "what the bank paid" is the reconciliation problem, and at scale it is worked
by hand.

**The hard part is not finding the gap. It is deciding whether you are allowed to
act on it.** A system that guesses confidently is worse than one that refuses.

---

## 2. Module map

```
                    ┌──────────────────────────────────────────────┐
  CSV feeds  ─────► │  api  (Spring Boot 3.3, Java 21)             │
  7 entities        │   ingest · validate · persist · orchestrate  │
                    └───────────────┬──────────────────────────────┘
                                    │ calls, in-process
                    ┌───────────────▼──────────────────────────────┐
                    │  engine  (Java 21, ZERO dependencies)        │
                    │   normalise → match → net → decompose        │
                    │   → investigate → decide → record            │
                    └───────┬──────────────────────┬───────────────┘
                            │                      │
              ┌─────────────▼──────┐   ┌───────────▼──────────────┐
              │ ml  (FastAPI)      │   │ LlmAdapter → Ollama      │
              │ pair scorer, served│   │ read-only lookup ORDER   │
              │ from mlservice/    │   │ only. Never writes.      │
              └────────────────────┘   └──────────────────────────┘
                            │
                    ┌───────▼──────────────────────────────────────┐
                    │  PostgreSQL 16 · Flyway · append-only ledger │
                    └──────────────────────────────────────────────┘
```

### Why the engine has zero dependencies

`engine/pom.xml` is enforced by `maven-enforcer` to have no third-party
dependencies at all. Every figure that touches money is computed by code in this
module, using `long` paise and nothing else.

This is not minimalism for its own sake. It means the money path has no
transitive dependency that could change a rounding mode, no JSON library
coercing a decimal through a double, and no framework deciding when a
transaction commits. The engine is a pure function from CSV rows to decisions,
which is also why it is fast (see §7) and why it can be tested without a
database.

### `ml/` versus `mlservice/`

Confusingly named, both live:

- **`ml/`** — the FastAPI service (`app/main.py`). Built into the ML container.
- **`mlservice/`** — the *trained artifact* (`model.json`) plus `train.py`.
  Mounted read-only into the container at `/app/model`.

The split exists so the model can be replaced without rebuilding the image, and
so the service image contains no training code. Worth renaming to
`ml-service/` and `ml-model/` at some point; left alone here because the build
references it in four places and the win is cosmetic.

---

## 3. The pipeline, stage by stage

Each stage can be switched off independently — that is what the ablation in the
Metrics screen measures, and each row there is a real run.

### Stage 0 — Normalise

Bank narration is free text. `Normalizer` pulls candidate references out of it
with six patterns (NEFT structured, loose NEFT, 12-digit, long numeric, 14–22
alphanumeric, 10–13 mixed) plus a repair stage for transposition and
character-confusion errors (`O`/`0`, `I`/`1`).

A repaired reference that resolves to exactly one known settlement is treated as
**identity evidence, not a guess** — it is deliberately not gated on the amount
agreeing, because requiring both would discard the one signal that is actually
unique.

### Stage 1–4 — Match bank credit → settlement

Four escalating strategies, in order, each only handling what the previous could
not:

1. **Exact UTR** — the narration carries the settlement's reference verbatim.
   Confidence 1.000. No model involved.
2. **Repaired UTR** — unique repair to a known settlement. Identity evidence.
3. **Blocking + scoring** — candidates within a banking-day window and an amount
   window, scored by a calibrated GBDT over engineered features.
4. **Global assignment** — Hungarian algorithm over `-log(probability)`, so the
   *set* of assignments is optimal rather than each row greedily taking its best
   partner. Below `minAssignProbability = 0.55` nothing is assigned at all.

Measured contribution (86 credits, held-out ground truth):

| Configuration | Credits matched | Value unexplained |
|---|---|---|
| Exact UTR only | 72/86 | 19.75% |
| + UTR repair | 75/86 | 15.85% |
| + pair scorer | **86/86** | **7.30%** |
| + global assignment | 86/86 | 7.30% |
| + netting | 86/86 | **0.38%** |

Global assignment shows no gain on this dataset — after exact and repaired
matching, every remaining credit has one viable partner, so greedy and Hungarian
agree. Reported rather than hidden.

The final 0.38% is higher than it once was, deliberately. Adjustments used to be
placed into a batch by interpolating a boundary when no batch clearly bracketed
their timestamp — a guess that leaves both payouts balancing and goes unnoticed
for weeks. Attribution now refuses unless exactly one batch's observed members
bracket the timestamp, which refuses 18 of 250 adjustments on `merchant_1` and
moves ₹19,024.78 out of "silently netted" and into "admitted as unplaceable".
See [LIMITATIONS.md](LIMITATIONS.md).

### Stage 5 — Netting: prove batch membership

A settlement reports a gross. Which payments make it up is not stated anywhere.

`Netting` solves a **subset-sum with a uniqueness proof**: it finds the set of
payments summing exactly to the reported gross, and — critically — establishes
whether that set is the *only* one. Where several batches settle the same day it
becomes a k-way exact partition over a shared candidate pool.

Outcomes are explicit: `UNIQUE`, `AMBIGUOUS_MULTIPLE_PARTITIONS`,
`NO_EXACT_SUBSET`, `BOUND_ITEMS`, `BOUND_TIME`.

**When it cannot prove membership it refuses**, and the decomposition does *not*
fall back to the settlement's own reported gross. That fallback is tempting and
wrong: the bank credit was derived from that same reported figure, so the batch
would reconcile to zero residue by construction and the refusal would never reach
anyone. The solver would be safe and the decomposition would quietly override it.

**Swap-invariance.** Two payments with the same amount, method, rate tier and
settle date, in different batches, cannot be told apart — exchanging them leaves
every batch gross and every fee total unchanged. These are flagged and are never
auto-postable, *even when the residue is exactly zero.* This is the clearest
expression of the product's thesis: perfect arithmetic is not sufficient grounds
to act.

### Stage 6 — Decompose

For each settlement, in integer paise:

```
predicted = gross
          − platform_fee − gst_on_fee − tds_194o
          − refunds_netted − chargeback − dispute_fee − gst_on_dispute_fee
          + chargeback_reversal
          − reserve_held + reserve_released

residue   = bank_credit − predicted
```

Fees come from the merchant's **published rate card only**. The generator applies
a mid-month card-rate change that the fee model is deliberately not told about,
so where the platform charged differently the model is wrong by a known,
systematic amount — which must surface downstream as `fee_variance` rather than
being silently absorbed. Handing the model the generator's parameters would make
the benchmark circular.

Rounding is per-payment HALF_UP, then summed. Summing then rounding produces
paise-level drift on every batch.

### Stage 6b — Investigate

An exception is raised when `!withinTolerance || ambiguous`. Each one is worked
by `ExceptionAgent`, which runs a bounded planning loop:

```
for step in 1..MAX_STEPS(6):
    remaining = TOOLS − already_observed
    choice    = deterministic_planner(state)
    if llm.available():
        choice = llm.choose(context, remaining)   # REORDERS a closed list
    result = tools.call(choice)                   # read-only
    evidence += result.facts                      # each with a provenance id
```

**The eleven tools are read-only lookups over the merchant's own rows.** The
model selects which to run next from a closed list. It cannot invent a tool,
cannot compute an amount, and cannot write anything. If it answers off-list,
times out, or is not configured, the deterministic choice stands and the trace
records `llm_used=false`.

#### Mandatory completion — the most important detail in this file

After the planning loop, before classification, the engine runs any lookup in
`DECISIVE` that the planner never reached:

```java
static final List<String> DECISIVE = List.of(
        "check_swap_invariance", "check_duplicate_credit",
        "get_refunds", "get_chargebacks", "get_reserve_movements",
        "check_rate_card");
```

This exists because of a measured failure. `classify()` can only name a cause it
has evidence for, so **a lookup that was never run is indistinguishable from one
that came back empty** — both leave the break "unexplained".

With a model planning the sequence, three `fee_variance` cases collapsed to
`unexplained`: the six planned steps were spent on breadth and `check_rate_card`
was never reached. The deterministic planner happened to reach it in three. More
evidence was gathered and less was known.

Neither planner is trusted to remember. The model chooses the **order**; the
engine guarantees the **questions**. Steps added this way are recorded as
`engine:mandatory` so the trace never implies the model asked for them.

After the fix: unexplained back from 11 to 8, verdicts unchanged.

### Stage 7 — Policy decides

`Policy` re-derives every figure from source rows and applies six gates. **All
must pass** to auto-post:

| Gate | Threshold |
|---|---|
| Match confidence | ≥ 0.95 |
| Residue, absolute | ≤ ₹100.00 |
| Residue, relative | ≤ 0.1% of batch value |
| Exception type | on the auto-post allowlist |
| Ambiguity flag | must be absent |
| Daily budgets | ≤ ₹50,000 and ≤ 60 postings per value date |

A refusal names every gate that failed, in the record. This is the only code path
that writes to the ledger, and no model can reach it.

### Stage 8 — Record

Every decision appends to `audit_ledger`:

```
hash = SHA-256( previous_hash ‖ canonical_row )
```

Canonicalisation covers the row's *contents* — payload, verdict, score, model
version — not just the link. An edited payload is caught, not only a cut chain.
`UPDATE`, `DELETE` and `TRUNCATE` are refused by database trigger.

**Idempotency.** Each posting is keyed by a hash of the facts it was computed
from, including an evidence digest of the exact provenance ids used. Re-running
an identical batch appends nothing and reports the suppression; a re-run over
*changed* inputs is correctly not suppressed.

---

## 4. Where the model is, and is not

```
   messy feeds
        │
        ▼
   ┌─────────────────┐
   │ LLM: chooses    │   order of read-only lookups, with its stated reason
   │ WHAT TO LOOK AT │   bounded by SETTLEIQ_LLM_MAX_CALLS
   └────────┬────────┘
            ▼
   ┌─────────────────┐
   │ ENGINE          │   runs the decisive lookups regardless of the planner,
   │ COMPUTES        │   in integer paise, from source rows
   └────────┬────────┘
            ▼
   ┌─────────────────┐
   │ POLICY DECIDES  │   six deterministic gates — the only write path
   └───┬─────────┬───┘
       ▼         ▼
    LEDGER    ESCALATE
```

Two independent budgets bound the agent:

- **`MAX_STEPS = 6`** per case, hard-asserted. Guarantees each investigation
  terminates. Removing the call budget does not remove this.
- **`SETTLEIQ_LLM_MAX_CALLS`** per run, shared across cases. Decides *how many
  cases* get model-ordered investigation before the rest fall back.

The call budget is spent largest-unexplained-residue first. Swap-invariant cases
sort **last on purpose**: their verdict is already fixed by arithmetic, so no
sequence of lookups can change the outcome and planning there buys a prettier
trace and nothing else.

Measured: removing the budget entirely takes a run from 99s to 501s, spends 354
model calls instead of 181, sends 32 of 59 cases to provably undecidable
problems — and changes **zero** verdicts and zero classifications.

---

## 5. Data model

Seven ingested entities, each its own table, each validated per row:

| Entity | Key columns |
|---|---|
| `payment` | payment_id, order_id, amount_paise, method, status, created_at_ist |
| `order_row` | order_id, customer_id, amount_paise, created_at_utc |
| `settlement` | settlement_id, utr, gross/fees/gst/tds/net_paise, settled_at, instant |
| `bank_txn` | bank_txn_id, value_date, amount_paise, narration |
| `refund` | refund_id, payment_id, amount_paise, created_at_ist, netted |
| `chargeback` | dispute_id, payment_id, amount_paise, fee_paise, raised_at, reversed_at |
| `reserve_entry` | reserve_id, amount_paise, held_on, release_date, released_at, netted |

**Money is `bigint` paise everywhere.** No `float`, `double`, `numeric` or
`decimal` appears in any money column, and `JobClaimIT.moneyColumnsAreIntegerPaiseOnly`
asserts it against the live schema.

**Multi-tenancy** is a composite `(merchant_id, id)` primary key on every table,
with `TenantGuard` rejecting cross-tenant access at the API boundary and
API-key scopes enforcing it per key.

**Ingestion validates row by row.** A settlement whose own columns fail
`gross − fees − gst − tds = net` is refused as a source-system fault before it
can become the thing every downstream figure is measured against. Refused rows
are stored with a reason code and their original text.

---

## 6. Frontend

Vanilla HTML/CSS/JS. No framework, no build step, no bundler — served straight
from the Spring Boot jar.

Five screens: **Overview** (throughput, match rate, value accounted for, and what
it could not resolve), **Reconciliation** (every credit), **Exceptions** (the
queue, opening into a three-pane investigation workspace), **Ledger**, **Audit**.

Two design rules that are enforced, not aspirational:

**The frontend computes no money.** Every figure arrives as integer paise the
engine produced and is only formatted. The one exception is the money trail's
"expected net", which sums the decomposition — and it immediately checks that
sum against the residue the engine wrote. If they disagree the panel says so
rather than displaying a number that would be the frontend's own opinion.

**It does not claim more than the record supports.** `LLM INVESTIGATES` appears
only when the persisted row says a model answered and its choice was accepted.
A configured-but-unreachable model shows `LLM UNREACHABLE`; an unconfigured one
shows `LLM CONFIGURATION REQUIRED`; a deterministic run says so. Match provenance
distinguishes `EXACT REFERENCE` (1.000, no model) from `SCORED MATCH · 0.955`
(the learned scorer) from `NO CREDIT LINKED`.

The **Control Trace** is reconstructed server-side from persisted reconciliation
state, not from a recorded event log — deliberately, so there is no second source
of truth about money that could drift from the first.

---

## 7. Performance

Wall time is dominated by model latency, not by record count:

| Merchant | Payments | **Reconciled in** | Exceptions | Investigating |
|---|---:|---:|---:|---:|
| merchant_1 | 4,781 | **69 ms** | 78 | 127.9 s |
| merchant_2 | 1,669 | **22 ms** | 16 | 121.3 s |
| merchant_3 | 16 | **2 ms** | 11 | 95.4 s |

Reconciliation scales with records (~70,000/sec). Investigation is roughly flat,
because it is capped by the call budget rather than by batch size. The offline
benchmark with the planner off measures **2,845 records/sec end to end**.

Investigation is sequential by design. Parallelising it was measured and
rejected: Ollama serving a single 3.8B model returned only **1.29×** on four
concurrent requests, which does not justify restructuring a pipeline whose
decision-and-audit phase must stay strictly ordered for the hash chain.

---

## 8. Accuracy

Offline, against held-out ground truth the running service cannot read:

| Metric | Value |
|---|---|
| Amount-weighted F1 | 100.00 |
| False-match rate | 0.000% (budget 0.500%) |
| Exception precision | 100.0% |
| Ambiguous missed | 0 |
| Unexplained residue | ₹23,962.37 — 0.3845% of GMV |

The pair scorer earns its place explicitly: amount alone reaches 83.33% top-1;
the full feature set reaches 100.00%, changing 4 decisions, all 4 to the correct
settlement.

Artifacts are produced by `make evaluate` / `make benchmark` and served read-only
from `reports/metrics/`. The Metrics screen states, unprompted, that these are
stored results and not the current run.

---

## 9. Testing

77 tests, all passing.

**47 unit** — money and calendar arithmetic, subset-sum with uniqueness, policy
gates and audit hashing, attribution safety, LLM adapter behaviour including
unreachable and misconfigured providers.

**30 integration**, against real PostgreSQL in Testcontainers — audit ledger,
control trace reconstruction, job claiming (`onlyOneWorkerClaimsAJob`,
`staleJobIsRequeued`), multi-tenancy isolation, and seven tamper scenarios
including payload edits, verdict edits, row deletion, insertion and reordering.

Plus `ops/smoke.sh` end-to-end against the running stack, and `demo/verify.py`,
which fails loudly if the engine ever stops agreeing with the hand-built demo
dataset.

> Integration tests need `-Dapi.version=1.41`, already set in `api/pom.xml`.
> docker-java defaults to API 1.32, which Docker Engine 25+ refuses with a 400
> that Testcontainers reports as "could not find a valid Docker environment".

---

## 10. Configuration

| Variable | Default | Effect |
|---|---|---|
| `SETTLEIQ_LLM_PROVIDER` | `ollama` | `ollama`, `openai`, `anthropic`, `off` |
| `SETTLEIQ_LLM_MODEL` | `phi4-mini:latest` | planner model |
| `SETTLEIQ_LLM_BASE_URL` | `http://host.docker.internal:11434` | host-side Ollama |
| `SETTLEIQ_LLM_MAX_CALLS` | `72` | ÷6 = cases given model-ordered planning |
| `SETTLEIQ_LLM_TIMEOUT_MS` | `90000` | per call |
| `SETTLEIQ_API_KEYS` | unset | `key:merchant,merchant\|key2:*`; unset = open |
| `SETTLEIQ_RATE_LIMIT_PER_MIN` | `600` | per key |

With auth on, the UI's posture lamp opens a panel to hold a key in the browser;
it is sent only as `x-api-key` to this API and never reaches the ledger or a log.

---

## 11. Known limits

Stated in full in [LIMITATIONS.md](LIMITATIONS.md). The ones that matter most:

- **Synthetic data.** Real bank statements are MT940 or CAMT.053, not tidy CSV,
  and real reports carry 30–80 columns. What is built here is the normalised
  internal model those adapters would feed.
- **No alerting, no retry queue, single node.** If the service is down at 2am
  nobody is paged. The guarantee that holds is that nothing wrong is *written*
  while nobody is looking.
- **`timing_offset` and `rounding_drift` are unreachable.** The first requires a
  residue within tolerance on a matched, proven, unambiguous batch — a
  combination that never raises an exception at all. The second is in the
  auto-post allowlist but absent from the taxonomy. Both are dead branches, and
  both are documented rather than quietly left.
