#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
node --experimental-strip-types --test tests/plugin-release-ui/controller.test.mjs tests/plugin-release-ui/permission-host.test.mjs
tsc integration/frontend-shared/pluginReleaseManager.ts --strict --noEmit --target ES2022 --module ES2022 --lib ES2022,DOM
# Full component type checking requires Vue and vue-tsc; do not substitute stubs.
: "${FCP_VUE_TOOLS:?Set FCP_VUE_TOOLS to a node_modules directory containing real Vue and vue-tsc}"
tmp=$(mktemp -d);trap 'rm -rf "$tmp"' EXIT
python3 - "$PWD" "$FCP_VUE_TOOLS" "$tmp/tsconfig.json" <<'PY'
import sys,json
root,modules,target=sys.argv[1:]
config={'compilerOptions':{'strict':True,'noEmit':True,'target':'ES2022','module':'ESNext','moduleResolution':'Bundler','lib':['ES2022','DOM'],'baseUrl':root,'paths':{'vue':[modules+'/vue'],'@/fcp/*':['integration/frontend-shared/*']},'skipLibCheck':True},'include':[root+'/integration/frontend-shared/PluginReleaseManager.vue',root+'/integration/frontend-shared/pluginReleaseManager.ts',root+'/integration/frontend-release/ReleaseConsole.vue']}
open(target,'w').write(json.dumps(config))
PY
"$FCP_VUE_TOOLS/.bin/vue-tsc" -p "$tmp/tsconfig.json"
