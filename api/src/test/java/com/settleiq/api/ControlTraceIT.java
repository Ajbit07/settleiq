package com.settleiq.api;

import com.settleiq.api.config.TenantGuard;
import com.settleiq.api.web.NotFoundException;
import com.settleiq.api.web.TraceController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The control trace must describe what happened, and nothing else.
 *
 * It is a screen an evaluator will read INSTEAD of the backend, which makes a
 * plausible-but-wrong event worse than a missing one. So these tests build a
 * run whose facts are known exactly, then assert the trace says those facts:
 * that an LLM event appears only when a model genuinely answered, that the
 * deterministic planner is never dressed up as one, that evidence ids survive,
 * that a refusal reads as a refusal, and that no part of it crosses a tenant.
 *
 * Rows are inserted directly rather than by running the engine. The engine's
 * behaviour is covered elsewhere; what is under test here is the projection
 * from persisted state to events, and that needs inputs chosen to be
 * unambiguous rather than realistic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ControlTraceIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) { PostgresIT.configure(r); }

    @Autowired JdbcTemplate jdbc;
    @Autowired TraceController trace;
    @Autowired TenantGuard guard;

    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);

    private String merchant(String base) {
        String id = base + "_" + RUN;
        jdbc.update("INSERT INTO merchant (merchant_id, name, rate_card) "
                + "VALUES (?,?,'{}'::jsonb) ON CONFLICT DO NOTHING", id, "Test " + id);
        return id;
    }

    /** A succeeded run with known stats. Returns the run id. */
    private long run(String merchantId) {
        return jdbc.queryForObject("""
                INSERT INTO recon_run (merchant_id, preset, model_version, state,
                                       started_at, finished_at, wall_ms, stats)
                VALUES (?, 'full', 'model-test-1', 'succeeded', now(), now(), 1234, ?::jsonb)
                RETURNING run_id
                """, Long.class, merchantId, """
                {"payments":100,"settlements":4,"bank_rows":9,"bank_matched":4,
                 "exact_matches":3,"candidate_pairs":7,"payment_links":97,
                 "unassigned_payments":0,"exceptions":2,"auto_posted":1,"escalated":1,
                 "residue_abs_paise":5140,
                 "timings":{"stage0_normalise":10,"stage1_4_match":20,
                            "stage5_netting":30,"stage6_decompose":40}}""");
    }

    /** One exception, with an agent plan attributed to whoever `chosenBy` says. */
    private void exception(long runId, String settlementId, String type, String verdict,
                           boolean ambiguous, boolean llmUsed, String llmModel,
                           String chosenBy, String tool, String evidenceId) {
        jdbc.update("""
                INSERT INTO recon_exception
                  (run_id, settlement_id, bank_txn_id, exception_type, residue_paise,
                   batch_value_paise, confidence, ambiguous, value_date, verdict,
                   policy_reason, hypothesis, resolution, idempotency_key, evidence,
                   agent_steps, llm_used, llm_model, llm_calls, agent_plan)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?::jsonb)
                """, runId, settlementId, "bnk_test_1", type, -5140L, 1_212_551L, 0.9,
                ambiguous, java.sql.Date.valueOf("2025-11-17"), verdict,
                verdict.equals("AUTO_POST") ? "all six arms passed"
                        : "held for human review: unique match cannot be proven",
                "hypothesis text", "escalate", "idem_" + settlementId,
                "[{\"claim\":\"a fact\",\"provenance_type\":\"settlement\",\"provenance_id\":\""
                        + evidenceId + "\"}]",
                tool + ">classify>policy_check", llmUsed, llmModel, llmUsed ? 1 : 0,
                "[{\"n\":1,\"tool\":\"" + tool + "\",\"chosen_by\":\"" + chosenBy
                        + "\",\"reason\":\"stated reason\",\"facts\":1,\"ms\":7}]");
    }

    private static List<Map<String, Object>> events(Map<String, Object> t) {
        @SuppressWarnings("unchecked")
        var e = (List<Map<String, Object>>) t.get("events");
        return e;
    }

    private static List<String> stages(Map<String, Object> t) {
        return events(t).stream().map(e -> String.valueOf(e.get("stage"))).toList();
    }

    /* ── 1. events come from real backend stages ─────────────────────────── */

    @Test
    void everyPipelineStageIsProjectedFromPersistedRunState() {
        String m = merchant("trace_stages");
        long id = run(m);
        var t = trace.trace(m, id, null);
        assertTrue(stages(t).containsAll(List.of("MATCH", "SCORING", "NETTING", "EXCEPTION")),
                "pipeline stages must be present: " + stages(t));
        var match = events(t).stream().filter(e -> "MATCH".equals(e.get("stage"))).findFirst().orElseThrow();
        assertTrue(String.valueOf(match.get("title")).contains("4 / 4"),
                "the figure must come from the run's own stats: " + match.get("title"));
    }

    /* ── 2 & 3. LLM only when a model actually answered ──────────────────── */

    @Test
    void anLlmEventAppearsOnlyWhenAModelActuallyAnswered() {
        String m = merchant("trace_llm");
        long id = run(m);
        exception(id, "setl_llm", "fee_variance", "ESCALATE", false,
                true, "phi4-mini:latest", "llm:phi4-mini:latest", "get_settlement", "setl_llm");
        var t = trace.trace(m, id, "setl_llm");
        var llm = events(t).stream().filter(e -> "LLM".equals(e.get("stage"))).toList();
        assertEquals(1, llm.size(), "one LLM event for one model-chosen step");
        assertTrue(String.valueOf(llm.get(0).get("title")).contains("phi4-mini"),
                "the event must name the model that answered");
        assertEquals("get_settlement", llm.get(0).get("tool"));
    }

    @Test
    void theDeterministicPlannerIsNeverLabelledAsAnLlm() {
        String m = merchant("trace_det");
        long id = run(m);
        exception(id, "setl_det", "fee_variance", "ESCALATE", false,
                false, null, "deterministic", "get_settlement", "setl_det");
        var t = trace.trace(m, id, "setl_det");
        assertFalse(stages(t).contains("LLM"),
                "a deterministic run must produce no LLM event: " + stages(t));
        var inv = events(t).stream().filter(e -> "INVESTIGATION".equals(e.get("stage")))
                .findFirst().orElseThrow();
        assertEquals("deterministic", inv.get("planner"));
        assertEquals(false, inv.get("llm_used"));
    }

    /* ── 4. an unreachable model is reported as not used ─────────────────── */

    @Test
    void aModelThatWasConfiguredButNeverAnsweredIsNotShownAsUsed() {
        // llm_used=false is exactly what the adapter persists when the server was
        // unreachable. The trace must treat that as "deterministic", never as a
        // consultation that happened.
        String m = merchant("trace_unreach");
        long id = run(m);
        exception(id, "setl_un", "unexplained", "ESCALATE", false,
                false, "phi4-mini:latest", "deterministic", "get_settlement", "setl_un");
        var t = trace.trace(m, id, "setl_un");
        assertFalse(stages(t).contains("LLM"));
        var inv = events(t).stream().filter(e -> "INVESTIGATION".equals(e.get("stage")))
                .findFirst().orElseThrow();
        assertEquals(false, inv.get("llm_used"),
                "a model name on the row is not evidence the model answered");
    }

    /* ── 5, 6. tool selection and evidence ids survive ───────────────────── */

    @Test
    void toolSelectionAndEvidenceIdsArePreserved() {
        String m = merchant("trace_tool");
        long id = run(m);
        exception(id, "setl_tool", "fee_variance", "ESCALATE", false,
                true, "phi4-mini:latest", "llm:phi4-mini:latest", "check_rate_card", "rate_card_9");
        var t = trace.trace(m, id, "setl_tool");
        var tool = events(t).stream().filter(e -> "TOOL".equals(e.get("stage"))).findFirst().orElseThrow();
        assertTrue(String.valueOf(tool.get("title")).startsWith("check_rate_card"), tool.toString());
        var ev = events(t).stream().filter(e -> "EVIDENCE".equals(e.get("stage"))).findFirst().orElseThrow();
        assertEquals("rate_card_9", ev.get("source_id"),
                "the provenance id must survive into the trace unchanged");
    }

    /* ── 7, 8. policy and refusal read correctly ─────────────────────────── */

    @Test
    void aRefusalReadsAsARefusalAndNothingIsPosted() {
        String m = merchant("trace_refuse");
        long id = run(m);
        exception(id, "setl_amb", "ambiguous_candidates", "ESCALATE", true,
                false, null, "deterministic", "check_swap_invariance", "setl_amb");
        var t = trace.trace(m, id, "setl_amb");
        var policy = events(t).stream().filter(e -> "POLICY".equals(e.get("stage"))).toList();
        assertEquals(2, policy.size(), "an ambiguous case gets an ambiguity event and a verdict");
        assertTrue(String.valueOf(policy.get(0).get("title")).contains("ambiguity"));
        assertTrue(String.valueOf(policy.get(1).get("title")).contains("blocked"));
        var ledger = events(t).stream().filter(e -> "LEDGER".equals(e.get("stage")))
                .findFirst().orElseThrow();
        assertTrue(String.valueOf(ledger.get("title")).contains("nothing posted"),
                "a refusal must not report a posting");
    }

    @Test
    void anAutoPostReadsAsAPosting() {
        String m = merchant("trace_post");
        long id = run(m);
        exception(id, "setl_ok", "fee_variance", "AUTO_POST", false,
                false, null, "deterministic", "check_rate_card", "setl_ok");
        var t = trace.trace(m, id, "setl_ok");
        var ledger = events(t).stream().filter(e -> "LEDGER".equals(e.get("stage")))
                .findFirst().orElseThrow();
        assertTrue(String.valueOf(ledger.get("title")).contains("recorded"), ledger.toString());
    }

    /* ── 9, 10. tenancy ──────────────────────────────────────────────────── */

    @Test
    void aRunFromAnotherMerchantIsNotFoundRatherThanFiltered() {
        String a = merchant("trace_ta");
        String b = merchant("trace_tb");
        long idA = run(a);
        assertThrows(NotFoundException.class, () -> trace.trace(b, idA, null),
                "addressing another tenant's run by id must not resolve");
    }

    @Test
    void anotherMerchantsSettlementYieldsNoInvestigationEvents() {
        String a = merchant("trace_xa");
        String b = merchant("trace_xb");
        long idA = run(a), idB = run(b);
        exception(idA, "setl_secret", "fee_variance", "AUTO_POST", false,
                true, "phi4-mini:latest", "llm:phi4-mini:latest", "get_settlement", "setl_secret");

        var t = trace.trace(b, idB, "setl_secret");
        assertFalse(stages(t).contains("INVESTIGATION"),
                "another tenant's exception must not surface: " + stages(t));
        assertFalse(stages(t).contains("EVIDENCE"));
        assertNull(t.get("settlement_id"),
                "an unresolved id must not be echoed back as if it belonged here");
        assertFalse(t.toString().contains("setl_secret"),
                "no trace of the other tenant's identifiers anywhere in the payload");
    }

    /* ── 11. no secrets ──────────────────────────────────────────────────── */

    @Test
    void theTraceCarriesNoCredentialsOrInternals() {
        String m = merchant("trace_clean");
        long id = run(m);
        exception(id, "setl_clean", "fee_variance", "ESCALATE", false,
                true, "phi4-mini:latest", "llm:phi4-mini:latest", "get_settlement", "setl_clean");
        String dump = trace.trace(m, id, "setl_clean").toString().toLowerCase();
        for (String forbidden : List.of("api_key", "apikey", "authorization", "bearer",
                                        "password", "select ", "insert ", "jdbc:",
                                        "org.springframework", "com.settleiq", "stacktrace"))
            assertFalse(dump.contains(forbidden),
                    "the trace must not expose '" + forbidden + "': it is a control view, not a log");
    }

    /* ── 12. audit event matches a real ledger row ───────────────────────── */

    @Test
    void theAuditEventCorrespondsToAnActualLedgerRow() {
        String m = merchant("trace_audit");
        long id = run(m);
        var led = new com.settleiq.api.repo.PostgresAuditLedger(jdbc, m, id);
        led.append("engine", "auto_post", "settlement", "setl_aud", "idem_aud_" + m,
                "model-test-1", 0.99, "AUTO_POST", Map.of("amount_paise", "1000"));
        var t = trace.trace(m, id, null);
        var audit = events(t).stream().filter(e -> "AUDIT".equals(e.get("stage")))
                .findFirst().orElseThrow();
        assertNotNull(audit.get("at"), "the audit event must carry the ledger row's own timestamp");
        assertTrue(String.valueOf(audit.get("title")).contains("1"),
                "the count must come from the ledger: " + audit.get("title"));
    }

    /* ── 13, 14. ordering and idempotence ────────────────────────────────── */

    @Test
    void eventsAreOrderedFromIngestThroughToAudit() {
        String m = merchant("trace_order");
        long id = run(m);
        exception(id, "setl_ord", "fee_variance", "ESCALATE", false,
                true, "phi4-mini:latest", "llm:phi4-mini:latest", "get_settlement", "setl_ord");
        var st = stages(trace.trace(m, id, "setl_ord"));
        int match = st.indexOf("MATCH"), exc = st.indexOf("EXCEPTION"),
            llm = st.indexOf("LLM"), tool = st.indexOf("TOOL"), ev = st.indexOf("EVIDENCE"),
            arith = st.indexOf("ARITHMETIC"), pol = st.indexOf("POLICY");
        assertTrue(match < exc, "matching precedes the exception it produced");
        assertTrue(exc < llm, "the exception precedes its investigation");
        assertTrue(llm < tool, "the model chooses before the tool runs");
        assertTrue(tool < ev, "the tool runs before it returns evidence");
        assertTrue(ev < arith, "evidence precedes the arithmetic that uses it");
        assertTrue(arith < pol, "arithmetic precedes the policy decision");
    }

    @Test
    void repeatedCallsProduceTheSameTrace() {
        // The trace is a projection, so asking twice must not accumulate or
        // reorder anything. A duplicated event would be a fabricated event.
        String m = merchant("trace_idem");
        long id = run(m);
        exception(id, "setl_idem", "fee_variance", "ESCALATE", false,
                true, "phi4-mini:latest", "llm:phi4-mini:latest", "get_settlement", "setl_idem");
        var first = stages(trace.trace(m, id, "setl_idem"));
        var second = stages(trace.trace(m, id, "setl_idem"));
        assertEquals(first, second, "the projection must be stable across calls");
    }
}
