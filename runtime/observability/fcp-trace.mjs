// Node stdlib correlation only. Install in a trusted plugin package as needed; no exporter.
import { AsyncLocalStorage } from 'node:async_hooks';
import { randomBytes } from 'node:crypto';
const context = new AsyncLocalStorage();
export function withTrace(traceparent, action) {
  const match = typeof traceparent === 'string' && traceparent.length === 55
    ? /^00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}$/.exec(traceparent) : null;
  const valid = match && !/^0+$/.test(match[1]) && !/^0+$/.test(match[2]);
  return context.run(Object.freeze({ traceId: valid ? match[1] : randomBytes(16).toString('hex'),
    spanId: randomBytes(8).toString('hex'), parentSpanId: valid ? match[2] : '' }), action);
}
export function currentTrace() { return context.getStore(); }
export function outboundHeaders() { const t = currentTrace(); return t ? { traceparent: `00-${t.traceId}-${t.spanId}-00` } : {}; }
