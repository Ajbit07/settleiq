#!/usr/bin/env bash
# Put the demo merchant back to empty, WITHOUT ingesting anything.
#
# Use this before recording: it leaves merchant_3 existing but with no source
# rows, so every file goes in by hand through the Ingest screen on camera.
# `load.sh` is the same thing plus the seven uploads and a reconcile.
set -euo pipefail
cd "$(dirname "$0")/.."

docker exec -i settleiq-postgres-1 psql -U settleiq -d settleiq -q < demo/setup.sql

docker exec -i settleiq-postgres-1 psql -U settleiq -d settleiq -q <<'SQL'
DELETE FROM refund        WHERE merchant_id = 'merchant_3';
DELETE FROM chargeback    WHERE merchant_id = 'merchant_3';
DELETE FROM reserve_entry WHERE merchant_id = 'merchant_3';
DELETE FROM bank_txn      WHERE merchant_id = 'merchant_3';
DELETE FROM settlement    WHERE merchant_id = 'merchant_3';
DELETE FROM payment       WHERE merchant_id = 'merchant_3';
DELETE FROM order_row     WHERE merchant_id = 'merchant_3';
DELETE FROM ingestion_run WHERE merchant_id = 'merchant_3';
SQL

cat <<'TXT'

  merchant_3 is empty and ready.

  Upload these in order on the Ingest screen, picking the matching entity:

    1_PAYMENTS.csv      -> PAYMENTS       17 rows
    2_ORDERS.csv        -> ORDERS         17 rows
    3_SETTLEMENTS.csv   -> SETTLEMENTS    12 rows
    4_BANK.csv          -> BANK           11 rows
    5_REFUNDS.csv       -> REFUNDS         1 row
    6_CHARGEBACKS.csv   -> CHARGEBACKS     1 row
    7_RESERVE.csv       -> RESERVE         1 row

    8_PAYMENTS_with_errors.csv -> PAYMENTS   7 rows, 5 refused

  They are in demo/upload/. Then select Reconcile.

  Note: the audit ledger already holds decisions from earlier runs, so the
  per-gate refusal detail will read "decided on an earlier run". For a clean
  chain use a fresh merchant id -- see README.

TXT
