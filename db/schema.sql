-- Postgres 16 equivalent of the file-backed audit ledger the engine runs today.
-- Same semantics: append-only, hash-chained, idempotency-keyed. Provided so the
-- containerised deployment path is real rather than described. See LIMITATIONS.md
-- for why the running system uses the JSONL implementation.

CREATE TABLE IF NOT EXISTS audit_ledger (
    seq             BIGSERIAL PRIMARY KEY,
    ts              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actor           TEXT         NOT NULL CHECK (actor IN ('engine','model','agent','human')),
    action          TEXT         NOT NULL,
    entity_type     TEXT         NOT NULL,
    entity_id       TEXT         NOT NULL,
    idempotency_key TEXT         NOT NULL UNIQUE,   -- the duplicate-posting guard
    model_version   TEXT         NOT NULL,
    score           NUMERIC(9,6) NOT NULL,
    verdict         TEXT         NOT NULL,
    payload         JSONB        NOT NULL,
    prev_hash       CHAR(64)     NOT NULL,
    hash            CHAR(64)     NOT NULL UNIQUE
);

-- Append-only: no UPDATE, no DELETE, ever.
CREATE OR REPLACE FUNCTION audit_ledger_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_ledger is append-only (attempted %)', TG_OP;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS audit_ledger_no_mutate ON audit_ledger;
CREATE TRIGGER audit_ledger_no_mutate
    BEFORE UPDATE OR DELETE ON audit_ledger
    FOR EACH ROW EXECUTE FUNCTION audit_ledger_immutable();

CREATE INDEX IF NOT EXISTS audit_ledger_entity_idx ON audit_ledger (entity_type, entity_id);
CREATE INDEX IF NOT EXISTS audit_ledger_ts_idx     ON audit_ledger (ts DESC);

-- Money columns elsewhere are BIGINT paise, never NUMERIC with a float cast and
-- never DOUBLE PRECISION.
CREATE TABLE IF NOT EXISTS decomposition (
    settlement_id TEXT   NOT NULL,
    bank_txn_id   TEXT,
    component     TEXT   NOT NULL,
    amount_paise  BIGINT NOT NULL,
    PRIMARY KEY (settlement_id, component)
);

-- Job coordination without Redis or Kafka: a jobs table plus advisory locks.
CREATE TABLE IF NOT EXISTS recon_jobs (
    job_id       BIGSERIAL PRIMARY KEY,
    merchant_id  TEXT        NOT NULL,
    period       DATERANGE   NOT NULL,
    state        TEXT        NOT NULL DEFAULT 'queued',
    claimed_by   TEXT,
    claimed_at   TIMESTAMPTZ,
    finished_at  TIMESTAMPTZ,
    UNIQUE (merchant_id, period)
);
-- worker claims with: SELECT pg_try_advisory_lock(hashtext(merchant_id||period::text))
