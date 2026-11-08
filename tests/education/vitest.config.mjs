import base from '../frontend/vitest.config.mjs';
import path from 'node:path';
// Resolve ElementPlus from the actual locked native admin, not an independent UI fixture.
export default { ...base, resolve: { ...base.resolve, alias: [
  ...base.resolve.alias.map(alias => alias.find?.source === '^vue-router$' ? { ...alias, replacement: path.join(base.root, 'node_modules/vue-router/dist/vue-router.js') } : alias),
  { find: /^element-plus$/, replacement: path.join(base.root, 'node_modules/element-plus/es/index.mjs') }
] } };
