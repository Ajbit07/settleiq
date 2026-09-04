package com.settleiq;

import com.settleiq.Model.Payment;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The H2 subset-sum fallback.
 *
 * On merchant A the ordered-block hypothesis resolves every date-group, so this
 * solver never runs on the headline dataset. It is exercised directly here
 * because "never reached in production" and "works" are different claims, and
 * the batch that finally reaches it will be the one nobody is watching.
 *
 * What is asserted is exactness and refusal: a returned subset must hit the
 * target to the paise using each payment at most once, and anything the solver
 * cannot hit exactly must come back null so the batch escalates.
 */
class NettingDpTest {

    private static final LocalDateTime T = LocalDateTime.of(2025, 11, 20, 10, 0);

    private static Payment pay(String id, long paise) {
        return new Payment(id, "ord_" + id, paise, "upi", "captured", T);
    }

    private static List<Payment> pool(long... amounts) {
        List<Payment> l = new ArrayList<>();
        for (int i = 0; i < amounts.length; i++) l.add(pay("pay_" + i, amounts[i]));
        return l;
    }

    private static Netting solver() { return new Netting(null, true, 400); }

    private static long sum(List<Payment> l) {
        long t = 0;
        for (Payment p : l) t += p.amount();
        return t;
    }

    @Test
    void findsASubsetNoContiguousWindowCanReach() {
        // 7,500 is reachable only as 1,000 + 6,500 -- not by any prefix or any
        // contiguous run in the given order, which is precisely the case H1
        // hands over.
        List<Payment> items = pool(100_000, 250_000, 650_000, 90_000);
        var sol = solver().h2SubsetSum(items, 750_000);
        List<Payment> got = sol.subset();

        assertNotNull(got, "an exact subset exists and must be found");
        assertEquals(750_000, sum(got), "the subset must hit the target to the paise");
    }

    @Test
    void usesEachPaymentAtMostOnce() {
        // 6,000 is 3,000 + 2,500 + 500, but also 3,000 twice. A payment can only
        // be in one batch, so reuse would be a false match, not a shortcut.
        List<Payment> items = pool(300_000, 250_000, 50_000);
        var sol = solver().h2SubsetSum(items, 600_000);
        List<Payment> got = sol.subset();

        assertNotNull(got);
        assertEquals(600_000, sum(got));
        Set<String> ids = new HashSet<>();
        for (Payment p : got)
            assertTrue(ids.add(p.paymentId()), "payment reused: " + p.paymentId());
    }

    @Test
    void refusesWhenNoSubsetIsExact() {
        // Every amount is even, the target is odd: unreachable. The solver must
        // refuse rather than return the nearest sum.
        assertFalse(solver().h2SubsetSum(pool(100_000, 200_000, 400_000), 250_001).proven());
    }

    @Test
    void refusesWhenThePoolCannotCoverTheTarget() {
        assertFalse(solver().h2SubsetSum(pool(100_000, 50_000), 500_000).proven());
    }

    @Test
    void refusesAboveTheItemCap() {
        // The cap is a bound on work, so it must be enforced before the search
        // starts even when a trivial answer exists.
        List<Payment> items = pool(new long[]{500_000, 100_000, 100_000, 100_000});
        var r = new Netting(null, true, 3).h2SubsetSum(items, 500_000);
        assertFalse(r.proven());
        assertEquals(Netting.Outcome.BOUND_ITEMS, r.outcome(),
                "exceeding the item cap must be reported as a bound, not as impossible");
    }

    @Test
    void refusesANonPositiveTarget() {
        assertFalse(solver().h2SubsetSum(pool(100_000), 0).proven());
    }

    /* ─────────────────────────────── uniqueness, the safety property ────── */

    /**
     * SCENARIO 4 — ambiguous partition.
     *
     * 10,000 + 40,000 and 20,000 + 30,000 both sum to 50,000 exactly. Both are
     * arithmetically perfect and nothing distinguishes them. A solver that
     * stops at the first hit posts real payments into the wrong batch while
     * every total still ties out, which is the error nobody catches.
     */
    @Test
    void refusesWhenTwoDifferentCombinationsBothSumToTheTarget() {
        var sol = solver().h2SubsetSum(pool(1_000_000, 4_000_000, 2_000_000, 3_000_000), 5_000_000);
        assertFalse(sol.proven(), "two valid partitions must never resolve to one of them");
        assertEquals(Netting.Outcome.AMBIGUOUS_MULTIPLE_PARTITIONS, sol.outcome());
        assertTrue(sol.reason().contains("two different combinations"),
                "the refusal must say why: " + sol.reason());
    }

    /** Duplicate amounts are the commonest way two partitions tie. */
    @Test
    void refusesWhenEqualAmountsMakeThePartitionInterchangeable() {
        // 500 + 500 reaches 1,000, and so does the other 500 + 500.
        var sol = solver().h2SubsetSum(pool(50_000, 50_000, 50_000, 50_000), 100_000);
        assertFalse(sol.proven(), "interchangeable equal amounts cannot identify a partition");
        assertEquals(Netting.Outcome.AMBIGUOUS_MULTIPLE_PARTITIONS, sol.outcome());
    }

    /** A genuinely unique partition still resolves — refusal must not be reflex. */
    @Test
    void stillSolvesWhenExactlyOneCombinationFits() {
        var sol = solver().h2SubsetSum(pool(700_000, 130_000, 45_000), 175_000);
        assertTrue(sol.proven(), "a unique partition must be found, not refused");
        assertEquals(Netting.Outcome.UNIQUE, sol.outcome());
        assertEquals(175_000, sum(sol.subset()));
    }

    /**
     * SCENARIO 5 — the verified search bound.
     *
     * The outcome must name the bound that was hit. "Refused" and "impossible"
     * are different answers and an operator has to be able to tell which one
     * they are looking at.
     */
    @Test
    void distinguishesBoundRefusalFromImpossibility() {
        var impossible = solver().h2SubsetSum(pool(300_000, 700_000), 450_000);
        assertEquals(Netting.Outcome.NO_EXACT_SUBSET, impossible.outcome());

        List<Payment> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) many.add(pay("p" + i, 100_000 + i));
        var bounded = new Netting(null, true, 10).h2SubsetSum(many, 999_999_999L);
        assertEquals(Netting.Outcome.BOUND_ITEMS, bounded.outcome());
        assertNotEquals(impossible.outcome(), bounded.outcome(),
                "a bound must never be reported as an impossibility");
    }

    /** Every refusal carries a reason and its work, for the exception queue. */
    @Test
    void everyRefusalCarriesAReasonAndItsCost() {
        for (var sol : List.of(
                solver().h2SubsetSum(pool(1_000_000, 4_000_000, 2_000_000, 3_000_000), 5_000_000),
                solver().h2SubsetSum(pool(300_000, 700_000), 450_000),
                new Netting(null, true, 1).h2SubsetSum(pool(1, 2, 3), 3))) {
            assertFalse(sol.proven());
            assertFalse(sol.reason().isBlank(), "a refusal with no reason is a silent failure");
            assertTrue(sol.micros() >= 0, "solver cost must be reported");
        }
    }
}
