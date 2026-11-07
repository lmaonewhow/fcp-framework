#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
BUILD=$(mktemp -d); trap 'rm -rf "$BUILD"' EXIT
printf 'UTC: '; date -u +%FT%TZ
printf 'Baseline: '; git rev-parse HEAD
javac --release 17 -d "$BUILD" $(find core/src/main/java -name '*.java') tests/runtime-exchange/*.java
java -cp "$BUILD" ExchangeTest "$(command -v python3)"
