#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
build=$(mktemp -d); packages=$(mktemp -d)
trap 'rm -rf "$build" "$packages"' EXIT
python3 tests/runtime-native/native_runtime_acceptance.py --package-output "$packages"
javac --release 17 -d "$build" $(find core/src/main/java -name '*.java') tests/plugin-multiversion/*.java tests/runtime-lifecycle/RealRuntimeTest.java
java -cp "$build" PluginMultiVersionTest "$packages" "$(command -v python3)" "$(command -v node)"

java -cp "$build" ReleaseCommitFenceTest "$(command -v python3)"

java -cp "$build" ReleaseRenewalTest
