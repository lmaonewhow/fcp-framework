#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${FCP_VUE_ROOT:?Actual Vue/vue-router/Vite node_modules required}"
tmp=$(mktemp -d);trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/source/runtime"
cp integration/frontend-shared/*.{ts,vue} "$tmp/source/"
cp integration/frontend-runtime/*.ts "$tmp/source/runtime/"
ln -s "$FCP_VUE_ROOT" "$tmp/node_modules"
cat > "$tmp/vite.config.mjs" <<CONFIG
export default {build:{lib:{entry:'$tmp/source/usePlugins.ts',formats:['es'],fileName:()=> 'usePlugins.mjs'},rollupOptions:{external:['vue','vue-router']},outDir:'$tmp/dist',emptyOutDir:true}};
CONFIG
python3 - "$tmp" "$FCP_VUE_ROOT" <<'PYCONFIG'
import json,sys
root,modules=sys.argv[1:]
config={'compilerOptions':{'strict':True,'noEmit':True,'target':'ES2022','module':'ESNext','moduleResolution':'Bundler','lib':['ES2022','DOM'],'paths':{'vue':[modules+'/vue'],'vue-router':[modules+'/vue-router']},'skipLibCheck':True},'include':[root+'/source/PluginWorkspace.vue',root+'/source/usePlugins.ts',root+'/source/env.d.ts']}
open(root+'/tsconfig.json','w').write(json.dumps(config))
PYCONFIG
"$FCP_VUE_ROOT/.bin/vue-tsc" -p "$tmp/tsconfig.json"
"$FCP_VUE_ROOT/.bin/vite" build --config "$tmp/vite.config.mjs"
cp tests/plugin-business-ui/composable.test.mjs "$tmp/composable.test.mjs"
FCP_PLUGIN_COMPOSABLE="file://$tmp/dist/usePlugins.mjs" node --test "$tmp/composable.test.mjs"

node tests/frontend-runtime/verify-vue-host.mjs "$(dirname "$FCP_VUE_ROOT")"
node --experimental-strip-types --test tests/frontend-runtime/runtime.test.mjs
