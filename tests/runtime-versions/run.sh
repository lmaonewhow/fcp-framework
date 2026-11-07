#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
BUILD=$(mktemp -d); PACKAGES=$(mktemp -d)
trap 'rm -rf "$BUILD" "$PACKAGES"' EXIT
printf 'UTC: '; date -u +%FT%TZ
printf 'Baseline: '; git rev-parse HEAD
java -version; python3 --version; node --version
python3 tests/runtime-native/native_runtime_acceptance.py --package-output "$PACKAGES"
javac --release 17 -d "$BUILD" $(find core/src/main/java business/src/main/java host/src/main/java -name '*.java') tests/runtime-versions/*.java
java -cp "$BUILD" VersionLifecycleTest "$PACKAGES" "$(command -v python3)" "$(command -v node)"
java -cp "$BUILD" VersionSchemaFenceTest "$PACKAGES"
java -cp "$BUILD" VersionCrashTest "$PACKAGES" "$(command -v python3)" "$(command -v node)"
find "$BUILD/fcp/control" "$BUILD/fcp/runtime" "$BUILD/dev/fcp/host" -name '*.class' -print0 | sort -z | xargs -0 sha256sum
