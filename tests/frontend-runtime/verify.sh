#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
compiler="${TSC:-tsc}"
if ! command -v "$compiler" >/dev/null 2>&1 && [[ ! -f "$compiler" ]]; then
  echo 'BLOCKED: TypeScript compiler unavailable. Set TSC to typescript/bin/tsc or install the official TypeScript package.' >&2
  exit 2
fi
build="$(mktemp -d /tmp/fcp-frontend-runtime.XXXXXX)"
trap 'rm -rf "$build"' EXIT
"$compiler" --strict --target ES2022 --module commonjs --lib ES2022,DOM --outDir "$build" integration/frontend-runtime/index.ts
FCP_UI_MODULE="$build/index.js" node --test tests/frontend-runtime/runtime.test.mjs
printf '\nSource identity:\n'
git rev-parse HEAD
sha256sum integration/frontend-runtime/index.ts tests/frontend-runtime/runtime.test.mjs
printf '\nNative Vue, browser and server authorization acceptance: NOT_RUN by this suite.\n'
