import type { ReleaseHost } from './controller';

/** Native RuoYi super-admin menu state contains only *:*:*. Never treat it as an
 * FCP grant: obtain the explicit, fresh server projection for both real shells. */
export class ReleasePermissionHost implements ReleaseHost {
  readonly shell: ReleaseHost['shell'];
  private grants: readonly string[] = [];
  private epoch = 0;
  private alive = true;
  private pending?: AbortController;
  private listeners = new Set<() => void>();
  private stop: () => void;
  private host: ReleaseHost;
  constructor(host: ReleaseHost) {
    this.host = host; this.shell = host.shell;
    this.stop = host.observeIdentity(() => this.clear());
  }
  identityKey() { return JSON.stringify([this.host.identityKey(), this.grants]); }
  permissions() { return this.grants; }
  observeIdentity(changed: () => void) { this.listeners.add(changed); return () => { this.listeners.delete(changed); }; }
  request(path: string, method: 'GET' | 'POST', body: unknown, signal: AbortSignal) {
    return this.host.request(path, method, body, signal);
  }
  private clear() {
    this.epoch++; this.pending?.abort(); this.pending = undefined; this.grants = [];
    for (const listener of this.listeners) listener();
  }
  async refresh() {
    if (!this.alive) throw new Error('PERMISSION_DENIED');
    this.clear();
    const epoch = this.epoch, identity = this.host.identityKey(), controller = new AbortController();
    this.pending = controller;
    try {
      const raw = await this.host.request('/fcp/ui/session', 'GET', undefined, controller.signal);
      if (!this.alive || epoch !== this.epoch || controller.signal.aborted || identity !== this.host.identityKey()) throw new Error('STALE_IDENTITY');
      if (!raw || typeof raw !== 'object') throw new Error('INVALID_PERMISSION_PROJECTION');
      const envelope = raw as { code?: unknown; data?: { releasePermissions?: unknown }; error?: unknown };
      if (this.shell === 'admin' ? envelope.code !== 200 : envelope.error != null) throw new Error('PERMISSION_DENIED');
      const permissions = envelope.data?.releasePermissions;
      if (!Array.isArray(permissions) || permissions.some(value => value !== 'fcp:release:view' && value !== 'fcp:release:manage') || new Set(permissions).size !== permissions.length) throw new Error('INVALID_PERMISSION_PROJECTION');
      this.grants = Object.freeze([...permissions]);
      for (const listener of this.listeners) listener();
    } finally { if (this.pending === controller) this.pending = undefined; }
  }
  dispose() { this.alive = false; this.stop(); this.clear(); this.listeners.clear(); }
}
