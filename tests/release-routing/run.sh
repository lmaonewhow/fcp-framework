#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
mkdir -p build/release-routing
javac --release 17 -encoding UTF-8 -d build/release-routing core/src/main/java/fcp/{FcpException,Json,Schema}.java core/src/main/java/fcp/host/TrustedContext.java core/src/main/java/fcp/control/{StateJournal,FileStateJournal}.java core/src/main/java/fcp/release/routing/*.java tests/release-routing/ReleaseRoutingTest.java
java -cp build/release-routing ReleaseRoutingTest
