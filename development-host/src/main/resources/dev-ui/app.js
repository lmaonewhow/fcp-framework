import { clearTraceNotice, readResponseJson, responseError } from './trace.js';
import { UiRuntime } from './runtime.js';
const $ = id => document.getElementById(id);
const text = (tag, value) => { const n = document.createElement(tag); n.textContent = value; return n; };
const emptyIdentity = () => ({ userId: null, tenantId: null, language: 'zh-CN', permissions: [] });
let token = '', epoch = 0, identity = emptyIdentity(), busy = false, pending = null, plan = null, preview = null;
let revision = -1, catalog = { pages: [], slots: [] }, fields = {}, results = {};
const requests = new Set();
const components = ['text@1', 'field@1', 'button@1', 'table@1', 'stack@1'];
const runtime = new UiRuntime({
  name: 'development-shell', version: '1.0', upstreamVersion: 'standalone', components,
  slots: { 'dashboard.status': { components, inputSchema: {}, outputSchema: {} } },
  context: { get: () => identity, subscribe: () => () => {} },
  replaceCatalog(next) {
    const old = [...catalog.pages, ...catalog.slots];
    for (const item of old) if (JSON.stringify(item) !== JSON.stringify([...next.pages, ...next.slots].find(p => p.id === item.id))) {
      delete fields[item.id]; delete results[item.id];
    }
    catalog = next;
  },
  currentPath: () => location.hash.slice(1), restoreDeepLink: () => {}, render: renderNode,
  clearCaches() { fields = {}; results = {}; pending = null; updatePending(); },
  notifyDirty() { $('notice').textContent = '页面已停用或变更，未保存内容已清除。'; },
  invoke: (capability, input, options) => api(`/fcp/capabilities/${encodeURIComponent(capability)}/invoke`, {
    input, expectedRevision: options.revision, idempotencyKey: options.idempotencyKey
  }, {}, options.signal)
});
function clearIdentity() {
  clearTraceNotice();
  epoch++; for (const controller of requests) controller.abort(); requests.clear();
  token = ''; identity = emptyIdentity(); plan = null; preview = null; revision = -1; $('approve').checked = false;
  runtime.resetIdentity(); $('token').value = ''; $('workspace').hidden = true; $('logout').hidden = true;
  $('login').hidden = false; $('identity').textContent = ''; $('plan').hidden = true;
  for (const id of ['plugins', 'pages', 'page', 'slots', 'operation', 'exchange-preview', 'exchange-result']) $(id).replaceChildren();
  $('package').value = ''; $('csv').value = ''; $('trusted').checked = false;
  $('exchange-approve').checked = false; $('exchange-config').value = ''; $('export-fields').value = '';
}
async function api(path, body, extra = {}, externalSignal) {
  const mine = epoch, controller = new AbortController(); requests.add(controller);
  const abort = () => controller.abort(); externalSignal?.addEventListener('abort', abort, { once: true });
  try {
    const binary = body instanceof ArrayBuffer || body instanceof Uint8Array;
    const response = await fetch(path, { method: body === undefined ? 'GET' : 'POST', credentials: 'omit', cache: 'no-store',
      headers: { Authorization: `Bearer ${token}`, ...(body === undefined ? {} : { 'Content-Type': binary ? 'application/octet-stream' : 'application/json' }), ...extra },
      body: body === undefined ? undefined : binary ? body : JSON.stringify(body), signal: controller.signal });
    if (mine !== epoch) throw new Error('身份已变更，丢弃旧响应');
    if (response.status === 401) { clearIdentity(); throw responseError('开发身份无效或已撤销', {headers: response.headers}); }
    const envelope = await readResponseJson(response);
    if (mine !== epoch) throw new Error('身份已变更，丢弃旧响应');
    if (!response.ok || envelope?.code !== 200) throw responseError(`${envelope?.data?.fcpCode || response.status}: ${envelope?.msg || '请求被拒绝'}`, {headers: response.headers, data: envelope});
    return envelope.data;
  } finally { requests.delete(controller); externalSignal?.removeEventListener('abort', abort); }
}
async function run(action) {
  if (busy) return;
  busy = true; $('error').textContent = ''; updateControls();
  try { await action(); } catch (e) { if (e.name !== 'AbortError') $('error').textContent = e.message; }
  finally { busy = false; updateControls(); }
}
function updateControls() {
  for (const b of $('workspace').querySelectorAll('button, input')) b.disabled = busy || Boolean(pending);
  $('retry').disabled = busy; $('abandon').disabled = busy;
  $('confirm-plan').disabled = busy || Boolean(pending) || !plan || !$('approve').checked;
}
function updatePending() { $('pending').hidden = !pending; updateControls(); }
async function mutate(send, accept) {
  if (pending) throw new Error('请先核对待确认操作');
  pending = { send, accept, epoch }; updatePending(); await sendPending();
}
async function sendPending() {
  const attempt = pending;
  if (!attempt || attempt.epoch !== epoch) return;
  const value = await attempt.send();
  if (attempt.epoch !== epoch || pending !== attempt) return;
  attempt.accept(value); pending = null; updatePending(); await refresh();
}
async function refresh() {
  if (!token) return;
  const session = await api('/fcp/session');
  $('exchange').hidden = !session.exchangeEnabled;
  const response = await api('/fcp/ui/catalog');
  const c = response.context;
  if (!c || typeof c.userId !== 'string' || typeof c.tenantId !== 'string' || !Array.isArray(c.permissions) || c.permissions.some(p => typeof p !== 'string')) throw new Error('无效的身份响应');
  if (JSON.stringify(identity) !== JSON.stringify(c)) {
    runtime.resetIdentity(); identity = c; plan = null; preview = null; $('approve').checked = false; $('plan').hidden = true;
    $('exchange-preview').textContent = ''; $('exchange-approve').checked = false;
  }
  // Grant changes can alter a projected catalog without changing composition generation.
  if (response.catalog.revision === catalog.revision && JSON.stringify(response.catalog) !== JSON.stringify(catalog)) runtime.resetIdentity();
  runtime.applyRevision(response.catalog);
  $('identity').textContent = `开发身份：${identity.userId} / 租户：${identity.tenantId} / 目录：${catalog.revision}`;
  $('workspace').hidden = false; $('logout').hidden = false; $('login').hidden = true;
  $('upload').hidden = !identity.permissions.includes('install');
  renderCatalog();
  const state = await api('/fcp/plugins'); revision = state.revision;
  $('plugins').replaceChildren();
  for (const plugin of state.plugins) {
    const row = text('div', `${plugin.pluginId} · ${plugin.version} · ${plugin.effective}`);
    for (const action of identity.permissions.includes('manage') ? ['enable', 'disable'] : []) row.append(button(action === 'enable' ? '预览启用' : '预览停用', () => run(async () => {
      plan = null; $('approve').checked = false; $('plan').hidden = true;
      const value = await api(`/fcp/plugins/${encodeURIComponent(plugin.pluginId)}/plans`, { action, expectedRevision: revision });
      if (!value.planId || !value.digest || !Array.isArray(value.affectedPlugins) || !value.affectedPlugins.length) throw new Error('不完整的计划，拒绝确认');
      plan = value; $('plan-details').textContent = JSON.stringify(value, null, 2); $('approve').checked = false; $('plan').hidden = false;
    })));
    $('plugins').append(row);
  }
}
function button(label, handler) { const b = text('button', label); b.type = 'button'; b.addEventListener('click', handler); return b; }
function renderCatalog() {
  $('pages').replaceChildren(); $('slots').replaceChildren(); $('page').replaceChildren();
  for (const page of catalog.pages) $('pages').append(button(page.title, () => { if (!pending && !busy) location.hash = page.path; }));
  for (const slot of catalog.slots.filter(s => s.slotId === 'dashboard.status')) $('slots').append(runtime.render(slot.id));
  const selected = catalog.pages.find(p => p.path === location.hash.slice(1));
  if (selected) $('page').append(text('h3', selected.title), runtime.render(selected.id));
  else $('page').append(text('p', location.hash ? '该页面不存在、已停用或无权访问。' : '请选择一个已授权插件页面。'));
}
function renderNode(node, owner) {
  if (node.type === 'stack') { const container = document.createElement('div'); for (const child of node.children || []) container.append(renderNode(child, owner)); return container; }
  if (node.type === 'text') return text('p', node.text || '');
  if (node.type === 'field') {
    const label = text('label', node.text || node.field || ''), input = document.createElement('input');
    input.value = fields[owner]?.[node.field] || ''; input.disabled = busy || Boolean(pending);
    input.addEventListener('input', () => { (fields[owner] ||= {})[node.field] = input.value; if (catalog.pages.some(p => p.id === owner)) runtime.markDirty(owner); });
    label.append(input); return label;
  }
  if (node.type === 'button') return button(node.text || '执行', () => run(async () => {
    const input = { ...(fields[owner] || {}) }, key = crypto.randomUUID();
    await mutate(() => runtime.invoke(owner, node.capabilityId, input, { idempotencyKey: key }), value => { results[owner] = value; });
  }));
  const table = document.createElement('table'); table.append(text('caption', node.text || '调用结果'));
  for (const [key, value] of Object.entries(results[owner] || {})) { const row = document.createElement('tr'); row.append(text('th', key), text('td', typeof value === 'string' ? value : JSON.stringify(value))); table.append(row); }
  return table;
}
$('login').addEventListener('submit', event => { event.preventDefault(); const candidate = $('token').value; clearIdentity(); token = candidate; run(refresh); });
$('logout').addEventListener('click', clearIdentity);
$('refresh').addEventListener('click', () => run(refresh));
$('upload').addEventListener('submit', event => { event.preventDefault(); run(async () => {
  const file = $('package').files[0]; if (!file || !file.size || file.size > 64 * 1024 * 1024 || !$('trusted').checked) throw new Error('请选择不超过 64 MiB 的可信插件包并确认');
  const bytes = await file.arrayBuffer(), digest = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)), x => x.toString(16).padStart(2, '0')).join('');
  const headers = { 'X-Fcp-Digest': digest, 'X-Fcp-Revision': String(revision), 'Idempotency-Key': crypto.randomUUID() };
  await mutate(() => api('/fcp/packages', bytes, headers), value => { $('notice').textContent = `安装回执：${value.pluginId} ${value.state}；尚未自动授权或启用`; $('package').value = ''; $('trusted').checked = false; });
}); });
$('confirm-plan').addEventListener('click', () => run(async () => {
  if (!plan || !$('approve').checked) throw new Error('请确认完整影响范围');
  const candidate = plan, body = { digest: candidate.digest, affectedPlugins: [...candidate.affectedPlugins], idempotencyKey: crypto.randomUUID() };
  await mutate(() => api(`/fcp/plans/${encodeURIComponent(candidate.planId)}/confirm`, body), value => { $('operation').textContent = JSON.stringify(value, null, 2); plan = null; $('approve').checked = false; $('plan').hidden = true; });
}));
$('cancel-plan').addEventListener('click', () => { plan = null; $('approve').checked = false; $('plan').hidden = true; updateControls(); });
$('approve').addEventListener('change', updateControls);
$('retry').addEventListener('click', () => run(sendPending));
$('abandon').addEventListener('click', () => { if (busy) return; pending = null; updatePending(); $('notice').textContent = '已放弃重试信息；这不会撤销服务端可能完成的操作，请刷新核对。'; });
const config = () => { const value = $('exchange-config').value; if (!/^[a-zA-Z0-9._-]{1,128}$/.test(value)) throw new Error('请输入已配置的交换 ID'); return encodeURIComponent(value); };
async function showPreview(apiSource) {
  preview = null; $('exchange-approve').checked = false; $('exchange-preview').textContent = '';
  let body = {};
  if (!apiSource) { const file = $('csv').files[0]; if (!file || file.size > 128 * 1024) throw new Error('请选择不超过 128 KiB 的 CSV'); body = await file.arrayBuffer(); }
  preview = await api(`/fcp/exchange/configs/${config()}/${apiSource ? 'preview-api' : 'preview'}`, body);
  $('exchange-preview').textContent = JSON.stringify(preview, null, 2);
}
$('preview-file').addEventListener('click', () => run(() => showPreview(false)));
$('preview-api').addEventListener('click', () => run(() => showPreview(true)));
$('exchange-config').addEventListener('input', () => { preview = null; $('exchange-preview').textContent = ''; $('exchange-approve').checked = false; });
$('csv').addEventListener('change', () => { preview = null; $('exchange-preview').textContent = ''; $('exchange-approve').checked = false; });
$('confirm-exchange').addEventListener('click', () => run(async () => {
  if (!preview || !$('exchange-approve').checked || !preview.previewId || !preview.digest) throw new Error('请先预览并确认摘要');
  const candidate = preview, body = { digest: candidate.digest, idempotencyKey: crypto.randomUUID() };
  await mutate(() => api(`/fcp/exchange/previews/${encodeURIComponent(candidate.previewId)}/confirm`, body), value => { $('exchange-result').textContent = JSON.stringify(value, null, 2); preview = null; $('exchange-approve').checked = false; });
}));
$('export').addEventListener('click', () => run(async () => {
  const fields = $('export-fields').value.split(',').map(v => v.trim()).filter(Boolean);
  const value = await api(`/fcp/exchange/configs/${config()}/exports`, { fields });
  $('exchange-result').textContent = JSON.stringify(value, null, 2);
  if (typeof value.handle !== 'string' || !/^[a-zA-Z0-9-]+$/.test(value.handle)) throw new Error('无效下载句柄');
  const mine = epoch, controller = new AbortController(); requests.add(controller);
  try {
    const response = await fetch(`/fcp/exchange/downloads/${encodeURIComponent(value.handle)}`, { headers: { Authorization: `Bearer ${token}` }, credentials: 'omit', cache: 'no-store', signal: controller.signal });
    if (mine !== epoch) throw new Error('身份已变化，已丢弃下载');
    if (!response.ok) throw responseError('下载被拒绝，请检查当前授权', {headers: response.headers});
    const blob = await response.blob(); if (mine !== epoch) throw new Error('身份已变化，已丢弃下载');
    const url = URL.createObjectURL(blob), link = document.createElement('a'); link.href = url; link.download = 'fcp-fixture-export.csv'; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
    $('notice').textContent = '已下载当前身份授权的导出。';
  } finally { requests.delete(controller); }
}));
window.addEventListener('hashchange', () => { if (pending || busy) { $('error').textContent = '待确认请求仍保留；请先核对后再操作。'; } renderCatalog(); });
window.addEventListener('beforeunload', event => { if (pending) { event.preventDefault(); event.returnValue = ''; } });
window.addEventListener('pagehide', clearIdentity);
setInterval(() => { if (token && !busy && !pending) run(refresh); }, 5000);
