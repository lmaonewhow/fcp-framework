import { afterEach, describe, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import Workspace from '@/fcp/education/Workspace.vue';
import { operationLabels } from '@/fcp/education/contracts';
const probe = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('@/utils/request', () => ({ default: probe.request }));
vi.mock('@/store/modules/user', () => ({ useUserStore: () => ({ userId: 'ui-unit-user', tenantId: 'ui-unit-tenant' }) }));
const operations = Object.fromEntries(Object.keys(operationLabels).map(key => [key, true]));
const caps = { schemaVersion: 'education-foundation-v1', status: 'READY', operations, readiness: { schoolAuthorization: 'READY', transactionProtocol: 'READY', nativeAcceptance: 'NOT_RUN' }, blockers: [] };
const wrappers = [];
function open() { const wrapper = mount(Workspace, { props: { capabilities: caps }, global: { plugins: [ElementPlus] } }); wrappers.push(wrapper); return wrapper; }
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); probe.request.mockReset(); sessionStorage.clear(); });
describe('education rendered workspace: native Vue/ElementPlus, explicit identity and HTTP unit doubles', () => {
  it('renders real school creation inputs, cancel is harmless, and commits exact payload', async () => {
    probe.request.mockImplementation(async config => config.method === 'get' ? { code: 200, data: { items: [], nextAfterId: null } } : { code: 200, data: { operation: { intentId: config.data.intentId, outcome: 'COMMITTED' }, activation: { status: 'EXECUTION_GRANT_REQUIRED', schoolId: '9007199254740993' }, record: { id: '9007199254740993', parentId: null, orgCode: config.data.orgCode, orgKind: 'SCHOOL', name: config.data.name, validFrom: config.data.validFrom, validTo: null, createdAt: config.data.validFrom, rowVersion: '0' } } });
    const wrapper = open(); await flushPromises();
    const button = text => wrapper.findAll('button').find(item => item.text() === text);
    await button('新建学校').trigger('click'); expect(wrapper.find('[data-testid="education-form"]').exists()).toBe(true);
    await button('取消').trigger('click'); expect(probe.request.mock.calls.filter(([config]) => config.method === 'post')).toHaveLength(0);
    await button('新建学校').trigger('click');
    const inputs = wrapper.get('[data-testid="education-form"]').findAll('input');
    await inputs[0].setValue('测试学校'); await inputs[1].setValue('0001'); await inputs[2].setValue('2026-11-08T00:00:00.123456Z');
    await wrapper.get('[data-testid="education-form"] form').trigger('submit'); await flushPromises();
    const command = probe.request.mock.calls.map(([config]) => config).find(config => config.method === 'post');
    expect(command.url).toBe('/fcp/education/foundation/schools'); expect(command.data).toMatchObject({ name: '测试学校', orgCode: '0001', orgKind: 'SCHOOL', parentId: null, validFrom: '2026-11-08T00:00:00.123456Z' });
    expect(Object.keys(command.data).sort()).toEqual(['intentId', 'name', 'orgCode', 'orgKind', 'parentId', 'validFrom']);
    expect(wrapper.find('[data-testid="education-form"]').exists()).toBe(false); expect(wrapper.get('[data-testid="education-detail"]').text()).toContain('9007199254740993');
  });
  it('does not permit a new school after authority read fails', async () => {
    probe.request.mockRejectedValue({ response: { status: 403 } }); const wrapper = open(); await flushPromises();
    const button = wrapper.findAll('button').find(item => item.text() === '新建学校'); expect(button.element.disabled).toBe(true); expect(wrapper.find('[data-testid="education-form"]').exists()).toBe(false);
  });
});
