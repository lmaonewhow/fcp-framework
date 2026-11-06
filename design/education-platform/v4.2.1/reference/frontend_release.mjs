/** Pure release policy helpers, not a UI framework or production authentication layer.
 * Values must come from the authenticated bootstrap endpoint. Browser claims never
 * choose the tenant or grant permission. First version explicitly disables SW/offline writes.
 */
const identity = value => typeof value === 'string' && value.length > 0 && Array.from(value).length <= 100 && !/[\u0000-\u001f\u007f]/u.test(value);
const record = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const digest = value => typeof value === 'string' && /^[0-9a-f]{64}$/.test(value);
export function transition(boot, page, state = {}) {
  if (!state || typeof state !== 'object' || Array.isArray(state)) return 'BLOCK_INVALID_CONFIG';
  const { dirty = false, hasServiceWorker = false, unresolvedWrite = false } = state;
  if (!record(boot) || !record(page) || !digest(boot.frontendDigest) || !digest(page.frontendDigest) ||
      !Array.isArray(boot.supportedFrontendDigests) || boot.supportedFrontendDigests.length === 0 ||
      boot.supportedFrontendDigests.some(x => !digest(x)) || !boot.supportedFrontendDigests.includes(boot.frontendDigest) ||
      new Set(boot.supportedFrontendDigests).size !== boot.supportedFrontendDigests.length ||
      typeof boot.writesAllowed !== 'boolean' || typeof dirty !== 'boolean' ||
      typeof hasServiceWorker !== 'boolean' || typeof unresolvedWrite !== 'boolean') return 'BLOCK_INVALID_CONFIG';
  if (hasServiceWorker) return 'BLOCK_UNSUPPORTED_SW';
  // Unknown write outcome must be reconciled by the original operation ID before any replay/reload.
  if (unresolvedWrite) return 'RECONCILE_FIRST';
  if (!boot.writesAllowed) return dirty ? 'READ_ONLY_KEEP_DRAFT' : 'READ_ONLY';
  if (boot.supportedFrontendDigests.includes(page.frontendDigest)) return 'KEEP_PAGE';
  return dirty ? 'PROMPT_SAVE_OR_DISCARD' : 'RELOAD_SAFE';
}
export function operationForRetry(original, currentContext) {
  if (!record(original) || !record(currentContext) ||
      !identity(original.tenant) || !identity(original.actor) ||
      !identity(currentContext.tenant) || !identity(currentContext.actor) ||
      original.tenant !== currentContext.tenant ||
      original.actor !== currentContext.actor || currentContext.authorized !== true ||
      typeof original.operationId !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(original.operationId)) throw new Error('RETRY_REQUIRES_REAUTH');
  return original.operationId;
}
