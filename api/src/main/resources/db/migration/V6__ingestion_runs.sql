-- V6 records every ingestion as a first-class run with per-row rejections.
--
-- WHY THIS EXISTS
--
-- Before this, ingestion was a startup side effect: it either worked or logged
-- a warning, and nothing downstream could tell you which rows entered the
-- system or why a row is missing. That is the shape of the V5 bug -- a silent
-- drop that surfaced weeks later as a very large, very confident number.
--
-- So ingestion now produces evidence. Every accepted row carries the id of the
-- run that admitted it, and every rejected row is stored with the reason and
-- the raw line, because "4,998 of 5,012 accepted" is only trustworthy if the
-- other 14 can be produced on demand.

CREATE TABLE ingestion_run (
    ingestion_id   BIGSERIAL PRIMARY KEY,
    merchant_id    TEXT        NOT NULL REFERENCES merchant(merchant_id),
    entity         TEXT        NOT NULL,
    source_name    TEXT        NOT NULL,
    source_bytes   BIGINT      NOT NULL,
    source_sha256  TEXT        NOT NULL,
    rows_seen      INTEGER     NOT NULL DEFAULT 0,
    rows_accepted  INTEGER     NOT NULL DEFAULT 0,
    rows_rejected  INTEGER     NOT NULL DEFAULT 0,
    state          TEXT        NOT NULL DEFAULT 'running',
    error          TEXT,
    started_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at    TIMESTAMPTZ
);
CREATE INDEX ingestion_run_merchant_idx ON ingestion_run (merchant_id, ingestion_id DESC);

CREATE TABLE ingestion_reject (
    reject_id     BIGSERIAL PRIMARY KEY,
    ingestion_id  BIGINT  NOT NULL REFERENCES ingestion_run(ingestion_id) ON DELETE CASCADE,
    line_no       INTEGER NOT NULL,
    reason_code   TEXT    NOT NULL,
    detail        TEXT    NOT NULL,
    raw_line      TEXT    NOT NULL
);
CREATE INDEX ingestion_reject_run_idx ON ingestion_reject (ingestion_id, line_no);

-- Orders were observable in the generated data but had no table, so an order
-- file had nowhere to land. Keyed by (merchant_id, order_id) from the start,
-- for the reason V5 exists.
CREATE TABLE order_row (
    merchant_id     TEXT      NOT NULL REFERENCES merchant(merchant_id),
    order_id        TEXT      NOT NULL,
    customer_id     TEXT      NOT NULL,
    amount_paise    BIGINT    NOT NULL CHECK (amount_paise >= 0),
    created_at_utc  TIMESTAMP NOT NULL,
    PRIMARY KEY (merchant_id, order_id)
);
CREATE INDEX order_row_customer_idx ON order_row (merchant_id, customer_id);

-- Provenance on every source row: which ingestion admitted it, and when.
-- Nullable because rows seeded before this migration genuinely have no run to
-- point at, and backfilling them with a fabricated id would be worse than
-- admitting they predate the mechanism.
ALTER TABLE payment       ADD COLUMN ingestion_id BIGINT REFERENCES ingestion_run(ingestion_id);
ALTER TABLE refund        ADD COLUMN ingestion_id BIGINT REFERENCES ingestion_run(ingestion_id);
ALTER TABLE chargeback    ADD COLUMN ingestion_id BIGINT REFERENCES ingestion_run(ingestion_id);
ALTER TABLE reserve_entry ADD COLUMN ingestion_id BIGINT REFERENCES ingestion_run(ingestion_id);
ALTER TABLE settlement    ADD COLUMN ingestion_id BIGINT REFERENCES ingestion_run(ingestion_id);
ALTER TABLE bank_txn      ADD COLUMN ingestion_id BIGINT REFERENCES ingestion_run(ingestion_id);
ALTER TABLE order_row     ADD COLUMN ingestion_id BIGINT REFERENCES ingestion_run(ingestion_id);

ALTER TABLE payment       ADD COLUMN ingested_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE refund        ADD COLUMN ingested_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE chargeback    ADD COLUMN ingested_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE reserve_entry ADD COLUMN ingested_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE settlement    ADD COLUMN ingested_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE bank_txn      ADD COLUMN ingested_at TIMESTAMPTZ NOT NULL DEFAULT now();
