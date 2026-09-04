package com.settleiq;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Currency primitives.
 *
 * INVARIANT: money is a long count of paise. There is no float or double
 * anywhere in this codebase. Rate arithmetic goes through BigDecimal with an
 * explicit RoundingMode.HALF_UP and comes straight back to long paise.
 *
 * This mirrors datagen/money.py exactly, which is what lets the fee model
 * reproduce generator arithmetic to the paise.
 */
public final class Money {
    private Money() {}

    public static final BigDecimal HUNDRED = new BigDecimal(100);

    /** Parse "1234.56" (or "-1234.56") to paise. */
    public static long parse(String rupees) {
        if (rupees == null || rupees.isBlank()) return 0L;
        return new BigDecimal(rupees.trim())
                .multiply(HUNDRED)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** paise -> "1234.56", sign preserved, always 2dp. */
    public static String fmt(long paise) {
        String sign = paise < 0 ? "-" : "";
        long p = Math.abs(paise);
        return sign + (p / 100) + "." + String.format("%02d", p % 100);
    }

    /** paise -> "12,34,567.89" with Indian digit grouping. */
    public static String fmtInr(long paise) {
        String sign = paise < 0 ? "-" : "";
        long p = Math.abs(paise);
        String whole = Long.toString(p / 100);
        String frac = String.format("%02d", p % 100);
        if (whole.length() > 3) {
            String head = whole.substring(0, whole.length() - 3);
            String tail = whole.substring(whole.length() - 3);
            StringBuilder sb = new StringBuilder();
            while (head.length() > 2) {
                sb.insert(0, "," + head.substring(head.length() - 2));
                head = head.substring(0, head.length() - 2);
            }
            whole = head + sb + "," + tail;
        }
        return sign + whole + "." + frac;
    }

    /** Percentage of an amount, HALF_UP to whole paise. */
    public static long pctOf(long paise, BigDecimal pct) {
        return BigDecimal.valueOf(paise)
                .multiply(pct)
                .divide(HUNDRED, 10, RoundingMode.HALF_UP)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** Rupees as a display string in lakh, for headlines only. */
    public static String lakh(long paise) {
        return BigDecimal.valueOf(paise)
                .divide(BigDecimal.valueOf(10_000_000L), 2, RoundingMode.HALF_UP) + "L";
    }
}
