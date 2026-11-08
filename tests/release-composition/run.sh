#!/bin/bash
set -euo pipefail
repo=$(cd "$(dirname "$0")/../.." && pwd)
source_root=${FCP_COMPOSITION_SOURCE_ROOT:-$repo}
build=$(mktemp -d)
trap 'rm -rf "$build"' EXIT
mkdir -p "$build/inputs"
for name in CompositeReleaseDeployment NativeCompositionComponents; do
  cp "$source_root/host/src/main/java/dev/fcp/host/release/$name.java" "$build/inputs/"
done
sha256sum "$build"/inputs/*.java
find "$source_root/core/src/main/java" "$source_root/business/src/main/java" "$source_root/host/src/main/java" -name '*.java' ! -name CompositeReleaseDeployment.java ! -name NativeCompositionComponents.java > "$build/sources"
javac --release 17 -d "$build/classes" @"$build/sources" "$build"/inputs/*.java "$repo/tests/release-composition/CompositeReleaseTest.java" "$repo/tests/release-composition/RetireStoppedTest.java"
java -cp "$build/classes" CompositeReleaseTest

java -cp "$build/classes" RetireStoppedTest
