import type { ReleaseHost } from './controller';

/** Native RuoYi super-admin menu state contains only *:*:*. Never treat it as an
 * FCP grant: obtain the explicit, fresh server projection for both real shells. */
export class ReleasePermissionHost implements ReleaseHost {
  readonly shell: ReleaseHost['shell'];
  private grants: readonly string[] = [];
  private epoch = 0;
  private alive = true;
  private identity: string;
  private pending?: AbortController;
  private listeners = new Set<() => void>();
  private permissionListeners = new Set<() => void>();
  private stop: () => void;
  private stopPermissions: () => void;
  private host: ReleaseHost;
  constructor(host: ReleaseHost) {
    this.host = host; this.shell = host.shell; this.identity = host.identityKey();
    this.stop = host.observeIdentity(() => {
      this.invalidate();
      const identity = host.identityKey();
      if (identity === this.identity) { this.publish([]); return; }
      this.identity = identity; this.grants = [];
      for (const listener of this.listeners) listener();
    });
    this.stopPermissions = host.observePermissions?.(() => { this.invalidate(); this.publish([]); }) ?? (() => {});
  }
  // Permission snapshots are not identities. Changing them must never settle a write.
  identityKey() { return this.host.identityKey(); }
  permissions() { return this.grants; }
  observeIdentity(changed: () => void) { this.listeners.add(changed); return () => { this.listeners.delete(changed); }; }
  observePermissions(changed: () => void) { this.permissionListeners.add(changed); return () => { this.permissionListeners.delete(changed); }; }
  request(path: string, method: 'GET' | 'POST', body: unknown, signal: AbortSignal) {
    return this.host.request(path, method, body, signal);
  }
  private invalidate() { this.epoch++; this.pending?.abort(); this.pending = undefined; }
  private publish(permissions: readonly string[]) {
    const next = [...permissions].sort();
    if (next.length === this.grants.length && next.every((value, index) => value === this.grants[index])) return;
    this.grants = Object.freeze(next);
    for (const listener of this.permissionListeners) listener();
  }
  async refresh() {
    if (!this.alive) throw new Error('PERMISSION_DENIED');
    this.invalidate();
    const epoch = this.epoch, identity = this.host.identityKey(), controller = new AbortController();
    this.pending = controller;
    const current = () => this.alive && epoch === this.epoch && !controller.signal.aborted && identity === this.host.identityKey();
    try {
      const raw = await this.host.request('/fcp/ui/session', 'GET', undefined, controller.signal);
      if (!current()) throw new Error('STALE_IDENTITY');
      if (!raw || typeof raw !== 'object') throw new Error('INVALID_PERMISSION_PROJECTION');
      const envelope = raw as { code?: unknown; data?: { releasePermissions?: unknown }; error?: unknown };
      if (this.shell === 'admin' ? envelope.code !== 200 : envelope.error != null) throw new Error('PERMISSION_DENIED');
      const permissions = envelope.data?.releasePermissions;
      if (!Array.isArray(permissions) || permissions.some(value => value !== 'fcp:release:view' && value !== 'fcp:release:manage') || new Set(permissions).size !== permissions.length) throw new Error('INVALID_PERMISSION_PROJECTION');
      this.publish(permissions);
    } catch (error) {
      // Only the latest request may revoke the projection. This notification is
      // authorization loss, not proof that an interrupted write never executed.
      if (current()) this.publish([]);
      throw error;
    } finally { if (this.pending === controller) this.pending = undefined; }
  }
  dispose() { this.alive = false; this.stop(); this.stopPermissions(); this.invalidate(); this.publish([]); this.listeners.clear(); this.permissionListeners.clear(); }
}
