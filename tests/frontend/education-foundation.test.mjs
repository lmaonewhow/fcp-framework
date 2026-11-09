import { afterEach, describe, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import Foundation from '@/views/fcp/education/index.vue';
import { parseCapabilities, operationLabels } from '@/fcp/education/contracts';
const probes = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('@/store/modules/user', () => ({ useUserStore: () => ({ userId: 'unit-user', tenantId: 'unit-tenant' }) }));
vi.mock('@/utils/request', () => ({ default: probes.request }));
const capabilities = () => ({ schemaVersion: 'education-foundation-v1', status: 'BLOCKED',
  readiness: { schoolAuthorization: 'NOT_IMPLEMENTED', transactionProtocol: 'NOT_IMPLEMENTED', nativeAcceptance: 'NOT_RUN' },
  operations: Object.fromEntries(Object.keys(operationLabels).map(key => [key, false])),
  blockers: ['SCHOOL_AUTHORIZATION_NOT_IMPLEMENTED'] });
const envelope = () => ({ code: 200, data: capabilities() });
const wrappers = [];
const open = () => { const wrapper = mount(Foundation, { global: { plugins: [ElementPlus] } }); wrappers.push(wrapper); return wrapper; };
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); probes.request.mockReset(); });
describe('education foundation: real Vue/ElementPlus, isolated API double, not native E2E', () => {
  it('renders actual blocked capability, without fake data, writes or controls', async () => {
    probes.request.mockResolvedValue(envelope()); const wrapper = open(); await flushPromises();
    expect(probes.request).toHaveBeenCalledOnce();
    expect(probes.request.mock.calls[0][0]).toMatchObject({ url: '/fcp/education/foundation/capabilities', method: 'get' });
    expect(Object.keys(probes.request.mock.calls[0][0]).sort()).toEqual(['method', 'signal', 'url']);
    expect(wrapper.text()).toContain('尚未接入逐学校管理授权');
    expect(wrapper.findAll('button').map(button => button.text())).toEqual(['重新检查']);
    expect(wrapper.find('input').exists()).toBe(false);
  });
  it.each([null, {}, { ...capabilities(), status: 'FUTURE' }, { ...capabilities(), operations: { ...capabilities().operations, orgCreate: 'true' } }])('fails closed on incompatible data %s', value => {
    expect(() => parseCapabilities(value)).toThrow();
  });
  it.each([401, 403, 503])('handles host envelope failure %s without previous capability', async code => {
    probes.request.mockResolvedValueOnce(envelope()).mockResolvedValueOnce({ code, data: capabilities() });
    const wrapper = open(); await flushPromises(); await wrapper.get('[data-testid="education-refresh"]').trigger('click'); await flushPromises();
    expect(wrapper.find('[data-testid="education-error"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="education-blockers"]').exists()).toBe(false);
  });
  it('does not expose server exception content', async () => {
    probes.request.mockRejectedValue(new Error('secret database details')); const wrapper = open(); await flushPromises();
    expect(wrapper.findComponent({ name: 'ElAlert' }).props('title')).toContain('所有操作保持关闭'); expect(wrapper.html()).not.toContain('secret');
  });
  it('suppresses duplicate clicks, aborts cancellation and ignores late responses', async () => {
    let resolve; probes.request.mockImplementationOnce(() => new Promise(done => { resolve = done; }));
    const wrapper = open(); await wrapper.get('[data-testid="education-refresh"]').trigger('click');
    expect(probes.request).toHaveBeenCalledOnce();
    const signal = probes.request.mock.calls[0][0].signal;
    await wrapper.get('[data-testid="education-cancel"]').trigger('click'); expect(signal.aborted).toBe(true);
    resolve(envelope()); await flushPromises(); expect(wrapper.find('[data-testid="education-blockers"]').exists()).toBe(false);
    probes.request.mockResolvedValue(envelope()); await wrapper.get('[data-testid="education-refresh"]').trigger('click'); await flushPromises();
    expect(wrapper.find('[data-testid="education-blockers"]').exists()).toBe(true);
  });
  it('aborts on route disposal and rechecks on remount', async () => {
    probes.request.mockImplementationOnce(() => new Promise(() => {})); const wrapper = open(); const signal = probes.request.mock.calls[0][0].signal;
    wrapper.unmount(); expect(signal.aborted).toBe(true);
    probes.request.mockResolvedValue(envelope()); open(); await flushPromises(); expect(probes.request).toHaveBeenCalledTimes(2);
  });
});
