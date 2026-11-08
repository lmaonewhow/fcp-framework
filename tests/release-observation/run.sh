#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
build=$(mktemp -d)
trap 'rm -rf "$build"' EXIT
javac --release 17 -d "$build" $(find core/src/main/java -name '*.java') tests/release-observation/ObservationTest.java
java -cp "$build" ObservationTest "$(command -v python3)"
