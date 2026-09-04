package com.settleiq.api.web;

import com.settleiq.LlmAdapter;
import com.settleiq.api.config.ApiKeyAuthFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What this deployment actually has switched on.
 *
 * Both flags are reported honestly, including when the answer is "no". A UI
 * that cannot tell whether it is talking to an authenticated API, or whether an
 * LLM was really consulted, will eventually imply the flattering answer -- so
 * the backend states it and the UI only renders what it is told.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Deployment status")
public class AuthStatusController {

    private final ApiKeyAuthFilter auth;

    public AuthStatusController(ApiKeyAuthFilter auth) { this.auth = auth; }

    @Operation(summary = "Whether auth and the LLM planner are configured on this deployment")
    @GetMapping("/status")
    public Map<String, Object> status() {
        var llm = new LlmAdapter();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("auth_enabled", auth.enabled());
        m.put("auth_mode", auth.enabled() ? "api-key" : "open");
        m.put("auth_note", auth.enabled()
                ? "requests must carry x-api-key; keys are scoped to merchants"
                : "SETTLEIQ_API_KEYS is not set: every endpoint is open");
        m.put("llm_available", llm.available());
        m.put("llm_provider", llm.provider());
        m.put("llm_model", llm.model());
        m.put("llm_endpoint", llm.endpoint());
        // The adapter PROBES a local server rather than assuming it is up, so
        // this string distinguishes "not configured" from "configured but down".
        m.put("llm_status", llm.status());
        m.put("llm_note", llm.available()
                ? "the agent's planner may propose which read-only lookup to run next; "
                  + "it can neither compute an amount nor write to the ledger"
                : "the agent is using its deterministic planner; every trace records llm_used=false");
        return m;
    }
}
