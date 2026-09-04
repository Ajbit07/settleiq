package com.settleiq.api.worker;

import com.settleiq.api.config.SettleIqProperties;
import com.settleiq.api.service.ReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Polls recon_job and runs claimed work.
 *
 * Claiming is done by claim_recon_job(), which combines FOR UPDATE SKIP LOCKED
 * with a session advisory lock. SKIP LOCKED lets several replicas poll without
 * queueing behind each other; the advisory lock is held for the duration of the
 * run and is released by Postgres if the connection drops, so a worker that
 * dies does not strand the job.
 *
 * No broker. See V3__jobs.sql for why.
 */
@Component
@ConditionalOnProperty(prefix = "settleiq", name = "worker-enabled", havingValue = "true")
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JdbcTemplate jdbc;
    private final ReconciliationService service;
    private final SettleIqProperties props;

    public JobWorker(JdbcTemplate jdbc, ReconciliationService service, SettleIqProperties props) {
        this.jdbc = jdbc;
        this.service = service;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${settleiq.worker-poll-ms}")
    public void poll() {
        List<Map<String, Object>> claimed;
        try {
            claimed = jdbc.queryForList("SELECT * FROM claim_recon_job(?)", props.workerId());
        } catch (RuntimeException e) {
            log.warn("job claim failed; will retry next tick", e);
            return;
        }
        if (claimed.isEmpty()) return;

        Map<String, Object> job = claimed.get(0);
        long jobId = ((Number) job.get("job_id")).longValue();
        String merchant = (String) job.get("merchant_id");
        String preset = (String) job.get("preset");
        log.info("claimed job id={} merchant={} preset={} worker={}",
                jobId, merchant, preset, props.workerId());

        jdbc.update("UPDATE recon_job SET state='running' WHERE job_id=?", jobId);
        try {
            var res = service.run(merchant, preset);
            jdbc.update("""
                    UPDATE recon_job SET state='succeeded', finished_at=now(),
                           run_id=?, last_error=NULL WHERE job_id=?
                    """, res.runId(), jobId);
        } catch (RuntimeException e) {
            // Leave it queued if attempts remain; the reaper picks it up.
            jdbc.update("""
                    UPDATE recon_job
                       SET state = CASE WHEN attempts >= max_attempts THEN 'failed' ELSE 'queued' END,
                           last_error = ?, finished_at = now()
                     WHERE job_id = ?
                    """, e.toString(), jobId);
            log.error("job {} failed", jobId, e);
        } finally {
            jdbc.queryForObject("SELECT pg_advisory_unlock(recon_job_lock_key(?,?,?))",
                    Boolean.class, merchant, job.get("period_start"), job.get("period_end"));
        }
    }

    /** Re-queue work whose worker vanished mid-run. */
    @Scheduled(fixedDelay = 60_000L)
    public void reap() {
        try {
            Integer n = jdbc.queryForObject("SELECT reap_stale_jobs(make_interval(mins => ?))",
                    Integer.class, props.staleJobMinutes());
            if (n != null && n > 0) log.warn("re-queued {} stale job(s)", n);
        } catch (RuntimeException e) {
            log.warn("stale job reap failed", e);
        }
    }
}
