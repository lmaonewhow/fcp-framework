/** Response-only diagnostics. No request data, persistence, telemetry, timers or network calls. */
export interface TraceResponse { headers?: unknown; data?: unknown }
export function validTraceId(value: unknown): string {
  return typeof value === 'string' && /^[0-9a-f]{32}$/i.test(value) && !/^0{32}$/.test(value) ? value.toLowerCase() : '';
}
const binaryTraces = new WeakMap<object, string>();
/** Keep only the response ID when the native download wrapper returns a Blob. */
export function rememberResponseTrace<T extends object>(value: T, response: TraceResponse): T {
  const id = responseTraceId(response);
  if (id) binaryTraces.set(value, id);
  return value;
}
export function responseTraceId(response?: TraceResponse | null): string {
  if (!response) return '';
  const headers = response.headers as {get?: (key: string) => unknown; [key: string]: unknown} | undefined;
  const header = headers && (typeof headers.get === 'function' ? headers.get('X-Trace-Id') : headers['x-trace-id'] ?? headers['X-Trace-Id']);
  const body = response.data as {traceId?: unknown} | null | undefined;
  return validTraceId(header) || validTraceId(body?.traceId) || (body && typeof body === 'object' ? binaryTraces.get(body) || '' : '');
}
let panel: HTMLElement | undefined;
/** One bounded, selectable diagnostic. It never repeats the server's message/body or takes focus. */
export function clearTraceNotice(): void { panel?.remove(); panel = undefined; }
export function showTraceNotice(value: unknown): void {
  const id = validTraceId(value);
  if (!id || typeof document === 'undefined' || !document.body) return;
  if (panel?.isConnected && panel.dataset.traceId === id) return;
  if (!panel?.isConnected) {
    panel = document.createElement('aside');
    panel.dataset.testid = 'request-trace';
    panel.setAttribute('aria-label', '最近一次请求错误的排障编号');
    panel.style.cssText = 'position:fixed;bottom:12px;right:12px;z-index:3000;max-width:calc(100vw - 24px);padding:12px;background:#fff;color:#243047;border:1px solid #aab7ca;border-radius:8px;box-shadow:0 2px 8px #0002;font:14px sans-serif;overflow-wrap:anywhere';
    document.body.append(panel);
  }
  panel.dataset.traceId = id;
  panel.replaceChildren();
  const label = document.createElement('div'); label.textContent = '最近一次请求错误 · Trace ID';
  const code = document.createElement('code'); code.textContent = id; code.style.userSelect = 'all';
  const copy = document.createElement('button'); copy.type = 'button'; copy.textContent = '复制 Trace ID';
  copy.onclick = async () => {
    try { await navigator.clipboard.writeText(id); copy.textContent = '已复制'; }
    catch { copy.textContent = '请选中编号手动复制'; }
  };
  const close = document.createElement('button'); close.type = 'button'; close.textContent = '关闭'; close.onclick = clearTraceNotice;
  panel.append(label, code, document.createElement('br'), copy, close);
}
export function withResponseTrace<T extends Error>(error: T, response?: TraceResponse | null): T & {traceId?: string} {
  const id = responseTraceId(response);
  if (id) {
    Object.assign(error, {traceId: id});
    if (!error.message.includes(`Trace ID: ${id}`)) error.message += ` (Trace ID: ${id})`;
    showTraceNotice(id);
  }
  return error;
}
export function responseError(message: string, response?: TraceResponse | null): Error & {traceId?: string} {
  return withResponseTrace(new Error(message), response);
}
/** Keep the header even for an HTML gateway error or malformed JSON. */
export async function readResponseJson(response: Response): Promise<any> {
  try { return await response.json(); }
  catch { throw responseError('宿主响应不是有效 JSON', {headers: response.headers}); }
}
