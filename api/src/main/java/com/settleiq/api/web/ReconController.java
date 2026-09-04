package com.settleiq.api.web;

import com.settleiq.Money;
import com.settleiq.api.repo.PostgresAuditLedger;
import com.settleiq.api.repo.RunRepository;
import com.settleiq.api.repo.SourceRepository;
import com.settleiq.api.service.ReconciliationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST surface.
 *
 * Money leaves this API as BOTH an integer of paise and a formatted string.
 * Clients must compute on the integer; the string exists so a UI never has to
 * do currency arithmetic in JavaScript, where 0.1 + 0.2 is not 0.3.
 *
 * Query-parameter names are written out explicitly rather than inferred from
 * bytecode. Inference needs the -parameters compiler flag, which is easy to
 * lose in a build refactor -- and losing it breaks every endpoint at runtime
 * with an HTTP 400 while the build and the unit tests stay green.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Reconciliation")
public class ReconController {

    private final ReconciliationService service;
    private final RunRepository runs;
    private final SourceRepository sources;
    private final JdbcTemplate jdbc;
    private final com.settleiq.api.config.TenantGuard guard;

    public ReconController(ReconciliationService service, RunRepository runs,
                           SourceRepository sources, JdbcTemplate jdbc,
                           com.settleiq.api.config.TenantGuard guard) {
        this.service = service;
        this.runs = runs;
        this.sources = sources;
        this.jdbc = jdbc;
        this.guard = guard;
    }

    public record RunRequest(
            @NotBlank String merchantId,
            @Pattern(regexp = "deterministic|repair|scorer|global|netting|full")
            String preset) {}

    @Operation(summary = "Run a reconciliation synchronously")
    @PostMapping("/runs")
    public Map<String, Object> createRun(@RequestBody RunRequest req) {
        // The merchant arrives in the BODY here, which the auth filter cannot
        // read, so authorisation is enforced on the parsed value.
        guard.require(req.merchantId());
        String preset = req.preset() == null ? "full" : req.preset();
        var res = service.run(req.merchantId(), preset);
        var out = res.output();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run_id", res.runId());
        m.put("merchant_id", req.merchantId());
        m.put("preset", preset);
        m.put("wall_ms", res.wallMs());
        m.put("payment_links", out.paymentToSettlement.size());
        m.put("bank_matched", out.bankToSettlement.size());
        m.put("exceptions", out.exceptions.size());
        m.put("auto_posted", out.autoPosted);
        m.put("escalated", out.escalated);
        m.put("suppressed_duplicate", out.suppressed);
        // Rows the ledger actually WROTE. This, not the verdict counters, is
        // the idempotency signal: on a re-run the verdicts stay AUTO_POST /
        // ESCALATE (the items still need whatever they needed) while this
        // drops to zero.
        m.put("audit_appended", out.auditRows);
        m.put("residue_abs_paise", out.absResidue);
        m.put("residue_abs", Money.fmtInr(out.absResidue));
        m.put("audit_head", out.auditHead);
        return m;
    }

    @Operation(summary = "Queue a reconciliation for a worker to pick up")
    @PostMapping("/jobs")
    public Map<String, Object> enqueue(@RequestBody JobRequest req) {
        guard.require(req.merchantId());
        Long jobId = jdbc.queryForObject("""
                INSERT INTO recon_job (merchant_id, period_start, period_end, preset)
                VALUES (?,?,?,?)
                ON CONFLICT (merchant_id, period_start, period_end)
                  DO UPDATE SET state='queued', attempts=0, last_error=NULL
                RETURNING job_id
                """, Long.class, req.merchantId(),
                java.sql.Date.valueOf(req.periodStart()),
                java.sql.Date.valueOf(req.periodEnd()),
                req.preset() == null ? "full" : req.preset());
        return Map.of("job_id", jobId, "state", "queued");
    }

    public record JobRequest(@NotBlank String merchantId, LocalDate periodStart,
                             LocalDate periodEnd, String preset) {}

    @GetMapping("/merchants")
    public List<String> merchants() { return sources.merchantIds(); }

    @GetMapping("/runs")
    public List<Map<String, Object>> listRuns(@RequestParam("merchantId") String merchantId,
                                              @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return runs.runs(merchantId, Math.min(limit, 200));
    }

    @GetMapping("/runs/{runId}")
    public Map<String, Object> run(@PathVariable("runId") long runId) {
        var r = runs.run(runId);
        if (r == null) throw new NotFoundException("no such run: " + runId);
        return r;
    }

    @Operation(summary = "Bank credits with their full decomposition")
    @GetMapping("/credits")
    public List<Map<String, Object>> credits(@RequestParam("merchantId") String merchantId,
                                             @RequestParam(name = "runId", required = false) Long runId) {
        long rid = resolveRun(merchantId, runId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (var row : runs.credits(rid)) {
            // Already parsed by RunRepository.unwrapJson. Re-parsing
            // String.valueOf(map) would feed the JSON reader a Java map
            // toString -- "{gross=123, ...}" -- which is not JSON.
            @SuppressWarnings("unchecked")
            Map<String, Object> comps = (Map<String, Object>) row.get("components");
            if (comps == null) continue;
            long residue = num(comps.get("residue"));
            long total = num(comps.get("TOTAL_bank_credit"));
            List<Map<String, Object>> ordered = new ArrayList<>();
            for (String k : COMPONENT_ORDER) {
                if (!comps.containsKey(k)) continue;
                long v = num(comps.get(k));
                ordered.add(Map.of("name", k, "paise", v, "fmt", Money.fmtInr(v)));
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("settlement_id", row.get("settlement_id"));
            m.put("bank_txn_id", row.get("bank_txn_id"));
            m.put("value_date", String.valueOf(row.get("value_date")));
            m.put("narration", row.get("narration"));
            m.put("instant", row.get("instant"));
            m.put("members", row.get("members"));
            m.put("bank_amount", total);
            m.put("bank_amount_fmt", Money.fmtInr(total));
            m.put("residue", residue);
            m.put("residue_fmt", Money.fmtInr(residue));
            m.put("within_tolerance", Math.abs(residue) <= 100);
            m.put("components", ordered);
            out.add(m);
        }
        return out;
    }

    private static final List<String> COMPONENT_ORDER = List.of(
            "gross", "platform_fee", "gst_on_fee", "tds_194o", "refund_netted",
            "chargeback_debit", "dispute_fee", "dispute_fee_gst", "chargeback_reversal",
            "reserve_held", "reserve_released");

    private static long num(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        return (long) Double.parseDouble(String.valueOf(o));
    }

    /**
     * The payments behind one bank credit — the first link of the evidence chain.
     *
     * Money is returned as integer paise plus a formatted string, like every
     * other money-bearing endpoint here. `order_id` and `customer_id` are null
     * when no order file has been ingested for this merchant; that is reported
     * as absent rather than filled in, because a fabricated upstream reference
     * is exactly the kind of unverifiable claim the evidence chain exists to
     * make impossible.
     */
    @Operation(summary = "Payments this run attributed to one settlement batch")
    @GetMapping("/credits/{settlementId}/payments")
    public List<Map<String, Object>> creditPayments(
            @PathVariable("settlementId") String settlementId,
            @RequestParam("merchantId") String merchantId,
            @RequestParam(name = "runId", required = false) Long runId) {
        long rid = resolveRun(merchantId, runId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : runs.paymentsFor(rid, settlementId)) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            Object amt = r.get("amount_paise");
            if (amt instanceof Number n) m.put("amount_fmt", Money.fmtInr(n.longValue()));
            Object oamt = r.get("order_amount_paise");
            if (oamt instanceof Number n) m.put("order_amount_fmt", Money.fmtInr(n.longValue()));
            out.add(m);
        }
        return out;
    }

    @Operation(summary = "Payments this run refused to separate, with the tie key")
    @GetMapping("/ambiguous")
    public List<Map<String, Object>> ambiguous(@RequestParam("merchantId") String merchantId,
                                               @RequestParam(name = "runId", required = false) Long runId) {
        long rid = resolveRun(merchantId, runId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : runs.ambiguousPayments(rid)) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            Object amt = r.get("amount_paise");
            if (amt instanceof Number n) m.put("amount_fmt", Money.fmtInr(n.longValue()));
            out.add(m);
        }
        return out;
    }

    @GetMapping("/exceptions")
    public List<Map<String, Object>> exceptions(@RequestParam("merchantId") String merchantId,
                                                @RequestParam(name = "runId", required = false) Long runId) {
        long rid = resolveRun(merchantId, runId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : runs.exceptions(rid)) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            long res = ((Number) r.get("residue_paise")).longValue();
            m.put("residue_fmt", Money.fmtInr(res));
            m.put("batch_value_fmt",
                    Money.fmtInr(((Number) r.get("batch_value_paise")).longValue()));
            // already parsed by RunRepository.unwrapJson
            out.add(m);
        }
        return out;
    }

    /**
     * The ledger, as rows a controller can read.
     *
     * Verification already existed; listing did not, so the ledger could be
     * proven intact but never shown. Read-only and scoped by merchant: the
     * ledger is the one table where a cross-tenant read would expose another
     * merchant's decisions AND their amounts in a single query.
     *
     * The payload is deliberately not the whole row -- `prev_hash` and the
     * canonical fields are what verification consumes, not what an operator
     * reads, and shipping them invites a UI that reimplements verification in
     * JavaScript and disagrees with the server.
     */
    @Operation(summary = "Audit ledger entries for a merchant, newest first")
    @GetMapping("/audit/entries")
    public List<Map<String, Object>> auditEntries(
            @RequestParam("merchantId") String merchantId,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return jdbc.queryForList("""
                SELECT seq, ts, actor, action, entity_type, entity_id, verdict,
                       model_version, score, idempotency_key, hash,
                       -- ::text, not the raw jsonb: the driver otherwise hands back
                       -- a PGobject that serialises as {"type","value"}, and the
                       -- client would be left unwrapping a database detail.
                       payload::text AS payload
                  FROM audit_ledger WHERE merchant_id = ?
                 ORDER BY seq DESC LIMIT ?
                """, merchantId, Math.min(Math.max(limit, 1), 500));
    }

    @Operation(summary = "Walk the hash chain and prove nothing was altered")
    @GetMapping("/audit/verify")
    public ResponseEntity<Map<String, Object>> verify(@RequestParam("merchantId") String merchantId) {
        var breaks = PostgresAuditLedger.verify(jdbc, merchantId);
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM audit_ledger WHERE merchant_id=?", Integer.class, merchantId);
        var head = jdbc.queryForList(
                "SELECT hash FROM audit_ledger WHERE merchant_id=? ORDER BY seq DESC LIMIT 1",
                merchantId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("merchant_id", merchantId);
        m.put("rows", rows);
        m.put("valid", breaks.isEmpty());
        m.put("head", head.isEmpty() ? "0".repeat(64) : head.get(0).get("hash"));
        m.put("breaks", breaks);
        return breaks.isEmpty() ? ResponseEntity.ok(m)
                : ResponseEntity.status(409).body(m);
    }

    private long resolveRun(String merchantId, Long runId) {
        if (runId != null) return runId;
        Long latest = runs.latestSucceededRun(merchantId);
        if (latest == null)
            throw new NotFoundException("no successful run for merchant " + merchantId
                    + "; POST /api/v1/runs first");
        return latest;
    }
}
