package com.settleiq.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * API-key authentication with per-tenant authorisation and rate limiting.
 *
 * Keys come from configuration only -- `SETTLEIQ_API_KEYS`, formatted as
 * `key:merchant,merchant|key2:*`. Nothing is compiled in, and a key is never
 * logged: the MDC and every log line carry a truncated SHA-256 of it, which is
 * enough to correlate requests without printing the credential.
 *
 * AUTHORISATION IS THE POINT, not authentication. A key is bound to the
 * merchants it may act for, so possessing a valid key is not sufficient to
 * reach another tenant's payments. This filter enforces that for every request
 * that names the merchant in the query string; TenantGuard enforces it for the
 * write paths that name it in the body, which a filter cannot read. Both are
 * required -- the query-string check alone returned 200 for a cross-tenant
 * POST /runs, because the parameter had simply moved.
 *
 * If no keys are configured the filter runs in OPEN mode: every request is
 * allowed, and the API advertises that plainly on /api/v1/auth/status so the UI
 * can show it. That is deliberate -- the offline demo has to work with no
 * setup -- but an unauthenticated deployment says so out loud rather than
 * looking secure.
 */
@Component
@Order(1)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    /** Unauthenticated by design: health, docs, the UI shell and the auth probe. */
    private static final List<String> PUBLIC = List.of(
            "/actuator/health", "/actuator/prometheus", "/docs", "/swagger-ui",
            "/v3/api-docs", "/api/v1/auth/status", "/index.html", "/app.js", "/app.css", "/");

    /** key -> merchants it may act for; "*" means every merchant. */
    private final Map<String, Set<String>> keys;
    private final int rateLimitPerMinute;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public ApiKeyAuthFilter() {
        this.keys = parse(System.getenv("SETTLEIQ_API_KEYS"));
        this.rateLimitPerMinute = intEnv("SETTLEIQ_RATE_LIMIT_PER_MIN", 600);
        if (keys.isEmpty())
            log.warn("SETTLEIQ_API_KEYS is not set: the API is running UNAUTHENTICATED. "
                   + "Set it before exposing this on a network.");
        else
            log.info("API-key auth enabled for {} key(s), rate limit {}/min",
                    keys.size(), rateLimitPerMinute);
    }

    static Map<String, Set<String>> parse(String spec) {
        Map<String, Set<String>> out = new ConcurrentHashMap<>();
        if (spec == null || spec.isBlank()) return out;
        for (String entry : spec.split("\\|")) {
            String[] kv = entry.split(":", 2);
            if (kv.length != 2 || kv[0].isBlank()) continue;
            Set<String> scope = new java.util.HashSet<>();
            for (String m : kv[1].split(",")) if (!m.isBlank()) scope.add(m.trim());
            if (!scope.isEmpty()) out.put(kv[0].trim(), scope);
        }
        return out;
    }

    public boolean enabled() { return !keys.isEmpty(); }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse rsp,
                                    FilterChain chain) throws ServletException, IOException {
        String path = req.getRequestURI();
        if (isPublic(path) || keys.isEmpty()) {
            chain.doFilter(req, rsp);
            return;
        }

        String key = req.getHeader("x-api-key");
        Set<String> scope = key == null ? null : keys.get(key);
        if (scope == null) {
            // Same response whether the key is absent or wrong: distinguishing
            // them turns the endpoint into a key-validity oracle.
            log.warn("auth_denied path={} reason=invalid_or_missing_key remote={}",
                    path, req.getRemoteAddr());
            deny(rsp, 401, "invalid or missing API key");
            return;
        }

        String fp = fingerprint(key);
        if (!allow(fp)) {
            log.warn("rate_limited key={} path={}", fp, path);
            rsp.setHeader("Retry-After", "60");
            deny(rsp, 429, "rate limit exceeded: " + rateLimitPerMinute + " requests/minute");
            return;
        }

        // Tenant authorisation for everything that names the merchant in the
        // QUERY STRING, which is every read. It is deliberately not the only
        // check: POST /runs and POST /jobs carry the merchant in the body,
        // where a filter cannot see it without consuming the stream, and a key
        // scoped to one tenant could otherwise act on another simply by moving
        // the parameter. TenantGuard closes that on the parsed value.
        String merchant = req.getParameter("merchantId");
        if (merchant != null && !scope.contains("*") && !scope.contains(merchant)) {
            log.warn("authz_denied key={} requested_merchant={} path={}", fp, merchant, path);
            deny(rsp, 403, "this key is not authorised for merchant " + merchant);
            return;
        }

        MDC.put("api_key", fp);
        if (merchant != null) MDC.put("merchant_id", merchant);
        // Published for the write paths, which take the merchant in the request
        // BODY and so cannot be covered by the query-parameter check above.
        TenantGuard.set(scope);
        try {
            chain.doFilter(req, rsp);
        } finally {
            TenantGuard.clear();
            MDC.remove("api_key");
            MDC.remove("merchant_id");
        }
    }

    private static boolean isPublic(String path) {
        for (String p : PUBLIC) {
            if (p.equals("/") ? path.equals("/") : path.startsWith(p)) return true;
        }
        return false;
    }

    private static void deny(HttpServletResponse rsp, int code, String detail) throws IOException {
        rsp.setStatus(code);
        rsp.setContentType("application/problem+json");
        rsp.getWriter().write("{\"status\":" + code + ",\"title\":\"" +
                (code == 401 ? "Unauthorized" : code == 403 ? "Forbidden" : "Too Many Requests")
                + "\",\"detail\":" + com.settleiq.Csv.q(detail) + "}");
    }

    /** Fixed-window counter. Enough to blunt a runaway client; not a DDoS defence. */
    private record Window(AtomicInteger count, long minute) {}

    private boolean allow(String fingerprint) {
        long minute = System.currentTimeMillis() / 60_000;
        Window w = windows.compute(fingerprint, (k, cur) ->
                cur == null || cur.minute() != minute
                        ? new Window(new AtomicInteger(0), minute) : cur);
        return w.count().incrementAndGet() <= rateLimitPerMinute;
    }

    /** Truncated SHA-256. Correlates requests without ever logging the key. */
    static String fingerprint(String key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static int intEnv(String name, int dflt) {
        try {
            String v = System.getenv(name);
            return v == null || v.isBlank() ? dflt : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) { return dflt; }
    }
}
