package com.settleiq.api.repo;

import com.settleiq.FeeModel;
import com.settleiq.Model;
import com.settleiq.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Loads the OBSERVABLE sources for one merchant into the engine's input record.
 *
 * Everything here reads BIGINT paise straight into Java longs. There is no
 * decimal conversion on this path at all, which is the point: the value that
 * reaches the fee model is bit-identical to the value stored.
 */
@Repository
public class SourceRepository {

    private final JdbcTemplate jdbc;

    public SourceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean merchantExists(String merchantId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM merchant WHERE merchant_id = ?", Integer.class, merchantId);
        return n != null && n > 0;
    }

    public List<String> merchantIds() {
        return jdbc.queryForList("SELECT merchant_id FROM merchant ORDER BY merchant_id",
                String.class);
    }

    public Model.Inputs load(String merchantId) {
        Model.Inputs in = new Model.Inputs();

        jdbc.query("""
                SELECT payment_id, order_id, amount_paise, method, status, created_at_ist
                  FROM payment WHERE merchant_id = ? ORDER BY payment_id
                """, rs -> {
            in.payments.add(new Model.Payment(rs.getString(1), rs.getString(2),
                    rs.getLong(3), rs.getString(4), rs.getString(5),
                    rs.getTimestamp(6).toLocalDateTime()));
        }, merchantId);

        jdbc.query("""
                SELECT refund_id, payment_id, amount_paise, created_at_ist, netted
                  FROM refund WHERE merchant_id = ? ORDER BY refund_id
                """, rs -> {
            in.refunds.add(new Model.Refund(rs.getString(1), rs.getString(2), rs.getLong(3),
                    rs.getTimestamp(4).toLocalDateTime(), rs.getBoolean(5)));
        }, merchantId);

        jdbc.query("""
                SELECT dispute_id, payment_id, amount_paise, fee_paise,
                       raised_at_ist, reversed_at_ist
                  FROM chargeback WHERE merchant_id = ? ORDER BY dispute_id
                """, rs -> {
            var rev = rs.getTimestamp(6);
            in.chargebacks.add(new Model.Chargeback(rs.getString(1), rs.getString(2),
                    rs.getLong(3), rs.getLong(4), rs.getTimestamp(5).toLocalDateTime(),
                    rev == null ? null : rev.toLocalDateTime()));
        }, merchantId);

        jdbc.query("""
                SELECT reserve_id, amount_paise, held_on, release_date, released_at_ist, netted
                  FROM reserve_entry WHERE merchant_id = ? ORDER BY reserve_id
                """, rs -> {
            in.reserve.add(new Model.ReserveEntry(rs.getString(1), rs.getLong(2),
                    rs.getDate(3).toLocalDate(), rs.getDate(4).toLocalDate(),
                    rs.getTimestamp(5).toLocalDateTime(), rs.getBoolean(6)));
        }, merchantId);

        jdbc.query("""
                SELECT settlement_id, utr, gross_paise, fees_paise, gst_paise, tds_paise,
                       net_paise, settled_at, instant
                  FROM settlement WHERE merchant_id = ? ORDER BY settlement_id
                """, rs -> {
            in.settlements.add(new Model.Settlement(rs.getString(1),
                    rs.getString(2) == null ? "" : rs.getString(2),
                    rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7),
                    rs.getTimestamp(8).toLocalDateTime(), rs.getBoolean(9)));
        }, merchantId);

        jdbc.query("""
                SELECT bank_txn_id, value_date, amount_paise, narration
                  FROM bank_txn WHERE merchant_id = ? ORDER BY value_date, bank_txn_id
                """, rs -> {
            in.bank.add(new Model.BankTxn(rs.getString(1), rs.getDate(2).toLocalDate(),
                    rs.getLong(3), rs.getString(4)));
        }, merchantId);

        in.rateCard = rateCard(merchantId);
        return in;
    }

    /** Rebuild the published rate card from JSONB. */
    @SuppressWarnings("unchecked")
    public Model.RateCard rateCard(String merchantId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT name, rate_card::text AS rc FROM merchant WHERE merchant_id = ?",
                merchantId);
        var m = (Map<String, Object>) com.settleiq.Csv.json((String) row.get("rc"));

        var methods = new java.util.LinkedHashMap<String, List<Model.RateTier>>();
        var mm = (Map<String, Object>) m.get("methods");
        for (var e : mm.entrySet()) {
            var tiers = new java.util.ArrayList<Model.RateTier>();
            for (Object o : (List<Object>) e.getValue()) {
                var t = (Map<String, Object>) o;
                tiers.add(new Model.RateTier(
                        LocalDate.parse((String) t.get("effective_from")),
                        new java.math.BigDecimal((String) t.get("pct")),
                        (long) (double) (Double) t.get("flat_paise")));
            }
            methods.put(e.getKey(), tiers);
        }
        var reserveMethods = new java.util.ArrayList<String>();
        for (Object o : (List<Object>) m.get("reserve_methods")) reserveMethods.add((String) o);

        return new Model.RateCard(merchantId, (String) row.get("name"),
                new java.math.BigDecimal((String) m.get("gst_rate_pct")),
                new java.math.BigDecimal((String) m.get("tds_rate_pct")),
                new java.math.BigDecimal((String) m.get("reserve_rate_pct")),
                reserveMethods,
                (int) (double) (Double) m.get("reserve_hold_days"),
                new java.math.BigDecimal((String) m.get("instant_surcharge_pct")),
                Money.parse((String) m.get("dispute_fee")),
                (long) (double) (Double) m.get("tolerance_paise"),
                methods);
    }
}
