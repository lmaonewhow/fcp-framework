#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
build="$(mktemp -d /tmp/fcp-plugin-host-tests.XXXXXX)"
trap 'rm -rf "$build"' EXIT
mkdir -p "$build/classes" "$build/session"
mapfile -t sources < <(find core/src/main/java business/src/main/java host/src/main/java -name '*.java' | sort)
javac --release 17 -encoding UTF-8 -d "$build/classes" "${sources[@]}" tests/frontend-runtime/UiRoutePolicyTest.java
java -cp "$build/classes" UiRoutePolicyTest
"${TSC:-tsc}" integration/frontend-shared/session.ts --target ES2022 --module ES2022 --lib ES2022,DOM --strict --outDir "$build/session"
printf '{"type":"module"}\n' > "$build/session/package.json"
node tests/js/session.test.mjs "$build/session/session.js"
printf '\nComponent tests only. Real Spring/login/database/browser: NOT_RUN by this command.\n'
