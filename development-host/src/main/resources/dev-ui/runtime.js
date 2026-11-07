export class UiError extends Error {
    code;
    constructor(code, message) { super(message); this.name = 'UiError'; this.code = code; }
}
const idPattern = /^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$/;
const capPattern = idPattern;
const types = ['text', 'field', 'button', 'table', 'stack'];
function fail(message) { throw new UiError('VALIDATION', message); }
function object(value, keys, required = keys) {
    if (!value || typeof value !== 'object' || Array.isArray(value) || ![Object.prototype, null].includes(Object.getPrototypeOf(value)))
        fail('Expected plain object');
    const obj = value;
    for (const key of Object.keys(obj))
        if (!keys.includes(key))
            fail(`Unknown field ${key}`);
    for (const key of required)
        if (!Object.hasOwn(obj, key))
            fail(`Missing field ${key}`);
    return obj;
}
function str(v) { if (typeof v !== 'string' || v.length > 2048 || v.trim().length === 0 || /[<>]|\$\{|\{\{/.test(v))
    fail('Expected bounded literal string'); }
function strings(v) { if (!Array.isArray(v) || v.length > 256 || v.some(x => typeof x !== 'string' || x.length > 128) || new Set(v).size !== v.length)
    fail('Expected unique string list'); }
function node(v, components, capabilities, depth = 0, budget = { remaining: 1000 }) {
    if (--budget.remaining < 0)
        fail('Node count limit');
    if (depth > 13)
        fail('Node nesting limit');
    const n = object(v, ['type', 'text', 'field', 'capabilityId', 'children'], ['type']);
    if (typeof n.type !== 'string' || !types.includes(n.type))
        fail('Unknown component');
    if (!components.includes(n.type + '@1'))
        throw new UiError('MISSING_CAPABILITY', `Component ${n.type}@1 not declared/supported`);
    if (n.text !== undefined) {
        str(n.text);
        if (/[<>]|\$\{|\{\{/.test(n.text))
            fail('Markup or expression forbidden');
    }
    if (n.field !== undefined) {
        str(n.field);
        if (n.field.length > 128 || !idPattern.test(n.field))
            fail('Invalid field');
    }
    if (n.capabilityId !== undefined) {
        str(n.capabilityId);
        if (n.type !== 'button' || !capabilities.includes(n.capabilityId))
            fail('Undeclared action');
    }
    if (n.children !== undefined) {
        if (n.type !== 'stack' || !Array.isArray(n.children) || n.children.length > 128)
            fail('Invalid children');
        for (const child of n.children)
            node(child, components, capabilities, depth + 1, budget);
    }
}
export function validateCatalog(value, adapter) {
    const c = object(value, ['contractVersion', 'schemaVersion', 'revision', 'pages', 'slots']);
    if (c.contractVersion !== '1.0' || c.schemaVersion !== 1)
        throw new UiError('INCOMPATIBLE_VERSION', 'Unsupported contract or schema');
    if (!Number.isSafeInteger(c.revision) || c.revision < 0)
        fail('Invalid revision');
    if (!Array.isArray(c.pages) || !Array.isArray(c.slots) || c.pages.length + c.slots.length > 512)
        fail('Invalid catalog size');
    const ids = new Set(), paths = new Set();
    for (const raw of [...c.pages, ...c.slots]) {
        const isPage = c.pages.includes(raw);
        const p = object(raw, isPage ? ['id', 'pluginId', 'path', 'title', 'permissions', 'components', 'capabilities', 'content'] : ['id', 'pluginId', 'slotId', 'order', 'permissions', 'capabilities', 'components', 'content']);
        str(p.id);
        str(p.pluginId);
        if (p.pluginId.length > 128 || p.id.slice(p.pluginId.length + 1).length > 128 || !idPattern.test(p.pluginId) || !p.id.startsWith(p.pluginId + ':') || !idPattern.test(p.id.slice(p.pluginId.length + 1)) || ids.has(p.id))
            fail('Invalid or duplicate namespaced id');
        ids.add(p.id);
        strings(p.components);
        strings(p.permissions);
        strings(p.capabilities);
        if (p.capabilities.some(x => !capPattern.test(x)) || p.permissions.some(x => !idPattern.test(x)))
            fail('Invalid capability');
        if (p.components.length > 32 || p.components.some(x => !types.map(t => t + '@1').includes(x)))
            fail('Invalid component dependency');
        for (const component of p.components)
            if (!adapter.components.includes(component))
                throw new UiError('MISSING_CAPABILITY', `Missing component ${component}`);
        if (isPage) {
            str(p.path);
            str(p.title);
            strings(p.permissions);
            strings(p.capabilities);
            if (p.capabilities.some(x => !capPattern.test(x)) || p.permissions.some(x => !idPattern.test(x)))
                fail('Invalid capability');
            if (p.path !== `/fcp/plugins/${p.pluginId}/${p.id.slice(p.pluginId.length + 1)}` || paths.has(p.path))
                fail('Invalid or conflicting route');
            paths.add(p.path);
            node(p.content, p.components, p.capabilities);
        }
        else {
            str(p.slotId);
            if (p.slotId.length > 128 || !idPattern.test(p.slotId))
                fail('Invalid slot id');
            const slot = Object.hasOwn(adapter.slots, p.slotId) ? adapter.slots[p.slotId] : undefined;
            if (!slot)
                throw new UiError('MISSING_CAPABILITY', `Unknown host slot ${p.slotId}`);
            if (!Number.isSafeInteger(p.order) || Math.abs(p.order) > 1000000)
                fail('Invalid slot order');
            if (p.components.some(x => !slot.components.includes(x)))
                fail('Component forbidden in slot');
            node(p.content, p.components, p.capabilities);
        }
    }
    return JSON.parse(JSON.stringify(c));
}
function freeze(value) { if (value && typeof value === 'object') {
    Object.freeze(value);
    for (const child of Object.values(value))
        freeze(child);
} return value; }
const empty = (revision = -1) => ({ contractVersion: '1.0', schemaVersion: 1, revision, pages: [], slots: [] });
export class UiRuntime {
    adapter;
    catalog = freeze(empty());
    epoch = 0;
    disposed = false;
    projectionFingerprint = null;
    controllers = new Set();
    resources = new Map();
    dirty = new Set();
    unsubscribe;
    constructor(adapter) { this.adapter = adapter; this.unsubscribe = adapter.context.subscribe(() => this.resetIdentity()); }
    get identityEpoch() { return this.epoch; }
    get snapshot() { return this.catalog; }
    active() { if (this.disposed)
        throw new UiError('STALE_VERSION', 'Runtime disposed'); }
    abort() { for (const c of this.controllers)
        c.abort(); this.controllers.clear(); }
    cleanup(id) { const callbacks = this.resources.get(id); this.resources.delete(id); for (const cb of callbacks ?? []) {
        try {
            cb();
        }
        catch { /* cleanup all resources even if one fails */ }
    } }
    notice(ids) { const dirty = ids.filter(id => this.dirty.has(id)); ids.forEach(id => this.dirty.delete(id)); if (dirty.length) {
        try {
            this.adapter.notifyDirty(dirty);
        }
        catch { /* cannot veto revocation */ }
    } }
    resetIdentity() {
        this.active();
        this.epoch++;
        this.abort();
        this.projectionFingerprint = null;
        const ids = [...this.resources.keys(), ...this.catalog.pages.map(p => p.id)];
        this.notice(ids);
        ids.forEach(id => this.cleanup(id));
        this.catalog = freeze(empty());
        this.adapter.clearCaches();
        this.adapter.replaceCatalog(this.catalog);
        this.adapter.restoreDeepLink(null);
    }
    /** Authorization projection changes independently of Engine generation. Never invent an invoke revision. */
    applyProjection(value, fingerprint, epoch = this.epoch) {
        this.active();
        if (epoch !== this.epoch)
            return false;
        if (!/^[a-f0-9]{64}$/.test(fingerprint))
            throw new UiError('VALIDATION', 'Invalid projection fingerprint');
        const next = validateCatalog(value, this.adapter);
        if (next.revision < this.catalog.revision)
            return false;
        if (this.projectionFingerprint !== null && this.projectionFingerprint !== fingerprint)
            this.resetIdentity();
        const applied = this.applyRevision(next);
        if (applied || next.revision === this.catalog.revision)
            this.projectionFingerprint = fingerprint;
        return applied;
    }
    applyRevision(value, epoch = this.epoch) {
        this.active();
        if (epoch !== this.epoch)
            return false;
        const next = validateCatalog(value, this.adapter);
        if (next.revision <= this.catalog.revision)
            return false;
        const identity = this.adapter.context.get();
        next.pages = next.pages.filter(p => identity.userId !== null && p.permissions.every(x => identity.permissions.includes(x)));
        next.slots = next.slots.filter(p => identity.userId !== null && p.permissions.every(x => identity.permissions.includes(x)));
        next.slots.sort((a, b) => a.order - b.order || a.id.localeCompare(b.id));
        const changed = [...this.catalog.pages, ...this.catalog.slots].filter(old => JSON.stringify(old) !== JSON.stringify([...next.pages, ...next.slots].find(p => p.id === old.id))).map(p => p.id);
        const path = this.adapter.currentPath();
        this.adapter.replaceCatalog(freeze(next)); // transactional host binding; validation did not mutate current state
        this.catalog = next;
        this.abort();
        this.notice(changed);
        changed.forEach(id => this.cleanup(id));
        this.adapter.restoreDeepLink(next.pages.some(p => p.path === path) ? path : null);
        return true;
    }
    async refresh(fetchCatalog) {
        this.active();
        const epoch = this.epoch, controller = new AbortController();
        this.controllers.add(controller);
        try {
            const catalog = await fetchCatalog(controller.signal);
            if (controller.signal.aborted)
                return false;
            return this.applyRevision(catalog, epoch);
        }
        catch (error) {
            if (controller.signal.aborted || epoch !== this.epoch)
                return false;
            throw error;
        }
        finally {
            this.controllers.delete(controller);
        }
    }
    render(ownerId) {
        this.active();
        const owner = [...this.catalog.pages, ...this.catalog.slots].find(item => item.id === ownerId);
        if (!owner)
            throw new UiError('PLUGIN_DISABLED', ownerId);
        return this.adapter.render(owner.content, owner.id);
    }
    markDirty(pageId, dirty = true) { this.page(pageId); if (dirty)
        this.dirty.add(pageId);
    else
        this.dirty.delete(pageId); }
    trackSubscription(id, dispose) {
        this.active();
        if (![...this.catalog.pages, ...this.catalog.slots].some(p => p.id === id))
            throw new UiError('PLUGIN_DISABLED', id);
        let set = this.resources.get(id);
        if (!set)
            this.resources.set(id, set = new Set());
        let done = false;
        const once = () => { if (!done) {
            done = true;
            set.delete(once);
            dispose();
        } };
        set.add(once);
        return once;
    }
    page(id) { this.active(); const p = this.catalog.pages.find(p => p.id === id); if (!p)
        throw new UiError('PLUGIN_DISABLED', id); return p; }
    async invoke(pageId, capabilityId, input, options = {}) {
        this.active();
        const page = [...this.catalog.pages, ...this.catalog.slots].find(p => p.id === pageId), context = this.adapter.context.get();
        if (!page)
            throw new UiError('PLUGIN_DISABLED', pageId);
        if (!context.userId)
            throw new UiError('UNAUTHENTICATED', 'Login required');
        if (!page.permissions.every(x => context.permissions.includes(x)))
            throw new UiError('FORBIDDEN', pageId);
        if (!page.capabilities.includes(capabilityId))
            throw new UiError('MISSING_CAPABILITY', capabilityId);
        const epoch = this.epoch, revision = this.catalog.revision, controller = new AbortController();
        this.controllers.add(controller);
        try {
            const result = await this.adapter.invoke(capabilityId, input, { signal: controller.signal, epoch, revision, idempotencyKey: options.idempotencyKey });
            if (controller.signal.aborted || epoch !== this.epoch || revision !== this.catalog.revision)
                throw new UiError('STALE_VERSION', 'Discarded stale response');
            return result;
        }
        catch (error) {
            if (controller.signal.aborted || epoch !== this.epoch || revision !== this.catalog.revision)
                throw new UiError('STALE_VERSION', 'Discarded stale response');
            throw error;
        }
        finally {
            this.controllers.delete(controller);
        }
    }
    dispose() { if (this.disposed)
        return; this.resetIdentity(); this.unsubscribe(); this.disposed = true; }
}
function adapter(bindings, soybean) {
    return { ...bindings, name: soybean ? 'soybean' : 'plus-ui', version: '1.0.0', upstreamVersion: soybean ? '1.1.3' : '5.6.2-v2.6.2',
        async invoke(capabilityId, input, options) {
            const raw = await bindings.request(capabilityId, input, options);
            if (!raw || typeof raw !== 'object')
                throw new UiError('VALIDATION', 'Malformed host response');
            const r = raw;
            if (soybean ? r.error != null : r.code !== 200) {
                const err = soybean && r.error && typeof r.error === 'object' ? r.error : r;
                const code = String(err.code);
                const codes = ['UNAUTHENTICATED', 'FORBIDDEN', 'PLUGIN_DISABLED', 'STALE_VERSION', 'MISSING_CAPABILITY', 'VALIDATION', 'INCOMPATIBLE_VERSION'];
                throw new UiError(code === '401' ? 'UNAUTHENTICATED' : code === '403' ? 'FORBIDDEN' : codes.includes(code) ? code : 'VALIDATION', String(err.message ?? err.msg ?? 'Host request failed'));
            }
            return r.data;
        } };
}
export const createPlusUiAdapter = (bindings) => adapter(bindings, false);
export const createSoybeanAdapter = (bindings) => adapter(bindings, true);
