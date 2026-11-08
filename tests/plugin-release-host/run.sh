#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
build=$(mktemp -d); packages=$(mktemp -d)
trap 'rm -rf "$build" "$packages"' EXIT
python3 tests/runtime-native/native_runtime_acceptance.py --package-output "$packages"
javac --release 17 -d "$build" $(find core/src/main/java business/src/main/java host/src/main/java -name '*.java') tests/plugin-release-host/*.java
java -cp "$build" PluginReleaseApplicationTest "$packages" "$(command -v python3)"

java -cp "$build" NativeExecutorLockTest

# Same actual host/runtime suite with explicitly synthetic identity-violating package bytes.
python3 - "$packages" <<'PYFIXTURE'
import sys
from pathlib import Path
sys.path.insert(0,'tests/runtime-native')
from native_observation_acceptance import package_bytes
Path(sys.argv[1],'ci.python-1.1.0.zip').write_bytes(package_bytes('python','1.1.0')[0])
PYFIXTURE
java -cp "$build" PluginReleaseApplicationTest "$packages" "$(command -v python3)" identity-fault

java -cp "$build" ProtectedDeploymentConfigTest
