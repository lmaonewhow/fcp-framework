import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('../../', import.meta.url));
const app = process.env.FCP_ADMIN_PROJECT || path.join(root, 'work/direct-build/upstream/admin');
const tools = process.env.FCP_COMPONENT_TOOLS || path.join(root, '../toolchain/component-tests');
const appRequire = createRequire(path.join(app, 'package.json'));
const { defineConfig } = await import(pathToFileURL(appRequire.resolve('vitest/config')));
const { default: vue } = await import(pathToFileURL(appRequire.resolve('@vitejs/plugin-vue')));

export default defineConfig({
  root: app,
  plugins: [vue()],
  // Derived education/roster configs share these owned test/overlay files.
  // Keep Vite's strict file boundary, with explicit paths instead of fs.strict=false.
  server: { fs: { allow: [app, path.join(root, 'tests'), path.join(root, 'integration'), tools] } },
  resolve: {
    alias: [
      { find: '@', replacement: path.join(app, 'src') },
      { find: /^vue$/, replacement: path.join(app, 'node_modules/vue/dist/vue.esm-bundler.js') },
      { find: /^vue-router$/, replacement: path.join(app, 'node_modules/vue-router/dist/vue-router.mjs') },
      { find: /^@vue\/test-utils$/, replacement: path.join(tools, 'node_modules/@vue/test-utils/dist/vue-test-utils.esm-bundler.mjs') }
    ]
  },
  test: {
    include: [path.join(root, 'tests/frontend/**/*.test.mjs').replaceAll('\\', '/')],
    environment: './' + path.relative(app, path.join(root, 'tests/frontend/environment.mjs')).replaceAll('\\', '/'),
    setupFiles: [path.join(root, 'tests/frontend/setup.mjs')],
    maxWorkers: 1,
    minWorkers: 1,
    server: { deps: { inline: [/@vue\//, /vue/, /vue-types/] } }
  }
});
