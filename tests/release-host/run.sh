#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
build=$(mktemp -d)
trap 'rm -rf "$build"' EXIT
javac --release 17 -d "$build" $(find core/src/main/java business/src/main/java host/src/main/java -name '*.java') tests/release-host/SystemReleaseHostTest.java
java -cp "$build" SystemReleaseHostTest
