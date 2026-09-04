package com.settleiq.api.repo;

import com.settleiq.Model;
import com.settleiq.Pipeline;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** Persists run metadata and results. Results are versioned by run_id. */
@Repository
public class RunRepository {

    private final JdbcTemplate jdbc;

    public RunRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long createRun(String merchantId, String preset) {
        return jdbc.queryForObject("""
                INSERT INTO recon_run (merchant_id, preset, state, started_at)
                VALUES (?, ?, 'running', now()) RETURNING run_id
                """, Long.class, merchantId, preset);
    }

    public void markSucceeded(long runId, String modelVersion, long wallMs, String statsJson) {
        jdbc.update("""
                UPDATE recon_run SET state='succeeded', finished_at=now(),
                       model_version=?, wall_ms=?, stats=?::jsonb
                 WHERE run_id=?
                """, modelVersion, wallMs, statsJson, runId);
    }

    public void markFailed(long runId, String error) {
        jdbc.update("UPDATE recon_run SET state='failed', finished_at=now(), error=? "
                + "WHERE run_id=?", error == null ? "unknown" : error, runId);
    }

    public Map<String, Object> run(long runId) {
        var r = jdbc.queryForList("SELECT * FROM recon_run WHERE run_id=?", runId);
        return r.isEmpty() ? null : unwrapJson(r.get(0));
    }

    /**
     * Turn pgjdbc's PGobject wrappers into parsed JSON.
     *
     * Left alone, Jackson serialises the wrapper's bean properties and the
     * client receives {"type":"jsonb","value":"{...}","null":false} instead of
     * the object. It looks like data, so it survives a smoke test that only
     * checks for HTTP 200.
     */
    private static Map<String, Object> unwrapJson(Map<String, Object> row) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (var e : row.entrySet()) {
            Object v = e.getValue();
            if (v instanceof org.postgresql.util.PGobject pg
                    && ("jsonb".equals(pg.getType()) || "json".equals(pg.getType()))) {
                out.put(e.getKey(), pg.getValue() == null
                        ? null : com.settleiq.Csv.json(pg.getValue()));
            } else {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }

    public List<Map<String, Object>> runs(String merchantId, int limit) {
        return jdbc.queryForList("""
                SELECT run_id, merchant_id, preset, state, model_version, wall_ms,
                       started_at, finished_at, stats
                  FROM recon_run WHERE merchant_id=? ORDER BY run_id DESC LIMIT ?
                """, merchantId, limit).stream().map(RunRepository::unwrapJson).toList();
    }

    public Long latestSucceededRun(String merchantId) {
        var r = jdbc.queryForList("""
                SELECT run_id FROM recon_run
                 WHERE merchant_id=? AND state='succeeded'
                 ORDER BY run_id DESC LIMIT 1
                """, merchantId);
        return r.isEmpty() ? null : ((Number) r.get(0).get("run_id")).longValue();
    }

    /**
     * Write the whole result set for one run in a single transaction.
     *
     * All or nothing: a half-written run would let the API serve a
     * decomposition whose payment links do not exist, which reads as a
     * reconciliation failure rather than the crash it actually is.
     */
    @Transactional
    public void saveResults(long runId, Pipeline.Output out) {
        List<Object[]> decs = new java.util.ArrayList<>();
        for (Model.Decomposition d : out.decompositions) {
            for (var c : d.components.entrySet())
                decs.add(new Object[]{runId, d.settlementId, d.bankTxnId, c.getKey(), c.getValue()});
            decs.add(new Object[]{runId, d.settlementId, d.bankTxnId, "residue", d.residue});
            decs.add(new Object[]{runId, d.settlementId, d.bankTxnId,
                    "TOTAL_bank_credit", d.bankAmount});
        }
        jdbc.batchUpdate("""
                INSERT INTO decomposition (run_id, settlement_id, bank_txn_id, component, amount_paise)
                VALUES (?,?,?,?,?)
                ON CONFLICT (run_id, settlement_id, component) DO NOTHING
                """, decs);

        List<Object[]> links = new java.util.ArrayList<>();
        for (var e : out.paymentToSettlement.entrySet()) {
            String bank = null;
            for (var b : out.bankToSettlement.entrySet())
                if (b.getValue().equals(e.getValue())) { bank = b.getKey(); break; }
            links.add(new Object[]{runId, e.getKey(), e.getValue(), bank,
                    out.ambiguousPayments.contains(e.getKey())});
        }
        jdbc.batchUpdate("""
                INSERT INTO payment_link (run_id, payment_id, settlement_id, bank_txn_id, ambiguous)
                VALUES (?,?,?,?,?)
                ON CONFLICT (run_id, payment_id) DO NOTHING
                """, links);

        List<Object[]> excs = new java.util.ArrayList<>();
        for (var x : out.exceptions) {
            StringBuilder ev = new StringBuilder("[");
            String resolution = null, steps = null;
            if (x.trace != null) {
                var list = x.trace.evidence();
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) ev.append(',');
                    ev.append("{\"claim\":").append(com.settleiq.Csv.q(list.get(i).claim()))
                      .append(",\"provenance_type\":")
                      .append(com.settleiq.Csv.q(list.get(i).provenanceType()))
                      .append(",\"provenance_id\":")
                      .append(com.settleiq.Csv.q(list.get(i).provenanceId())).append('}');
                }
                resolution = x.trace.resolution();
                steps = String.join(">", x.trace.steps());
            }
            ev.append(']');

            // Who chose each step, and the chooser's own words. Without this the
            // UI can redraw the path but cannot say whether a model picked it,
            // which is the only interesting question about an AI-assisted run.
            StringBuilder plan = new StringBuilder("[");
            boolean llmUsed = false;
            String llmModel = null;
            int llmCalls = 0;
            if (x.trace != null) {
                llmUsed = x.trace.llmUsed();
                llmModel = x.trace.llmModel();
                llmCalls = x.trace.llmCalls();
                var steps2 = x.trace.plan();
                for (int i = 0; i < steps2.size(); i++) {
                    var st = steps2.get(i);
                    if (i > 0) plan.append(',');
                    plan.append("{\"n\":").append(st.n())
                        .append(",\"tool\":").append(com.settleiq.Csv.q(st.tool()))
                        .append(",\"chosen_by\":").append(com.settleiq.Csv.q(st.chosenBy()))
                        .append(",\"reason\":").append(com.settleiq.Csv.q(st.reason()))
                        .append(",\"facts\":").append(st.factsReturned())
                        .append(",\"ms\":").append(st.latencyNanos() / 1_000_000).append('}');
                }
            }
            plan.append(']');

            excs.add(new Object[]{runId, x.settlementId, x.bankTxnId, x.type, x.residue,
                    x.batchValue, x.confidence, x.ambiguous,
                    x.valueDate == null ? null : java.sql.Date.valueOf(x.valueDate),
                    x.verdict, x.policyReason, x.hypothesis, resolution,
                    x.idempotencyKey, ev.toString(), steps,
                    llmUsed, llmModel, llmCalls, plan.toString()});
        }
        jdbc.batchUpdate("""
                INSERT INTO recon_exception
                  (run_id, settlement_id, bank_txn_id, exception_type, residue_paise,
                   batch_value_paise, confidence, ambiguous, value_date, verdict,
                   policy_reason, hypothesis, resolution, idempotency_key, evidence, agent_steps,
                   llm_used, llm_model, llm_calls, agent_plan)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?::jsonb)
                ON CONFLICT (run_id, settlement_id) DO NOTHING
                """, excs);
    }

    /**
     * Bank credits with their components.
     *
     * The joins are scoped by merchant_id via recon_run. Since V5 the source
     * tables are keyed on (merchant_id, id), so joining on the bare id would
     * fan out across tenants that happen to share an identifier -- which is
     * exactly the collision V5 exists to prevent.
     */
    public List<Map<String, Object>> credits(long runId) {
        return jdbc.queryForList("""
                SELECT d.settlement_id, d.bank_txn_id,
                       jsonb_object_agg(d.component, d.amount_paise) AS components,
                       max(b.value_date)  AS value_date,
                       max(b.narration)   AS narration,
                       max(s.settled_at)  AS settled_at,
                       bool_or(s.instant) AS instant,
                       (SELECT count(*) FROM payment_link pl
                         WHERE pl.run_id = d.run_id
                           AND pl.settlement_id = d.settlement_id) AS members
                  FROM decomposition d
                  JOIN recon_run  r ON r.run_id = d.run_id
                  LEFT JOIN bank_txn   b ON b.bank_txn_id   = d.bank_txn_id
                                        AND b.merchant_id   = r.merchant_id
                  LEFT JOIN settlement s ON s.settlement_id  = d.settlement_id
                                        AND s.merchant_id    = r.merchant_id
                 WHERE d.run_id = ?
                 -- run_id is grouped as well: the member-count subquery
                 -- correlates on it, and Postgres will not accept an
                 -- ungrouped column there.
                 GROUP BY d.run_id, d.settlement_id, d.bank_txn_id
                 ORDER BY d.settlement_id
                """, runId).stream().map(RunRepository::unwrapJson).toList();
    }

    /**
     * The payments this run attributed to one settlement batch, with the order
     * each came from.
     *
     * This is the first link of the evidence chain, and it is a real join: the
     * membership comes from payment_link (what the netting solver decided), the
     * amounts from the payment rows themselves, and the order from order_row
     * where one was ingested. Scoped by merchant through recon_run, because the
     * source tables are keyed on (merchant_id, id) and a bare id join would fan
     * out across tenants that share an identifier.
     */
    public List<Map<String, Object>> paymentsFor(long runId, String settlementId) {
        return jdbc.queryForList("""
                SELECT pl.payment_id, pl.ambiguous, pl.bank_txn_id,
                       p.amount_paise, p.method, p.status, p.created_at_ist,
                       p.order_id, o.customer_id, o.amount_paise AS order_amount_paise
                  FROM payment_link pl
                  JOIN recon_run r ON r.run_id = pl.run_id
                  LEFT JOIN payment p   ON p.payment_id = pl.payment_id
                                       AND p.merchant_id = r.merchant_id
                  LEFT JOIN order_row o ON o.order_id = p.order_id
                                       AND o.merchant_id = r.merchant_id
                 WHERE pl.run_id = ? AND pl.settlement_id = ?
                 ORDER BY p.created_at_ist NULLS LAST, pl.payment_id
                """, runId, settlementId);
    }

    /**
     * Every payment this run flagged swap-invariant, with what makes it so.
     *
     * The tie is BETWEEN batches, not inside one: two payments of the same
     * amount and method, in different batches settling the same day, can be
     * exchanged without changing any batch total. So a per-batch query finds
     * only one half of each pair, and the UI needs the whole flagged set to
     * show an operator both sides of the thing the engine refused to guess.
     */
    public List<Map<String, Object>> ambiguousPayments(long runId) {
        return jdbc.queryForList("""
                SELECT pl.payment_id, pl.settlement_id,
                       p.amount_paise, p.method, p.created_at_ist,
                       s.settled_at::date AS settle_date
                  FROM payment_link pl
                  JOIN recon_run r  ON r.run_id = pl.run_id
                  LEFT JOIN payment p    ON p.payment_id    = pl.payment_id
                                        AND p.merchant_id   = r.merchant_id
                  LEFT JOIN settlement s ON s.settlement_id = pl.settlement_id
                                        AND s.merchant_id   = r.merchant_id
                 WHERE pl.run_id = ? AND pl.ambiguous
                 ORDER BY p.amount_paise, p.method, pl.payment_id
                """, runId);
    }

    public List<Map<String, Object>> exceptions(long runId) {
        return jdbc.queryForList("""
                SELECT settlement_id, bank_txn_id, exception_type, residue_paise,
                       batch_value_paise, confidence, ambiguous, value_date, verdict,
                       policy_reason, hypothesis, resolution, idempotency_key,
                       evidence, agent_steps,
                       llm_used, llm_model, llm_calls, agent_plan
                  FROM recon_exception WHERE run_id=? ORDER BY settlement_id
                """, runId).stream().map(RunRepository::unwrapJson).toList();
    }
}
