/** Host-bound release administration. This module has no access to credentials or plugin code. */
export interface ReleaseHost {
  shell: 'admin' | 'teacher';
  identityKey(): string;
  permissions(): readonly string[];
  observeIdentity(changed: () => void): () => void;
  /** Authorization changes invalidate data, never the outcome of a submitted write. */
  observePermissions?(changed: () => void): () => void;
  request(path: string, method: 'GET' | 'POST', body: unknown, signal: AbortSignal): Promise<unknown>;
}
export type ReleaseAction = 'DEPLOY' | 'CANARY' | 'EXPAND' | 'PAUSE' | 'ABORT' | 'PROMOTE' | 'ROLLBACK' | 'RETIRE';
export interface ReleasePlan {
  environment: string; stableRelease: string; candidateRelease: string;
  stableDigest: string; candidateDigest: string; schemaVersion: number;
  stableSchemaMin: number; stableSchemaMax: number; candidateSchemaMin: number; candidateSchemaMax: number;
}
export interface ContainmentReview {revision: number; plan: ReleasePlan; pendingOperationId: string}
export interface ConsoleState {
  containmentReview?: ContainmentReview | null;
  busy: boolean; error: string; notice: string; unknown: boolean;
  state: Record<string, unknown> | null; versions: Record<string, unknown> | null;
  review: { plan: ReleasePlan; revision: number } | null;
}
const object = (v: unknown): Record<string, unknown> => {
  if (!v || typeof v !== 'object' || Array.isArray(v)) throw new Error('INVALID_RESPONSE');
  return v as Record<string, unknown>;
};
const integer = (v: unknown): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v >= 0;
export function parsePlan(input: unknown): ReleasePlan {
  const p = object(input);
  const ids = ['environment', 'stableRelease', 'candidateRelease'];
  const hashes = ['stableDigest', 'candidateDigest'];
  const numbers = ['schemaVersion', 'stableSchemaMin', 'stableSchemaMax', 'candidateSchemaMin', 'candidateSchemaMax'];
  if (Object.keys(p).length !== 10 || ids.some(k => typeof p[k] !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$/.test(p[k] as string)) ||
      hashes.some(k => typeof p[k] !== 'string' || !/^[a-f0-9]{64}$/.test(p[k] as string)) || numbers.some(k => !integer(p[k]))) throw new Error('INVALID_PLAN');
  if (p.stableRelease === p.candidateRelease || (p.stableSchemaMin as number) > (p.stableSchemaMax as number) || (p.candidateSchemaMin as number) > (p.candidateSchemaMax as number)) throw new Error('INVALID_PLAN');
  return { ...p } as unknown as ReleasePlan;
}
export class ReleaseConsole {
  readonly data: ConsoleState = {busy: false, error: '', notice: '', unknown: false, state: null, versions: null, review: null};
  private epoch = 0;
  private alive = true;
  private requests = new Set<AbortController>();
  private stop: () => void;
  private stopPermissions: () => void;
  private mutationInFlight = false;
  private containmentUncertain = false;
  private host: ReleaseHost;
  private changed: (state: ConsoleState) => void;
  constructor(host: ReleaseHost, changed: (state: ConsoleState) => void = () => {}) {
    this.host = host; this.changed = changed;
    this.stop = host.observeIdentity(() => this.reset());
    this.stopPermissions = host.observePermissions?.(() => this.reset(true)) ?? (() => {});
  }
  get canView() { return this.host.permissions().includes('fcp:release:view'); }
  get canManage() { return this.canView && this.host.permissions().includes('fcp:release:manage'); }
  get canRecover() { return this.canManage && this.data.state?.supportsRecovery === true && this.data.state.pending != null && integer(this.data.state.revision); }
  get canContain() { return this.canManage && !this.containmentUncertain && this.eligibleContainment(this.data.state); }
  private eligibleContainment(state: Record<string,unknown> | null): boolean {
    try { if (!state || state.supportsFencedContainment !== true || !Array.isArray(state.capabilities) || !state.capabilities.includes('ABORT') || !integer(state.revision)) return false;
      const pending = object(state.pending); if (typeof pending.operationId !== 'string' || !pending.operationId || pending.action === 'ABORT') return false;
      parsePlan(state.plan); return true;
    } catch { return false; }
  }
  private containmentSnapshot(state: Record<string,unknown>): ContainmentReview {
    return {revision: state.revision as number, plan: parsePlan(state.plan), pendingOperationId: object(state.pending).operationId as string};
  }
  private sameContainment(a: ContainmentReview, b: ContainmentReview) {
    return a.revision === b.revision && a.pendingOperationId === b.pendingOperationId && Object.keys(a.plan).every(k => a.plan[k as keyof ReleasePlan] === b.plan[k as keyof ReleasePlan]);
  }
  supports(action: ReleaseAction) { return Array.isArray(this.data.state?.capabilities) && this.data.state.capabilities.includes(action); }
  private emit() { this.changed({...this.data}); }
  reset(preserveUncertainty = false) {
    if (!preserveUncertainty) this.containmentUncertain = false;
    const unknown = preserveUncertainty && (this.data.unknown || this.mutationInFlight);
    this.mutationInFlight = false;
    this.epoch++;
    for (const request of this.requests) request.abort();
    this.requests.clear();
    Object.assign(this.data, {busy: false, state: null, versions: null, review: null, containmentReview: null, unknown, error: '', notice: '身份或权限已变化，请重新加载。之前已提交的操作可能仍在执行，请回原身份核对。'});
    this.emit();
  }
  dispose() { this.alive = false; this.stop(); this.stopPermissions(); this.reset(); }
  private require(manage = false) {
    if (!this.alive || !(manage ? this.canManage : this.canView)) throw new Error('PERMISSION_DENIED');
  }
  private async request(path: string, method: 'GET' | 'POST', body?: unknown) {
    const epoch = this.epoch, identity = this.host.identityKey(), controller = new AbortController();
    this.requests.add(controller);
    try {
      const raw = object(await this.host.request('/fcp/releases' + path, method, body, controller.signal));
      if (!this.alive || epoch !== this.epoch || identity !== this.host.identityKey() || controller.signal.aborted) throw new Error('STALE_IDENTITY');
      if (this.host.shell === 'admin' ? raw.code !== 200 : raw.error != null) throw new Error('SERVER_REJECTED');
      return object(raw.data);
    } finally { this.requests.delete(controller); }
  }
  private async work(mutation: boolean, fn: (guard: () => void) => Promise<void>, allowUncertain = false) {
    this.require(mutation);
    if (this.data.busy) return;
    if (mutation && this.data.unknown && !allowUncertain) throw new Error('RECONCILE_REQUIRED');
    const epoch = this.epoch, identity = this.host.identityKey();
    const guard = () => { if (!this.alive || epoch !== this.epoch || identity !== this.host.identityKey()) throw new Error('STALE_OPERATION'); this.require(mutation); };
    this.mutationInFlight = mutation;
    this.data.busy = true; this.data.error = ''; this.emit();
    try { guard(); await fn(guard); }
    catch (error) {
      if (epoch !== this.epoch || !this.alive) return;
      const code = error instanceof Error ? error.message : '';
      // A response error does not prove a mutating request was never committed.
      if (mutation) { this.data.unknown = true; this.data.review = null; }
      this.data.error = code === 'SERVER_REJECTED' ? '请求被拒绝。若已提交变更，请先核对服务器状态。' : '请求未完成。请核对状态，不要重复提交。';
    } finally { if (epoch === this.epoch && this.alive) { this.mutationInFlight = false; this.data.busy = false; this.emit(); } }
  }
  async load() {
    return this.work(false, async guard => {
      const state = await this.request('/state', 'GET'); guard();
      if (!integer(state.revision)) throw new Error('INVALID_RESPONSE');
      const versions = await this.request('/versions', 'GET'); guard();
      this.data.state = state; this.data.versions = versions;
      this.data.unknown = this.data.unknown || state.pending != null || state.phase === 'UNKNOWN' || state.phase === 'PENDING';
      this.data.containmentReview = null;
      if (this.data.review?.revision !== state.revision) this.data.review = null;
      this.data.notice = this.data.unknown ? '读取状态不会解除未决操作，请执行对账。' : '已读取服务器状态。期望状态不代表实际部署成功。';
    });
  }
  review(plan: unknown) {
    this.require(true);
    if (this.data.busy || this.data.unknown || !this.data.state || !integer(this.data.state.revision)) throw new Error('LOAD_STATE_FIRST');
    this.data.review = {plan: parsePlan(plan), revision: this.data.state.revision}; this.emit();
  }
  clearReview() { this.data.review = null; this.emit(); }
  async confirmPlan(confirmed: boolean) {
    if (!confirmed || !this.data.review) throw new Error('CONFIRMATION_REQUIRED');
    const review = this.data.review;
    return this.work(true, async guard => {
      const result = await this.request('/plans', 'POST', {expectedRevision: review.revision, operationId: crypto.randomUUID(), plan: review.plan}); guard();
      this.accept(result); this.data.review = null; this.data.notice = '发布计划已提交；尚未表示候选版本已部署。';
    });
  }
  async execute(action: ReleaseAction, basisPoints: number, confirmed: boolean) {
    if (!confirmed) throw new Error('CONFIRMATION_REQUIRED');
    const actions: ReleaseAction[] = ['DEPLOY', 'CANARY', 'EXPAND', 'PAUSE', 'ABORT', 'PROMOTE', 'ROLLBACK', 'RETIRE'];
    if (!actions.includes(action) || !integer(basisPoints) || basisPoints > 10000) throw new Error('INVALID_ACTION');
    if (!this.data.state || !integer(this.data.state.revision)) throw new Error('LOAD_STATE_FIRST');
    if (!this.supports(action)) throw new Error('UNSUPPORTED_ACTION');
    const revision = this.data.state.revision;
    return this.work(true, async guard => {
      const result = await this.request('/operations', 'POST', {expectedRevision: revision, operationId: crypto.randomUUID(), action, basisPoints}); guard();
      this.accept(result); this.data.notice = '操作已提交，请检查实际观测状态。';
    });
  }
  private accept(result: Record<string, unknown>) {
    if (!integer(result.revision)) throw new Error('INVALID_RESPONSE');
    this.data.state = result;
    // Never unlock on a pending/unknown deployment. Only reconciliation can settle it.
    this.data.unknown = result.pending != null || result.phase === 'UNKNOWN' || result.phase === 'PENDING';
  }
  async reconcile() {
    this.require(true);
    return this.work(false, async guard => {
      const result = await this.request('/reconcile', 'POST', {}); guard();
      this.accept(result); if (!this.data.unknown) this.containmentUncertain = false; this.data.notice = this.data.unknown ? '执行结果尚不明确，请保留现场并继续查询。' : '已完成服务器对账，请检查实际状态。';
    });
  }
  /** Explicit emergency closure only; it cannot expand, roll back, or retry an unknown write. */
  async reviewContainment() {
    this.require(true);
    if (this.containmentUncertain) throw new Error('RECONCILE_REQUIRED');
    return this.work(false, async guard => {
      this.data.containmentReview = null;
      const current = await this.request('/state', 'GET'); guard(); this.require(true);
      if (!this.eligibleContainment(current)) throw new Error('FENCED_CONTAINMENT_UNAVAILABLE');
      this.data.state = current; this.data.unknown = true;
      this.data.containmentReview = this.containmentSnapshot(current);
      this.data.notice = '已读取待决操作。封禁将关闭全部受管新入口，不回切旧版，也不撤销已执行效果。';
    });
  }
  async contain(confirmed: boolean) {
    this.require(true);
    if (!confirmed || !this.data.containmentReview) throw new Error('CONFIRMATION_REQUIRED');
    if (!this.canContain) throw new Error('FENCED_CONTAINMENT_UNAVAILABLE');
    const review = this.data.containmentReview;
    return this.work(true, async guard => {
      const current = await this.request('/state', 'GET'); guard(); this.require(true);
      if (!this.eligibleContainment(current) || !this.sameContainment(review, this.containmentSnapshot(current))) {
        this.data.containmentReview = null;
        if (integer(current.revision)) this.data.state = current;
        this.data.notice = '发布状态或能力已变化，未发送封禁。请重新读取并确认。';
        return;
      }
      this.data.containmentReview = null; this.containmentUncertain = true;
      const result = await this.request('/operations', 'POST', {expectedRevision: review.revision, operationId: crypto.randomUUID(), action: 'ABORT', basisPoints: 0}); guard();
      this.accept(result);
      // Emergency closure does not settle the original unknown write or authorize normal operations.
      this.data.unknown = true;
      this.data.notice = '封禁请求已提交，请对账确认实际入口已关闭。原操作效果仍须核对；没有回切旧版。';
    }, true);
  }
  /** Recovery is an executor-proven atomic claim of the original command, never a new submission. */
  async recover(confirmed: boolean) {
    this.require(true);
    if (!confirmed) throw new Error('CONFIRMATION_REQUIRED');
    if (!this.canRecover) throw new Error('SAFE_RECOVERY_UNAVAILABLE');
    const expectedRevision = this.data.state!.revision;
    return this.work(true, async guard => {
      const result = await this.request('/recover', 'POST', {expectedRevision}); guard();
      this.accept(result);
      this.data.notice = this.data.unknown ? '安全恢复尚未确认，请继续对账；没有创建第二个发布操作。' : '已完成安全恢复检查，请核对实际部署结果。';
    }, true);
  }
}
