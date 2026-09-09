#!/usr/bin/env bash
# Cross-language wire contract check.
#
# Takes the sync request the Kotlin client actually produces and feeds it to the
# real Laravel validator. Reading both codebases and assuming they agree is how
# the HMAC canonicalisation bug survived; this makes the two sides prove it.
set -euo pipefail

BASE="${BULIG_API:-http://127.0.0.1:8401/api/v1}"
JSON="$(dirname "$0")/build/contract/sync-request.json"
H=(-H "Content-Type: application/json" -H "Accept: application/json")

[ -f "$JSON" ] || { echo "FAIL: run 'gradle :data:test' first to emit $JSON"; exit 1; }

# The origin device must exist server-side, so register the one the fixture names.
DEVICE_ID=$(python3 -c "import json,sys; print(json.load(open('$JSON'))['packets'][0]['origin_device_id'])")
TOKEN=$(curl -sS -X POST "$BASE/devices/register" "${H[@]}" \
  -d "{\"device_id\":\"$DEVICE_ID\",\"model\":\"contract-check\",\"android_version\":\"14\"}" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['device_token'])")

RESPONSE=$(curl -sS -X POST "$BASE/sync/packets" \
  -H "Authorization: Bearer $TOKEN" "${H[@]}" --data-binary "@$JSON")

python3 - "$RESPONSE" <<'PY'
import json, sys
try:
    body = json.loads(sys.argv[1])
except json.JSONDecodeError:
    print("FAIL: server did not return JSON\n" + sys.argv[1][:400]); sys.exit(1)

if "errors" in body:
    print("FAIL: the Laravel validator rejected the Kotlin client's request.")
    for field, msgs in body["errors"].items():
        print(f"  {field}: {'; '.join(msgs)}")
    sys.exit(1)

results = body.get("results", [])
if not results:
    print("FAIL: no per-packet results returned\n" + json.dumps(body)[:400]); sys.exit(1)

status = results[0]["status"]
# DUPLICATE is a pass: it means a previous run already delivered this fixture,
# which still proves the request shape was understood.
if status not in ("ACCEPTED", "DUPLICATE", "TTL_EXPIRED_ACCEPTED"):
    print(f"FAIL: packet was not accepted — {status} {results[0].get('reason','')}"); sys.exit(1)

print(f"PASS: Laravel accepted the Kotlin-generated request ({status})")
print(f"      emergency_code={results[0].get('emergency_code')} "
      f"priority={results[0].get('priority_level')}")
PY
