-- V5 scope source-document keys to the merchant.
--
-- THE BUG THIS FIXES
--
-- payment_id, order_id, refund_id, dispute_id and bank_txn_id were global
-- PRIMARY KEYs. The upstream id spaces are per-merchant, so merchant B's
-- `pay_00000000000001` collided with merchant A's, and the ingest -- which uses
-- ON CONFLICT DO NOTHING for idempotency -- silently dropped every colliding
-- row.
--
-- The failure was almost invisible. Ingest reported success. Settlements loaded
-- fine, because settlement_id already carried a merchant token. Merchant B then
-- reconciled ZERO payments against 43 real settlements and reported the entire
-- payout as unexplained residue. A correctness bug that presents as a very
-- large, very confident number is the worst kind.
--
-- Scoping the keys by merchant is also the right multi-tenant shape
-- independently of the generator: one tenant's identifiers should never be able
-- to collide with, or silently displace, another's.

ALTER TABLE payment       DROP CONSTRAINT payment_pkey;
ALTER TABLE payment       ADD  CONSTRAINT payment_pkey       PRIMARY KEY (merchant_id, payment_id);

ALTER TABLE refund        DROP CONSTRAINT refund_pkey;
ALTER TABLE refund        ADD  CONSTRAINT refund_pkey        PRIMARY KEY (merchant_id, refund_id);

ALTER TABLE chargeback    DROP CONSTRAINT chargeback_pkey;
ALTER TABLE chargeback    ADD  CONSTRAINT chargeback_pkey    PRIMARY KEY (merchant_id, dispute_id);

ALTER TABLE reserve_entry DROP CONSTRAINT reserve_entry_pkey;
ALTER TABLE reserve_entry ADD  CONSTRAINT reserve_entry_pkey PRIMARY KEY (merchant_id, reserve_id);

ALTER TABLE settlement    DROP CONSTRAINT settlement_pkey;
ALTER TABLE settlement    ADD  CONSTRAINT settlement_pkey    PRIMARY KEY (merchant_id, settlement_id);

ALTER TABLE bank_txn      DROP CONSTRAINT bank_txn_pkey;
ALTER TABLE bank_txn      ADD  CONSTRAINT bank_txn_pkey      PRIMARY KEY (merchant_id, bank_txn_id);

-- The UTR partial index was fine but is worth scoping too: two merchants can
-- legitimately be paid under references that look alike.
DROP INDEX IF EXISTS settlement_utr_idx;
CREATE INDEX settlement_utr_idx ON settlement (merchant_id, utr) WHERE utr IS NOT NULL;

-- Run-scoped result tables keep their own keys: run_id already implies a single
-- merchant, so they cannot collide across tenants.
