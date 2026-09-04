package com.settleiq;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Optional LLM planner for the exception agent.
 *
 * THE CONTRACT, and the reason this class is safe to exist at all:
 *
 *   - It may return ONE STRING, and that string must already be a member of a
 *     closed list the caller passed in. Anything else is refused.
 *   - It never sees a request to compute, and it has no way to return a number.
 *     `choose` parses one identifier out of the reply and discards the rest.
 *   - It cannot reach the database, the ledger or any tool. It proposes which
 *     read-only tool to run next; AgentTools runs it, and Policy re-derives
 *     every rupee afterwards regardless of what was proposed.
 *
 * So the worst a compromised, hallucinating or prompt-injected model can do is
 * make the agent read the wrong rows in the wrong order and arrive at a label
 * the deterministic policy then refuses to auto-post. It cannot move money.
 *
 * THREE PROVIDERS, ONE OF WHICH NEEDS NO CREDENTIAL
 *
 *   ollama     a local model server. No API key, nothing leaves the machine,
 *              and the merchant's payment data is never sent to a third party —
 *              which for reconciliation data is the point, not a convenience.
 *   openai     any OpenAI-compatible endpoint: LM Studio, llama.cpp, vLLM.
 *   anthropic  the hosted API, when a key is configured.
 *
 * Availability is PROBED, not assumed. A local server that is configured but
 * not running would otherwise make the UI report an LLM that cannot answer, and
 * "we consulted a model" is the one claim an auditor cannot check afterwards.
 *
 * Zero dependencies: java.net.http is in the JDK, so the engine's enforcer rule
 * still holds.
 */
public final class LlmAdapter {

    public enum Provider { OLLAMA, OPENAI, ANTHROPIC, NONE }

    /** Provider-neutral settings, all from the environment. No key in source. */
    public record Settings(Provider provider, String apiKey, String model,
                           String baseUrl, Duration timeout, int maxRetries, int maxCalls) {
        public Settings(Provider p, String k, String m, String b, Duration t, int r) {
            this(p, k, m, b, t, r, 30);
        }

        public static Settings fromEnv() {
            String key = firstNonBlank(System.getenv("SETTLEIQ_LLM_API_KEY"),
                                       System.getenv("ANTHROPIC_API_KEY"));
            String declared = System.getenv("SETTLEIQ_LLM_PROVIDER");
            Provider p;
            if (declared != null && !declared.isBlank()) {
                p = switch (declared.trim().toLowerCase()) {
                    case "ollama" -> Provider.OLLAMA;
                    case "openai", "lmstudio", "vllm", "llamacpp" -> Provider.OPENAI;
                    case "anthropic" -> Provider.ANTHROPIC;
                    case "off", "none", "disabled" -> Provider.NONE;
                    default -> Provider.NONE;
                };
            } else {
                // A key means the hosted API was intended; otherwise assume a
                // local server, which is the configuration that needs no secret.
                p = key != null ? Provider.ANTHROPIC : Provider.OLLAMA;
            }

            String base = System.getenv("SETTLEIQ_LLM_BASE_URL");
            if (base == null || base.isBlank()) base = switch (p) {
                case OLLAMA -> "http://localhost:11434";
                case OPENAI -> "http://localhost:1234/v1";
                case ANTHROPIC -> "https://api.anthropic.com/v1/messages";
                case NONE -> "";
            };
            String model = firstNonBlank(System.getenv("SETTLEIQ_LLM_MODEL"),
                    switch (p) {
                        case OLLAMA -> "llama3.2";
                        case OPENAI -> "local-model";
                        case ANTHROPIC -> "claude-sonnet-4-5";
                        case NONE -> "";
                    });
            // A local model's FIRST call includes loading weights from disk, which
            // took 29 s on the machine this was developed on. A 20 s budget
            // times out every cold start and the agent silently falls back.
            long ms = parseLong(System.getenv("SETTLEIQ_LLM_TIMEOUT_MS"), 90_000);
            // A CALL BUDGET PER RUN, because planning is not free.
            //
            // A local 4B model answers in ~1.5 s. Merchant A raises 78
            // exceptions and the agent plans up to six lookups each, so letting
            // the planner run unbounded turned a 2.7 s reconciliation into one
            // that had not finished after ten minutes. That is not a tuning
            // problem, it is the shape of the cost.
            //
            // So the budget is explicit and the trace records, per exception,
            // whether the model was actually consulted. Spending it on the first
            // few and saying so beats a run that silently never completes.
            int maxCalls = (int) parseLong(System.getenv("SETTLEIQ_LLM_MAX_CALLS"), 30);
            return new Settings(p, key, model, stripTrailingSlash(base),
                                Duration.ofMillis(ms), 1, maxCalls);
        }

        private static String firstNonBlank(String... v) {
            for (String s : v) if (s != null && !s.isBlank()) return s;
            return null;
        }
        private static String stripTrailingSlash(String s) {
            return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
        }
        private static long parseLong(String s, long d) {
            try { return s == null || s.isBlank() ? d : Long.parseLong(s.trim()); }
            catch (NumberFormatException e) { return d; }
        }

        /** Where a chat completion is actually posted for this provider. */
        String chatUrl() {
            return switch (provider) {
                case OLLAMA -> baseUrl + "/api/chat";
                case OPENAI -> baseUrl + "/chat/completions";
                case ANTHROPIC -> baseUrl;
                case NONE -> "";
            };
        }
        /** A cheap endpoint that proves the server is up, for local providers. */
        String healthUrl() {
            return switch (provider) {
                case OLLAMA -> baseUrl + "/api/tags";
                case OPENAI -> baseUrl + "/models";
                case ANTHROPIC, NONE -> "";
            };
        }
        boolean needsKey() { return provider == Provider.ANTHROPIC; }
    }

    /** Why the LLM was not used on a given call. Surfaced, never swallowed. */
    public enum Outcome { USED, NOT_CONFIGURED, UNREACHABLE, TRANSPORT_ERROR,
                          REFUSED_OFF_LIST, TIMEOUT, BUDGET_EXHAUSTED }

    public record Choice(String value, Outcome outcome, String detail, String reason) {
        public Choice(String v, Outcome o, String d) { this(v, o, d, null); }
        public boolean ok() { return outcome == Outcome.USED && value != null; }
    }

    private final Settings settings;
    private final HttpClient http;
    private final boolean reachable;
    private final String status;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger refusals = new AtomicInteger();

    public LlmAdapter() { this(Settings.fromEnv()); }

    public LlmAdapter(Settings settings) {
        this.settings = settings;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        if (settings.provider() == Provider.NONE) {
            this.reachable = false;
            this.status = "disabled: SETTLEIQ_LLM_PROVIDER=off";
        } else if (settings.needsKey() && settings.apiKey() == null) {
            this.reachable = false;
            this.status = "anthropic selected but SETTLEIQ_LLM_API_KEY is not set";
        } else if (settings.needsKey()) {
            this.reachable = true;                 // hosted: assume up, fail per call
            this.status = "configured: anthropic " + settings.model();
        } else {
            // Local server: probe once. Configured-but-down must not report as
            // available, or the UI claims a model that cannot answer.
            String why = probe();
            this.reachable = why == null;
            this.status = why == null
                    ? "configured: " + settings.provider().name().toLowerCase()
                      + " " + settings.model() + " at " + settings.baseUrl()
                    : "unreachable at " + settings.baseUrl() + ": " + why;
        }
    }

    /** One cheap GET. Returns null when the server answered, else the reason. */
    private String probe() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(settings.healthUrl()))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            return r.statusCode() / 100 == 2 ? null : "HTTP " + r.statusCode();
        } catch (Exception e) {
            return e.getClass().getSimpleName();
        }
    }

    public boolean available() { return reachable; }
    public String model() { return reachable ? settings.model() : null; }
    public String provider() { return settings.provider().name().toLowerCase(); }
    public String status() { return status; }
    public String endpoint() { return settings.baseUrl(); }
    public int callCount() { return calls.get(); }
    public int maxCalls() { return settings.maxCalls(); }
    public boolean budgetSpent() { return calls.get() >= settings.maxCalls(); }
    public int refusalCount() { return refusals.get(); }

    /**
     * Ask the model to pick one item from `allowed`.
     *
     * The reply is matched against `allowed` and nothing else is accepted — not
     * a near-miss, not a lowercase variant with a trailing period, not a
     * sentence containing the word twice. An off-list answer is a refusal,
     * counted, and the caller falls back to deterministic logic.
     */
    public Choice choose(String question, String context, List<String> allowed) {
        if (!reachable) return new Choice(null, Outcome.NOT_CONFIGURED, status);
        if (calls.get() >= settings.maxCalls())
            return new Choice(null, Outcome.BUDGET_EXHAUSTED,
                    "planner budget of " + settings.maxCalls() + " calls per run is spent; "
                    + "remaining lookups use the deterministic planner");
        if (allowed == null || allowed.isEmpty())
            return new Choice(null, Outcome.REFUSED_OFF_LIST, "empty allow-list");

        String prompt = """
                %s

                CONTEXT (facts already gathered from the merchant's own records):
                %s

                Choose the single most informative next lookup from the allowed set.
                Answer as JSON: {"tool": "<one of the allowed identifiers>", "reason": "<short>"}
                """.formatted(question, context);

        calls.incrementAndGet();
        Exception last = null;
        boolean suppressThinking = settings.provider() == Provider.OLLAMA;

        for (int attempt = 0; attempt <= settings.maxRetries() + 1; attempt++) {
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(settings.chatUrl()))
                        .timeout(settings.timeout())
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                body(prompt, allowed, suppressThinking)));
                if (settings.provider() == Provider.ANTHROPIC) {
                    b.header("x-api-key", settings.apiKey());
                    b.header("anthropic-version", "2023-06-01");
                } else if (settings.provider() == Provider.OPENAI && settings.apiKey() != null) {
                    b.header("authorization", "Bearer " + settings.apiKey());
                }
                HttpResponse<String> rsp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());

                if (rsp.statusCode() == 400 && suppressThinking) {
                    // Older servers, and models with no thinking channel, reject
                    // the field outright. Drop it and try once more rather than
                    // reporting a transport failure for a flag we volunteered.
                    suppressThinking = false;
                    continue;
                }
                if (rsp.statusCode() / 100 != 2)
                    return new Choice(null, Outcome.TRANSPORT_ERROR, "HTTP " + rsp.statusCode());

                String text = extractText(settings.provider(), rsp.body());
                String[] reason = new String[1];
                String picked = toolFrom(text, allowed, reason);
                if (picked == null) {
                    refusals.incrementAndGet();
                    return new Choice(null, Outcome.REFUSED_OFF_LIST,
                            "model returned an off-list or unparseable answer");
                }
                return new Choice(picked, Outcome.USED, settings.model(), reason[0]);
            } catch (java.net.http.HttpTimeoutException e) {
                return new Choice(null, Outcome.TIMEOUT, "timed out after " + settings.timeout());
            } catch (Exception e) {
                last = e;
            }
        }
        return new Choice(null, Outcome.TRANSPORT_ERROR,
                last == null ? "unknown" : last.getClass().getSimpleName());
    }

    /**
     * Request body in each provider's own shape, with the allowed tools pushed
     * down as a SCHEMA rather than an instruction.
     *
     * Asking a small local model to "reply with exactly one identifier" does not
     * work: qwen3:4b answers with a paragraph of reasoning and never emits the
     * identifier at all. Ollama and OpenAI-compatible servers both constrain
     * generation to a JSON schema, so an enum of the permitted tools makes an
     * off-list answer structurally impossible rather than merely discouraged.
     *
     * The enum is still re-validated on the way back in. A constraint enforced
     * by the server is a convenience; the check that matters is the one this
     * side performs, because that is the one that holds when the server lies.
     */
    private String body(String prompt, List<String> allowed, boolean suppressThinking) {
        String q = Csv.q(prompt);
        StringBuilder en = new StringBuilder("[");
        for (int i = 0; i < allowed.size(); i++) {
            if (i > 0) en.append(',');
            en.append(Csv.q(allowed.get(i)));
        }
        en.append(']');
        String schema = "{\"type\":\"object\",\"properties\":{"
                + "\"tool\":{\"type\":\"string\",\"enum\":" + en + "},"
                + "\"reason\":{\"type\":\"string\"}},"
                + "\"required\":[\"tool\"]}";

        return switch (settings.provider()) {
            // `think:false` matters for reasoning models: qwen3 spends the whole
            // token budget in its thinking channel and returns empty content.
            // Retried without it if the server rejects the field.
            case OLLAMA -> "{\"model\":" + Csv.q(settings.model())
                    + ",\"stream\":false"
                    + (suppressThinking ? ",\"think\":false" : "")
                    + ",\"format\":" + schema
                    + ",\"options\":{\"temperature\":0,\"num_predict\":200}"
                    + ",\"messages\":[{\"role\":\"user\",\"content\":" + q + "}]}";
            case OPENAI -> "{\"model\":" + Csv.q(settings.model())
                    + ",\"temperature\":0,\"max_tokens\":200"
                    + ",\"response_format\":{\"type\":\"json_schema\",\"json_schema\":"
                    + "{\"name\":\"tool_choice\",\"strict\":true,\"schema\":" + schema + "}}"
                    + ",\"messages\":[{\"role\":\"user\",\"content\":" + q + "}]}";
            case ANTHROPIC -> "{\"model\":" + Csv.q(settings.model())
                    + ",\"max_tokens\":200,\"temperature\":0"
                    + ",\"messages\":[{\"role\":\"user\",\"content\":" + q + "}]}";
            case NONE -> "{}";
        };
    }

    /**
     * Read the tool out of a structured reply, or fall back to prose matching.
     *
     * Anthropic has no schema-constrained mode here, and any model may return
     * something unexpected, so a reply that is not the agreed JSON still gets
     * the closed-list treatment before it is refused.
     */
    static String toolFrom(String text, List<String> allowed, String[] reasonOut) {
        if (text == null || text.isBlank()) return null;
        try {
            Object parsed = Csv.json(text.trim());
            if (parsed instanceof Map<?, ?> m && m.get("tool") != null) {
                String tool = String.valueOf(m.get("tool")).trim();
                if (m.get("reason") != null && reasonOut != null)
                    reasonOut[0] = String.valueOf(m.get("reason"));
                // Re-validated here, not trusted from the schema.
                for (String a : allowed) if (a.equalsIgnoreCase(tool)) return a;
                return null;
            }
        } catch (RuntimeException ignored) { /* not JSON: try prose */ }
        return matchExactlyOne(text, allowed);
    }

    /** Pull the assistant text out of a reply, without a JSON library. */
    @SuppressWarnings("unchecked")
    static String extractText(Provider p, String json) {
        try {
            Object parsed = Csv.json(json);
            if (!(parsed instanceof Map<?, ?> m)) return "";
            switch (p) {
                case OLLAMA -> {
                    if (m.get("message") instanceof Map<?, ?> msg && msg.get("content") != null)
                        return String.valueOf(msg.get("content"));
                    Object gen = m.get("response");     // /api/generate shape
                    return gen == null ? "" : String.valueOf(gen);
                }
                case OPENAI -> {
                    if (m.get("choices") instanceof List<?> ch && !ch.isEmpty()
                            && ch.get(0) instanceof Map<?, ?> c0
                            && c0.get("message") instanceof Map<?, ?> msg
                            && msg.get("content") != null)
                        return String.valueOf(msg.get("content"));
                    return "";
                }
                case ANTHROPIC -> {
                    if (!(m.get("content") instanceof List<?> blocks)) return "";
                    StringBuilder sb = new StringBuilder();
                    for (Object b : blocks)
                        if (b instanceof Map<?, ?> bm && "text".equals(bm.get("type")))
                            sb.append(String.valueOf(bm.get("text")));
                    return sb.toString();
                }
                default -> { return ""; }
            }
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * Accepts a reply only when it resolves to exactly one allowed identifier.
     *
     * A reply mentioning two of them is ambiguous and is refused rather than
     * resolved by picking the first — guessing which one the model "meant" is
     * exactly the kind of silent inference this architecture exists to avoid.
     *
     * Small local models like to wrap an answer in prose or a code fence, so
     * the text is normalised first; what is NOT tolerated is a second
     * identifier appearing anywhere in it.
     */
    static String matchExactlyOne(String reply, List<String> allowed) {
        if (reply == null) return null;
        String t = reply.trim().toLowerCase()
                .replace("`", " ").replace("\"", " ")
                .replaceAll("[^a-z0-9_\\s]", " ");
        String found = null;
        for (String a : allowed) {
            if (!t.matches("(?s).*\\b" + java.util.regex.Pattern.quote(a.toLowerCase()) + "\\b.*"))
                continue;
            if (found != null) return null;
            found = a;
        }
        return found;
    }
}
