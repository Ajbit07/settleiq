-- V2 audit ledger: append-only, hash-chained, idempotency-keyed.
--
-- Three guarantees, enforced by the database rather than by convention:
--
--   1. APPEND-ONLY.  A trigger raises on UPDATE and DELETE. Application code
--      cannot quietly revise history, and neither can anyone with a psql
--      prompt and good intentions.
--
--   2. NO DUPLICATE POSTINGS.  idempotency_key is UNIQUE. A retried
--      reconciliation run re-derives the same key from the same facts, the
--      insert conflicts, and nothing is written. Reconciliation jobs get
--      retried constantly in production; double-posting is the failure that
--      actually costs money.
--
--   3. TAMPER-EVIDENT.  Each row stores SHA-256(prev_hash || canonical_json).
--      Altering any historical row breaks every hash after it.

CREATE TABLE audit_ledger (
    seq             BIGSERIAL PRIMARY KEY,
    ts              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    run_id          BIGINT       REFERENCES recon_run(run_id),
    merchant_id     TEXT         NOT NULL,
    actor           TEXT         NOT NULL
                    CHECK (actor IN ('engine','model','agent','human')),
    action          TEXT         NOT NULL,
    entity_type     TEXT         NOT NULL,
    entity_id       TEXT         NOT NULL,
    idempotency_key TEXT         NOT NULL UNIQUE,
    model_version   TEXT         NOT NULL,
    score           NUMERIC(9,6) NOT NULL,
    verdict         TEXT         NOT NULL,
    payload         JSONB        NOT NULL,
    prev_hash       CHAR(64)     NOT NULL,
    hash            CHAR(64)     NOT NULL UNIQUE
);

CREATE INDEX audit_ledger_entity_idx   ON audit_ledger (entity_type, entity_id);
CREATE INDEX audit_ledger_merchant_idx ON audit_ledger (merchant_id, seq);
CREATE INDEX audit_ledger_run_idx      ON audit_ledger (run_id);

CREATE OR REPLACE FUNCTION audit_ledger_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION
      'audit_ledger is append-only; % rejected on seq %',
      TG_OP, COALESCE(OLD.seq, -1)
      USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_ledger_no_update
    BEFORE UPDATE ON audit_ledger
    FOR EACH ROW EXECUTE FUNCTION audit_ledger_immutable();

CREATE TRIGGER audit_ledger_no_delete
    BEFORE DELETE ON audit_ledger
    FOR EACH ROW EXECUTE FUNCTION audit_ledger_immutable();

-- TRUNCATE bypasses row triggers, so it needs its own statement-level guard.
CREATE TRIGGER audit_ledger_no_truncate
    BEFORE TRUNCATE ON audit_ledger
    FOR EACH STATEMENT EXECUTE FUNCTION audit_ledger_immutable();

-- Walk the chain server-side. Returns the first break, or no rows if intact.
CREATE OR REPLACE FUNCTION audit_chain_break(p_merchant TEXT)
RETURNS TABLE (broken_seq BIGINT, reason TEXT) AS $$
DECLARE
    r          RECORD;
    expected   CHAR(64) := repeat('0', 64);
BEGIN
    FOR r IN
        SELECT seq, prev_hash, hash FROM audit_ledger
        WHERE merchant_id = p_merchant ORDER BY seq
    LOOP
        IF r.prev_hash <> expected THEN
            broken_seq := r.seq;
            reason := 'prev_hash does not match the previous row hash';
            RETURN NEXT;
            RETURN;
        END IF;
        expected := r.hash;
    END LOOP;
END;
$$ LANGUAGE plpgsql STABLE;
