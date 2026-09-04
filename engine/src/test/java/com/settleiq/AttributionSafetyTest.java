package com.settleiq;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adjustment attribution must be proven, not inferred.
 *
 * A refund attributed to the wrong batch is the worst class of bug this system
 * can have: both payouts still balance, the totals still tie out, and nobody
 * notices for weeks. So these tests construct the exact situations where a
 * timestamp does NOT identify a batch, and assert the engine refuses instead of
 * choosing the nearest boundary.
 *
 * The windows are built directly rather than generated, because the point is to
 * pin the decision rule — generating a month of data and hoping the adversarial
 * case appears would test the generator, not the rule.
 */
class AttributionSafetyTest {

    private static final LocalDate D = LocalDate.of(2025, 11, 20);

    /** A pipeline with empty inputs: only the attribution rule is under test. */
    private static Pipeline engine() {
        Model.Inputs in = new Model.Inputs();
        in.rateCard = new Model.RateCard("m", "Test",
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO, List.of(), 0,
                java.math.BigDecimal.ZERO, 0L, 100L, Map.of());
        return new Pipeline(Config.full(), in, new Scorer(null));
    }

    private static int sec(int h, int m) { return LocalTime.of(h, m).toSecondOfDay(); }
    private static LocalDateTime at(int h, int m) { return LocalDateTime.of(D, LocalTime.of(h, m)); }

    /**
     * Two batches, each with observed trading hours, and a gap between them.
     *   batch A observed 06:00-09:00, inferred boundary 00:00-10:29
     *   batch B observed 12:00-17:00, inferred boundary 10:30-23:59
     * Nothing traded between 09:00 and 12:00.
     */
    private static Map<LocalDate, List<Pipeline.Window>> twoBatches() {
        return Map.of(D, List.of(
                new Pipeline.Window("setl_A", 0, sec(10, 29), sec(6, 0), sec(9, 0)),
                new Pipeline.Window("setl_B", sec(10, 30), 86399, sec(12, 0), sec(17, 0))));
    }

    @Test
    void attributesWhenObservedMembersBracketTheTimestamp() {
        // 07:30 sits inside batch A's actual trading. Real payments of that
        // batch were being taken either side of it, which is evidence.
        var a = engine().attribute(twoBatches(), D, at(7, 30));
        assertEquals("setl_A", a.settlementId(), "a bracketed timestamp must attribute");
        assertEquals("observed_members_bracket_timestamp", a.basis());
    }

    /**
     * REQUIREMENT C — the ambiguous same-day case.
     *
     * 10:15 falls in the gap where neither batch traded. The old rule put it in
     * A because A's inferred boundary reached to 10:29 — a boundary the engine
     * invented. Nothing observed supports either batch.
     */
    @Test
    void refusesWhenTheTimestampFallsInTheGapBetweenBatches() {
        var a = engine().attribute(twoBatches(), D, at(10, 15));
        assertNull(a.settlementId(), "an adjustment in a dead gap must not be attributed");
        assertEquals("timestamp_in_inferred_gap", a.basis());
        assertTrue(a.reason().contains("interpolated"),
                "the refusal must say the boundary was interpolated: " + a.reason());
    }

    /**
     * REQUIREMENT D — the wrong-batch case.
     *
     * 10:31 is one minute past the inferred cut. Naive time-window attribution
     * flips it from A to B on the strength of a boundary that was never
     * observed — two adjacent minutes landing in different payouts. Both must
     * refuse, and both must name the same candidate batches, so the flip cannot
     * happen silently.
     */
    @Test
    void adjacentMinutesAcrossAnInferredCutBothRefuse() {
        var before = engine().attribute(twoBatches(), D, at(10, 29));
        var after  = engine().attribute(twoBatches(), D, at(10, 31));
        assertNull(before.settlementId(), "10:29 must not attribute to A on an invented boundary");
        assertNull(after.settlementId(),  "10:31 must not attribute to B on an invented boundary");
        assertEquals(before.basis(), after.basis(),
                "two minutes apart must not be decided by different rules");
    }

    /** Overlapping traffic identifies nothing: two batches were live at once. */
    @Test
    void refusesWhenTwoBatchesWereTradingSimultaneously() {
        var windows = Map.of(D, List.of(
                new Pipeline.Window("setl_A", 0, sec(12, 0), sec(6, 0), sec(11, 0)),
                new Pipeline.Window("setl_B", sec(12, 1), 86399, sec(9, 0), sec(17, 0))));
        var a = engine().attribute(windows, D, at(10, 0));
        assertNull(a.settlementId(), "simultaneous batches cannot be separated by a timestamp");
        assertEquals("overlapping_observed_ranges", a.basis());
        assertEquals(2, a.candidates().size(), "both live batches must be named");
    }

    /**
     * The control. One batch on the date means there is nothing to confuse it
     * with, and refusing there would be refusal theatre — it would make the
     * system look cautious while costing real reconciliation.
     */
    @Test
    void attributesFreelyWhenOnlyOneBatchSettledThatDay() {
        var windows = Map.of(D, List.of(
                new Pipeline.Window("setl_only", 0, 86399, sec(6, 0), sec(9, 0))));
        var a = engine().attribute(windows, D, at(23, 45));
        assertEquals("setl_only", a.settlementId(), "a lone batch owns the whole date");
        assertEquals("sole_batch_on_date", a.basis());
    }

    /** No reconstructed batch on the date: refuse, and say why. */
    @Test
    void refusesWhenNoBatchWasReconstructedForThatDate() {
        var a = engine().attribute(Map.of(), D, at(10, 0));
        assertNull(a.settlementId());
        assertEquals("no_batches_on_date", a.basis());
    }

    /** Every refusal must name the batches it declined to choose between. */
    @Test
    void everyRefusalNamesTheCandidatesItDeclinedToChooseBetween() {
        for (var a : List.of(engine().attribute(twoBatches(), D, at(10, 15)),
                             engine().attribute(twoBatches(), D, at(10, 31)))) {
            assertFalse(a.candidates().isEmpty(),
                    "a refusal with no candidates tells a reviewer nothing");
            assertFalse(a.reason().isBlank(), "a refusal must carry a reason");
        }
    }
}
