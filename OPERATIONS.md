# Operations

The API serves both `/api/v1` and the UI that reads it. See "What is not here"
for what this deployment deliberately does not include.

## Run it

```bash
cp .env.example .env      # then change POSTGRES_PASSWORD
make up
```

`API_PORT` and `ML_PORT` default to 8080 and 8000. Change them in `.env` if
something already holds those ports; the examples below use `$API_PORT`.

That is the whole thing. `docker compose up` starts Postgres, waits for it to be
genuinely ready, runs Flyway migrations, seeds the generated merchant data, then
starts the API and the ML service. No manual step, no ordering by `sleep`.

| surface | url |
|---|---|
| UI | `http://localhost:${API_PORT}/` |
| API docs (OpenAPI/Swagger) | `http://localhost:${API_PORT}/docs` |
| liveness / readiness | `/actuator/health/liveness`, `/actuator/health/readiness` |
| Prometheus metrics | `/actuator/prometheus` |
| ML service | `http://localhost:${ML_PORT}/docs` |

```bash
make logs        # follow api logs (JSON outside the local profile)
make psql        # psql into the running database
make down        # stop, keep the volume
make down-hard   # stop and DELETE the database volume
```

## The API

```bash
# reconcile now, synchronously
curl -XPOST localhost:${API_PORT}/api/v1/runs \
  -H 'content-type: application/json' \
  -d '{"merchantId":"merchant_1","preset":"full"}'

# or queue it for a worker
curl -XPOST localhost:${API_PORT}/api/v1/jobs \
  -H 'content-type: application/json' \
  -d '{"merchantId":"merchant_1","periodStart":"2025-11-01","periodEnd":"2025-11-30"}'

curl 'localhost:${API_PORT}/api/v1/credits?merchantId=merchant_1'
curl 'localhost:${API_PORT}/api/v1/exceptions?merchantId=merchant_1'
curl 'localhost:${API_PORT}/api/v1/audit/verify?merchantId=merchant_1'
```

`/audit/verify` returns **409**, not 200, when the chain is broken. A monitoring
system should alert on that status alone.

### What a re-run reports, and why

Re-running an identical reconciliation writes nothing to the ledger. But the
exceptions it returns still read `AUTO_POST` and `ESCALATE`, not
`SUPPRESSED_DUPLICATE`:

```
run 1:  58 exceptions, 14 auto-posted, 44 escalated, audit_appended = 58
run 2:  58 exceptions, 14 auto-posted, 44 escalated, audit_appended = 0
```

"Suppressed as a duplicate" describes what the LEDGER did. It says nothing about
the money. An item that was escalated is still escalated and still needs a
human; reporting the whole queue as suppressed would hide every actionable item
behind a retry, which is exactly what a controller opening the queue after a
retried nightly job would hit.

So the verdict carried on each exception is the ORIGINAL decision, looked up by
idempotency key, with `already_posted` set. The idempotency signal is
`audit_appended`, which goes to zero. Assert on that, not on the verdict counts.

Money is returned as **both** an integer of paise and a formatted string.
Clients must compute on the integer. The string exists so a UI never has to do
currency arithmetic in JavaScript, where `0.1 + 0.2 !== 0.3`.

## Design decisions worth challenging

**Two modules, one dependency arrow.** `settleiq-engine` is pure Java 21 with
**zero runtime dependencies**, enforced by `maven-enforcer-plugin` and re-checked
in CI. All money arithmetic lives there. `settleiq-api` owns transport,
persistence and scheduling and performs no arithmetic. The point is that a
Spring or JDBC-driver upgrade cannot change a settlement figure.

**JDBC, not JPA.** Money is `BIGINT` paise and the SQL is explicit. An ORM would
put a mapping layer between us and the numbers that matter, and would encourage
someone to map a money column to a `BigDecimal` property with a scale bug in it.

**Idempotency is a UNIQUE constraint, not a SELECT.** `PostgresAuditLedger`
inserts and catches the conflict. The check-then-act version passes a
single-threaded test and double-posts under the exact concurrency it was written
to survive. `AuditLedgerIT` asserts the retry writes nothing.

**No Redis, no Kafka.** Job claiming is `FOR UPDATE SKIP LOCKED` plus a Postgres
advisory lock (`V3__jobs.sql`). A broker would create a second source of truth
about whether a run happened — precisely the ambiguity an audit trail exists to
remove. `JobClaimIT` proves two concurrent workers cannot claim the same
merchant-period, using two real connections because the lock is session-scoped.

**The ledger is immutable at the database level.** `BEFORE UPDATE`,
`BEFORE DELETE` and `BEFORE TRUNCATE` triggers raise. Application code cannot
revise history and neither can a person with a psql prompt and good intentions.
This is testable in an unusual way: the integration tests **cannot clean up after
themselves**, so they use a fresh identity per run instead. The impossibility of
cleanup is the guarantee working.

**Fix forward, never edit an applied migration.** `V4__fix_claim_ambiguity.sql`
exists because `claim_recon_job` had an OUT parameter shadowing a column —
Postgres resolves that at run time, so the function was accepted at `CREATE` and
failed on first use. Editing `V3` would have broken Flyway checksum validation
and left two environments on the same version with different behaviour.

## Gotchas found by actually running this

Five bugs only appeared when the stack ran for real. All are fixed; they are
recorded because each one is invisible to a passing build.

**`-parameters` was missing, and every query endpoint returned HTTP 400.**
`spring-boot-starter-parent` sets that compiler flag; we import the BOM instead
so we keep our own parent, and the flag has to be set by hand. Without it Spring
cannot infer `@RequestParam` names. The build was green, the unit tests were
green, and every GET with a query string failed at runtime. Fixed in the parent
POM, and the parameter names are now also written out explicitly so a future
build refactor cannot reintroduce it silently.

**`POSTGRES_PASSWORD` does nothing after the first start.** Postgres only reads
it when it initialises an empty data directory. Changing it in `.env` later
leaves the volume on the old password and the API fails authentication on boot.
Use `make down-hard` to re-initialise, and expect this the first time anyone
changes credentials.

**A plpgsql OUT parameter shadowed a column.** `claim_recon_job` declared
`attempts` as an OUT parameter and then wrote `attempts = attempts + 1` against
`recon_job`. Postgres resolves that at run time, so `CREATE FUNCTION` succeeded
and the first real claim failed. Caught by `JobClaimIT`, fixed forward in
`V4__fix_claim_ambiguity.sql`.

**Source ids collided across merchants and one tenant silently vanished.**
Payment, order, refund, dispute and bank-txn ids were global primary keys, but
the upstream id spaces are per-merchant. Seeding a second merchant hit
`ON CONFLICT DO NOTHING` on every row and dropped it. Ingest reported success,
health checks stayed green, and that merchant then reconciled zero payments and
declared its whole payout unexplained. Fixed by `V5__tenant_scoped_keys.sql`
(composite `(merchant_id, id)` keys) and by scoping the report joins the same
way. The smoke test now asserts every seeded merchant reconciles something.

**The smoke test had a false pass.** `python - <<'PY' <<<"$data"` sends both the
program and the data to stdin; the last redirection wins, so Python read the
DATA as its program, found nothing to run, and exited 0. Two assertions
"passed" without ever executing. The program now lives in a file and the data
goes on stdin. A test that cannot fail is worse than no test.

## Configuration

Everything is environment-driven with sane defaults (`application.yml`).
Policy thresholds are **validated on startup** in `SettleIqProperties`: a
misconfigured auto-post limit is a money bug, so the service refuses to boot
rather than run with one.

| variable | default | meaning |
|---|---|---|
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | localhost settleiq | database |
| `AUTO_POST_MIN_CONFIDENCE` | `0.95` | must be in [0.5, 1.0] |
| `AUTO_POST_MAX_RESIDUE_PAISE` | `10000` (₹100) | absolute residue arm |
| `AUTO_POST_MAX_RESIDUE_FRACTION` | `0.001` | must be in [0, 0.05] |
| `DAILY_AUTO_POST_BUDGET_PAISE` | `5000000` (₹50,000) | daily value cap |
| `WORKER_ENABLED` | `true` | set false for API-only replicas |
| `MODEL_PATH` | `/app/model/model.json` | pinned scorer artifact |

## Tests

```bash
make test    # 22 unit tests, no docker, no database
make it      # 7 integration tests against real Postgres 16
```

There is no H2 fallback. The schema depends on advisory locks, statement
triggers, JSONB and `SKIP LOCKED`; an in-memory substitute would pass tests that
production would fail.

Integration tests take a database from `TEST_DB_URL` if set (what `make it`
does), otherwise they start one with Testcontainers (what CI does). Both paths
run real Postgres 16. The env-var path exists because Docker Desktop on Windows
exposes a named pipe that docker-java cannot negotiate against Engine 29.x,
while the CLI talks to it fine.

## Operational characteristics

- **Graceful shutdown**, 45s drain, so a rolling deploy cannot orphan a
  half-written run.
- **Container-aware heap** (`MaxRAMPercentage=70`); without it the JVM sizes
  against host memory and gets OOM-killed by the cgroup limit.
- **Non-root** in both images, with a real healthcheck (not `CMD true`).
- **Layered Spring Boot jar**, so dependency layers are cached across pushes.
- **Structured JSON logs** outside the local profile, with `run_id` and
  `merchant_id` in the MDC.
- **Gauges hold strong references.** `MeterRegistry.gauge(name, number)` keeps
  only a weak reference; a boxed value is collected and the gauge then reports
  `NaN` forever. Residue gauges are backed by retained `AtomicLong`s.
- **Stale job reaping** every 60s: a worker that dies mid-run releases its
  advisory lock on disconnect, and the row is re-queued after
  `STALE_JOB_MINUTES`.

## What is not here

- **No authentication.** Every endpoint is open. This is a demo backend; putting
  it on a network without an auth layer in front would be a mistake.
- **No rate limiting, no multi-tenancy isolation** beyond `merchant_id` filters.
- **No migration rollback story.** Flyway forward-only; there are no `U__` undo
  scripts.
- **Secrets are environment variables**, not a secret manager.
