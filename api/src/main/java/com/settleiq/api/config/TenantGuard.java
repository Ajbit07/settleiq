package com.settleiq.api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Tenant authorisation at the point the merchant is actually resolved.
 *
 * THE HOLE THIS CLOSES
 *
 * ApiKeyAuthFilter checks `?merchantId=`, which covers every GET. But
 * `POST /api/v1/runs` and `POST /api/v1/jobs` take the merchant in the JSON
 * BODY, and a servlet filter cannot read the body without consuming the stream.
 * So a key scoped to merchant A could start a reconciliation on merchant B just
 * by moving the parameter -- authenticated, authorised for something else, and
 * returning 200.
 *
 * Found by trying it rather than by reading the filter.
 *
 * The fix cannot be "remember to check in each controller", because the one
 * that forgets is the one that leaks. So the scope for the current request is
 * published here by the filter, and every write path calls `require` with the
 * merchant it has actually decided to act on -- after parsing, not before.
 */
@Component
public class TenantGuard {

    private static final Logger log = LoggerFactory.getLogger(TenantGuard.class);

    /** Null means auth is disabled for this deployment; "*" means unrestricted. */
    private static final ThreadLocal<Set<String>> SCOPE = new ThreadLocal<>();

    static void set(Set<String> scope) { SCOPE.set(scope); }
    static void clear() { SCOPE.remove(); }

    /** Thrown when a caller addresses a merchant its key does not cover. */
    public static class Forbidden extends RuntimeException {
        public Forbidden(String m) { super(m); }
    }

    /**
     * Refuses unless the caller may act for this merchant.
     *
     * Call it with the merchant id the request will actually use, once that is
     * known. Calling it with the raw request parameter defeats the purpose.
     */
    public void require(String merchantId) {
        Set<String> scope = SCOPE.get();
        if (scope == null) return;                       // auth disabled: open mode
        if (scope.contains("*") || scope.contains(merchantId)) return;
        log.warn("authz_denied_body requested_merchant={}", merchantId);
        throw new Forbidden("this key is not authorised for merchant " + merchantId);
    }
}
