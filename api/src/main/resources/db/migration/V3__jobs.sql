-- V3 job coordination.
--
-- NO REDIS, NO KAFKA -- deliberately. A reconciliation run is a low-frequency,
-- long-ish, strictly-once-per-(merchant, period) job. Postgres advisory locks
-- plus a jobs table give exactly-once claiming with transactional visibility
-- against the same data the job reads. Adding a broker would introduce a second
-- source of truth about whether a run happened, which is precisely the
-- ambiguity an audit trail exists to remove.

CREATE TABLE recon_job (
    job_id       BIGSERIAL PRIMARY KEY,
    merchant_id  TEXT        NOT NULL REFERENCES merchant(merchant_id),
    period_start DATE        NOT NULL,
    period_end   DATE        NOT NULL,
    preset       TEXT        NOT NULL DEFAULT 'full',
    state        TEXT        NOT NULL DEFAULT 'queued'
                 CHECK (state IN ('queued','claimed','running','succeeded','failed','cancelled')),
    attempts     INT         NOT NULL DEFAULT 0,
    max_attempts INT         NOT NULL DEFAULT 3,
    claimed_by   TEXT,
    claimed_at   TIMESTAMPTZ,
    finished_at  TIMESTAMPTZ,
    run_id       BIGINT      REFERENCES recon_run(run_id),
    last_error   TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- one live job per merchant-period; retries reuse the row
    CONSTRAINT recon_job_unique_period UNIQUE (merchant_id, period_start, period_end)
);

CREATE INDEX recon_job_claimable_idx
    ON recon_job (state, created_at) WHERE state = 'queued';

-- Stable 64-bit lock key for a merchant-period. hashtextextended is stable
-- across sessions and versions, which matters because the lock has to mean the
-- same thing to every worker.
CREATE OR REPLACE FUNCTION recon_job_lock_key(p_merchant TEXT, p_start DATE, p_end DATE)
RETURNS BIGINT AS $$
    SELECT hashtextextended(p_merchant || ':' || p_start::text || ':' || p_end::text, 0);
$$ LANGUAGE sql IMMUTABLE;

-- Claim one queued job. SKIP LOCKED lets N workers poll concurrently without
-- blocking each other; the advisory lock then guards the whole run, so a worker
-- that dies mid-run releases it on disconnect rather than wedging the queue.
CREATE OR REPLACE FUNCTION claim_recon_job(p_worker TEXT)
RETURNS TABLE (job_id BIGINT, merchant_id TEXT, period_start DATE,
               period_end DATE, preset TEXT, attempts INT) AS $$
DECLARE
    j RECORD;
BEGIN
    FOR j IN
        SELECT * FROM recon_job
        WHERE state = 'queued' AND attempts < max_attempts
        ORDER BY created_at
        FOR UPDATE SKIP LOCKED
    LOOP
        IF pg_try_advisory_lock(
               recon_job_lock_key(j.merchant_id, j.period_start, j.period_end)) THEN
            UPDATE recon_job
               SET state = 'claimed', claimed_by = p_worker,
                   claimed_at = now(), attempts = attempts + 1
             WHERE recon_job.job_id = j.job_id;
            job_id := j.job_id; merchant_id := j.merchant_id;
            period_start := j.period_start; period_end := j.period_end;
            preset := j.preset; attempts := j.attempts + 1;
            RETURN NEXT;
            RETURN;
        END IF;
    END LOOP;
END;
$$ LANGUAGE plpgsql;

-- Re-queue jobs whose worker vanished. Called by the scheduler, not by hand.
CREATE OR REPLACE FUNCTION reap_stale_jobs(p_older_than INTERVAL)
RETURNS INT AS $$
DECLARE n INT;
BEGIN
    UPDATE recon_job
       SET state = 'queued', claimed_by = NULL, claimed_at = NULL,
           last_error = 'reclaimed after worker timeout'
     WHERE state IN ('claimed','running')
       AND claimed_at < now() - p_older_than
       AND attempts < max_attempts;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN n;
END;
$$ LANGUAGE plpgsql;

-- Pinned model artifacts. A decision recorded in the ledger names a version
-- here, so a run can be reproduced years later with the weights it used.
CREATE TABLE model_registry (
    model_version TEXT PRIMARY KEY,
    kind          TEXT        NOT NULL,
    artifact      JSONB       NOT NULL,
    metrics       JSONB       NOT NULL DEFAULT '{}'::jsonb,
    active        BOOLEAN     NOT NULL DEFAULT false,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- At most one active model per kind.
CREATE UNIQUE INDEX model_registry_one_active
    ON model_registry (kind) WHERE active;
