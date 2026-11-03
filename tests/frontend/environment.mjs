import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('../../', import.meta.url));
const app = process.env.FCP_ADMIN_PROJECT || path.join(root, 'work/direct-build/upstream/admin');
const tools = process.env.FCP_COMPONENT_TOOLS || path.join(root, '../toolchain/component-tests');
const appRequire = createRequire(path.join(app, 'package.json'));
const toolsRequire = createRequire(path.join(tools, 'package.json'));
const { populateGlobal } = await import(pathToFileURL(appRequire.resolve('vitest/environments')));
const { JSDOM } = await import(pathToFileURL(toolsRequire.resolve('jsdom')));

export default {
  name: 'fcp-isolated-component-dom',
  transformMode: 'web',
  setup(global) {
    const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' });
    const { keys, originals } = populateGlobal(global, dom.window, { bindFunctions: true });
    return {
      teardown(global) {
        dom.window.close();
        keys.forEach(key => delete global[key]);
        originals.forEach((value, key) => { global[key] = value; });
      }
    };
  }
};
