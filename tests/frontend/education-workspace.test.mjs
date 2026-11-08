import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { defineComponent } from 'vue';
import { mount, flushPromises } from '@vue/test-utils';
import { useWorkspace } from '@/fcp/education/useWorkspace';
import { operationLabels } from '@/fcp/education/contracts';
const probe = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('@/utils/request', () => ({ default: probe.request }));
const operations = Object.fromEntries(Object.keys(operationLabels).map(key => [key, true]));
const caps = { schemaVersion: 'education-foundation-v1', status: 'READY', operations, readiness: {}, blockers: [] };
const schoolId = '9007199254740993';
const org = { id: '18446744073709551615', parentId: schoolId, orgCode: '0001', orgKind: 'DEPARTMENT', name: '教研组', validFrom: '2026-11-08T00:00:00.123456Z', validTo: null, createdAt: '2026-11-08T00:00:00.123456Z', rowVersion: '9007199254740994' };
const term = { id: '9007199254740995', schoolId, termCode: '2026-A', startsOn: '2026-09-01', endsOn: '2027-01-31', status: 'OPEN', rowVersion: '0' };
const schoolClass = { id: '9007199254740996', schoolId, termId: term.id, classCode: '001', name: '一年一班', gradeCode: '01', createdAt: org.createdAt, rowVersion: '0' };
const envelope = data => ({ code: 200, data });
const list = items => envelope({ items, nextAfterId: null });
const wrappers = [];
function handler(config) {
  if (config.url.includes('/operations/')) return envelope({ operation: { intentId: config.url.split('/').at(-1), outcome: 'UNKNOWN' } });
  if (config.method === 'post') return envelope({ operation: { intentId: config.data.intentId, outcome: 'COMMITTED' }, record: config.url.includes('classes') ? schoolClass : { ...org, name: config.data.name } });
  if (/\/schools\?/.test(config.url)) return list([{ id: schoolId, name: '测试学校', rowVersion: '0', readiness: 'READY', operations }]);
  if (/\/terms\?/.test(config.url)) return list([term]);
  if (/\/organizations\?/.test(config.url)) return list([org]);
  if (/\/classes\?/.test(config.url)) return list([schoolClass]);
  return envelope(config.url.includes('classes') ? schoolClass : org);
}
function open() {
  let state;
  const wrapper = mount(defineComponent({ setup() { state = useWorkspace(caps, 'test-education-pending'); return () => null; } }));
  wrappers.push(wrapper); return { state, wrapper };
}
async function select(state) { await flushPromises(); state.schoolId.value = schoolId; await state.chooseSchool(); }
beforeEach(() => { sessionStorage.clear(); probe.request.mockImplementation(handler); });
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); probe.request.mockReset(); });
describe('education business state: actual Vue and HTTP unit double, not native authorization acceptance', () => {
  it('loads trusted school scope and exact uint64/string dates without numeric coercion', async () => {
    const { state } = open(); await select(state);
    expect(state.schools.value[0].id).toBe(schoolId); expect(state.organizations.value[0]).toEqual(org); expect(state.terms.value[0].startsOn).toBe('2026-09-01');
    await state.showDetail(org); expect(state.detail.value.rowVersion).toBe('9007199254740994');
  });
  it('keeps missing per-school grants closed even with global capability', async () => {
    probe.request.mockImplementation(config => /\/schools\?/.test(config.url) ? list([{ id: schoolId, name: '校', rowVersion: '0' }]) : handler(config));
    const { state } = open(); await select(state); state.openForm(); expect(state.formOpen.value).toBe(false); expect(state.allowed('orgList')).toBe(false);
  });
  it('sends only name, string CAS and intent for rename', async () => {
    const { state } = open(); await select(state); await state.showDetail(org); state.openForm(true); state.form.value.name = ' 新名称 '; await state.submit();
    const command = probe.request.mock.calls.map(([value]) => value).find(value => value.method === 'post');
    expect(Object.keys(command.data).sort()).toEqual(['expectedRowVersion', 'intentId', 'name']); expect(command.data.expectedRowVersion).toBe(org.rowVersion); expect(command.data.name).toBe(' 新名称 '); expect(state.pending.value).toBe(null);
  });
  it('creates a class only with a server-listed term and retains codes as strings', async () => {
    const { state } = open(); await select(state); state.kind.value = 'classes'; state.openForm(); expect(state.formOpen.value).toBe(false);
    state.termId.value = term.id; await state.loadRows(); state.openForm(); state.form.value = { ...state.form.value, name: '一年一班', code: '001', gradeCode: '01' }; await state.submit();
    const command = probe.request.mock.calls.map(([value]) => value).find(value => value.method === 'post'); expect(command.data).toMatchObject({ termId: term.id, classCode: '001', gradeCode: '01' }); expect(command.data).not.toHaveProperty('schoolId');
  });
  it('cancel before save sends no write and resets on reopening', async () => {
    const { state } = open(); await select(state); state.openForm(); state.form.value.name = '丢弃'; state.closeForm(); state.openForm(); expect(state.form.value.name).toBe(''); expect(probe.request.mock.calls.some(([value]) => value.method === 'post')).toBe(false);
  });
  it('CAS conflict closes stale form and requires a fresh detail', async () => {
    probe.request.mockImplementation(config => config.method === 'post' ? Promise.reject({ response: { status: 409, data: { error: 'EDUCATION_VERSION_CONFLICT' } } }) : handler(config));
    const { state } = open(); await select(state); await state.showDetail(org); state.openForm(true); await state.submit(); expect(state.error.value).toContain('冲突'); expect(state.formOpen.value).toBe(false); expect(state.detail.value).toBe(null); expect(state.pending.value).toBe(null);
  });
  it('unknown commit persists original request, refuses duplicate and uses only GET for lookup', async () => {
    probe.request.mockImplementation(config => config.method === 'post' ? Promise.reject({ response: { status: 503, data: { error: 'COMMIT_UNKNOWN' } } }) : handler(config));
    const { state, wrapper } = open(); await select(state); await state.showDetail(org); state.openForm(true); await state.submit(); const original = state.pending.value;
    await state.submit(); expect(probe.request.mock.calls.filter(([value]) => value.method === 'post')).toHaveLength(1); wrapper.unmount();
    const next = open().state; await flushPromises(); expect(next.pending.value.body).toEqual(original.body); await next.checkPending(); expect(next.pending.value.intentId).toBe(original.intentId); expect(next.notice.value).toContain('仍未确认');
    expect(probe.request.mock.calls.filter(([value]) => value.method === 'post')).toHaveLength(1);
    probe.request.mockImplementation(config => config.url.includes('/operations/') ? envelope({ operation: { intentId: original.intentId, outcome: 'COMMITTED' }, record: org }) : handler(config));
    await next.checkPending(); expect(next.pending.value).toBe(null); expect(next.notice.value).toContain('已提交');
  });
  it('duplicate saving and unmount retain uncertain operation for recovery', async () => {
    let reject;
    probe.request.mockImplementation(config => config.method === 'post' ? new Promise((_, fail) => { reject = fail; }) : handler(config));
    const { state, wrapper } = open(); await select(state); await state.showDetail(org); state.openForm(true); const first = state.submit(); await state.submit();
    expect(probe.request.mock.calls.filter(([value]) => value.method === 'post')).toHaveLength(1); const request = probe.request.mock.calls.map(([value]) => value).find(value => value.method === 'post'); wrapper.unmount(); expect(request.signal.aborted).toBe(true); reject(new Error('aborted')); await first; expect(sessionStorage.getItem('test-education-pending')).toContain('intentId');
  });

  it('keeps unknown or pending school readiness closed despite advertised grants', async () => {
    probe.request.mockImplementation(config => /\/schools\?/.test(config.url) ? list([{ id: schoolId, name: '校', rowVersion: '0', readiness: 'EXECUTION_GRANT_MISSING', operations }]) : handler(config));
    const { state } = open(); await select(state); expect(state.selectedSchool.value.readiness).toBe('EXECUTION_GRANT_MISSING'); expect(state.allowed('orgCreate')).toBe(false);
  });
  it('identity invalidation clears prior school data and blocks old forms', async () => {
    const { state } = open(); await select(state); await state.showDetail(org); state.openForm(true); state.invalidateIdentity();
    expect(state.schools.value).toEqual([]); expect(state.detail.value).toBe(null); expect(state.formOpen.value).toBe(false); expect(state.allowed('orgRename')).toBe(false);
  });
  it('read failure invalidates prior school grants', async () => {
    const { state } = open(); await select(state); probe.request.mockRejectedValue({ response: { status: 403 } }); await state.loadRows(); expect(state.allowed('orgRename')).toBe(false); state.openForm(); expect(state.formOpen.value).toBe(false);
  });
});
