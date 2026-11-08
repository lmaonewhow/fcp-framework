import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import GeneratorApply from '../../integration/plus-ui/src/fcp/generator/GeneratorApply.vue';
const probe = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('@/utils/request', () => ({ default: probe.request }));
const profile = {
  profileId: 'education-p042-v1', version: '1', inputDigests: { 'P042.sql': 'verified-sql-digest' }, provenance: {},
  modules: ['foundation', 'roster'].map(module => ({ module, sourceId: `education:p042:${module}`, resources: [{ id: module, table: 'reviewed_table', label: module === 'foundation' ? '基础资料' : '名册关系', allowedOperations: [] }] }))
};
let wrapper;
// JSDOM has no animation frame/layout. Keep actual controls and transitions; emulate frame scheduling without browser geometry.
beforeEach(() => {
  vi.stubGlobal('requestAnimationFrame', callback => setTimeout(() => callback(performance.now()), 0));
  vi.stubGlobal('cancelAnimationFrame', handle => clearTimeout(handle));
});
afterEach(async () => { wrapper?.unmount(); wrapper = undefined; probe.request.mockReset(); await new Promise(resolve => setTimeout(resolve, 150)); document.body.innerHTML = '';  vi.unstubAllGlobals(); });
function open() {
  wrapper = mount(GeneratorApply, { attachTo: document.body, global: { plugins: [ElementPlus], stubs: { transition: false } } });
  return wrapper.vm.showEducation();
}
describe('education generator native ElementPlus entry (isolated HTTP, not browser E2E)', () => {
  it('renders locked profile provenance, selections, safe registration and truncated preview warning', async () => {
    probe.request.mockImplementation(async config => ({ code: 200, data: config.url.endsWith('/config') ? { enabled: true, workspace: 'unit-workspace' } : config.method === 'get' ? profile : {
      planId: 'reviewed', expiresAt: 'later', applicable: true, conflicts: [], files: [{ path: 'frontend/generated.vue', status: 'adopt_generated', currentPreview: 'part', candidatePreview: 'part', currentBytes: 70000, candidateBytes: 70000, currentPreviewTruncated: true, candidatePreviewTruncated: true }]
    } }));
    await open(); await flushPromises(); await vi.waitFor(() => expect(document.querySelector('[data-testid="generator-refresh"]')).not.toBeNull());
    expect(document.body.textContent).toContain('education-p042-v1');
    expect(document.body.textContent).toContain('verified-sql-digest');
    expect(document.body.textContent).toContain('人员资料 PENDING');
    expect(probe.request.mock.calls.filter(([config]) => config.method === 'post')).toHaveLength(0);
    const choices = document.querySelectorAll('[data-testid="education-generator-modules"] input[type="checkbox"]');
    expect(choices).toHaveLength(2); expect([...choices].every(choice => choice.checked)).toBe(true);
    choices[0].click(); await flushPromises();
    document.querySelector('[data-testid="generator-refresh"]').click(); await flushPromises();
    const planned = probe.request.mock.calls.map(([config]) => config).find(config => config.method === 'post');
    expect(planned.url).toBe('/fcp/generator/profiles/education-p042-v1/plans');
    expect(planned.data).toEqual({ profileId: 'education-p042-v1', version: '1', modules: ['roster'] });
    expect(document.body.textContent).toContain('登记已生成文件，不改源码');
    expect(document.querySelector('[data-testid="generator-truncated-warning"]').textContent).toContain('不是完整代码审阅');
    choices[1].click(); await flushPromises();
    expect(document.querySelector('[data-testid="generator-apply-confirm"]').disabled).toBe(true);
    expect(document.querySelector('[data-testid="generator-refresh"]').disabled).toBe(true);
  });
  it('fails closed on an unsupported profile and does not leave enabled preview', async () => {
    probe.request.mockImplementation(async config => ({ code: 200, data: config.url.endsWith('/config') ? { enabled: true, workspace: 'unit-workspace' } : { ...profile, profileId: 'unknown-profile' } }));
    await open(); await flushPromises(); await vi.waitFor(() => expect(document.querySelector('[data-testid="generator-refresh"]')).not.toBeNull());
    expect(document.body.textContent).toContain('教育生成规则响应不受支持');
    expect(document.querySelector('[data-testid="generator-refresh"]').disabled).toBe(true);
    expect(document.querySelector('[data-testid="generator-apply-confirm"]').disabled).toBe(true);
  });
});
