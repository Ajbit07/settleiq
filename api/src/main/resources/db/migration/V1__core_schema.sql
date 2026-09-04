-- V1 core schema.
--
-- MONEY RULE: every monetary column is BIGINT paise. No NUMERIC, no DOUBLE
-- PRECISION, no FLOAT anywhere in this database. A CHECK constraint cannot
-- enforce a type choice, so V4 adds a test that fails the build if a money
-- column of the wrong type is ever introduced.

CREATE TABLE merchant (
    merchant_id   TEXT PRIMARY KEY,
    name          TEXT        NOT NULL,
    rate_card     JSONB       NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------- sources
-- These four tables are the OBSERVABLE inputs: what a merchant actually has.
-- Nothing here records which payment belongs to which settlement batch --
-- reconstructing that is the engine's job.

CREATE TABLE payment (
    payment_id      TEXT PRIMARY KEY,
    merchant_id     TEXT        NOT NULL REFERENCES merchant(merchant_id),
    order_id        TEXT,
    amount_paise    BIGINT      NOT NULL CHECK (amount_paise >= 0),
    method          TEXT        NOT NULL,
    status          TEXT        NOT NULL,
    created_at_ist  TIMESTAMP   NOT NULL     -- wall-clock IST, deliberately naive
);
CREATE INDEX payment_merchant_created_idx ON payment (merchant_id, created_at_ist);
CREATE INDEX payment_amount_idx           ON payment (merchant_id, amount_paise);

CREATE TABLE refund (
    refund_id       TEXT PRIMARY KEY,
    merchant_id     TEXT        NOT NULL REFERENCES merchant(merchant_id),
    payment_id      TEXT        NOT NULL,
    amount_paise    BIGINT      NOT NULL CHECK (amount_paise >= 0),
    created_at_ist  TIMESTAMP   NOT NULL,
    netted          BOOLEAN     NOT NULL
);
CREATE INDEX refund_merchant_idx ON refund (merchant_id, created_at_ist);

CREATE TABLE chargeback (
    dispute_id       TEXT PRIMARY KEY,
    merchant_id      TEXT       NOT NULL REFERENCES merchant(merchant_id),
    payment_id       TEXT       NOT NULL,
    amount_paise     BIGINT     NOT NULL CHECK (amount_paise >= 0),
    fee_paise        BIGINT     NOT NULL CHECK (fee_paise >= 0),
    raised_at_ist    TIMESTAMP  NOT NULL,
    reversed_at_ist  TIMESTAMP
);
CREATE INDEX chargeback_merchant_idx ON chargeback (merchant_id, raised_at_ist);

CREATE TABLE reserve_entry (
    reserve_id       TEXT PRIMARY KEY,
    merchant_id      TEXT       NOT NULL REFERENCES merchant(merchant_id),
    amount_paise     BIGINT     NOT NULL CHECK (amount_paise >= 0),
    held_on          DATE       NOT NULL,
    release_date     DATE       NOT NULL,
    released_at_ist  TIMESTAMP  NOT NULL,
    netted           BOOLEAN    NOT NULL
);
CREATE INDEX reserve_release_idx ON reserve_entry (merchant_id, release_date);

CREATE TABLE settlement (
    settlement_id  TEXT PRIMARY KEY,
    merchant_id    TEXT      NOT NULL REFERENCES merchant(merchant_id),
    utr            TEXT,                      -- nullable: source systems lose it
    gross_paise    BIGINT    NOT NULL,
    fees_paise     BIGINT    NOT NULL,
    gst_paise      BIGINT    NOT NULL,
    tds_paise      BIGINT    NOT NULL,
    net_paise      BIGINT    NOT NULL,
    settled_at     TIMESTAMP NOT NULL,
    instant        BOOLEAN   NOT NULL DEFAULT false
);
CREATE INDEX settlement_merchant_date_idx ON settlement (merchant_id, settled_at);
CREATE INDEX settlement_utr_idx           ON settlement (utr) WHERE utr IS NOT NULL;

CREATE TABLE bank_txn (
    bank_txn_id  TEXT PRIMARY KEY,
    merchant_id  TEXT   NOT NULL REFERENCES merchant(merchant_id),
    value_date   DATE   NOT NULL,
    amount_paise BIGINT NOT NULL,             -- signed: credits +, debits -
    narration    TEXT   NOT NULL
);
CREATE INDEX bank_txn_merchant_date_idx ON bank_txn (merchant_id, value_date);

-- ------------------------------------------------------------------ runs
CREATE TABLE recon_run (
    run_id         BIGSERIAL PRIMARY KEY,
    merchant_id    TEXT        NOT NULL REFERENCES merchant(merchant_id),
    preset         TEXT        NOT NULL DEFAULT 'full',
    model_version  TEXT,
    state          TEXT        NOT NULL DEFAULT 'queued'
                   CHECK (state IN ('queued','running','succeeded','failed')),
    started_at     TIMESTAMPTZ,
    finished_at    TIMESTAMPTZ,
    wall_ms        BIGINT,
    stats          JSONB       NOT NULL DEFAULT '{}'::jsonb,
    error          TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX recon_run_merchant_idx ON recon_run (merchant_id, created_at DESC);

-- Results are versioned by run, so a re-run never overwrites the evidence a
-- previous decision was based on.
CREATE TABLE decomposition (
    run_id         BIGINT NOT NULL REFERENCES recon_run(run_id) ON DELETE CASCADE,
    settlement_id  TEXT   NOT NULL,
    bank_txn_id    TEXT,
    component      TEXT   NOT NULL,
    amount_paise   BIGINT NOT NULL,
    PRIMARY KEY (run_id, settlement_id, component)
);
CREATE INDEX decomposition_run_idx ON decomposition (run_id, settlement_id);

CREATE TABLE payment_link (
    run_id        BIGINT  NOT NULL REFERENCES recon_run(run_id) ON DELETE CASCADE,
    payment_id    TEXT    NOT NULL,
    settlement_id TEXT    NOT NULL,
    bank_txn_id   TEXT,
    ambiguous     BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (run_id, payment_id)
);
CREATE INDEX payment_link_settlement_idx ON payment_link (run_id, settlement_id);

CREATE TABLE recon_exception (
    run_id           BIGINT  NOT NULL REFERENCES recon_run(run_id) ON DELETE CASCADE,
    settlement_id    TEXT    NOT NULL,
    bank_txn_id      TEXT,
    exception_type   TEXT    NOT NULL,
    residue_paise    BIGINT  NOT NULL,
    batch_value_paise BIGINT NOT NULL,
    confidence       NUMERIC(9,6) NOT NULL,
    ambiguous        BOOLEAN NOT NULL,
    value_date       DATE,
    verdict          TEXT    NOT NULL,
    policy_reason    TEXT    NOT NULL,
    hypothesis       TEXT    NOT NULL,
    resolution       TEXT,
    idempotency_key  TEXT    NOT NULL,
    evidence         JSONB   NOT NULL DEFAULT '[]'::jsonb,
    agent_steps      TEXT,
    PRIMARY KEY (run_id, settlement_id)
);
CREATE INDEX recon_exception_verdict_idx ON recon_exception (run_id, verdict);
