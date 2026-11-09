// jsdom has no rendering loop. Supply its missing browser scheduling API only;
// Vue and ElementPlus remain real components, with no layout/visual claims.
import { afterEach } from 'vitest';
const frames = new Set();
globalThis.requestAnimationFrame = callback => { const token = setTimeout(() => { frames.delete(token); callback(performance.now()); }, 0); frames.add(token); return token; };
globalThis.cancelAnimationFrame = token => { clearTimeout(token); frames.delete(token); };
afterEach(() => { for (const token of frames) clearTimeout(token); frames.clear(); });
