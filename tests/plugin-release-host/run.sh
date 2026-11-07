#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
build=$(mktemp -d); packages=$(mktemp -d)
trap 'rm -rf "$build" "$packages"' EXIT
python3 tests/runtime-native/native_runtime_acceptance.py --package-output "$packages"
javac --release 17 -d "$build" $(find core/src/main/java business/src/main/java host/src/main/java -name '*.java') tests/plugin-release-host/*.java
java -cp "$build" PluginReleaseApplicationTest "$packages" "$(command -v python3)"

java -cp "$build" NativeExecutorLockTest
