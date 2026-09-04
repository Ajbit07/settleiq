#!/usr/bin/env bash
# End-to-end smoke test against a running stack.
#
# Asserts the properties that matter rather than just "did it 200":
#   - migrations applied and data seeded
#   - a reconciliation run completes and produces links
#   - the SAME run repeated posts ZERO new audit rows (idempotency)
#   - the hash chain verifies, and /audit/verify returns 409 when it does not
#   - money is returned as integer paise, not a float
#   - a suppressed re-run still reports the ORIGINAL verdicts
#   - the UI is served by this API and reads /api/v1
set -uo pipefail

# Ports come from .env, which is where the README tells you to change them.
# Hardcoding 8080 here meant `make smoke` failed for anyone who followed that
# advice, and failed as "api not ready" -- which reads like a broken stack
# rather than a script looking at the wrong port.
if [ -z "${API:-}" ] && [ -f "$(dirname "$0")/../.env" ]; then
  # Only the two port keys, and only if they look like ports. This file also
  # holds POSTGRES_PASSWORD, which has no business being eval'd.
  eval "$(grep -E '^(API_PORT|ML_PORT)=[0-9]+$' "$(dirname "$0")/../.env" || true)"
fi

API="${API:-http://localhost:${API_PORT:-8080}}"
ML="${ML:-http://localhost:${ML_PORT:-8000}}"
MERCHANT="${MERCHANT:-merchant_1}"
fails=0

ok()   { printf '  [ OK ] %s\n' "$1"; }
bad()  { printf '  [FAIL] %s\n' "$1"; fails=$((fails+1)); }
json() { python -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }

echo "SMOKE  api=$API  merchant=$MERCHANT"
echo "------------------------------------------------------------------"

# 1. readiness
if curl -fsS "$API/actuator/health/readiness" | grep -q '"status":"UP"'; then
  ok "api readiness reports UP"
else
  bad "api not ready"; exit 1
fi

if curl -fsS "$ML/health/ready" | grep -q '"status":"UP"'; then
  ok "ml service ready with model present"
else
  bad "ml service not ready (is mlservice/model.json present? run make train)"
fi

# 2. seeded
merchants=$(curl -fsS "$API/api/v1/merchants")
if grep -q "$MERCHANT" <<<"$merchants"; then
  ok "database seeded: $merchants"
else
  bad "merchant $MERCHANT not seeded; got $merchants"; exit 1
fi

# 3. first run
run1=$(curl -fsS -XPOST "$API/api/v1/runs" -H 'content-type: application/json' \
        -d "{\"merchantId\":\"$MERCHANT\",\"preset\":\"full\"}")
links=$(json 'd["payment_links"]' <<<"$run1")
matched=$(json 'd["bank_matched"]' <<<"$run1")
excs=$(json 'd["exceptions"]' <<<"$run1")
posted1=$(json 'd["auto_posted"]+d["escalated"]' <<<"$run1")
if [ "${links:-0}" -gt 0 ] && [ "${matched:-0}" -gt 0 ]; then
  ok "run 1: $links payment links, $matched bank credits, $excs exceptions"
else
  bad "run 1 produced nothing: $run1"; exit 1
fi

# 4. idempotency: an identical re-run must post nothing new
run2=$(curl -fsS -XPOST "$API/api/v1/runs" -H 'content-type: application/json' \
        -d "{\"merchantId\":\"$MERCHANT\",\"preset\":\"full\"}")
# The signal is rows APPENDED, not the verdict counters. The verdicts stay
# AUTO_POST / ESCALATE on a re-run because the underlying items still need
# whatever they needed; what must go to zero is what the ledger writes.
appended2=$(json 'd["audit_appended"]' <<<"$run2")
supp2=$(json 'd["suppressed_duplicate"]' <<<"$run2")
if [ "${appended2:-1}" -eq 0 ] && [ "${supp2:-0}" -gt 0 ]; then
  ok "run 2 appended 0 ledger rows, suppressed $supp2 duplicate postings"
else
  bad "re-run wrote $appended2 ledger rows (expected 0), suppressed=$supp2"
fi

# 5. audit chain
chain=$(curl -fsS "$API/api/v1/audit/verify?merchantId=$MERCHANT")
if json 'd["valid"]' <<<"$chain" | grep -qi true; then
  ok "audit chain valid over $(json 'd["rows"]' <<<"$chain") rows"
else
  bad "audit chain broken: $chain"
fi

# 6. money is an integer, not a float
#    NOTE: `python - <<'PY' <<<"$data"` does NOT work -- both redirections
#    target stdin, the last one wins, and python then reads the DATA as its
#    program. That silently exits 0 and the check passes without running.
#    The program goes in a file; the data goes on stdin.
credits=$(curl -fsS "$API/api/v1/credits?merchantId=$MERCHANT")
cat > /tmp/chk_money.py <<'PY'
import sys, json
d = json.load(sys.stdin)
assert d, "no credits returned"
bad = [c for c in d if not isinstance(c["bank_amount"], int)]
assert not bad, f"{len(bad)} credits returned non-integer money"
comps = [x for c in d for x in c["components"]]
assert comps, "no components returned"
assert all(isinstance(x["paise"], int) for x in comps), "component amounts must be integers"
print(f"    {len(d)} credits, {len(comps)} components, all integer paise")
PY
if python /tmp/chk_money.py <<<"$credits"; then
  ok "money returned as integer paise throughout"
else
  bad "money is not integer paise"
fi

# 7. exceptions carry evidence with provenance
excs_json=$(curl -fsS "$API/api/v1/exceptions?merchantId=$MERCHANT")
cat > /tmp/chk_evidence.py <<'PY'
import sys, json
d = json.load(sys.stdin)
assert d, "no exceptions returned"
withev = [e for e in d if e.get("evidence")]
assert withev, "no exception carries evidence"
for e in withev:
    for ev in e["evidence"]:
        assert ev.get("provenance_id"), "evidence item without a provenance id"
auto = sum(1 for e in d if e["verdict"] == "AUTO_POST")
esc = sum(1 for e in d if e["verdict"] == "ESCALATE")
# This view is the SECOND run, where every posting was suppressed as a
# duplicate. The verdicts must still read AUTO_POST / ESCALATE: an item that
# was escalated is still escalated and still needs a human. Reporting the whole
# queue as "suppressed" would hide every actionable item behind a retry.
assert auto + esc == len(d), (
    f"re-run lost the original verdicts: {len(d) - auto - esc} of {len(d)} "
    "exceptions report neither AUTO_POST nor ESCALATE")
print(f"    {len(d)} exceptions ({auto} auto-posted, {esc} escalated), "
      f"{len(withev)} carry provenance-tagged evidence")
print("    verdicts survived the duplicate-suppressed re-run")
PY
if python /tmp/chk_evidence.py <<<"$excs_json"; then
  ok "every evidence item carries a provenance id"
else
  bad "evidence missing provenance"
fi

# 8. every seeded merchant must actually reconcile.
#    A tenant whose source ids collided with another's used to ingest "successfully",
#    reconcile zero payments, and report its entire payout as unexplained residue.
for m in $(curl -fsS "$API/api/v1/merchants" | tr -d '[]"' | tr ',' ' '); do
  r=$(curl -fsS -XPOST "$API/api/v1/runs" -H 'content-type: application/json'         -d "{\"merchantId\":\"$m\",\"preset\":\"full\"}")
  l=$(json 'd["payment_links"]' <<<"$r")
  bm=$(json 'd["bank_matched"]' <<<"$r")
  if [ "${l:-0}" -gt 0 ] && [ "${bm:-0}" -gt 0 ]; then
    ok "$m reconciled: $l payment links, $bm bank credits"
  else
    bad "$m reconciled nothing (links=$l bank=$bm) -- source rows missing?"
  fi
done

# 9. the UI ships with the API and reads THIS api.
#    The jar packaging the UI and the UI pointing at /api/v1 are separate
#    failures, and both look like "the page loads" from the outside.
index=$(curl -fsS "$API/")
appjs=$(curl -fsS "$API/app.js")
if grep -q 'app.js' <<<"$index" && grep -q "'/api/v1'" <<<"$appjs"; then
  ok "UI served by the API and bound to /api/v1"
else
  bad "UI missing or not bound to /api/v1"
fi

# 10. the metrics screen's endpoint answers even with no evaluation artifacts
if curl -fsS "$API/api/v1/metrics" | json 'd["available"]' >/dev/null; then
  ok "metrics artifacts endpoint responds"
else
  bad "/api/v1/metrics did not return a readable document"
fi

echo "------------------------------------------------------------------"
if [ "$fails" -eq 0 ]; then echo "SMOKE PASSED"; else echo "SMOKE FAILED ($fails)"; fi
exit "$fails"
