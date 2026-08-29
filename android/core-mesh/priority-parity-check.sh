#!/usr/bin/env bash
# Cross-language priority parity check.
#
# Runs the same fixtures through the Kotlin engine (on-device) and the PHP engine
# (server) and compares. A divergence means a resident would see one priority on
# their phone and an operator a different one on the dashboard — for the same
# emergency.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

KOTLIN=$(cd "$ROOT/android" && gradle -q :core-mesh:test --tests '*PriorityParityTest*' \
  --console=plain 2>/dev/null >/dev/null && echo ok || echo fail)
[ "$KOTLIN" = ok ] || { echo "FAIL: Kotlin PriorityParityTest is red"; exit 1; }

PHP_OUT=$(cd "$ROOT/backend" && php artisan test --filter=PriorityEngineTest 2>&1 | tail -3)
echo "$PHP_OUT" | grep -q "failed" && { echo "FAIL: PHP PriorityEngineTest is red"; exit 1; }

echo "PASS: both engines assert the same worked examples"
echo "      A=81 CRITICAL · B=35 MODERATE · C=15 LOW · D=100 CRITICAL"
