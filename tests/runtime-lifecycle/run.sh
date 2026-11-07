#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
BUILD="$(mktemp -d)"
trap 'rm -rf "$BUILD"' EXIT
printf 'UTC: '; date -u +%FT%TZ
printf 'Baseline: '; git rev-parse HEAD
java -version
python3 --version
node --version
javac --release 17 -d "$BUILD" $(find core/src/main/java -name '*.java') tests/runtime-lifecycle/*.java tests/java/ProcessTerminationRegression.java tests/java/ProcessTreeRegression.java tests/legacy/java/ProcessBoundsRegression.java tests/runtime-contracts/CatalogPublicationTest.java
java -cp "$BUILD" LifecycleTest
java -cp "$BUILD" EnablementPolicyTest
FCP_TEST_SECRET=must_not_leak java -cp "$BUILD" RealRuntimeTest "$(command -v python3)" "$(command -v node)"
java -cp "$BUILD" HostPortsRuntimeTest "$(command -v python3)"
java -cp "$BUILD" ProcessRuntimeExtensionsTest "$(command -v python3)"
java -cp "$BUILD" CatalogPublicationTest
java -cp "$BUILD" ProcessTerminationRegression
java -cp "$BUILD" ProcessTreeRegression
FCP_TEST_SECRET=synthetic-canary java -Dfcp.maxProcesses=2 -cp "$BUILD" ProcessBoundsRegression
printf 'Compiled runtime/control class hashes:\n'
find "$BUILD/fcp/runtime" "$BUILD/fcp/control" -name '*.class' -print0 | sort -z | xargs -0 sha256sum
