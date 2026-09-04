package com.settleiq.api.service;

import com.settleiq.Config;
import com.settleiq.Csv;
import com.settleiq.Model;
import com.settleiq.Pipeline;
import com.settleiq.Scorer;
import com.settleiq.api.config.SettleIqProperties;
import com.settleiq.api.repo.PostgresAuditLedger;
import com.settleiq.api.repo.RunRepository;
import com.settleiq.api.repo.SourceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs one reconciliation.
 *
 * The service does no arithmetic. It loads inputs, hands them to the engine,
 * and persists what comes back. Policy thresholds are copied from validated
 * configuration into the engine's Config so the operating point is explicit in
 * the run record rather than implicit in a build.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final SourceRepository sources;
    private final RunRepository runs;
    private final JdbcTemplate jdbc;
    private final SettleIqProperties props;
    private final MeterRegistry meters;
    private final AtomicReference<Scorer> scorer = new AtomicReference<>();

    /**
     * Gauge backing values, held strongly and on purpose.
     *
     * MeterRegistry.gauge(name, number) keeps only a WEAK reference to the
     * value. Pass a freshly boxed Long and the next GC collects it, after which
     * the gauge reports NaN forever -- a dashboard that silently stops
     * reporting residue. The registry holds a weak reference to these, but the
     * map holds a strong one, so they survive.
     */
    private final java.util.concurrent.ConcurrentMap<String, java.util.concurrent.atomic.AtomicLong>
            residueGauges = new java.util.concurrent.ConcurrentHashMap<>();

    public ReconciliationService(SourceRepository sources, RunRepository runs,
                                 JdbcTemplate jdbc, SettleIqProperties props,
                                 MeterRegistry meters) {
        this.sources = sources;
        this.runs = runs;
        this.jdbc = jdbc;
        this.props = props;
        this.meters = meters;
    }

    /** Loaded once and reused: the model is a pinned artifact, not a live service. */
    private Scorer scorer() {
        return scorer.updateAndGet(s -> s != null ? s : new Scorer(Path.of(props.modelPath())));
    }

    public Config engineConfig(String preset) {
        Config cfg = switch (preset) {
            case "deterministic" -> Config.deterministicOnly();
            case "repair" -> Config.plusRepair();
            case "scorer" -> Config.plusScorer();
            case "global" -> Config.plusGlobalAssignment();
            case "netting" -> Config.plusNetting();
            default -> Config.full();
        };
        cfg.tolerancePaise = props.tolerancePaise();
        cfg.autoPostMinConfidence = props.autoPostMinConfidence();
        cfg.autoPostMaxResiduePaise = props.autoPostMaxResiduePaise();
        cfg.autoPostMaxResidueFraction = props.autoPostMaxResidueFraction();
        cfg.dailyAutoPostBudgetPaise = props.dailyAutoPostBudgetPaise();
        cfg.dailyAutoPostCountBudget = props.dailyAutoPostCountBudget();
        cfg.modelPath = props.modelPath();
        return cfg;
    }

    public record RunResult(long runId, Pipeline.Output output, long wallMs) {}

    public RunResult run(String merchantId, String preset) {
        if (!sources.merchantExists(merchantId))
            throw new IllegalArgumentException("unknown merchant: " + merchantId);

        long runId = runs.createRun(merchantId, preset);
        Timer.Sample sample = Timer.start(meters);
        long t0 = System.currentTimeMillis();
        try {
            Model.Inputs in = sources.load(merchantId);
            Scorer sc = scorer();
            var sink = new PostgresAuditLedger(jdbc, merchantId, runId);
            Pipeline.Output out = new Pipeline(engineConfig(preset), in, sc, sink).run();
            long wall = System.currentTimeMillis() - t0;

            runs.saveResults(runId, out);
            runs.markSucceeded(runId, sc.modelVersion, wall, stats(out, in));

            meters.counter("settleiq.runs", "outcome", "succeeded").increment();
            residueGauges.computeIfAbsent(merchantId, m -> {
                var holder = new java.util.concurrent.atomic.AtomicLong();
                meters.gauge("settleiq.residue.abs.paise",
                        java.util.List.of(io.micrometer.core.instrument.Tag.of("merchant", m)),
                        holder, java.util.concurrent.atomic.AtomicLong::doubleValue);
                return holder;
            }).set(out.absResidue);
            meters.counter("settleiq.exceptions.auto_posted").increment(out.autoPosted);
            meters.counter("settleiq.exceptions.escalated").increment(out.escalated);
            meters.counter("settleiq.exceptions.suppressed").increment(out.suppressed);

            log.info("reconciliation complete merchant={} run={} preset={} wall_ms={} "
                     + "links={} exceptions={} auto={} escalated={} residue_paise={}",
                    merchantId, runId, preset, wall, out.paymentToSettlement.size(),
                    out.exceptions.size(), out.autoPosted, out.escalated, out.absResidue);
            return new RunResult(runId, out, wall);
        } catch (RuntimeException e) {
            runs.markFailed(runId, e.toString());
            meters.counter("settleiq.runs", "outcome", "failed").increment();
            log.error("reconciliation failed merchant={} run={}", merchantId, runId, e);
            throw e;
        } finally {
            sample.stop(meters.timer("settleiq.run.duration"));
        }
    }

    private static String stats(Pipeline.Output out, Model.Inputs in) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"payments\":").append(in.payments.size())
          .append(",\"settlements\":").append(in.settlements.size())
          .append(",\"bank_rows\":").append(in.bank.size())
          .append(",\"bank_matched\":").append(out.bankToSettlement.size())
          // Needed by the control trace, which would otherwise print "0 candidate
          // pairs scored" -- a statement that is false rather than merely absent.
          .append(",\"exact_matches\":").append(out.matchResult == null ? 0
                  : out.matchResult.exactCount())
          .append(",\"candidate_pairs\":").append(out.candidatePairs)
          .append(",\"unassigned_payments\":").append(
                  out.netting == null ? 0 : out.netting.unassignedPaymentIds().size())
          .append(",\"payment_links\":").append(out.paymentToSettlement.size())
          .append(",\"ambiguous_payments\":").append(out.ambiguousPayments.size())
          .append(",\"exceptions\":").append(out.exceptions.size())
          .append(",\"auto_posted\":").append(out.autoPosted)
          .append(",\"escalated\":").append(out.escalated)
          .append(",\"suppressed_duplicate\":").append(out.suppressed)
          .append(",\"residue_abs_paise\":").append(out.absResidue)
          .append(",\"residue_net_paise\":").append(out.totalResidue)
          .append(",\"audit_rows\":").append(out.auditRows)
          .append(",\"audit_head\":").append(Csv.q(out.auditHead))
          .append(",\"timings\":{");
        for (int i = 0; i < out.timings.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(Csv.q(out.timings.get(i).stage())).append(':')
              .append(out.timings.get(i).millis());
        }
        return sb.append("}}").toString();
    }
}
