#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
BUILD=$(mktemp -d)
trap 'rm -rf "$BUILD"' EXIT
printf 'UTC: '; date -u +%FT%TZ
printf 'Baseline: '; git rev-parse HEAD
java -version
javac --release 17 -d "$BUILD" core/src/main/java/fcp/{Json,FcpException,Schema}.java core/src/main/java/fcp/control/{StateJournal,FileStateJournal}.java core/src/main/java/fcp/release/version/*.java tests/system-version/VersionProviderTest.java
java -cp "$BUILD" VersionProviderTest
find core/src/main/java/fcp/release/version tests/system-version -type f -print0 | sort -z | xargs -0 sha256sum
