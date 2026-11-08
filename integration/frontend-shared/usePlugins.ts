import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue';
import type { Component } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { UiRuntime, UiError, createPlusUiAdapter, createSoybeanAdapter } from './runtime';
import type { Catalog, Identity, UiNode } from './runtime';
import { getReleaseAffinity, rememberReleaseContext } from './release-session';

/** Trusted shell bindings. Raw tokens never enter the declarative catalog/runtime. */
export interface NativePluginHost {
  shell: 'admin' | 'teacher';
  button: Component; input: Component; fieldValueProp: 'modelValue' | 'value'; fieldUpdateProp: 'onUpdate:modelValue' | 'onUpdate:value';
  /** Map accessible text onto the library's actual native input, never its wrapper. */
  fieldAttributes: (label: string) => Record<string, unknown>;
  request(path: string, method: 'GET' | 'POST', input: unknown, signal: AbortSignal, options?: {headers?: Record<string,string>}): Promise<unknown>;
  controlPermissions?(): readonly string[];
  identityKey(): string;
  /** Trusted shell principal: JSON.stringify([String(tenantId), String(userId)]), null on logout. No credentials. */
  principalKey?(): string | null;
  observePermissions?(changed: () => void): () => void;
  observeIdentity(changed: () => void): () => void;
}
interface PendingAction {identityOwner: string; ownerId: string; capabilityId: string; input: Record<string,string>; key: string; epoch: number; affinity: string | null}
interface CatalogResponse { catalog: Catalog; context: Identity; releaseId?: string; affinity?: string; projectionFingerprint?: string }
const blank = (): Catalog => ({contractVersion:'1.0',schemaVersion:1,revision:-1,pages:[],slots:[]});
const components = ['text@1','field@1','button@1','table@1','stack@1'];

export function usePlugins(host: NativePluginHost, render: (node: UiNode, ownerId: string) => unknown = node => node) {
  const route = useRoute(), router = useRouter();
  const catalog = shallowRef<Readonly<Catalog>>(blank()), error = ref(''), notice = ref(''), busy = ref(false);
  const fields = ref<Record<string, Record<string, string>>>({}), results = ref<Record<string, unknown>>({});
  const pending = ref<PendingAction | null>(null), uncertainty = ref(false);
  // Component-lifetime, payload-free tombstones. Never store credentials or previous-user input.
  const uncertainOwners = new Set<string>();
  let activeOwner: string | null = null;
  const ownerKey = (value:Identity) => value.userId && value.tenantId ? JSON.stringify([value.tenantId,value.userId]) : null;
  let identity: Identity = {userId:null,tenantId:null,language:'en',permissions:[]};
  const identityView = shallowRef<Identity>(identity);
  let alive = true, loading = false, timer: ReturnType<typeof setInterval> | undefined;
  let fetchController: AbortController | undefined;
  const identityListeners = new Set<() => void>();
  const clear = () => {
    if (pending.value) notice.value = '身份、权限或插件页面已变化，已清除本页待重试信息。上次操作可能已完成，请核对原操作结果，勿直接新建重复请求。';
    if (pending.value) uncertainOwners.add(pending.value.identityOwner);
    pending.value = null; fields.value = {}; results.value = {};
    uncertainty.value = activeOwner !== null && uncertainOwners.has(activeOwner);
  };
  function authorizationInvalidated() {
    fetchController?.abort(); identity = {userId:null,tenantId:null,language:'en',permissions:[]}; identityView.value = identity;
    for (const changed of identityListeners) changed();
  }
  function identityChanged() {
    // Legacy adapters cannot prove a different principal; retain the fence until a fresh server context does.
    const samePrincipal = !host.principalKey || (activeOwner !== null && host.principalKey() === activeOwner);
    authorizationInvalidated();
    if (!samePrincipal) { activeOwner = null; uncertainty.value = false; }
  }
  const stopPermissions = host.observePermissions?.(authorizationInvalidated) ?? (() => {});
  const stopIdentity = host.observeIdentity(identityChanged);
  const factory = host.shell === 'admin' ? createPlusUiAdapter : createSoybeanAdapter;
  const runtime = new UiRuntime(factory({
    components, slots: {'dashboard.status': {components, inputSchema:{}, outputSchema:{}}},
    context: {get: () => identity, subscribe: listener => {identityListeners.add(listener); return () => identityListeners.delete(listener);}},
    replaceCatalog: next => {
      // The pinned host owns one precompiled namespace route. A single ref swap commits
      // navigation, pages and reserved slot mounts together, never unrelated host menus.
      for (const page of next.pages) {
        const resolved = router.resolve(page.path);
        if (!resolved.matched.some(record => record.meta.fcpPluginContainer === true)) throw new UiError('VALIDATION', `Plugin route conflicts with host: ${page.path}`);
      }
      const previous = catalog.value;
      catalog.value = next;
      for (const old of [...previous.pages,...previous.slots]) if (JSON.stringify(old) !== JSON.stringify([...next.pages,...next.slots].find(p => p.id === old.id))) {delete fields.value[old.id]; delete results.value[old.id];}
      for (const id of Object.keys(fields.value)) if (![...next.pages,...next.slots].some(p => p.id === id)) delete fields.value[id];
      for (const id of Object.keys(results.value)) if (![...next.pages,...next.slots].some(p => p.id === id)) delete results.value[id];
    },
    currentPath: () => route.path,
    restoreDeepLink: path => { if (path) document.title = catalog.value.pages.find(p => p.path === path)?.title ?? 'FCP'; },
    request: async (capabilityId,input,options) => {
      const affinity = pending.value ? pending.value.affinity : await getReleaseAffinity();
      return host.request(`/fcp/capabilities/${encodeURIComponent(capabilityId)}/invoke`,'POST',{input,expectedRevision:options.revision,idempotencyKey:options.idempotencyKey},options.signal,{headers: affinity ? {'X-FCP-Release-Affinity':affinity} : {}});
    },
    render,
    notifyDirty: ids => { notice.value = `插件页面已停用或更新，未保存内容已清除：${ids.join('、')}。请勿继续提交旧表单。`; },
    clearCaches: clear
  }));
  function unwrap(raw: unknown): unknown {
    if (!raw || typeof raw !== 'object') throw new UiError('VALIDATION','无效的宿主响应');
    const body = raw as {code?:unknown;data?:unknown;error?:{code?:string;message?:string};msg?:string};
    const failed = host.shell === 'admin' ? body.code !== 200 : body.error != null;
    if (failed) {
      const code = String((body.error as {response?:{status?:number}} | undefined)?.response?.status ?? body.error?.code ?? body.code);
      if (code === '403') throw new UiError('FORBIDDEN','权限或发布上下文已失效，已清空插件页面。请核对未确认操作后手动刷新；不会自动重放。');
      throw new UiError(code === '401' ? 'UNAUTHENTICATED' : code === '403' ? 'FORBIDDEN' : 'VALIDATION',body.error?.message ?? body.msg ?? '宿主请求失败');
    }
    return body.data;
  }
  async function refresh() {
    if (!alive || loading) return;
    loading = true; const key = host.identityKey(); const controller = new AbortController(); fetchController = controller;
    try {
      const affinity = await getReleaseAffinity();
      const response = unwrap(await host.request('/fcp/ui/catalog','GET',undefined,controller.signal,{headers: affinity ? {'X-FCP-Release-Affinity':affinity} : {}})) as CatalogResponse;
      if (!alive || controller.signal.aborted || key !== host.identityKey()) return;
      if (response?.releaseId || response?.affinity) {
        if (typeof response.releaseId !== 'string' || typeof response.affinity !== 'string') throw new UiError('VALIDATION','发布上下文无效');
        if (!pending.value) await rememberReleaseContext({releaseId:response.releaseId,affinity:response.affinity});
        if (!alive || controller.signal.aborted || key !== host.identityKey()) return;
      }
      const context = response?.context;
      if (!context || typeof context.userId !== 'string' || typeof context.tenantId !== 'string' || typeof context.language !== 'string' || !Array.isArray(context.permissions) || context.permissions.some(p => typeof p !== 'string')) throw new UiError('VALIDATION','身份目录响应无效');
      const nextIdentity: Identity = {userId:context.userId,tenantId:context.tenantId,language:context.language,permissions:[...context.permissions].sort()};
      activeOwner = ownerKey(nextIdentity);
      if (JSON.stringify(identity) !== JSON.stringify(nextIdentity)) {
        for (const changed of identityListeners) changed();
        identity = nextIdentity; identityView.value = identity;
      }
      if (typeof response.projectionFingerprint !== 'string' || !/^[a-f0-9]{64}$/.test(response.projectionFingerprint)) { runtime.resetIdentity(); throw new UiError('VALIDATION','权限投影缺失或失效，请刷新页面重新确认。'); }
      runtime.applyProjection(response.catalog,response.projectionFingerprint);
      uncertainty.value = activeOwner !== null && uncertainOwners.has(activeOwner);
      error.value = '';
    } catch (failure) {
      if (!alive || controller.signal.aborted || key !== host.identityKey()) return;
      // Auth failures revoke immediately; invalid candidates never replace the valid snapshot.
      if (failure instanceof UiError && ['UNAUTHENTICATED','FORBIDDEN'].includes(failure.code)) authorizationInvalidated();
      error.value = failure instanceof Error ? failure.message : '插件目录加载失败';
    } finally { loading = false; if (fetchController === controller) fetchController = undefined; }
  }
  const selected = computed(() => catalog.value.pages.find(page => page.path === route.path));
  const isDeepLink = computed(() => route.path !== '/fcp/plugins' && route.path.startsWith('/fcp/plugins/'));
  function edit(id: string, field: string, value: string) {
    if (pending.value || uncertainty.value) { error.value = '请先重试或明确放弃待确认的操作，再修改输入。'; return; }
    if (![...catalog.value.pages,...catalog.value.slots].some(p => p.id === id)) return;
    (fields.value[id] ??= {})[field] = value;
    if (catalog.value.pages.some(p => p.id === id)) runtime.markDirty(id);
  }
  async function send(attempt: PendingAction) {
    if (busy.value || !alive) return;
    if (attempt.epoch !== runtime.identityEpoch) { clear(); return; }
    busy.value = true; error.value = '';
    try {
      const result = await runtime.invoke(attempt.ownerId,attempt.capabilityId,{...attempt.input},{idempotencyKey:attempt.key});
      if (!alive || pending.value?.key !== attempt.key || attempt.epoch !== runtime.identityEpoch) return;
      results.value[attempt.ownerId] = result; pending.value = null;
      if (catalog.value.pages.some(p => p.id === attempt.ownerId)) runtime.markDirty(attempt.ownerId,false);
    } catch (failure) {
      // No automatic replay. Preserve the original key and exact input unless the
      // identity epoch was revoked. A failed network response does not prove no write.
      if (alive && attempt.identityOwner === activeOwner && attempt.epoch === runtime.identityEpoch) error.value = failure instanceof Error ? failure.message : '能力调用结果未确认';
    } finally { if (alive) busy.value = false; }
  }
  async function invoke(id: string, capabilityId: string) {
    if (busy.value) return;
    if (pending.value || uncertainty.value) {error.value = '上次操作结果尚未确认，请明确重试同一请求或放弃后再开始新操作。'; return;}
    const epoch = runtime.identityEpoch, affinity = await getReleaseAffinity();
    if (!alive || epoch !== runtime.identityEpoch || pending.value || uncertainty.value || activeOwner === null) return;
    const attempt: PendingAction = {identityOwner:activeOwner,ownerId:id,capabilityId,input:{...(fields.value[id] ?? {})},key:crypto.randomUUID(),epoch,affinity};
    pending.value = attempt; await send(attempt);
  }
  async function retry() { if (uncertainty.value) {error.value='旧权限或发布上下文已失效，禁止重放。请先核对原操作结果。';return;} if (pending.value) await send(pending.value); }
  function abandon(confirmed = false) {
    if (busy.value) return;
    if (!confirmed || activeOwner === null) {error.value='请明确确认已核对原操作结果；放弃重试不会撤销服务端效果。';return;}
    uncertainOwners.delete(activeOwner); uncertainty.value = false;
    pending.value = null;
    notice.value = '已放弃本页重试信息，这不会撤销服务端可能完成的操作。请先核对，再发起新的操作。';
  }
  const stopNavigation = router.beforeEach(to => {
    if (to.path === '/login') { if (pending.value) identityChanged(); return true; }
    if ((pending.value || uncertainty.value) && to.path !== route.path) {
      error.value = '请先重试或明确放弃待确认操作，再离开插件页面。'; return false;
    }
    return true;
  });
  const beforeUnload = (event: BeforeUnloadEvent) => {
    if (pending.value || uncertainty.value) { event.preventDefault(); event.returnValue = ''; }
  };
  onMounted(() => { if (typeof window !== 'undefined') window.addEventListener('beforeunload',beforeUnload); void refresh(); timer = setInterval(() => {void refresh();},5000); });
  onBeforeUnmount(() => { alive = false; fetchController?.abort(); if (timer) clearInterval(timer); stopIdentity(); stopPermissions(); stopNavigation(); if (typeof window !== 'undefined') window.removeEventListener('beforeunload',beforeUnload); runtime.dispose(); });
  return {identityView,catalog,error,notice,busy,fields,results,pending,uncertainty,retry,abandon,selected,isDeepLink,edit,invoke,refresh,render:(id:string) => runtime.render(id),navigate:(path:string) => router.push(path)};
}
