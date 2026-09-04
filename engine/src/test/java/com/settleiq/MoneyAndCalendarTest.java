package com.settleiq;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The arithmetic the whole system rests on.
 *
 * These tests are deliberately about rounding ORDER and integer exactness,
 * because those are the bugs that produce a paise of drift on every batch and
 * are invisible until someone reconciles a month.
 */
class MoneyAndCalendarTest {

    @Test
    void parsesAndFormatsExactly() {
        assertEquals(50193044L, Money.parse("501930.44"));
        assertEquals(-1234L, Money.parse("-12.34"));
        assertEquals(0L, Money.parse(""));
        assertEquals("501930.44", Money.fmt(50193044L));
        assertEquals("5,01,930.44", Money.fmtInr(50193044L));
        assertEquals("-5,01,930.44", Money.fmtInr(-50193044L));
        assertEquals("99.00", Money.fmt(9900L));
        assertEquals("0.07", Money.fmt(7L));
    }

    @Test
    void indianDigitGroupingAtEveryMagnitude() {
        assertEquals("1.00", Money.fmtInr(100L));
        assertEquals("999.00", Money.fmtInr(99900L));
        assertEquals("1,000.00", Money.fmtInr(100000L));
        assertEquals("1,00,000.00", Money.fmtInr(10000000L));
        assertEquals("1,00,00,000.00", Money.fmtInr(1000000000L));
    }

    @Test
    void percentageRoundsHalfUpNotHalfEven() {
        // 2.5 paise must go to 3, not to 2. HALF_EVEN would give 2 and lose a
        // paise on every other transaction.
        assertEquals(3L, Money.pctOf(125L, new BigDecimal("2.00")));
        assertEquals(2000L, Money.pctOf(100000L, new BigDecimal("2.00")));
        assertEquals(0L, Money.pctOf(100000L, new BigDecimal("0.00")));
    }

    @Test
    void feesRoundPerPaymentThenSum_notSumThenRound() {
        // Three payments at a rate that lands each on a half-paise. Rounding
        // per payment gives 3; summing first and rounding once gives 2. The
        // platform rounds per payment, so the engine must too or every batch
        // drifts.
        long each = Money.pctOf(125L, new BigDecimal("2.00"));
        long perPaymentThenSum = each * 3;
        long sumThenRound = Money.pctOf(375L, new BigDecimal("2.00"));
        assertEquals(9L, perPaymentThenSum);
        assertEquals(8L, sumThenRound);
        assertNotEquals(perPaymentThenSum, sumThenRound,
                "this test is pointless if the two orders agree");
    }

    @Test
    void noPrecisionLossAtLargeAmounts() {
        // A double cannot hold this exactly; a long can. If money were ever
        // moved to double, this is the assertion that would fail.
        long big = 999_999_999_999L;                 // ~Rs 1,000 crore in paise
        assertEquals(big, Money.parse(Money.fmt(big)));
    }

    @Test
    void weekendsAndSecondFourthSaturdaysAreNotBankingDays() {
        assertTrue(BankingCalendar.isHoliday(LocalDate.of(2025, 11, 2)));   // Sunday
        assertTrue(BankingCalendar.isHoliday(LocalDate.of(2025, 11, 8)));   // 2nd Sat
        assertTrue(BankingCalendar.isHoliday(LocalDate.of(2025, 11, 22)));  // 4th Sat
        assertFalse(BankingCalendar.isHoliday(LocalDate.of(2025, 11, 15))); // 3rd Sat works
        assertFalse(BankingCalendar.isHoliday(LocalDate.of(2025, 11, 1)));  // 1st Sat works
    }

    @Test
    void tPlusTwoSkipsHolidaysAndWeekends() {
        // Thu 13 Nov: +2 banking days crosses a declared holiday (14th) and a
        // Sunday (16th), landing on Monday the 17th rather than Saturday the 15th.
        assertEquals(LocalDate.of(2025, 11, 17),
                BankingCalendar.addBankingDays(LocalDate.of(2025, 11, 13), 2));
        // Wed 12 Nov lands on the 3rd Saturday, which is a working day.
        assertEquals(LocalDate.of(2025, 11, 15),
                BankingCalendar.addBankingDays(LocalDate.of(2025, 11, 12), 2));
    }

    @Test
    void bankingDayDistanceIsSignedAndSkipsClosures() {
        assertEquals(0, BankingCalendar.between(
                LocalDate.of(2025, 11, 10), LocalDate.of(2025, 11, 10)));
        assertEquals(-BankingCalendar.between(
                        LocalDate.of(2025, 11, 10), LocalDate.of(2025, 11, 13)),
                BankingCalendar.between(
                        LocalDate.of(2025, 11, 13), LocalDate.of(2025, 11, 10)));
        // Fri 21 -> Mon 24 is one banking day apart, not three calendar days.
        assertEquals(1, BankingCalendar.between(
                LocalDate.of(2025, 11, 21), LocalDate.of(2025, 11, 24)));
    }
}
