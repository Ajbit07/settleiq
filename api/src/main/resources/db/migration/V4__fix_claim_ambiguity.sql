-- V4 fix an ambiguous column reference in claim_recon_job.
--
-- The function declares an OUT parameter named `attempts`, which shadows
-- recon_job.attempts inside the UPDATE. Postgres resolves this at RUN time, not
-- at CREATE time, so the function was accepted and then failed the first time a
-- worker actually tried to claim a job -- caught by JobClaimIT.
--
-- Fixed forward as a new migration rather than by editing V3. V3 has already
-- been applied wherever this ran; changing it would break Flyway checksum
-- validation and, worse, leave two environments with the same schema version
-- and different behaviour.

CREATE OR REPLACE FUNCTION claim_recon_job(p_worker TEXT)
RETURNS TABLE (job_id BIGINT, merchant_id TEXT, period_start DATE,
               period_end DATE, preset TEXT, attempts INT) AS $$
DECLARE
    j RECORD;
BEGIN
    FOR j IN
        SELECT * FROM recon_job r
        WHERE r.state = 'queued' AND r.attempts < r.max_attempts
        ORDER BY r.created_at
        FOR UPDATE SKIP LOCKED
    LOOP
        IF pg_try_advisory_lock(
               recon_job_lock_key(j.merchant_id, j.period_start, j.period_end)) THEN
            UPDATE recon_job r
               SET state      = 'claimed',
                   claimed_by = p_worker,
                   claimed_at = now(),
                   -- qualify explicitly: bare `attempts` binds to the OUT
                   -- parameter, not to the column
                   attempts   = r.attempts + 1
             WHERE r.job_id = j.job_id;

            job_id       := j.job_id;
            merchant_id  := j.merchant_id;
            period_start := j.period_start;
            period_end   := j.period_end;
            preset       := j.preset;
            attempts     := j.attempts + 1;
            RETURN NEXT;
            RETURN;
        END IF;
    END LOOP;
END;
$$ LANGUAGE plpgsql;
