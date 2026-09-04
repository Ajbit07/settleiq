package com.settleiq;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/**
 * Indian banking calendar: Sundays, 2nd and 4th Saturdays, plus declared
 * holidays. Mirrors datagen/banking_calendar.py.
 *
 * This lives in the engine because "date delta" as a matching feature is
 * meaningless in calendar days -- a Friday capture and a Tuesday settlement are
 * adjacent in banking days but four apart in calendar days, and the scorer
 * needs the former.
 */
public final class BankingCalendar {
    private BankingCalendar() {}

    private static final Set<LocalDate> DECLARED = Set.of(
            LocalDate.of(2025, 10, 21),
            LocalDate.of(2025, 11, 5),
            LocalDate.of(2025, 11, 14),
            LocalDate.of(2025, 11, 25),
            LocalDate.of(2025, 12, 25));

    static int nthSaturday(LocalDate d) { return (d.getDayOfMonth() - 1) / 7 + 1; }

    public static boolean isHoliday(LocalDate d) {
        if (d.getDayOfWeek() == DayOfWeek.SUNDAY) return true;
        if (d.getDayOfWeek() == DayOfWeek.SATURDAY) {
            int n = nthSaturday(d);
            if (n == 2 || n == 4) return true;
        }
        return DECLARED.contains(d);
    }

    public static boolean isBankingDay(LocalDate d) { return !isHoliday(d); }

    public static LocalDate next(LocalDate d) {
        LocalDate c = d.plusDays(1);
        while (isHoliday(c)) c = c.plusDays(1);
        return c;
    }

    public static LocalDate addBankingDays(LocalDate d, int n) {
        LocalDate c = d;
        while (isHoliday(c)) c = c.plusDays(1);
        for (int i = 0; i < n; i++) c = next(c);
        return c;
    }

    /** Signed banking-day distance from a to b. */
    public static int between(LocalDate a, LocalDate b) {
        if (a.equals(b)) return 0;
        int sign = b.isAfter(a) ? 1 : -1;
        LocalDate lo = sign > 0 ? a : b, hi = sign > 0 ? b : a;
        int n = 0;
        LocalDate c = lo;
        while (c.isBefore(hi)) {
            c = c.plusDays(1);
            if (isBankingDay(c)) n++;
        }
        return sign * n;
    }
}
