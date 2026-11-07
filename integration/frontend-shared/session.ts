import { clearTraceNotice, readResponseJson, responseError } from './trace.js';
/** Both shells are trusted same-origin applications. No token is sent in URLs or window messages. */
export const TOKEN_KEY = 'FCP-Host-Token';
export const readToken = (): string => localStorage.getItem(TOKEN_KEY) || '';
export const writeToken = (value: string): void => {
  if (value !== readToken()) clearTraceNotice();
  if (!value) { localStorage.removeItem(TOKEN_KEY); return; }
  localStorage.setItem(TOKEN_KEY, value);
};
export const clearToken = (): void => { clearTraceNotice(); localStorage.removeItem(TOKEN_KEY); };

/** Preserve Soybean's storage API/types; intercept only token, including every native caller. */
export function bridgeStorage<T extends object>(storage: T): T {
  return new Proxy(storage, {
    get(target, prop, receiver) {
      const original = Reflect.get(target, prop, receiver);
      if (typeof original !== 'function') return original;
      return (...args: unknown[]) => {
        if (args[0] === 'token') {
          if (prop === 'get') return readToken() || null;
          if (prop === 'set') {
            if (typeof args[1] !== 'string') throw new TypeError('Token must be a string');
            writeToken(args[1]); return;
          }
          if (prop === 'remove') { clearToken(); return; }
        }
        if (prop === 'clear') clearToken();
        return Reflect.apply(original, target, args);
      };
    }
  });
}

export type Shell = 'admin' | 'teacher';
export async function ensureShell(shell: Shell, clientId: string): Promise<boolean> {
  if (!clientId || !readToken()) throw new Error('请先完成宿主登录');
  const response = await fetch('/fcp/ui/session', {
    method: 'GET', cache: 'no-store', credentials: 'same-origin',
    headers: { Authorization: `Bearer ${readToken()}`, clientid: clientId }
  });
  const body = await readResponseJson(response);
  if (!response.ok || body?.code !== 200) throw responseError('无法取得服务器界面授权', {headers: response.headers, data: body});
  const allowed: unknown = body.data?.allowedShells;
  if (!Array.isArray(allowed)) throw new Error('界面授权响应无效');
  if (allowed.includes(shell)) return false;
  const home = body.data?.home;
  const page = ['/admin/fcp/plugins','/teacher/fcp/plugins'].includes(home) ? 'plugins' : ['/admin/fcp/ingestion','/teacher/fcp/ingestion'].includes(home) ? 'ingestion' : 'school';
  // Server chooses among fixed internal destinations; never follow an arbitrary URL in a response.
  if (allowed.includes('teacher')) {
    window.location.replace(`/teacher/fcp/${page}`); return true;
  }
  if (allowed.includes('admin')) {
    window.location.replace(`/admin/fcp/${page}`); return true;
  }
  throw new Error('当前账号未获授权访问工作台');
}

let syncInstalled = false;
export function installSessionSync(): void {
  if (syncInstalled) return;
  syncInstalled = true;
  window.addEventListener('storage', (event: StorageEvent) => {
    if ((event.key === TOKEN_KEY || event.key === null) && event.storageArea === localStorage) window.location.reload();
  });
}
