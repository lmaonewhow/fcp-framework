#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
BUILD=build/runtime-http
mkdir -p "$BUILD"
javac --release 17 -d "$BUILD" $(find core/src/main/java business/src/main/java host/src/main/java development-host/src/main/java -name '*.java') tests/runtime-staging/StagingTest.java
java -cp "$BUILD" StagingTest
python3 tests/runtime-staging/test_stage_http.py
