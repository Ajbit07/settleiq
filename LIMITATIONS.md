# LIMITATIONS

Read this before believing any number in the README.

## The central caveat

Every metric in this repository was measured against data this project
generated. We wrote the noise model, and then we measured how well we cope with
our own noise. **That demonstrates algorithmic capability and calibration under
a self-authored noise model. It is not evidence of real-world accuracy, and we
do not claim it is.**

A 100.00 amount-weighted F1 means the pipeline inverted the generator's
composition rule exactly. It does not mean a real merchant's month would
reconcile perfectly. It means we have not yet found the cases that break it,
because we chose which cases exist.

## What the synthetic data CAN support

- **Arithmetic correctness.** Decompositions balance to the paise, verified
  against a ground-truth identity the engine never reads. Rounding order
  (per-payment HALF_UP, then sum) is exercised by construction.
- **Refusal behaviour.** The swap-invariant traps are provably unresolvable:
  swapping the pair leaves every batch gross and every fee total unchanged. A
  system that auto-matches them is wrong, and we can prove it is wrong.
- **Idempotency and tamper-evidence.** Re-running appends zero rows; altering
  one byte breaks the chain. These are properties of the code, not the data, and
  transfer directly.
- **Ablation.** Each row is a real run with a stage disabled. The relative
  contribution of components is a real measurement about this data.
- **Throughput.** Real wall-clock on real code.

## What it CANNOT support

- **Real-world match rates.** Our narration corruption is drawn from operators
  we invented (truncation, character confusion, field cut at 38 chars, dropped
  UTR). Real bank feeds differ per bank, per rail, per corporate channel, and
  contain failure modes we have not imagined.
- **Real fee schedules.** The rate card is plausible, not sourced from a real
  contract. Real merchants have negotiated rates, method sub-categories
  (credit vs debit vs international), MDR caps, and promotional periods.
- **The holiday calendar.** `DECLARED_HOLIDAYS` is a configured list for the
  synthetic window. It is part of the noise model and should not be read as an
  accurate RBI calendar. The Sunday and 2nd/4th-Saturday rules are real.
- **TDS treatment.** We model 194-O as a flat 1% on eligible volume taken from
  the settlement report. Real 194-O has thresholds, PAN-linked rate variation,
  and quarterly true-ups that we do not model at all.
- **Generalisation across merchants.** The out-of-distribution merchant differs
  in method mix, ticket size and narration style — but it came from the same
  generator, so it shares our blind spots. It is a weaker test than it looks.

## Known weaknesses in the system itself

- **Global assignment does not currently earn its place.** Greedy and Hungarian
  agree on every configuration we tested, including a stress setup that forces
  all matching through the scored candidate graph. It is retained for the
  structural one-to-one guarantee, but on this evidence it is insurance rather
  than a contributor. Reported in the ablation table rather than hidden.
- **On the headline merchant the pair scorer's task is nearly separable.** A
  bank credit equals its settlement net almost exactly, so amount delta alone
  separates positives from negatives. AUC 1.0000 and ECE 0.0007 there reflect an
  easy problem, not an outstanding model, and the test split is 30 pairs.

  `make adversarial` builds a candidate graph where that shortcut is removed:
  two settlements per credit with nets identical to the paise, and the
  narration reference damaged past deterministic repair. On 24 held-out credits
  the four-way feature ablation measures

  | subset | top-1 | AUC | decisions changed vs amount |
  |---|---|---|---|
  | amount | 83.33% | 0.8088 | — |
  | amount + timing | 83.33% | 0.8088 | 0 |
  | amount + identity | **100.00%** | 1.0000 | 4, all 4 to the correct settlement |
  | full (16 features) | **100.00%** | 1.0000 | 4, all 4 to the correct settlement |

  The clearest cell is the `clean_tie` shape, where the two candidates have
  byte-identical amount features: amount-only scores 3/6, which is chance, and
  identity evidence scores 6/6. **Timing adds nothing on this data** — zero
  decisions changed — and that is reported rather than quietly folded into the
  "full" number.

  One artifact worth recording because it nearly became a result: the first run
  of this ablation reported 100% for amount-only. The generator was placing the
  true settlement first in every pair, candidates with identical features scored
  identically, and a stable sort resolved every tie in construction order. The
  fixture was leaking the answer through ORDERING rather than through a feature.
  Both sides are now randomised.
- **Isotonic calibration is fitted on `dev`.** The `dev` ECE of 0.0000 is
  in-sample and meaningless. Use the `test` figure.
- **The partition solver stopped at the first subset that fit.** It returned as
  soon as any combination hit the batch gross, so whenever several combinations
  were arithmetically valid it silently picked one. Two different sets of
  payments summing to the same gross are observationally identical; choosing
  between them moves real payments into the wrong payout while every total still
  ties out. The DP now counts solutions with a saturating counter and refuses at
  two, and reports five distinct outcomes (`UNIQUE`,
  `AMBIGUOUS_MULTIPLE_PARTITIONS`, `NO_EXACT_SUBSET`, `BOUND_ITEMS`,
  `BOUND_TIME`) rather than conflating "impossible" with "gave up".

- **A refused partition was silently overridden downstream.** Found while
  testing the above. The decomposition took a batch's gross from the settlement
  report when membership was empty — and the bank credit was derived from that
  same reported figure, so a batch whose membership the solver had REFUSED
  reported itself reconciled to zero residue and never became an exception. The
  solver was safe and the next stage undid it. Batches with unproven membership
  are now never `withinTolerance`, always flagged, and raise a
  `partition_unproven` exception carrying the solver's own reason.

- **Subset-sum DP is unexercised by the headline data.** On merchant A the ordered-block
  hypothesis resolves all 25 date-groups, so the DP fallback never runs on the
  headline dataset. It is called directly by unit tests instead
  (`NettingDpTest`), which cover exactness, no-reuse of a payment, refusal when
  no subset is exact, and refusal above the item cap. Those establish that it
  works; they do not establish that its bounds (250k states, 1.5s, 400 items)
  are the right ones, which remain asserted rather than empirically tuned
  against pathological inputs. `make hard-netting` builds four date-groups the
  ordered-block hypothesis provably cannot solve (the fixture asserts that no
  prefix sums to a batch gross, which caught two construction mistakes), and
  runs them through the real pipeline: 2 UNIQUE, 2
  AMBIGUOUS_MULTIPLE_PARTITIONS, 2 NO_EXACT_SUBSET, 2 BOUND_ITEMS, six escalated
  exceptions, **zero auto-posts**.
- **No OR-Tools / CP-SAT.** The spec called for a CP-SAT fallback above a
  candidate-set threshold. It is not implemented; the bounded DP refuses instead
  and the batch escalates. That is safe but weaker than specified.
- **Cycle-window attribution used to guess, and now refuses.** Adjustments are
  attributed by equal time divisions of the day, verified against reconstructed
  membership, with a midpoint fallback. The window boundaries are a HYPOTHESIS;
  the observed trading range of each batch is evidence. The previous
  implementation ignored that distinction: when no window contained an
  adjustment's timestamp it returned whichever boundary was nearest, breaking
  ties by list order, so refunds and reserve releases were attributed on an
  interpolated cut. A refund in the wrong batch leaves both payouts balancing
  and is not caught for weeks.

  Attribution now requires exactly one batch's OBSERVED members to bracket the
  timestamp. Anything else — a timestamp in the dead gap between two batches,
  two batches trading simultaneously, no batch on the date — is refused and
  raised as an `attribution_ambiguous` exception naming the adjustment, its
  value, the candidate batches and the reason.

  The cost is real and is reported rather than absorbed: on merchant A this
  refuses **18 of 250 netted adjustments (7.2%, ₹19,024.78)** and pushes
  unexplained residue from ₹5,465.94 to ₹23,962.37. Those adjustments were
  previously netted into a batch on the strength of an invented boundary. What
  changed is not the data but whether the engine admits it cannot place them.
- **The agent's planner is deterministic unless a key is configured.** The
  agent is a real investigation loop: it chooses which read-only tool to call
  next from what earlier tools returned, so different breaks take different
  paths (an ambiguity stops after two lookups; a residue nothing accounts for
  exhausts all six). `LlmAdapter` will hand that choice to a real model when
  `SETTLEIQ_LLM_API_KEY` is set, constrained to picking one identifier from a
  closed list. With no key the deterministic planner runs and every trace
  records `llm_used=false` -- the system never claims a model was consulted
  when it was not. What is therefore NOT demonstrated by default is an LLM
  changing an investigation's course on live traffic.

## Deviations from the requested stack, and why

Most of the original deviations are now closed. What is built:

| requested | status |
|---|---|
| Spring Boot 3.x | **built** — `settleiq-api`, Boot 3.3.5, layered jar |
| PostgreSQL 16 + Flyway | **built** — 7 migrations, append-only ledger enforced by triggers |
| Docker Compose | **built** — `docker compose up` migrates, seeds and serves |
| Python FastAPI service | **built** — `ml/`, narration parsing with spans + scoring |
| LightGBM | substituted: GBDT in pure Python, exported to pinned JSON |
| LangGraph | substituted: bounded tool-calling loop in Java, 11 read-only tools, hard step cap |
| React + Vite + Tailwind | substituted: no-build single-file UI, light-only, WCAG AA measured |
| Authentication | **built** -- API keys, per-key tenant scope, rate limiting |
| File ingestion | **built** -- validating CSV upload with per-row rejection records |
| LLM investigation layer | **built, optional** -- real API call, closed-list answers only |
| OR-Tools CP-SAT fallback | **not built** — bounded DP refuses and escalates |

Two substitutions are worth defending rather than apologising for:

- **The scorer is an exported artifact, not a live service call.** A
  reconciliation decision has to be reproducible years later during an audit,
  which means the model must be a pinned, versioned artifact named in the ledger
  row — not an endpoint whose weights may have moved since. The FastAPI service
  exists for interactive use and retraining, and is deliberately not in the
  reconciliation hot path.
- **A bounded state machine instead of LangGraph.** The contract that matters is
  the hard step cap, the fixed taxonomy and the rule that no agent output
  becomes a number. Those are enforced in Java and tested. A graph framework
  would add a dependency to the module whose whole value is having none.

One deviation in the UI worth naming, because it was a recommendation we
declined: the design pass called for Fira Code / Fira Sans as the dashboard
pairing. Those are webfonts, and the offline path (`make serve`) runs from a
JDK with no network. A UI that renders in a fallback face on exactly the
machine the demo runs on is worse than one designed for the system stack, so
figures use `font-variant-numeric: tabular-nums` for column alignment instead
of a monospace face. That is a real trade: the numerals align, but the UI does
not carry the typographic personality the pairing would have given it.

## What the infrastructure does and does not prove

Proven by tests that actually run:

- Migrations apply cleanly to real Postgres 16 (not H2).
- The ledger rejects `UPDATE`, `DELETE` and `TRUNCATE` by trigger.
- A retried posting writes zero rows; the UNIQUE constraint suppresses it.
- A tampered `prev_hash` is detected by the server-side chain walk.
- Two concurrent workers on separate connections cannot claim the same
  merchant-period.
- No money column is anything but `BIGINT`, and no floating-point column exists
  anywhere in the schema. This is asserted against `information_schema`, so
  adding one breaks the build.

**The tamper evidence did not cover the contents.** `audit_chain_break()`
verified that each row's `prev_hash` matched the previous row's `hash`, and
nothing else. It never recomputed a hash from the row's own columns. Deletions,
forged inserts and rewritten link hashes were caught; rewriting `payload`,
`verdict` or `score` on a committed row was not -- the money-bearing fields
were silently editable by anyone with database access, and verification kept
reporting the ledger valid.

Found by writing a test that performs the tampering instead of one asserting
the mechanism exists. Fixing it exposed a second defect: the hash committed to
two values that were never persisted -- a Java-generated IST timestamp (the
`ts` column was filled by the database's own `now()`) and a per-merchant
sequence counter (the `seq` column is a global bigserial). Content verification
was impossible even in principle until `V7` stored both. `TamperEvidenceIT` now
performs six distinct attacks, including the two that used to pass.

One bug worth recording because of how it presented:

**Multi-tenant id collision produced a confident, catastrophic number.** The
generator emits the same `pay_...` identifiers for every merchant, and V1 made
them a global primary key. Seeding merchant B after merchant A therefore hit
`ON CONFLICT DO NOTHING` on every payment and bank row and dropped them all.
Ingest logged success. Settlements loaded, because settlement ids already
carried a merchant token. Merchant B then reconciled **zero** payments against
43 real settlements and reported its entire payout -- Rs 7.9 crore -- as
unexplained residue.

Nothing threw. Every health check was green. The system was simply, loudly
wrong. `V5__tenant_scoped_keys.sql` moves the source tables to
`(merchant_id, id)` primary keys, and the smoke test now asserts that *every*
seeded merchant reconciles a non-zero number of payments, because "it ran and
produced a number" is not evidence that it worked.

NOT proven:

- **Any of this at scale.** The largest dataset is 5,012 payments and 86
  batches. Connection-pool sizing, index choice and the batch-insert strategy
  are reasoned about, not load-tested. There is no benchmark above one merchant-
  month.
- **Failure behaviour under real infrastructure faults.** The stale-job reaper
  is tested by back-dating a timestamp, not by killing a worker mid-run.
- **The compose stack under anything but a cold start.** Upgrade paths, volume
  migration and rollback are untested.
- **Security is present but minimal.** API-key authentication, per-key tenant
  authorisation and fixed-window rate limiting are implemented and enforced
  (`SETTLEIQ_API_KEYS`). With no keys configured the API runs OPEN, and says so
  on `/api/v1/auth/status` rather than looking protected. What is still missing:
  no user accounts or roles, no key rotation, no mTLS, no per-endpoint scopes,
  and the rate limiter is per-process rather than shared, so it does not hold
  across replicas.

## The one number we would defend

**0 swap-invariant payments missed, and 0.000% false-match rate among
auto-postable links.** Those measure whether the system moves money to the wrong
place, which is the only error here that a human does not catch for weeks. Match
rate is a vanity metric by comparison.
