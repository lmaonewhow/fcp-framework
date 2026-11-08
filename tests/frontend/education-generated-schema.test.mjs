// The real ElementPlus table needs the existing JSDOM-only animation scheduler.
import '../education_roster/setup.mjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { reactive } from 'vue';
import { mount, flushPromises } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import Workspace from '@/views/fcp/education/generated/Workspace.vue';
import { schema } from '@/api/fcp/education/generated/foundation.schema';
import { endpoints, listClasses, writeEntity } from '@/api/fcp/education/generated/foundation.api';
import { operationLabels } from '@/fcp/education/contracts';
const probe = vi.hoisted(() => ({ request: vi.fn(), identity: undefined }));
vi.mock('@/utils/request', () => ({ default: probe.request }));
vi.mock('@/store/modules/user', () => ({ useUserStore: () => probe.identity }));
const original = structuredClone(schema.resources);
const operations = Object.fromEntries(Object.keys(operationLabels).map(key => [key, true]));
const capabilities = { schemaVersion: 'education-foundation-v1', status: 'READY', operations, readiness: {}, blockers: [] };
const org = { id: '9007199254740993', parentId: '10', orgCode: '001', orgKind: 'DEPARTMENT', name: '字段白名单测试', validFrom: '2026-11-08T00:00:00.123456Z', validTo: null, createdAt: '2026-11-08T00:00:00.123456Z', rowVersion: '0' };
const ok = data => ({ code: 200, data });
const page = items => ok({ items, nextAfterId: null });
const resource = id => schema.resources.find(item => item.id === id);
const wrappers = [];
function open() { const wrapper = mount(Workspace, { props: { capabilities }, global: { plugins: [ElementPlus] } }); wrappers.push(wrapper); return wrapper; }
const button = (wrapper, label) => wrapper.findAll('button').find(item => item.text() === label);
async function choose(wrapper) { const select = wrapper.findComponent({ name: 'ElSelect' }); select.vm.$emit('update:modelValue', '10'); select.vm.$emit('change', '10'); await flushPromises(); }
beforeEach(() => {
  probe.identity = reactive({ userId: 'generated-unit', tenantId: 'generated-unit', token: 'synthetic-session' });
  sessionStorage.clear();
  probe.request.mockImplementation(config => config.url.includes('/schools?') ? page([{ id: '10', name: '合成学校', rowVersion: '0', readiness: 'READY', operations }]) : config.url.includes('/terms?') ? page([]) : config.url.includes('/organizations?') ? page([org]) : ok(org));
});
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); schema.resources = structuredClone(original); probe.request.mockReset(); });
describe('Java-rendered education schema drives complete native Vue views; synthetic HTTP only', () => {
  it('carries SQL-derived string IDs, nullability, microseconds, DATE and name lengths', () => {
    expect(resource('organizations').detail.find(field => field.name === 'id')).toMatchObject({ scalar: 'uint64_string', nullable: false });
    expect(resource('organizations').detail.find(field => field.name === 'validTo')).toMatchObject({ scalar: 'utc_timestamp', nullable: true });
    expect(resource('terms').list.find(field => field.name === 'startsOn')).toMatchObject({ scalar: 'business_date', nullable: false });
    expect(resource('classes').create.find(field => field.name === 'name').maxLength).toBe(128);
  });
  it('changing generated create fields and maxLength changes actual form inputs, not a wrapper label', async () => {
    const school = resource('schools'); school.create = school.create.filter(field => field.name !== 'validFrom');
    school.create.find(field => field.name === 'name').maxLength = 37;
    const wrapper = open(); await flushPromises(); await button(wrapper, '新建学校').trigger('click');
    const form = wrapper.get('[data-testid="education-form"]');
    expect(form.get('input[data-field="name"]').attributes('maxlength')).toBe('37');
    expect(form.find('input[data-field="validFrom"]').exists()).toBe(false);
    expect(form.findAll('input')).toHaveLength(2);
  });
  it('generated list and detail allowlists determine real visible columns and detail fields', async () => {
    resource('organizations').list = resource('organizations').list.filter(field => field.name === 'name');
    resource('organizations').detail = resource('organizations').detail.filter(field => field.name === 'name');
    const wrapper = open(); await flushPromises(); await choose(wrapper);
    const records = wrapper.get('[data-testid="education-records"]');
    expect(records.text()).toContain(org.name); expect(records.text()).not.toContain(org.id); expect(records.text()).not.toContain(org.orgCode);
    await button(wrapper, '查看').trigger('click'); await flushPromises();
    const detail = wrapper.get('[data-testid="education-detail"]');
    expect(detail.text()).toContain(org.name); expect(detail.text()).not.toContain(org.id); expect(detail.findAll('dd')).toHaveLength(1);
  });
  it('generated operation restriction cannot be widened by server capability booleans', async () => {
    resource('organizations').allowedOperations = ['orgList', 'orgDetail'];
    const wrapper = open(); await flushPromises(); await choose(wrapper);
    expect(button(wrapper, '新建组织')).toBeUndefined();
    await button(wrapper, '查看').trigger('click'); await flushPromises(); expect(button(wrapper, '仅修改名称')).toBeUndefined();
  });
  it('generated endpoint descriptor performs an actual exact-string request', async () => {
    probe.request.mockResolvedValue(page([]));
    const signal = new AbortController().signal;
    await listClasses('9007199254740993', '9007199254740994', signal, '9007199254740995');
    const descriptor = endpoints.find(item => item.name === 'listClasses');
    expect(probe.request.mock.calls[0][0]).toMatchObject({ method: descriptor.method.toLowerCase(), url: descriptor.path.replace('{schoolId}', '9007199254740993') + '?limit=50&afterId=9007199254740995&termId=9007199254740994', signal });
  });
  it('generated write allowlist rejects unreviewed paths, extra fields and SQL-overlength values before HTTP', async () => {
    const body = { intentId: '10203040-5060-4070-8090-aabbccddeeff', expectedRowVersion: '0', name: '合成' };
    for (const [path, value] of [['/organizations/10/delete', body], ['/organizations/10/rename', { ...body, personId: '99' }], ['/organizations/10/rename', { ...body, name: '字'.repeat(191) }]]) {
      await expect(writeEntity('organizations', path, value, new AbortController().signal)).rejects.toMatchObject({ uncertain: false });
    }
    expect(probe.request).not.toHaveBeenCalled();
  });
  it('same-account login-session replacement closes the generated foundation workspace', async () => {
    const wrapper = open(); await flushPromises(); await choose(wrapper);
    expect(wrapper.get('[data-testid="education-records"]').text()).toContain(org.name);
    probe.identity.token = 'replacement-synthetic-session'; await flushPromises();
    // The conditional root changes from section to the real ElementPlus alert.
    expect(wrapper.findComponent({ name: 'ElAlert' }).props('title')).toContain('登录身份已变化');
    expect(wrapper.find('[data-testid="education-workspace"]').exists()).toBe(false);
    expect(wrapper.text()).not.toContain(org.name);
  });

});
