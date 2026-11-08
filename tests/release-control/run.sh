#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
mkdir -p build/release-control
javac --release 17 -encoding UTF-8 -d build/release-control $(find core/src/main/java -name '*.java') tests/release-control/ReleaseControllerTest.java
java -cp build/release-control fcp.release.control.ReleaseControllerTest
