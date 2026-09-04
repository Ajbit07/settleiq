package com.settleiq;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The LLM planner's safety envelope, tested without a model.
 *
 * Two things are worth pinning here and neither needs a live server.
 *
 * PARSING. Each provider returns the assistant's text in a different shape, and
 * a parser that silently returns "" on an unrecognised shape would look exactly
 * like a model that declined to answer — the agent would fall back to
 * deterministic planning and nobody would know the integration was broken.
 *
 * REFUSAL. The reply is matched against a closed list. This is the boundary
 * that makes an LLM safe to have in a financial pipeline at all, so it is
 * tested against the ways a small local model actually misbehaves: code fences,
 * surrounding prose, a chain of thought that names two of the options.
 */
class LlmAdapterTest {

    private static final List<String> TOOLS =
            List.of("get_settlement", "check_rate_card", "get_refunds", "compare_records");

    /* ── provider response shapes ────────────────────────────────────────── */

    @Test
    void parsesOllamaChatResponse() {
        String body = "{\"model\":\"llama3.2\",\"message\":{\"role\":\"assistant\","
                + "\"content\":\"check_rate_card\"},\"done\":true}";
        assertEquals("check_rate_card",
                LlmAdapter.extractText(LlmAdapter.Provider.OLLAMA, body).trim());
    }

    @Test
    void parsesOllamaGenerateResponse() {
        // /api/generate returns `response` rather than `message.content`.
        String body = "{\"model\":\"llama3.2\",\"response\":\"get_refunds\",\"done\":true}";
        assertEquals("get_refunds",
                LlmAdapter.extractText(LlmAdapter.Provider.OLLAMA, body).trim());
    }

    @Test
    void parsesOpenAiCompatibleResponse() {
        String body = "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":\"compare_records\"}}]}";
        assertEquals("compare_records",
                LlmAdapter.extractText(LlmAdapter.Provider.OPENAI, body).trim());
    }

    @Test
    void parsesAnthropicResponse() {
        String body = "{\"content\":[{\"type\":\"text\",\"text\":\"get_settlement\"}]}";
        assertEquals("get_settlement",
                LlmAdapter.extractText(LlmAdapter.Provider.ANTHROPIC, body).trim());
    }

    @Test
    void returnsEmptyRatherThanThrowingOnMalformedJson() {
        for (var p : LlmAdapter.Provider.values())
            assertEquals("", LlmAdapter.extractText(p, "{not json at all"),
                    "a malformed reply must degrade to no answer, never an exception");
    }

    /* ── the closed-list boundary ────────────────────────────────────────── */

    @Test
    void acceptsABareIdentifier() {
        assertEquals("get_refunds", LlmAdapter.matchExactlyOne("get_refunds", TOOLS));
    }

    @Test
    void toleratesTheWrappingSmallModelsAdd() {
        // Code fences, trailing punctuation and a leading label are cosmetic.
        assertEquals("check_rate_card",
                LlmAdapter.matchExactlyOne("```\ncheck_rate_card\n```", TOOLS));
        assertEquals("check_rate_card",
                LlmAdapter.matchExactlyOne("Answer: check_rate_card.", TOOLS));
        assertEquals("check_rate_card",
                LlmAdapter.matchExactlyOne("  CHECK_RATE_CARD  ", TOOLS));
    }

    /**
     * A reply naming two options is ambiguous and must be refused.
     *
     * This is the case that matters. A model that reasons aloud — "I could use
     * get_refunds, but check_rate_card is better" — has not made a choice the
     * caller can act on, and picking the first or last mention would be the
     * caller inventing the decision on the model's behalf.
     */
    @Test
    void refusesWhenTheReplyNamesMoreThanOneOption() {
        assertNull(LlmAdapter.matchExactlyOne(
                "I could use get_refunds, but check_rate_card is more informative.", TOOLS));
        assertNull(LlmAdapter.matchExactlyOne("get_settlement then compare_records", TOOLS));
    }

    @Test
    void refusesAnythingOffTheList() {
        assertNull(LlmAdapter.matchExactlyOne("drop_table_ledger", TOOLS));
        assertNull(LlmAdapter.matchExactlyOne("post the payment to settlement 42", TOOLS));
        assertNull(LlmAdapter.matchExactlyOne("", TOOLS));
        assertNull(LlmAdapter.matchExactlyOne(null, TOOLS));
    }

    @Test
    void refusesASubstringThatIsNotAWholeIdentifier() {
        // "get_refunds_v2" is not "get_refunds"; a prefix match would let a
        // hallucinated tool name through as a real one.
        assertNull(LlmAdapter.matchExactlyOne("get_refunds_v2", TOOLS));
    }

    /* ── configuration ───────────────────────────────────────────────────── */

    @Test
    void anUnreachableLocalServerReportsUnavailableRatherThanConfigured() {
        // Port 1 is reserved and nothing listens there. A local provider that is
        // configured but down must NOT report itself available, or the UI claims
        // a model that cannot answer and every trace silently falls back.
        var s = new LlmAdapter.Settings(LlmAdapter.Provider.OLLAMA, null, "llama3.2",
                "http://127.0.0.1:1", Duration.ofMillis(400), 0);
        var a = new LlmAdapter(s);
        assertFalse(a.available(), "a down server must not report as available");
        assertNull(a.model(), "no model may be named when none can be reached");
        assertTrue(a.status().contains("unreachable"), a.status());
    }

    @Test
    void chooseIsASafeNoOpWhenNothingIsConfigured() {
        var s = new LlmAdapter.Settings(LlmAdapter.Provider.NONE, null, "", "",
                Duration.ofMillis(400), 0);
        var a = new LlmAdapter(s);
        var c = a.choose("q", "ctx", TOOLS);
        assertFalse(c.ok());
        assertEquals(LlmAdapter.Outcome.NOT_CONFIGURED, c.outcome());
        assertEquals(0, a.callCount(), "an unconfigured adapter must not attempt a call");
    }

    @Test
    void anthropicWithoutAKeyIsUnavailableAndSaysSo() {
        var s = new LlmAdapter.Settings(LlmAdapter.Provider.ANTHROPIC, null, "m",
                "https://example.invalid", Duration.ofMillis(400), 0);
        var a = new LlmAdapter(s);
        assertFalse(a.available());
        assertTrue(a.status().contains("SETTLEIQ_LLM_API_KEY"), a.status());
    }
}
