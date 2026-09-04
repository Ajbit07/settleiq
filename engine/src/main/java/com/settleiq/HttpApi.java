package com.settleiq;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.settleiq.Model.*;

/**
 * Standalone REST surface, on the JDK's built-in HTTP server.
 *
 * Zero dependencies is a deliberate choice, not a shortcut: the whole system
 * has to run from `javac` and a JDK with no network access to a package
 * registry. See LIMITATIONS.md for what this trades away versus Spring Boot.
 *
 * The route table, the field names and the units are identical to the Spring
 * API's `/api/v1`, so one UI drives either backend. That is not cosmetic: the
 * two paths read the same data through different loaders (CSV here, JDBC
 * there), and serving them through one contract is what makes "the two data
 * paths agree to the paise" checkable by eye rather than by assertion.
 *
 * Money crosses the wire as an integer of paise plus a preformatted string.
 * Clients compute on the integer and never on the string.
 */
public final class HttpApi {

    /** One in-process run. The Spring path stores these in `recon_run`. */
    private record Run(long id, String preset, long wallMs, Pipeline.Output out) {}

    private final Inputs in;
    private final Config cfg;
    private final Scorer scorer;
    private final String preset;
    private final Path staticDir;
    private final List<Run> runs = new ArrayList<>();

    public HttpApi(Inputs in, Pipeline.Output out, Config cfg, Scorer scorer,
                   String preset, long wallMs, Path staticDir) {
        this.in = in;
        this.cfg = cfg;
        this.scorer = scorer;
        this.preset = preset;
        this.staticDir = staticDir.toAbsolutePath().normalize();
        this.runs.add(new Run(1, preset, wallMs, out));
    }

    public void start(int port) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(port), 0);
        s.createContext("/api/v1/merchants", e -> json(e, 200, merchants()));
        s.createContext("/api/v1/runs", this::runsRoute);
        s.createContext("/api/v1/credits", e -> json(e, 200, credits(resolveRun(e))));
        s.createContext("/api/v1/exceptions", e -> json(e, 200, exceptions(resolveRun(e))));
        s.createContext("/api/v1/metrics", e -> json(e, 200, metrics()));
        s.createContext("/api/v1/audit/verify", e -> {
            var v = Audit.verify(Path.of(cfg.auditPath));
            json(e, v.valid() ? 200 : 409,
                    "{\"merchant_id\":" + Csv.q(merchantId())
                    + ",\"rows\":" + v.rows()
                    + ",\"valid\":" + v.valid()
                    + ",\"head\":" + Csv.q(v.head())
                    + ",\"breaks\":" + (v.valid() ? "[]"
                        : "[{\"seq\":" + Csv.q(v.failureAt()) + "}]") + "}");
        });
        s.createContext("/", this::serveStatic);
        s.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        s.start();
        System.out.println("SettleIQ UI  ->  http://localhost:" + port);
    }

    private String merchantId() { return in.rateCard.merchantId(); }

    // ------------------------------------------------------------- endpoints
    private String merchants() { return "[" + Csv.q(merchantId()) + "]"; }

    private void runsRoute(HttpExchange e) throws IOException {
        if ("POST".equals(e.getRequestMethod())) {
            String created = createRun(e);
            if (created != null) json(e, 200, created);
            return;
        }
        StringBuilder sb = new StringBuilder("[");
        List<Run> snapshot = snapshot();
        for (int i = snapshot.size() - 1, n = 0; i >= 0; i--, n++) {
            if (n > 0) sb.append(',');
            sb.append(runJson(snapshot.get(i)));
        }
        json(e, 200, sb.append(']').toString());
    }

    /**
     * Re-run the pipeline in process.
     *
     * The inputs are already loaded, so this exercises exactly what the button
     * claims to: the pipeline, the policy engine and the audit ledger. A second
     * press appends zero ledger rows -- the idempotency keys are computed from
     * the facts, not from the request -- which is the property worth showing.
     */
    private String createRun(HttpExchange e) throws IOException {
        String body = new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String want = jsonString(body, "preset");
        if (want != null && !want.isEmpty() && !want.equals(preset)) {
            // The standalone server holds one Config, chosen by the --preset it
            // was launched with. Honouring the label without switching the
            // config would report a run that did not happen.
            json(e, 400, "{\"detail\":" + Csv.q("this server was started with --preset "
                    + preset + "; restart it to run a different preset") + "}");
            return null;
        }

        long t0 = System.currentTimeMillis();
        Pipeline.Output out = new Pipeline(cfg, in, scorer).run();
        long wall = System.currentTimeMillis() - t0;

        Run r;
        synchronized (runs) {
            r = new Run(runs.size() + 1, preset, wall, out);
            runs.add(r);
        }
        return "{\"run_id\":" + r.id()
             + ",\"merchant_id\":" + Csv.q(merchantId())
             + ",\"preset\":" + Csv.q(r.preset())
             + ",\"wall_ms\":" + r.wallMs()
             + ",\"payment_links\":" + out.paymentToSettlement.size()
             + ",\"bank_matched\":" + out.bankToSettlement.size()
             + ",\"exceptions\":" + out.exceptions.size()
             + ",\"auto_posted\":" + out.autoPosted
             + ",\"escalated\":" + out.escalated
             + ",\"suppressed_duplicate\":" + out.suppressed
             + ",\"audit_appended\":" + out.auditRows
             + ",\"residue_abs_paise\":" + out.absResidue
             + ",\"residue_abs\":" + Csv.q(Money.fmtInr(out.absResidue))
             + ",\"audit_head\":" + Csv.q(out.auditHead) + "}";
    }

    private String runJson(Run r) {
        var out = r.out();
        return "{\"run_id\":" + r.id()
             + ",\"merchant_id\":" + Csv.q(merchantId())
             + ",\"preset\":" + Csv.q(r.preset())
             + ",\"state\":\"succeeded\""
             + ",\"model_version\":" + Csv.q(out.modelVersion)
             + ",\"wall_ms\":" + r.wallMs()
             + ",\"stats\":{\"payment_links\":" + out.paymentToSettlement.size()
             + ",\"bank_matched\":" + out.bankToSettlement.size()
             + ",\"exceptions\":" + out.exceptions.size()
             + ",\"auto_posted\":" + out.autoPosted
             + ",\"escalated\":" + out.escalated
             + ",\"residue_abs_paise\":" + out.absResidue + "}}";
    }

    /** Components in the order the money actually moves, as the API emits them. */
    private static final List<String> COMPONENT_ORDER = List.of(
            "gross", "platform_fee", "gst_on_fee", "tds_194o", "refund_netted",
            "chargeback_debit", "dispute_fee", "dispute_fee_gst", "chargeback_reversal",
            "reserve_held", "reserve_released");

    private String credits(Run run) {
        Map<String, BankTxn> bankById = new LinkedHashMap<>();
        for (BankTxn b : in.bank) bankById.put(b.bankTxnId(), b);
        Map<String, Settlement> setl = new LinkedHashMap<>();
        for (Settlement s : in.settlements) setl.put(s.settlementId(), s);

        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Decomposition d : run.out().decompositions) {
            if (!first) sb.append(',');
            first = false;
            BankTxn b = d.bankTxnId == null ? null : bankById.get(d.bankTxnId);
            Settlement s = setl.get(d.settlementId);
            sb.append("{\"settlement_id\":").append(Csv.q(d.settlementId))
              .append(",\"bank_txn_id\":").append(Csv.q(d.bankTxnId))
              .append(",\"value_date\":").append(Csv.q(b == null ? "" : b.valueDate().toString()))
              .append(",\"narration\":").append(Csv.q(b == null ? "" : b.narration()))
              .append(",\"instant\":").append(s != null && s.instant())
              .append(",\"members\":").append(d.memberPaymentIds.size())
              .append(",\"bank_amount\":").append(d.bankAmount)
              .append(",\"bank_amount_fmt\":").append(Csv.q(Money.fmtInr(d.bankAmount)))
              .append(",\"residue\":").append(d.residue)
              .append(",\"residue_fmt\":").append(Csv.q(Money.fmtInr(d.residue)))
              .append(",\"within_tolerance\":").append(d.withinTolerance)
              .append(",\"components\":[");
            boolean f2 = true;
            for (String k : COMPONENT_ORDER) {
                Long v = d.components.get(k);
                if (v == null) continue;
                if (!f2) sb.append(',');
                f2 = false;
                sb.append("{\"name\":").append(Csv.q(k))
                  .append(",\"paise\":").append(v)
                  .append(",\"fmt\":").append(Csv.q(Money.fmtInr(v))).append('}');
            }
            sb.append("]}");
        }
        return sb.append(']').toString();
    }

    private String exceptions(Run run) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (var x : run.out().exceptions) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"settlement_id\":").append(Csv.q(x.settlementId))
              .append(",\"bank_txn_id\":").append(Csv.q(x.bankTxnId))
              .append(",\"exception_type\":").append(Csv.q(x.type))
              .append(",\"residue_paise\":").append(x.residue)
              .append(",\"residue_fmt\":").append(Csv.q(Money.fmtInr(x.residue)))
              .append(",\"batch_value_paise\":").append(x.batchValue)
              .append(",\"batch_value_fmt\":").append(Csv.q(Money.fmtInr(x.batchValue)))
              .append(",\"confidence\":").append(String.format("%.4f", x.confidence))
              .append(",\"ambiguous\":").append(x.ambiguous)
              .append(",\"value_date\":").append(Csv.q(x.valueDate == null ? "" : x.valueDate.toString()))
              .append(",\"verdict\":").append(Csv.q(x.verdict))
              .append(",\"policy_reason\":").append(Csv.q(x.policyReason))
              .append(",\"hypothesis\":").append(Csv.q(x.hypothesis))
              .append(",\"idempotency_key\":").append(Csv.q(x.idempotencyKey));
            if (x.trace != null) {
                sb.append(",\"resolution\":").append(Csv.q(x.trace.resolution()))
                  .append(",\"agent_steps\":").append(Csv.q(String.join(">", x.trace.steps())))
                  .append(",\"evidence\":[");
                var list = x.trace.evidence();
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append("{\"claim\":").append(Csv.q(list.get(i).claim()))
                      .append(",\"provenance_type\":").append(Csv.q(list.get(i).provenanceType()))
                      .append(",\"provenance_id\":").append(Csv.q(list.get(i).provenanceId()))
                      .append('}');
                }
                sb.append(']');
            } else {
                sb.append(",\"resolution\":null,\"agent_steps\":null,\"evidence\":[]");
            }
            sb.append('}');
        }
        return sb.append(']').toString();
    }

    /**
     * Evaluation artifacts, read-only.
     *
     * These are produced offline by `make evaluate` against the hidden ground
     * truth, which the running service deliberately cannot read. A missing file
     * serialises as null so the UI drops those panels rather than erroring.
     */
    private String metrics() {
        Path dir = Path.of("reports/metrics");
        StringBuilder sb = new StringBuilder("{\"available\":")
                .append(Files.isDirectory(dir));
        for (String f : List.of("ablation", "calibration", "coverage_risk", "test_report", "full")) {
            sb.append(",\"").append(f).append("\":");
            Path p = dir.resolve(f + ".json");
            try {
                sb.append(Files.isRegularFile(p)
                        ? Files.readString(p, StandardCharsets.UTF_8) : "null");
            } catch (IOException e) {
                sb.append("null");
            }
        }
        return sb.append('}').toString();
    }

    // ---------------------------------------------------------------- plumbing
    private List<Run> snapshot() {
        synchronized (runs) { return List.copyOf(runs); }
    }

    /** `runId` selects a run; absent means the newest, as in the Spring API. */
    private Run resolveRun(HttpExchange e) {
        String id = query(e).get("runId");
        List<Run> snapshot = snapshot();
        if (id != null && !id.isEmpty()) {
            for (Run r : snapshot) if (String.valueOf(r.id()).equals(id)) return r;
        }
        return snapshot.get(snapshot.size() - 1);
    }

    private static Map<String, String> query(HttpExchange e) {
        Map<String, String> m = new LinkedHashMap<>();
        String q = e.getRequestURI().getRawQuery();
        if (q == null) return m;
        for (String part : q.split("&")) {
            int i = part.indexOf('=');
            if (i < 0) continue;
            m.put(URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8),
                  URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    /** Reads one string field out of a flat request body. Enough for `{"preset":"full"}`. */
    private static String jsonString(String body, String key) {
        Object parsed;
        try { parsed = Csv.json(body); } catch (RuntimeException ex) { return null; }
        if (!(parsed instanceof Map<?, ?> m)) return null;
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private void json(HttpExchange e, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        e.getResponseHeaders().add("Cache-Control", "no-store");
        e.sendResponseHeaders(status, b.length);
        try (OutputStream os = e.getResponseBody()) { os.write(b); }
    }

    private void serveStatic(HttpExchange e) throws IOException {
        String p = e.getRequestURI().getPath();
        if (p.equals("/") || p.isEmpty()) p = "/index.html";
        Path f = staticDir.resolve(p.substring(1)).normalize();
        if (!f.startsWith(staticDir) || !Files.isRegularFile(f)) {
            byte[] b = "not found".getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(404, b.length);
            try (OutputStream os = e.getResponseBody()) { os.write(b); }
            return;
        }
        String ct = p.endsWith(".html") ? "text/html; charset=utf-8"
                : p.endsWith(".js") ? "text/javascript; charset=utf-8"
                : p.endsWith(".css") ? "text/css; charset=utf-8" : "text/plain";
        byte[] b = Files.readAllBytes(f);
        e.getResponseHeaders().add("Content-Type", ct);
        e.sendResponseHeaders(200, b.length);
        try (OutputStream os = e.getResponseBody()) { os.write(b); }
    }
}
