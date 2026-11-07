#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
BUILD=$(mktemp -d); trap 'rm -rf "$BUILD"' EXIT
printf 'UTC: '; date -u +%FT%TZ
printf 'Baseline: '; git rev-parse HEAD
javac --release 17 -d "$BUILD" $(find core/src/main/java -name '*.java') tests/runtime-exchange/*.java
java -cp "$BUILD" ExchangeTest "$(command -v python3)"
java -cp "$BUILD" ExportRetentionTest
set +e
env -u GITHUB_ACTIONS java -cp "$BUILD" NativeExportRetentionProbe age 00000000-0000-0000-0000-000000000000 > "$BUILD/retention-guard.json"
GUARD=$?
set -e
[[ "$GUARD" == 2 ]]
grep -q '"status":"FAIL"' "$BUILD/retention-guard.json"
printf 'PASS native retention helper rejects non-CI before database access\n'
