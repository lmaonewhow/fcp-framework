#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
# Caller selects its own JDK 17+, Python and Node toolchain. No machine setup is changed.
OUT="${1:-reports/runtime-crash/$(date -u +%Y%m%dT%H%M%SZ)-$$}"
mkdir -p "$OUT"
OUT="$(realpath "$OUT")"
BUILD="$(mktemp -d)"
trap 'rm -rf "$BUILD"' EXIT
exec > >(tee "$OUT/run.log") 2>&1
printf 'UTC: '; date -u +%FT%TZ
printf 'Command: bash tests/runtime-crash/run.sh %q\n' "$OUT"
printf 'Baseline: '; git rev-parse HEAD
printf 'Branch: '; git branch --show-current
git status --porcelain > "$OUT/worktree-status.txt"
java -version
javac -version
python3 --version
node --version
printf 'OS: '; uname -a
find core/src/main/java tests/runtime-crash tests/runtime-lifecycle/plugins -type f \( -name '*.java' -o -name '*.py' -o -name '*.mjs' -o -name '*.sh' \) -print0 | sort -z | xargs -0 sha256sum > "$OUT/source-sha256.txt"
printf 'Compile command: javac -g --release 17 --add-modules jdk.jdi -d BUILD core/src/main/java/**/*.java tests/runtime-crash/*.java\n'
javac -g --release 17 --add-modules jdk.jdi -d "$BUILD" $(find core/src/main/java -name '*.java') tests/runtime-crash/*.java
(cd "$BUILD"; find . -name '*.class' -print0 | sort -z | xargs -0 sha256sum) > "$OUT/artifact-sha256.txt"
java -cp "$BUILD" JournalConfigurationTest
CLASSPATH="$BUILD"
if [[ -n "${FCP_CRASH_TEST_DRIVER_CLASSPATH:-}" ]]; then
  CLASSPATH="$CLASSPATH:$FCP_CRASH_TEST_DRIVER_CLASSPATH"
  python3 - "$OUT" <<'PYDRIVER'
import hashlib,os,pathlib,sys
paths=[pathlib.Path(p) for p in os.environ['FCP_CRASH_TEST_DRIVER_CLASSPATH'].split(os.pathsep)]
if not paths or any(not p.is_file() or p.suffix!='.jar' for p in paths):
    raise SystemExit('Explicit crash-test driver classpath must contain existing JAR files only')
(pathlib.Path(sys.argv[1])/'driver-sha256.txt').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name+'\n' for p in paths))
PYDRIVER
fi
printf 'Execution: java --add-modules jdk.jdi -cp BUILD[:explicit-driver] CrashRecoveryTest run OUTPUT PYTHON NODE\n'
set +e
java --add-modules jdk.jdi -cp "$CLASSPATH" CrashRecoveryTest run "$OUT" "$(command -v python3)" "$(command -v node)"
STATUS=$?
sha256sum --check "$OUT/source-sha256.txt" > "$OUT/source-recheck.log"
RECHECK=$?
if (( RECHECK != 0 )); then printf 'FAIL: tested source files changed during the run\n'; STATUS=1; fi
set -e
printf '%s\n' "$STATUS" > "$OUT/exit-code.txt"
printf 'Exit code: %s\nEvidence: %s\n' "$STATUS" "$OUT"
exit "$STATUS"
