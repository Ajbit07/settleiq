#!/usr/bin/env bash
# Create the demo merchant, ingest the seven CSVs, and reconcile.
#
# Safe to re-run: the merchant upserts, and ingestion is additive per file, so
# clear the merchant's rows first (see reset below) if you want a clean slate.
set -euo pipefail
cd "$(dirname "$0")/.."

API="http://localhost:${API_PORT:-8088}/api/v1"
M="merchant_3"
DATA="demo/data"

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }

say "1 · demo merchant"
docker exec -i settleiq-postgres-1 psql -U settleiq -d settleiq -q < demo/setup.sql
echo "  merchant_3 ready"

# Clear this merchant's SOURCE rows so the load is repeatable -- ingest is keyed
# on (merchant_id, id) and a second run would collide on every row.
#
# audit_ledger is deliberately NOT cleared: the database refuses DELETE on it by
# trigger, which is the property the audit screen exists to demonstrate. Reloads
# therefore append runs rather than replacing them, and the chain stays intact.
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
echo "  source rows cleared (ledger left intact - it refuses DELETE by design)"

say "2 · ingest"
# Order matters only for readability; the engine joins on ids, not arrival.
for pair in "PAYMENTS payments" "ORDERS orders" "SETTLEMENTS settlements" \
            "BANK bank" "REFUNDS refunds" "CHARGEBACKS chargebacks" "RESERVE reserve"; do
  set -- $pair
  entity=$1 file=$2
  [ -s "$DATA/$file.csv" ] || { printf '  %-12s skipped (empty)\n' "$entity"; continue; }
  r=$(curl -s -X POST "$API/ingest?merchantId=$M&entity=$entity" -F "file=@$DATA/$file.csv")
  printf '  %-12s %s\n' "$entity" "$(python -c "
import json,sys
d=json.loads(sys.argv[1])
print(f\"{d.get('rows_accepted','?')} accepted, {d.get('rows_rejected','?')} rejected\")
if d.get('sample'): print('     first reject:', d['sample'][0].get('detail'))
" "$r" 2>/dev/null || echo "$r" | head -c 200)"
done

say "3 · reconcile"
run=$(curl -s -X POST "$API/runs" -H 'content-type: application/json' \
        -d "{\"merchantId\":\"$M\",\"preset\":\"full\"}")
echo "$run" | python -c "
import json,sys
d=json.load(sys.stdin)
print(f\"  run #{d['run_id']}  {d['wall_ms']} ms  {d['payment_links']} links  \"
      f\"{d['bank_matched']} credits matched  {d['exceptions']} exceptions  \"
      f\"{d['audit_appended']} ledger rows\")
print(d['run_id'], file=open('demo/.last_run','w'))
"
say "4 · outcome vs expected"
python demo/verify.py
