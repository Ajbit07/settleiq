# SettleIQ

## The problem, in merchant language

You sold ₹5,01,930 worth of goods this week. The bank credited you ₹4,83,712.44.

Nobody can tell you where the other ₹18,217.56 went.

It is not one deduction. That single bank credit is a batch of payments, minus
the platform fee at a different rate per method, minus 18% GST *on those fees*,
minus TDS under section 194-O, minus the refunds that were netted into the same
payout, minus a chargeback, minus the dispute fee on that chargeback, minus 5%
rolling reserve — plus a reserve release from three months ago that has nothing
to do with this week at all.

Your settlement report shows batch totals. It does not tell you which of your
payments are in which batch. Your bank statement shows a truncated reference
number and the words `RAZORPAYSOF`. Reconciling these by hand is where finance
teams lose their week.

SettleIQ decomposes every bank credit to the paise, and sends an agent after
only the residue that arithmetic cannot explain.

## What it does

```
bank credit  ->  which payments  ->  minus fees, GST, TDS, refunds, chargebacks,
                 are in it?          reserve  ->  residue  ->  agent  ->  policy
                                                                          |
                                          auto-post  <-------------------- or ---> escalate
```

## The rule that shapes everything

**The LLM chooses; arithmetic decides.**

No language model output ever becomes a number in the ledger. The agent picks a
label from a fixed taxonomy, selects a resolution template from a closed set,
and writes prose. Every rupee is computed by Java from source rows, as `long`
paise. There is no `float` or `double` anywhere in the money path.

## Measured results

Merchant A, one month, 5,012 payments, 86 settlement batches, 211 bank rows.
Thresholds tuned on `dev`; `test` scored once at the end.

| metric | value |
|---|---|
| payment → settlement link F1 (row) | **100.00** |
| payment → settlement link F1 (amount-weighted) | **100.00** |
| bank credit → settlement links | **86 / 86 correct, 0 wrong** |
| false-match rate among auto-postable links | **0.000%** (target < 0.5%) |
| exception precision | **100.00%** (48 refused, 48 genuinely ambiguous) |
| swap-invariant payments missed | **0** |
| unexplained residue | **₹23,962.37** = 0.385% of GMV |
| of which the agent could name | ₹3,194.45 (fee variance) |
| of which is refused adjustment attribution | ₹19,024.78 (18 adjustments, 7.2%) |
| of which genuinely unexplainable | ₹2,271.47 (1 batch, by construction) |
| throughput | ~4,100 records/sec, 1.3 s wall |
| cost of error | **₹5,760** triage vs **₹72,000** of unwinds avoided by refusing |

Out-of-distribution merchant B (different method mix, B2B ticket sizes, ₹8.06 cr
GMV): 100.00 F1 both ways, 0.000% false matches, 100% exception precision.

**The two data paths agree to the paise.** The CLI reads CSVs through the
engine's own loader; the API reads the same data from Postgres through JDBC.
Both produce identical results — merchant A: 4,781 links, 58 exceptions
(10 auto-posted, 68 escalated), residue ₹23,962.37; merchant B: 1,669 links,
residue ₹1,12,002.29. Nothing about the storage layer moves a number, which is
the whole reason the engine has no dependencies.

**One UI, either backend.** Both paths serve the same three screens over the
same `/api/v1` contract — the same route names, the same field names, the same
units. `make up` serves it from Spring over Postgres; `make serve` serves it
from the engine's own 200-line HTTP server over CSVs, with no docker and no
database. Pointing the same UI at both is how the agreement between the two
paths is checked by eye rather than only by assertion.

### Ablation — every row is a real run with that stage switched off

| configuration | bank ok | wrong | links | amt F1 | false % | residue | % GMV | ms |
|---|---|---|---|---|---|---|---|---|
| deterministic only | 72/86 | 0 | 0 | 0.00 | 0.000 | 12,30,869.56 | 19.751 | 644 |
| + UTR repair | 75/86 | 0 | 0 | 0.00 | 0.000 | 9,87,787.96 | 15.851 | 698 |
| + pair scorer | 86/86 | 0 | 0 | 0.00 | 0.000 | 4,54,838.28 | 7.299 | 750 |
| + global assignment | 86/86 | 0 | 0 | 0.00 | 0.000 | 4,54,838.28 | 7.299 | 765 |
| + netting solver | 86/86 | 0 | 4,781 | 100.00 | 0.000 | 5,465.94 | 0.088 | 990 |
| + exception agent | 86/86 | 0 | 4,781 | 100.00 | 0.000 | 5,465.94 | 0.088 | 1365 |

**Global assignment does not earn its place on this dataset.** Greedy and
Hungarian produce identical output, including under a stress configuration that
disables exact and repaired-UTR matching so every credit must go through the
scored candidate graph. The reason is that a bank credit equals its settlement
net almost exactly, so each credit has one viable partner and there is no
contention to resolve. It is kept because the guarantee it provides (no
settlement assigned twice) is structural rather than empirical, but the honest
reading of this table is that it is currently insurance, not a contributor.

### Cost of error

The two errors are not symmetric. A false match silently moves money to the
wrong place and is found weeks later, costing a full manual unwind (~2.5h). A
missed match sits visibly in a queue and costs an analyst ~6 minutes. At
₹1,200/hr fully loaded:

```
false matches        0  x 2.5h unwind   =        0.00
missed or refused   48  x 6min triage   =    5,760.00
```

A policy that refused nothing and auto-posted the swap-invariant pairs would
cost **₹72,000** in unwinds alone. That 12.5x gap is the whole argument for
building a system that declines to answer.

## What it can do today

Everything below runs against real data through the production code path. There
is no demo mode and no second code path for the curated dataset.

**Ingest.** Upload a CSV of payments, orders, refunds, settlements, bank rows,
chargebacks or reserve entries. Every row is validated individually — header
arity, missing ids, money as an exact decimal converted to integer paise (never
through a double), strict ISO-8601 with offset conversion, duplicates within the
file and against rows already committed for that merchant, strict UTF-8, 64 MB
ceiling. Accepted rows commit; refused rows are stored with the reason **and the
original line**, so any rejection count can be produced on demand. Settlement
files are additionally checked for internal consistency: a report whose own
columns do not add up is a source-system fault and is refused rather than
silently reconciled.

**Reconcile.** Match bank credits to settlement batches (exact UTR, repaired
UTR, learned pair scorer, Hungarian assignment), reconstruct batch membership
by exact k-way partition, decompose every credit to the paise, and hand the
residue to the exception agent.

**Investigate.** A bounded agent chooses which of eleven read-only tools to run
next based on what earlier tools returned, up to a hard cap of six lookups. An
ambiguity stops after two; a residue nothing accounts for exhausts all six and
still refuses to name a cause. Every fact it returns carries the id of the row
it came from. An optional LLM planner (`SETTLEIQ_LLM_API_KEY`) can make the
tool choice instead — constrained to picking one identifier from a closed list,
unable to compute an amount or reach the ledger. With no key the deterministic
planner runs and the UI reports `no llm` rather than implying one was consulted.

**Decide and post.** Six deterministic policy gates re-derive every figure; all
six must pass to auto-post. Postings are keyed by an idempotency hash of the
facts they were computed from, so a re-run appends zero ledger rows.

**Prove.** The audit ledger is append-only at the database level and hash
chained. Verification recomputes `sha256(prev_hash ‖ canonical row)` from each
row's own stored columns, so an edit to a payload, a verdict or a score is
caught — not only a broken link.

**Secure.** API-key authentication with per-key merchant scope and fixed-window
rate limiting. Authorisation is enforced both on query parameters and on the
merchant parsed out of a request body, because a filter cannot read the body and
a key scoped to one tenant could otherwise act on another by moving the
parameter.

### API surface

| method | path | does |
|---|---|---|
| GET | `/api/v1/merchants` | merchants present in the database |
| GET | `/api/v1/runs` · `/runs/{id}` | reconciliation run history and detail |
| POST | `/api/v1/runs` | run a reconciliation synchronously |
| POST | `/api/v1/jobs` | queue one for a worker to claim |
| GET | `/api/v1/credits` | bank credits with their full decomposition |
| GET | `/api/v1/credits/{id}/payments` | payments this run attributed to a batch |
| GET | `/api/v1/exceptions` | the investigation queue with evidence and verdicts |
| GET | `/api/v1/ambiguous` | payments the engine refused to separate |
| GET | `/api/v1/audit/verify` | walk the chain; **409** when it does not verify |
| POST | `/api/v1/ingest` | upload one CSV of one entity type |
| GET | `/api/v1/ingest/runs` · `/runs/{id}/rejects` · `/schema` | ingestion history, refused rows, required headers |
| GET | `/api/v1/metrics` | evaluation artifacts, read-only |
| GET | `/api/v1/auth/status` | whether auth and the LLM planner are actually on |

### The five screens

**Reconciliation** — the command centre. One headline figure for the money the
bank moved, the reconciled proportion as a bar, and a credit ledger beside the
evidence chain. **Investigations** — the queue, with the full case file: verdict,
tied-candidate comparison for refusals, the tool path, evidence with provenance,
and the policy gates. **Audit trail** — the hash chain drawn as linked segments,
a live verify button, run history. **Ingest** — upload, per-row rejection
reasons, ingestion history with source hashes. **Metrics** — held-out test,
ablation, calibration, coverage-vs-risk.

## Quickstart

```bash
cp .env.example .env    # change POSTGRES_PASSWORD; set API_PORT if 8080 is taken
make up                 # postgres + migrate + seed + api + ml
make smoke              # end-to-end assertions against the running stack
```

That is the whole thing: Compose starts Postgres, waits for it to be genuinely
ready, runs Flyway migrations, seeds the generated data, then starts the API.
No manual step, no ordering by `sleep`.

Ports come from `.env` (`API_PORT`, `ML_PORT`), defaulting to 8080 and 8000.
`make smoke` reads the same file, so changing a port does not break it.

| surface | url |
|---|---|
| UI | `http://localhost:${API_PORT}/` |
| API docs (OpenAPI) | `http://localhost:${API_PORT}/docs` |
| readiness / liveness | `/actuator/health/readiness`, `/actuator/health/liveness` |
| Prometheus metrics | `/actuator/prometheus` |
| ML service | `http://localhost:${ML_PORT}/docs` |

Optional environment, both off by default so the demo needs no setup:

```bash
SETTLEIQ_API_KEYS='key:merchant,merchant|admin:*'   # enables auth + tenant scope
SETTLEIQ_RATE_LIMIT_PER_MIN=600
SETTLEIQ_LLM_API_KEY=...                            # enables the LLM planner
SETTLEIQ_LLM_MODEL=claude-sonnet-5
```

With neither set the API runs open and deterministic, and says so on
`/api/v1/auth/status` rather than looking protected.

The offline pipeline still works without any of it:

```bash
make generate    # synthesise both merchants, verify integrity, print the census
make train       # fit the pair scorer, export model.json
make evaluate    # metrics, ablation, calibration, coverage-risk, held-out test
make demo        # scripted run with a live counter
make serve       # the same UI, on the engine alone, at http://localhost:8733
make adversarial # ML feature ablation where amount carries no information
make hard-netting # partition cases the ordered-block hypothesis cannot solve
make test        # 34 unit tests (no docker, no database)
make it          # 17 integration tests against real Postgres 16
```

See [OPERATIONS.md](OPERATIONS.md) for the API, configuration and the
infrastructure decisions.

## Architecture

Two Maven modules with exactly one dependency arrow, `api -> engine`:

```
engine/        deterministic core        Java 21, ZERO runtime dependencies
api/           transport + persistence   Spring Boot 3.3, JDBC, Flyway
```

`settleiq-engine` compiles with plain `javac` and owns every rupee of
arithmetic. The zero-dependency rule is enforced by `maven-enforcer-plugin` and
re-checked in CI, so a Spring or driver upgrade cannot change a settlement
figure. `settleiq-api` owns transport, persistence, scheduling and
observability, and performs no arithmetic at all.

```
datagen/       synthetic merchant-month + hidden ground truth   (Python, stdlib)
engine/        the whole deterministic pipeline                 (Java 21, zero deps)
  Money        long paise, BigDecimal rates, HALF_UP everywhere
  Normalizer   Stage 0 - narration parsing, UTR repair, provenance spans
  Matcher      Stages 1-4 - exact, repair-as-identity, candidates, scorer, Hungarian
  Netting      Stage 5 - k-way exact partition, H1 ordered-block then H2 subset-sum DP
  AgentTools   11 read-only investigation tools, every fact row-sourced
  ExceptionAgent  state-driven tool loop, hard step cap 6
  LlmAdapter   optional planner; picks from a closed list, never a number
  Policy       6 deterministic arms; auto-post only if all pass
  Audit        append-only SHA-256 hash chain, idempotency keys
mlservice/     GBDT + isotonic calibration, exports model.json  (Python, stdlib)
evaluator/     metrics, ablation, curves                        (Python, stdlib)
api/           Spring Boot: REST, JDBC, Flyway, jobs, metrics
ml/            FastAPI: narration parsing + scoring (advisory, not in the hot path)
ops/           Dockerfiles
db/            reference DDL for the standalone path
api/src/main/resources/static/
               five screens, no framework, no build step; served by both backends
```

### Infrastructure

- **PostgreSQL 16** with **Flyway** migrations (`V1` schema, `V2` audit ledger,
  `V3` jobs, `V4` a forward fix, `V5` tenant-scoped keys, `V6` ingestion runs,
  `V7` audit content verification). The audit ledger is append-only *at the
  database level*: `BEFORE UPDATE/DELETE/TRUNCATE` triggers raise.
- **Idempotency is a UNIQUE constraint**, not a `SELECT`. The check-then-act
  version passes a single-threaded test and double-posts under the exact
  concurrency it exists to survive.
- **No Redis, no Kafka.** Job claiming is `FOR UPDATE SKIP LOCKED` plus a
  Postgres advisory lock. A broker would create a second source of truth about
  whether a run happened, which is the ambiguity an audit trail removes.
- **51 tests**: 34 unit (no infra) and 17 integration against **real Postgres 16**,
  including six that tamper with a committed ledger, seven that construct
  ambiguous attribution windows, and five that prove the partition solver
  refuses rather than picking one of several valid answers.
  No H2 fallback — the schema depends on advisory locks, statement triggers,
  JSONB and `SKIP LOCKED`, and an in-memory substitute would pass tests that
  production fails.

### Design decisions worth arguing with

**Stage 5 is a partition problem, not a lookup.** Settlement reports publish
batch totals but never batch membership. The solver proposes that a cycle is a
contiguous window over an ordering key, *verifies* that prefix sums hit every
batch gross exactly, and falls back to a bounded subset-sum DP when that fails.
It refuses rather than approximating. On merchant A the ordered-block hypothesis
resolves all 25 date-groups and the DP is never needed.

**A unique UTR repair is identity evidence, not a score.** The learned scorer
leans on amount delta, because for almost every batch the credit equals the net.
That makes the one batch with a genuine discrepancy the lowest-scoring one — so
gating the link on the amount agreeing would leave exactly the batches you need
to investigate unmatched. Identity establishes the link; the amount disagreement
becomes the residue.

**Refusing is a success state.** Two payments with the same amount, method and
rate tier in different batches settling the same day are swap-invariant: no
arithmetic separates them. The system produces an assignment, flags it, and
refuses to auto-post. 48/48 such payments are caught, 0 missed.

**The UI has a build step of zero and one accent colour.** Five screens, no
framework, no bundler — one HTML file, one stylesheet, one module. It is a
light-only surface: a cool paper ground rather than the warm cream light
fintech UIs default to, ink at charcoal-navy rather than black, and a single
restrained indigo carrying every interactive affordance. Emerald means
reconciled, bronze means under investigation, coral means money at risk;
nothing else is coloured. Glass is an accent on four elements — the command
strip, the rail, the filter bars and the investigation header — and never on a
table, because a translucent number is a number you distrust. Every text role
clears 4.5:1 and every chart mark 3:1 on the surface it actually sits on,
measured in the running page rather than eyeballed.

The signature is the **evidence chain**: one credit traced top to bottom
through the payments captured, the orders behind them, the credit the bank
posted, the settlement decomposition line by line, the arithmetic check, the
policy decision and the ledger row. Every node carries the id of the row that
proves it, and where a link genuinely is not on record — no orders ingested,
say — the node says so rather than being quietly dropped. The refusal state
shows both tied payments side by side with the gap named, drawn from the run's
own flagged set; it deliberately does not print a per-candidate confidence,
because the engine does not score the alternatives separately and inventing one
would manufacture the very distinction the system is refusing to make.

**The engine is never told the fee schedule changed.** The generator moves the
card rate mid-month; the published rate card the engine reads does not show it.
The resulting ₹3,194.45 of drift has to surface as a `fee_variance` exception,
which it does.

## Not built

Stated plainly so nobody discovers it in a demo:

- **No user accounts or roles.** Authentication is API-key based and keys are
  scoped to merchants, which is enough to stop cross-tenant access but is not
  an identity system. There is no key rotation and no per-endpoint scope.
- **No OR-Tools CP-SAT fallback.** The bounded subset-sum DP refuses and
  escalates instead. On merchant A the ordered-block hypothesis resolves all 25
  date-groups, so the DP fallback never runs on the headline dataset; it is
  covered by unit tests calling it directly rather than by the data.
- **LangGraph** was substituted with a bounded state machine in Java (same node
  contract, same hard step cap). React/Vite/Tailwind were substituted with a
  no-build UI. See the deviations table in LIMITATIONS.md.

## What is proven and what is not

See [LIMITATIONS.md](LIMITATIONS.md). Short version: this demonstrates
algorithmic capability and calibration under a self-authored noise model. It is
not evidence of real-world accuracy.
