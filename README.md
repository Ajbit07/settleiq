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

`merchant_1`, one month, 5,012 payments, 86 settlement batches, 211 bank rows.
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
| throughput | **2,845 records/sec**, 1,836 ms wall (planner off) |
| cost of error | **₹5,760** triage vs **₹72,000** of unwinds avoided by refusing |

Out-of-distribution `merchant_2` (different method mix, B2B ticket sizes,
₹8.06 cr GMV): 100.00 F1 both ways, 0.000% false matches, 100% exception
precision.

**The two data paths agree to the paise.** The CLI reads CSVs through the
engine's own loader; the API reads the same data from Postgres through JDBC.
Both produce identical results — `merchant_1`: 4,781 links, 78 exceptions
(10 auto-posted, 68 escalated), residue ₹23,962.37; `merchant_2`: 1,669 links,
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
| + netting solver | 86/86 | 0 | 4,781 | 100.00 | 0.000 | 23,962.37 | 0.385 | 404 |
| + exception agent | 86/86 | 0 | 4,781 | 100.00 | 0.000 | 23,962.37 | 0.385 | 773 |

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

Requires **Docker** (Desktop or Engine) and about **8 GB RAM**. Nothing else —
Java, Maven and Python all run inside containers.

```bash
cp .env.example .env     # set POSTGRES_PASSWORD; change API_PORT if 8088 is taken
docker compose up -d     # postgres → migrate → seed → api + ml
```

Then open **http://localhost:8088** and pick a merchant.

That is the whole thing. Compose starts Postgres, waits until it is genuinely
ready, runs Flyway migrations, seeds the data in `data/`, and starts the API.
No manual step, no ordering by `sleep`.

```bash
bash ops/smoke.sh        # end-to-end assertions against the running stack
```

### The data is already in the repo

`data/` ships committed, so a fresh clone runs immediately:

| Merchant | Records | Purpose |
|---|---|---|
| **merchant_1** | 4,781 payments, 86 credits | throughput and accuracy |
| **merchant_2** | 1,669 payments, 42 credits | second tenant, out-of-distribution |
| **merchant_3** | 17 payments, 12 settlements | one example of every outcome |

Reconcile a merchant from the UI (**Reconcile**), or:

```bash
curl -X POST http://localhost:8088/api/v1/runs   -H 'content-type: application/json'   -d '{"merchantId":"merchant_1","preset":"full"}'
```

### Building the data from scratch instead

If you would rather regenerate everything rather than trust the committed CSVs:

```bash
make generate    # synthesise both merchants from datagen/, verify integrity
make train       # fit the pair scorer, export mlservice/model.json
make evaluate    # ablation, calibration, coverage-risk, held-out test
make benchmark   # combined report, including the LLM proof
```

`make generate` writes `data/merchant_a/` and `data/merchant_b_ood/`, each with
`observable/` (what the system may read) and `ground_truth/` (what it is scored
against and can never see). `verify_data.py` re-reads the written CSVs — never
the in-memory generator — so serialisation bugs are caught too.

Windows without `make`: use `make.cmd`, or run the underlying commands in the
`Makefile` directly.

### The hand-built demo set

`merchant_3` exists so every outcome can be pointed at individually — one
example each of auto-posted, held over the ceiling, held off the allowlist, held
as ambiguous, fee variance, netted refund, chargeback debit, reserve movement,
duplicate credit, unproven partition, and unexplained.

```bash
bash demo/load.sh        # create, ingest 7 CSVs, reconcile, self-check
bash demo/reset.sh       # empty it so you can ingest by hand in the UI
```

`demo/verify.py` fails loudly if the engine ever stops agreeing with the
dataset, so its documentation cannot quietly go stale. See
[demo/README.md](demo/README.md).

### The investigation planner

The agent works with or without a model. To enable it, install
[Ollama](https://ollama.com) on the host and pull the model:

```bash
ollama pull phi4-mini:latest
```

Compose already points at `host.docker.internal:11434` with a `host-gateway`
alias, so this works on Linux as well as Docker Desktop.

Without it the deterministic planner runs, every verdict is identical, and the
UI says `LLM UNREACHABLE` rather than pretending. Verified, not asserted: see
§4 of [ARCHITECTURE.md](ARCHITECTURE.md).

```bash
SETTLEIQ_LLM_MAX_CALLS=12    # fewer model-planned cases, ~20s runs
SETTLEIQ_LLM_MAX_CALLS=72    # default, ~128s runs
```

### Surfaces

| Surface | URL |
|---|---|
| UI | `http://localhost:8088/` |
| API docs (OpenAPI) | `http://localhost:8088/docs` |
| Readiness / liveness | `/actuator/health/readiness`, `/actuator/health/liveness` |
| Prometheus | `/actuator/prometheus` |
| ML service | `http://localhost:8010/docs` |

### Optional hardening, off by default

```bash
SETTLEIQ_API_KEYS='key1:merchant_1|admin:*'   # auth + per-key tenant scope
SETTLEIQ_RATE_LIMIT_PER_MIN=600
```

With auth on, the UI's posture lamp opens a panel to hold a key in the browser.
With it off the API runs open and says so on `/api/v1/auth/status` rather than
looking protected.

### Tests

```bash
./mvnw clean verify      # 47 unit + 30 integration (Testcontainers)
bash ops/smoke.sh        # end-to-end against the running stack
```

## Architecture

Full detail — pipeline stages, the agent's boundaries, the data model, the
performance and accuracy numbers and how they were measured — is in
**[ARCHITECTURE.md](ARCHITECTURE.md)**.

The one-paragraph version:

```
   messy feeds  →  LLM chooses WHAT TO LOOK AT   (read-only lookups, bounded)
                →  ENGINE COMPUTES                (integer paise, from source rows)
                →  POLICY DECIDES                 (six gates — the only write path)
                →  LEDGER records  |  ESCALATE
```

A model may reorder a closed list of read-only lookups and its stated reason is
kept verbatim. It cannot invent a tool, compute an amount, or write anything.
The engine runs the decisive lookups regardless of what the planner chose, and
only deterministic policy gates can append to the ledger.

Three modules: `engine/` (Java 21, **zero dependencies**, every money figure),
`api/` (Spring Boot 3.3, ingestion and orchestration), `ml/` + `mlservice/`
(FastAPI serving a calibrated GBDT pair scorer). PostgreSQL 16 with Flyway, and
an append-only audit ledger whose verification recomputes row *contents*, not
just links.

## Not built

Stated plainly so nobody discovers it in a demo:

- **No user accounts or roles.** Authentication is API-key based and keys are
  scoped to merchants, which is enough to stop cross-tenant access but is not
  an identity system. There is no key rotation and no per-endpoint scope.
- **No OR-Tools CP-SAT fallback.** The bounded subset-sum DP refuses and
  escalates instead. On `merchant_1` the ordered-block hypothesis resolves all 25
  date-groups, so the DP fallback never runs on the headline dataset; it is
  covered by unit tests calling it directly rather than by the data.
- **LangGraph** was substituted with a bounded state machine in Java (same node
  contract, same hard step cap). React/Vite/Tailwind were substituted with a
  no-build UI. See the deviations table in LIMITATIONS.md.

## What is proven and what is not

See [LIMITATIONS.md](LIMITATIONS.md). Short version: this demonstrates
algorithmic capability and calibration under a self-authored noise model. It is
not evidence of real-world accuracy.
