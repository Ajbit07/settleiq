package com.settleiq.api.service;

import com.settleiq.Loader;
import com.settleiq.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Seeds the database from a generated merchant directory.
 *
 * Reuses the engine's own CSV loader rather than a second parser in another
 * language: the rows that reach Postgres are parsed by exactly the code the
 * reconciliation path trusts, so a parsing difference cannot open a gap between
 * what was ingested and what gets reconciled.
 *
 * Invoked as `--ingest=/data/merchant_a`. Idempotent: re-seeding the same
 * directory upserts and leaves row counts unchanged, so the compose init
 * container is safe to re-run.
 *
 * The conflict targets are (merchant_id, id), not id alone. Upstream id spaces
 * are per-merchant, so a bare id conflict target silently discards a second
 * merchant whose ids overlap the first -- see V5__tenant_scoped_keys.sql.
 */
@Component
public class IngestRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestRunner.class);
    private static final int BATCH = 500;

    private final JdbcTemplate jdbc;

    public IngestRunner(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void run(ApplicationArguments args) {
        List<String> dirs = args.getOptionValues("ingest");
        if (dirs == null || dirs.isEmpty()) return;
        for (String d : dirs) ingest(Path.of(d));
        if (args.containsOption("ingest-then-exit")) {
            log.info("ingest complete; exiting as requested");
            System.exit(0);
        }
    }

    @Transactional
    public void ingest(Path merchantDir) {
        if (!Files.isDirectory(merchantDir)) {
            log.warn("ingest skipped, not a directory: {}", merchantDir);
            return;
        }
        Model.Inputs in = Loader.load(merchantDir);
        String mid = in.rateCard.merchantId();
        String rateCardJson = readRateCard(merchantDir);

        jdbc.update("""
                INSERT INTO merchant (merchant_id, name, rate_card)
                VALUES (?,?,?::jsonb)
                ON CONFLICT (merchant_id) DO UPDATE
                  SET name = EXCLUDED.name, rate_card = EXCLUDED.rate_card
                """, mid, in.rateCard.name(), rateCardJson);

        batch("""
                INSERT INTO payment (payment_id, merchant_id, order_id, amount_paise,
                                     method, status, created_at_ist)
                VALUES (?,?,?,?,?,?,?)
                ON CONFLICT (merchant_id, payment_id) DO NOTHING
                """, in.payments, p -> new Object[]{p.paymentId(), mid, p.orderId(),
                p.amount(), p.method(), p.status(), java.sql.Timestamp.valueOf(p.createdAtIst())});

        batch("""
                INSERT INTO refund (refund_id, merchant_id, payment_id, amount_paise,
                                    created_at_ist, netted)
                VALUES (?,?,?,?,?,?)
                ON CONFLICT (merchant_id, refund_id) DO NOTHING
                """, in.refunds, r -> new Object[]{r.refundId(), mid, r.paymentId(),
                r.amount(), java.sql.Timestamp.valueOf(r.createdAtIst()), r.netted()});

        batch("""
                INSERT INTO chargeback (dispute_id, merchant_id, payment_id, amount_paise,
                                        fee_paise, raised_at_ist, reversed_at_ist)
                VALUES (?,?,?,?,?,?,?)
                ON CONFLICT (merchant_id, dispute_id) DO NOTHING
                """, in.chargebacks, c -> new Object[]{c.disputeId(), mid, c.paymentId(),
                c.amount(), c.fee(), java.sql.Timestamp.valueOf(c.raisedAtIst()),
                c.reversedAtIst() == null ? null : java.sql.Timestamp.valueOf(c.reversedAtIst())});

        batch("""
                INSERT INTO reserve_entry (reserve_id, merchant_id, amount_paise, held_on,
                                           release_date, released_at_ist, netted)
                VALUES (?,?,?,?,?,?,?)
                ON CONFLICT (merchant_id, reserve_id) DO NOTHING
                """, in.reserve, r -> new Object[]{r.reserveId(), mid, r.amount(),
                java.sql.Date.valueOf(r.heldOn()), java.sql.Date.valueOf(r.releaseDate()),
                java.sql.Timestamp.valueOf(r.releasedAtIst()), r.netted()});

        batch("""
                INSERT INTO settlement (settlement_id, merchant_id, utr, gross_paise,
                        fees_paise, gst_paise, tds_paise, net_paise, settled_at, instant)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (merchant_id, settlement_id) DO NOTHING
                """, in.settlements, s -> new Object[]{s.settlementId(), mid,
                s.utr() == null || s.utr().isBlank() ? null : s.utr(),
                s.gross(), s.fees(), s.gst(), s.tds(), s.net(),
                java.sql.Timestamp.valueOf(s.settledAt()), s.instant()});

        batch("""
                INSERT INTO bank_txn (bank_txn_id, merchant_id, value_date, amount_paise, narration)
                VALUES (?,?,?,?,?)
                ON CONFLICT (merchant_id, bank_txn_id) DO NOTHING
                """, in.bank, b -> new Object[]{b.bankTxnId(), mid,
                java.sql.Date.valueOf(b.valueDate()), b.amount(), b.narration()});

        log.info("ingested merchant={} payments={} settlements={} bank_rows={} "
                 + "refunds={} chargebacks={} reserve={}",
                mid, in.payments.size(), in.settlements.size(), in.bank.size(),
                in.refunds.size(), in.chargebacks.size(), in.reserve.size());
    }

    private interface Row<T> { Object[] of(T t); }

    private <T> void batch(String sql, List<T> items, Row<T> mapper) {
        List<Object[]> buf = new ArrayList<>(BATCH);
        for (T t : items) {
            buf.add(mapper.of(t));
            if (buf.size() == BATCH) { jdbc.batchUpdate(sql, buf); buf.clear(); }
        }
        if (!buf.isEmpty()) jdbc.batchUpdate(sql, buf);
    }

    private static String readRateCard(Path merchantDir) {
        try {
            return Files.readString(merchantDir.resolve("config/rate_card_published.json"),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read published rate card for " + merchantDir, e);
        }
    }
}
