package com.settleiq;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import com.settleiq.Model.*;

/**
 * Loads the OBSERVABLE sources only.
 *
 * Anything under ground_truth/ is off limits to the engine; loadInputs()
 * refuses a path that points at it, so a careless change cannot quietly turn
 * the benchmark into a lookup.
 */
public final class Loader {
    private Loader() {}

    public static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

    /** Parse an ISO offset timestamp and express it in IST. */
    public static LocalDateTime ist(String s) {
        if (s == null || s.isBlank()) return null;
        OffsetDateTime odt = OffsetDateTime.parse(s.trim());
        return odt.withOffsetSameInstant(IST).toLocalDateTime();
    }

    public static Inputs load(Path merchantDir) {
        if (merchantDir.toString().replace('\\', '/').contains("ground_truth"))
            throw new IllegalArgumentException("engine may not read ground_truth/");
        Path obs = merchantDir.resolve("observable");
        Inputs in = new Inputs();

        for (Map<String, String> r : Csv.read(obs.resolve("payments.csv")))
            in.payments.add(new Payment(r.get("payment_id"), r.get("order_id"),
                    Money.parse(r.get("amount")), r.get("method"), r.get("status"),
                    ist(r.get("created_at_ist"))));

        for (Map<String, String> r : Csv.read(obs.resolve("orders.csv")))
            in.orders.add(new Order(r.get("order_id"), r.get("customer_id"),
                    Money.parse(r.get("amount")), ist(r.get("created_at_utc"))));

        for (Map<String, String> r : Csv.read(obs.resolve("refunds.csv")))
            in.refunds.add(new Refund(r.get("refund_id"), r.get("payment_id"),
                    Money.parse(r.get("amount")), ist(r.get("created_at_ist")),
                    "1".equals(r.get("netted_flag"))));

        for (Map<String, String> r : Csv.read(obs.resolve("chargebacks.csv")))
            in.chargebacks.add(new Chargeback(r.get("dispute_id"), r.get("payment_id"),
                    Money.parse(r.get("amount")), Money.parse(r.get("fee")),
                    ist(r.get("raised_at_ist")), ist(r.get("reversed_at_ist"))));

        for (Map<String, String> r : Csv.read(obs.resolve("settlements.csv")))
            in.settlements.add(new Settlement(r.get("settlement_id"), r.get("utr"),
                    Money.parse(r.get("gross")), Money.parse(r.get("fees")),
                    Money.parse(r.get("gst")), Money.parse(r.get("tds")),
                    Money.parse(r.get("net")), ist(r.get("settled_at")),
                    "1".equals(r.get("instant"))));

        for (Map<String, String> r : Csv.read(obs.resolve("bank_statement.csv")))
            in.bank.add(new BankTxn(r.get("bank_txn_id"), LocalDate.parse(r.get("value_date")),
                    Money.parse(r.get("amount")), r.get("narration")));

        for (Map<String, String> r : Csv.read(obs.resolve("reserve_ledger.csv")))
            in.reserve.add(new ReserveEntry(r.get("reserve_id"), Money.parse(r.get("amount")),
                    LocalDate.parse(r.get("held_on")), LocalDate.parse(r.get("release_date")),
                    ist(r.get("released_at_ist")), "1".equals(r.get("netted_flag"))));

        in.rateCard = FeeModel.loadCard(merchantDir.resolve("config/rate_card_published.json"));
        return in;
    }
}
