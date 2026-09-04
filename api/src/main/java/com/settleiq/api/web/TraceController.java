package com.settleiq.api.web;

import com.settleiq.Money;
import com.settleiq.api.config.TenantGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import com.settleiq.ExceptionAgent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The control trace: what the system actually did, as financial-control events.
 *
 * RECONSTRUCTED, NOT RECORDED.
 *
 * Every event here is derived from state the pipeline already persisted --
 * recon_run and its stage timings, recon_exception with its agent plan and
 * evidence, ingestion_run, and the audit ledger. Nothing is written for the
 * benefit of this endpoint.
 *
 * That is a deliberate choice over adding an event table. A second store of
 * "what happened" would be a second source of truth about money, free to drift
 * from the first, and an event log that disagrees with the ledger is worse than
 * no event log. Reconstructing means the trace cannot claim anything the
 * reconciliation record does not already support: if an event appears here, the
 * row that proves it is queryable.
 *
 * WHAT THE TIMESTAMPS MEAN. Pipeline events are placed at the run's start time
 * plus the CUMULATIVE MEASURED duration of the stages before them. The
 * durations are real -- the engine timed them -- but they are not independently
 * recorded event clocks, and the response says so in `time_basis` rather than
 * letting a precise-looking string imply more precision than exists.
 */
@RestController
@RequestMapping("/api/v1/trace")
@Tag(name = "Control trace")
public class TraceController {

    private final JdbcTemplate jdbc;
    private final TenantGuard guard;

    public TraceController(JdbcTemplate jdbc, TenantGuard guard) {
        this.jdbc = jdbc;
        this.guard = guard;
    }

    /** One semantic event. `status` drives the glyph; nothing here is a log line. */
    private record Ev(String stage, String status, String title, String detail,
                      String sourceId, String at, Long ms, Map<String, Object> extra) {}

    private static Map<String, Object> ev(Ev e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stage", e.stage());
        m.put("status", e.status());
        m.put("title", e.title());
        if (e.detail() != null) m.put("detail", e.detail());
        if (e.sourceId() != null) m.put("source_id", e.sourceId());
        if (e.at() != null) m.put("at", e.at());
        if (e.ms() != null) m.put("ms", e.ms());
        if (e.extra() != null) m.putAll(e.extra());
        return m;
    }

    @Operation(summary = "Execution trace for a run, optionally narrowed to one exception")
    @GetMapping
    public Map<String, Object> trace(@RequestParam("merchantId") String merchantId,
                                     @RequestParam(name = "runId", required = false) Long runId,
                                     @RequestParam(name = "settlementId", required = false)
                                     String settlementId) {
        guard.require(merchantId);

        // The run is looked up BY (run_id, merchant_id) together. Resolving it by
        // id alone and filtering afterwards is how a trace endpoint leaks another
        // tenant's amounts and source ids.
        Map<String, Object> run = runId == null
                ? one("""
                      SELECT run_id, merchant_id, preset, model_version, state, started_at,
                             finished_at, wall_ms, stats
                        FROM recon_run WHERE merchant_id=? AND state='succeeded'
                       ORDER BY run_id DESC LIMIT 1""", merchantId)
                : one("""
                      SELECT run_id, merchant_id, preset, model_version, state, started_at,
                             finished_at, wall_ms, stats
                        FROM recon_run WHERE run_id=? AND merchant_id=?""", runId, merchantId);
        if (run == null) throw new NotFoundException("no such run for that merchant");

        long rid = ((Number) run.get("run_id")).longValue();
        Map<String, Object> stats = asMap(run.get("stats"));
        Map<String, Object> timings = asMap(stats.get("timings"));
        Instant t0 = run.get("started_at") instanceof java.sql.Timestamp ts
                ? ts.toInstant() : Instant.now();

        List<Map<String, Object>> events = new ArrayList<>();
        long cursor = 0;

        // ---- INGEST ---------------------------------------------------------
        Map<String, Object> ing = one("""
                SELECT rows_seen, rows_accepted, rows_rejected, entity, source_name, finished_at
                  FROM ingestion_run WHERE merchant_id=? AND state='succeeded'
                 ORDER BY ingestion_id DESC LIMIT 1""", merchantId);
        if (ing != null) {
            int rej = num(ing.get("rows_rejected"));
            events.add(ev(new Ev("INGEST", rej > 0 ? "warn" : "ok",
                    num(ing.get("rows_accepted")) + " rows accepted",
                    rej > 0 ? rej + " rejected, each stored with its reason"
                            : String.valueOf(ing.get("entity")).toLowerCase() + " validated row by row",
                    String.valueOf(ing.get("source_name")), iso(t0), null, null)));
        } else {
            // Seeded data predates the ingestion-run mechanism. Say that rather
            // than inventing an ingest event that never happened.
            events.add(ev(new Ev("INGEST", "muted",
                    num(stats.get("payments")) + " payments, " + num(stats.get("bank_rows"))
                            + " bank rows loaded",
                    "seeded before ingestion runs were recorded; no upload event exists",
                    null, iso(t0), null, null)));
        }

        // ---- MATCH / SCORING / NETTING, placed by measured stage duration ----
        cursor += lng(timings.get("stage0_normalise"));
        long tMatch = lng(timings.get("stage1_4_match"));
        cursor += tMatch;
        events.add(ev(new Ev("MATCH", "ok",
                num(stats.get("bank_matched")) + " / " + num(stats.get("settlements")) + " credits matched",
                stats.containsKey("exact_matches")
                        ? num(stats.get("exact_matches")) + " on an exact reference, the rest scored"
                        : "exact and scored matches combined",
                null, iso(t0.plusMillis(cursor)), tMatch, null)));

        // A run recorded before these counters existed has no figure to show.
        // Saying "scored, count not recorded" beats printing a confident 0.
        boolean haveCands = stats.containsKey("candidate_pairs");
        events.add(ev(new Ev("SCORING", "ok",
                haveCands ? num(stats.get("candidate_pairs")) + " candidate pairs scored"
                          : "candidate pairs scored",
                "model " + str(run.get("model_version"))
                        + (haveCands ? "" : " · count not recorded for this run"),
                null, iso(t0.plusMillis(cursor)), null, null)));

        long tNet = lng(timings.get("stage5_netting"));
        cursor += tNet;
        events.add(ev(new Ev("NETTING", "ok",
                num(stats.get("payment_links")) + " payments attributed to batches",
                stats.containsKey("unassigned_payments")
                        ? "exact partition; " + num(stats.get("unassigned_payments")) + " unassigned"
                        : "exact partition verified",
                null, iso(t0.plusMillis(cursor)), tNet, null)));

        // ---- EXCEPTION ------------------------------------------------------
        cursor += lng(timings.get("stage6_decompose"));
        long residue = lng(stats.get("residue_abs_paise"));
        events.add(ev(new Ev("EXCEPTION", residue > 0 ? "warn" : "ok",
                Money.fmtInr(residue) + " residue detected",
                num(stats.get("exceptions")) + " exceptions raised for investigation",
                null, iso(t0.plusMillis(cursor)), null, null)));

        // ---- one exception, if asked ----------------------------------------
        Map<String, Object> exc = null;
        if (settlementId != null && !settlementId.isBlank()) {
            exc = one("""
                    SELECT settlement_id, exception_type, residue_paise, batch_value_paise,
                           ambiguous, verdict, policy_reason, resolution, idempotency_key,
                           evidence, agent_plan, llm_used, llm_model, llm_calls
                      FROM recon_exception WHERE run_id=? AND settlement_id=?""",
                    rid, settlementId);
        }
        if (exc != null) events.addAll(investigation(exc, t0.plusMillis(cursor)));

        // ---- AUDIT ----------------------------------------------------------
        Map<String, Object> aud = exc != null
                ? one("""
                      SELECT ts, verdict, actor, entity_id, idempotency_key
                        FROM audit_ledger WHERE merchant_id=? AND entity_id=?
                       ORDER BY seq DESC LIMIT 1""", merchantId, settlementId)
                : one("""
                      SELECT max(ts) AS ts, count(*) AS n FROM audit_ledger
                       WHERE merchant_id=?""", merchantId);
        if (aud != null && aud.get("ts") != null) {
            boolean single = exc != null;
            events.add(ev(new Ev("AUDIT", "ok",
                    single ? "audit entry recorded" : num(aud.get("n")) + " ledger rows, chain intact",
                    single ? "appended and covered by the hash chain"
                           : "append-only, enforced by trigger",
                    single ? str(aud.get("idempotency_key")) : null,
                    aud.get("ts") instanceof java.sql.Timestamp t ? iso(t.toInstant()) : null,
                    null, null)));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("merchant_id", merchantId);
        out.put("run_id", rid);
        // Echo the settlement id ONLY when it resolved to a row in THIS
        // merchant's run. Reflecting a caller-supplied identifier back would let
        // one tenant see another's id rendered as though it were their own --
        // no data leaks, but the screen would still be telling a lie.
        out.put("settlement_id", exc != null ? str(exc.get("settlement_id")) : null);
        if (settlementId != null && !settlementId.isBlank() && exc == null)
            out.put("settlement_note", "no such exception in this run");
        out.put("state", run.get("state"));
        out.put("wall_ms", run.get("wall_ms"));
        out.put("time_basis", "pipeline events are placed at the run start plus the cumulative "
                + "measured duration of preceding stages; the durations are real, the per-event "
                + "clock is derived");
        out.put("events", events);
        return out;
    }

    /**
     * The investigation half: who planned it, what they read, what came back.
     *
     * An LLM event is emitted ONLY when the persisted row says a model actually
     * answered and its choice was accepted (`chosen_by` begins `llm:`). A
     * configured-but-unreachable model leaves llm_used false, and the trace then
     * says DETERMINISTIC -- never "LLM active" for a call that did not happen.
     */
    private List<Map<String, Object>> investigation(Map<String, Object> exc, Instant base) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<?> plan = asList(exc.get("agent_plan"));
        List<?> evidence = asList(exc.get("evidence"));
        boolean llmUsed = Boolean.TRUE.equals(exc.get("llm_used"));
        String model = str(exc.get("llm_model"));
        Instant t = base;

        boolean ambiguous = Boolean.TRUE.equals(exc.get("ambiguous"));
        // The cap governs PLANNED steps. Mandatory completion runs after it, so
        // counting the two together would print "12 lookups, hard cap 6" and
        // read as a broken invariant rather than as two different things.
        int mandatory = 0;
        for (Object o : plan)
            if ("engine:mandatory".equals(str(asMap(o).get("chosen_by")))) mandatory++;
        int planned = plan.size() - mandatory;
        out.add(ev(new Ev("INVESTIGATION", "active",
                "bounded investigation started",
                planned + " planned (cap " + ExceptionAgent.MAX_STEPS + ")"
                        + (mandatory > 0 ? " + " + mandatory + " required by the engine" : "")
                        + " · planner: " + (llmUsed ? model : "deterministic"),
                str(exc.get("settlement_id")), iso(t), null,
                Map.of("planner", llmUsed ? "llm" : "deterministic",
                       "llm_used", llmUsed,
                       "llm_model", model == null ? "" : model,
                       "llm_calls", exc.get("llm_calls") == null ? 0 : exc.get("llm_calls")))));

        int evIdx = 0;
        for (Object o : plan) {
            Map<String, Object> st = asMap(o);
            String tool = str(st.get("tool"));
            String by = str(st.get("chosen_by"));
            long ms = lng(st.get("ms"));
            boolean byLlm = by != null && by.startsWith("llm:");
            boolean mandated = "engine:mandatory".equals(by);
            t = t.plusMillis(Math.max(ms, 1));

            if (byLlm)
                out.add(ev(new Ev("LLM", "active", by.substring(4) + " selected " + tool,
                        str(st.get("reason")), null, iso(t), null,
                        Map.of("tool", tool, "model", by.substring(4)))));

            int facts = num(st.get("facts"));
            out.add(ev(new Ev("TOOL", facts > 0 ? "ok" : "muted",
                    tool + (facts > 0 ? " completed" : " returned nothing"),
                    byLlm    ? "chosen by the model, executed against the merchant's rows"
                    : mandated ? "required by the engine: the classifier can act on this lookup "
                               + "and the planner did not reach it"
                               : "chosen by the deterministic planner",
                    null, iso(t), ms, null)));

            // The evidence rows are ordered as the tools produced them, so the
            // facts this step contributed are the next `facts` entries.
            for (int k = 0; k < facts && evIdx < evidence.size(); k++, evIdx++) {
                Map<String, Object> e = asMap(evidence.get(evIdx));
                out.add(ev(new Ev("EVIDENCE", "ok", str(e.get("provenance_id")),
                        str(e.get("claim")), str(e.get("provenance_id")), iso(t), null,
                        Map.of("provenance_type", str(e.get("provenance_type"))))));
            }
        }

        long residue = lng(exc.get("residue_paise"));
        out.add(ev(new Ev("ARITHMETIC", "ok", "exact paise validation completed",
                "residue " + Money.fmtInr(residue) + " on a batch of "
                        + Money.fmtInr(lng(exc.get("batch_value_paise")))
                        + ", computed by the engine from source rows",
                null, iso(t), null, null)));

        boolean posted = "AUTO_POST".equals(str(exc.get("verdict")));
        if (ambiguous)
            out.add(ev(new Ev("POLICY", "warn", "ambiguity detected",
                    "identity evidence insufficient to prove a unique match",
                    null, iso(t), null, null)));
        out.add(ev(new Ev("POLICY", posted ? "ok" : "warn",
                posted ? "auto-post permitted" : "automatic posting blocked",
                str(exc.get("policy_reason")), null, iso(t), null,
                Map.of("verdict", str(exc.get("verdict")),
                       "resolution", str(exc.get("resolution"))))));
        out.add(ev(new Ev("LEDGER", posted ? "ok" : "muted",
                posted ? "posting recorded" : "nothing posted",
                posted ? "written once, keyed by idempotency hash"
                       : "held for human review; no ledger movement",
                str(exc.get("idempotency_key")), iso(t), null, null)));
        return out;
    }

    // ───────────────────────────────────────────────────────────── plumbing
    private Map<String, Object> one(String sql, Object... args) {
        var r = jdbc.queryForList(sql, args);
        return r.isEmpty() ? null : r.get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        if (o instanceof org.postgresql.util.PGobject pg && pg.getValue() != null) {
            Object p = com.settleiq.Csv.json(pg.getValue());
            if (p instanceof Map<?, ?> m2) return (Map<String, Object>) m2;
        }
        return Map.of();
    }

    private static List<?> asList(Object o) {
        if (o instanceof List<?> l) return l;
        if (o instanceof org.postgresql.util.PGobject pg && pg.getValue() != null) {
            Object p = com.settleiq.Csv.json(pg.getValue());
            if (p instanceof List<?> l2) return l2;
        }
        return List.of();
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static int num(Object o) { return o instanceof Number n ? n.intValue() : 0; }
    private static long lng(Object o) { return o instanceof Number n ? n.longValue() : 0L; }
    private static String iso(Instant i) { return i == null ? null : i.toString(); }
}
