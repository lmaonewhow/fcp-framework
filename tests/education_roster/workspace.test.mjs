import { afterEach, describe, expect, it, vi } from 'vitest';
import { reactive, h, ref, KeepAlive, defineComponent } from 'vue';
import { mount, flushPromises } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import Workspace from '@/fcp/education-roster/Workspace.vue';
import { caps, operations, row, school, page, ok, deferred } from './fixtures.mjs';
const probe = vi.hoisted(() => ({ request: vi.fn(), identity: undefined }));
vi.mock('@/utils/request', () => ({ default: probe.request }));
vi.mock('@/store/modules/user', () => ({ useUserStore: () => probe.identity }));
const wrappers = [];
function open(handler = () => ok(page([]))) {
  probe.identity = reactive({ userId: 'synthetic-user', tenantId: 'synthetic-tenant', token: 'synthetic-session' });
  probe.request.mockImplementation(config => config.url.endsWith('/capabilities') ? Promise.resolve(ok(caps)) : config.url === '/fcp/education/roster/schools?limit=50' ? Promise.resolve(ok(page([school('10'), school('20')]))) : handler(config));
  const wrapper = mount(Workspace, { global: { plugins: [ElementPlus], stubs: { transition: false, 'transition-group': false } } }); wrappers.push(wrapper); return wrapper;
}
async function choose(wrapper, id = '10') { wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('update:modelValue', id); await flushPromises(); }
async function tab(wrapper, name) { wrapper.findComponent({ name: 'ElTabs' }).vm.$emit('update:modelValue', name); await flushPromises(); }
const button = (wrapper, label) => wrapper.findAll('button').find(item => item.text() === label);
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); probe.request.mockReset(); });
describe('real Vue + ElementPlus roster rendering; mocked HTTP and identity, not native acceptance', () => {
  it('renders three tabs, exact school selector, pending policy and only safe list/detail fields', async () => {
    const privateRow = row('9007199254740993', '10', 'enrollments', { displayName: '绝不显示的姓名', personId: 'SECRET_PERSON', studentCode: '001SECRET', privateRef: 'secret' });
    const wrapper = open(config => ok(config.url.endsWith('/9007199254740993') ? privateRow : page([privateRow]))); await flushPromises();
    expect(wrapper.text()).toContain('关联信息尚未开放'); expect(wrapper.text()).not.toContain('当前学校此项查询没有记录');
    await choose(wrapper); expect(wrapper.text()).toContain('合成学校10（10）');
    for (const label of ['学籍关系', '入班历史', '任教关系']) expect(wrapper.text()).toContain(label);
    await button(wrapper, '查看').trigger('click'); await flushPromises();
    expect(wrapper.get('[data-testid="roster-detail"]').text()).toContain('2026-11-08T00:00:00.123456Z');
    expect(wrapper.text()).not.toMatch(/绝不显示|SECRET|privateRef/);
    expect(wrapper.findAll('button').map(item => item.text()).join('|')).not.toMatch(/新增|保存|修改|删除/);
    await button(wrapper, '关闭详情').trigger('click'); expect(wrapper.find('[data-testid="roster-detail"]').exists()).toBe(false);
    expect(probe.request.mock.calls.every(([config]) => config.method === 'get' && config.url.startsWith('/fcp/education/roster/'))).toBe(true);
  });
  it('clears old school data synchronously and ignores late success despite ignored abort', async () => {
    const late = deferred();
    const wrapper = open(config => config.url.includes('/schools/10/') ? late.promise : ok(page([row('201', '20')]))); await flushPromises();
    await choose(wrapper, '10'); const oldSignal = probe.request.mock.calls.at(-1)[0].signal;
    await choose(wrapper, '20'); expect(oldSignal.aborted).toBe(true); expect(wrapper.text()).toContain('201');
    late.resolve(ok(page([row('101')]))); await flushPromises();
    expect(wrapper.get('[data-testid="roster-records"]').text()).toContain('201'); expect(wrapper.get('[data-testid="roster-records"]').text()).not.toContain('101');
  });
  it('late denied old-school request cannot clear a newer authorized school', async () => {
    const late = deferred(); const wrapper = open(config => config.url.includes('/schools/10/') ? late.promise : ok(page([row('201', '20')]))); await flushPromises();
    await choose(wrapper, '10'); await choose(wrapper, '20'); late.reject({ response: { status: 403 } }); await flushPromises();
    expect(wrapper.find('[data-testid="roster-error"]').exists()).toBe(false); expect(wrapper.text()).toContain('201');
  });
  it('switching tab clears prior detail and ignores a late detail response', async () => {
    const late = deferred(); const wrapper = open(config => config.url.endsWith('/enrollments/101') ? late.promise : ok(page([row(config.url.includes('memberships') ? '102' : '101', '10', config.url.includes('memberships') ? 'memberships' : 'enrollments')]))); await flushPromises();
    await choose(wrapper); await button(wrapper, '查看').trigger('click'); await tab(wrapper, 'memberships');
    late.resolve(ok(row('101'))); await flushPromises(); expect(wrapper.find('[data-testid="roster-detail"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('合成班级');
  });
  it('cancels without retaining records or interpreting the result as an empty list', async () => {
    const late = deferred(); const wrapper = open(() => late.promise); await flushPromises(); await choose(wrapper);
    await wrapper.get('[data-testid="roster-cancel"]').trigger('click'); late.resolve(ok(page([row('101')]))); await flushPromises();
    expect(wrapper.text()).toContain('本次查询已取消'); expect(wrapper.text()).not.toContain('当前学校此项查询没有记录'); expect(wrapper.text()).not.toContain('101');
  });
  it('repeated refresh and detail clicks produce only one outstanding read', async () => {
    const late = deferred(); const wrapper = open(() => late.promise); await flushPromises(); await choose(wrapper);
    const before = probe.request.mock.calls.length; await wrapper.get('[data-testid="roster-refresh"]').trigger('click'); await wrapper.get('[data-testid="roster-refresh-list"]').trigger('click');
    expect(probe.request.mock.calls).toHaveLength(before); late.resolve(ok(page([row('101')]))); await flushPromises();
    const detail = deferred(); probe.request.mockImplementation(() => detail.promise);
    await button(wrapper, '查看').trigger('click'); await button(wrapper, '查看').trigger('click'); expect(probe.request.mock.calls).toHaveLength(before + 1);
    detail.resolve(ok(row('101'))); await flushPromises();
  });
  it('appends pages using exact cursor strings and disables duplicate pagination clicks', async () => {
    const late = deferred(); const wrapper = open(config => config.url.includes('afterId=') ? late.promise : ok(page([row('9007199254740993')], '9007199254740993'))); await flushPromises(); await choose(wrapper);
    await wrapper.get('[data-testid="roster-more-records"]').trigger('click'); await wrapper.get('[data-testid="roster-more-records"]').trigger('click');
    expect(probe.request.mock.calls.filter(([config]) => config.url.includes('afterId='))).toHaveLength(1);
    expect(probe.request.mock.calls.at(-1)[0].url).toContain('afterId=9007199254740993'); late.resolve(ok(page([row('9007199254740994')]))); await flushPromises();
    expect(wrapper.get('[data-testid="roster-records"]').text()).toContain('9007199254740993'); expect(wrapper.text()).toContain('9007199254740994');
    expect(wrapper.find('[data-testid="roster-more-records"]').exists()).toBe(false);
  });
  it('authorization failure clears all schools, rows and details, raw error text is never rendered', async () => {
    const wrapper = open(config => ok(config.url.endsWith('/101') ? row('101') : page([row('101')]))); await flushPromises(); await choose(wrapper); await button(wrapper, '查看').trigger('click'); await flushPromises();
    probe.request.mockResolvedValue({ code: 403, msg: 'SECRET_AUTH', data: page([]) }); await wrapper.get('[data-testid="roster-refresh-list"]').trigger('click'); await flushPromises();
    expect(wrapper.get('[data-testid="roster-error"]').text()).toContain('该错误不代表没有记录');
    expect(wrapper.find('[data-testid="roster-detail"]').exists()).toBe(false); expect(wrapper.find('[data-testid="roster-records"]').exists()).toBe(false); expect(wrapper.find('[data-testid="roster-school"]').exists()).toBe(false); expect(wrapper.text()).not.toMatch(/SECRET_AUTH|101/);
  });
  it('identity change clears all state and blocks late responses and retry under old component', async () => {
    const late = deferred(); const wrapper = open(() => late.promise); await flushPromises(); await choose(wrapper);
    probe.identity.tenantId = 'another-tenant'; await flushPromises(); late.resolve(ok(page([row('101')]))); await flushPromises();
    expect(wrapper.text()).toContain('登录身份已变化'); expect(wrapper.find('[data-testid="roster-school"]').exists()).toBe(false); expect(wrapper.text()).not.toContain('101');
  });
  it('student-only school grant does not expose teacher records or a misleading empty state', async () => {
    const wrapper = open(); probe.request.mockImplementation(config => ok(config.url.endsWith('/capabilities') ? caps : config.url.includes('/schools?') ? page([school('10', { operations: { ...operations, assignmentList: false, assignmentDetail: false } })]) : page([row('101')]))); await wrapper.get('[data-testid="roster-refresh"]').trigger('click'); await flushPromises();
    // Initial mount may already be in flight, so recheck after it completes.
    await wrapper.get('[data-testid="roster-refresh"]').trigger('click'); await flushPromises(); await choose(wrapper); const before = probe.request.mock.calls.length; await tab(wrapper, 'assignments');
    expect(probe.request.mock.calls).toHaveLength(before); expect(wrapper.text()).toContain('当前学校未开放此项查询'); expect(wrapper.text()).not.toContain('当前学校此项查询没有记录');
  });
  it('server blocked state does not request the directory or claim the read-only page is usable', async () => {
    const wrapper = open(); await flushPromises(); probe.request.mockResolvedValue(ok({ ...caps, status: 'BLOCKED' })); probe.request.mockClear();
    await wrapper.get('[data-testid="roster-refresh"]').trigger('click'); await flushPromises(); expect(probe.request).toHaveBeenCalledTimes(1);
    expect(wrapper.get('[data-testid="roster-blocked"]').text()).toContain('名册查询尚未开放'); expect(wrapper.find('[data-testid="roster-school"]').exists()).toBe(false);
  });
  it('unmount aborts the pending request and no late result is retained', async () => {
    const late = deferred(); const wrapper = open(() => late.promise); await flushPromises(); await choose(wrapper); const signal = probe.request.mock.calls.at(-1)[0].signal;
    wrapper.unmount(); wrappers.pop(); expect(signal.aborted).toBe(true); late.resolve(ok(page([row('101')]))); await flushPromises();
  });
  it('session replacement for the same account also clears cached records', async () => {
    const wrapper = open(() => ok(page([row('101')]))); await flushPromises(); await choose(wrapper);
    probe.identity.token = 'replacement-session'; await flushPromises();
    expect(wrapper.text()).toContain('登录身份已变化'); expect(wrapper.find('[data-testid="roster-records"]').exists()).toBe(false);
  });
  it('paginates exact school choices and retains directory order without arbitrary school entry', async () => {
    const wrapper = open(); await flushPromises();
    probe.request.mockImplementation(config => ok(config.url.endsWith('/capabilities') ? caps : config.url.includes('afterId=') ? page([school('20')]) : page([school('10')], '10')));
    await wrapper.get('[data-testid="roster-refresh"]').trigger('click'); await flushPromises();
    await wrapper.get('[data-testid="roster-more-schools"]').trigger('click'); await flushPromises();
    expect(probe.request.mock.calls.at(-1)[0].url).toBe('/fcp/education/roster/schools?limit=50&afterId=10');
    expect(wrapper.find('[data-testid="roster-more-schools"]').exists()).toBe(false);
    const before = probe.request.mock.calls.length; await choose(wrapper, '999');
    expect(probe.request.mock.calls).toHaveLength(before); expect(wrapper.find('[data-testid="roster-records"]').exists()).toBe(false);
  });
  it('a malformed or cross-school detail closes all displayed metadata', async () => {
    const wrapper = open(config => ok(config.url.endsWith('/101') ? row('101', '20') : page([row('101')]))); await flushPromises(); await choose(wrapper);
    await button(wrapper, '查看').trigger('click'); await flushPromises();
    expect(wrapper.find('[data-testid="roster-error"]').exists()).toBe(true); expect(wrapper.find('[data-testid="roster-records"]').exists()).toBe(false);
  });
  it('keep-alive deactivation drops records and reactivation rechecks authority without a duplicate request', async () => {
    probe.identity = reactive({ userId: 'synthetic-user', tenantId: 'synthetic-tenant', token: 'synthetic-session' });
    probe.request.mockImplementation(config => ok(config.url.endsWith('/capabilities') ? caps : config.url.includes('/schools?') ? page([school('10')]) : page([row('101')])));
    const visible = ref(true);
    const Host = defineComponent({ setup: () => () => h(KeepAlive, {}, { default: () => visible.value ? h(Workspace) : null }) });
    const wrapper = mount(Host, { global: { plugins: [ElementPlus], stubs: { transition: false, 'transition-group': false } } }); wrappers.push(wrapper);
    await flushPromises(); expect(probe.request.mock.calls.filter(([config]) => config.url.endsWith('/capabilities'))).toHaveLength(1);
    await choose(wrapper); expect(wrapper.text()).toContain('101');
    visible.value = false; await flushPromises(); expect(wrapper.text()).not.toContain('101');
    visible.value = true; await flushPromises();
    expect(probe.request.mock.calls.filter(([config]) => config.url.endsWith('/capabilities'))).toHaveLength(2);
    expect(wrapper.find('[data-testid="roster-records"]').exists()).toBe(false);
  });

  it('cancelling the initial directory read does not claim that no schools exist', async () => {
    const wrapper = open(); await flushPromises(); const late = deferred();
    probe.request.mockImplementation(config => config.url.endsWith('/capabilities') ? ok(caps) : late.promise);
    await wrapper.get('[data-testid="roster-refresh"]').trigger('click'); await flushPromises();
    await wrapper.get('[data-testid="roster-cancel"]').trigger('click'); late.resolve(ok(page([]))); await flushPromises();
    expect(wrapper.text()).toContain('本次查询已取消'); expect(wrapper.find('[data-testid="roster-no-schools"]').exists()).toBe(false);
  });

  it('real ElementPlus admin sequence closes a mounted table when switching to a pending school without unhandled errors', async () => {
    // This is a component reproduction attempt using synthetic HTTP, not proof
    // about the native CI pageerror whose original stack was not retained.
    probe.identity = reactive({ userId: 'synthetic-admin', tenantId: 'synthetic-tenant', token: 'synthetic-session' });
    const schools = [school('10'), school('20'), school('30', { readiness: 'EXECUTION_GRANT_MISSING', operations: {} })];
    probe.request.mockImplementation(config => {
      if (config.url.endsWith('/capabilities')) return ok(caps);
      if (config.url.includes('/schools?')) return ok(page(schools));
      const match = config.url.match(/\/schools\/(10|20)\/(enrollments|memberships|assignments)(?:\/(101|201))?/);
      if (!match) throw new Error('Unexpected synthetic request');
      const result = row(match[1] === '10' ? '101' : '201', match[1], match[2]);
      return ok(match[3] ? result : page([result]));
    });
    const container = document.createElement('div'); document.body.appendChild(container);
    const wrapper = mount(Workspace, { attachTo: container, global: { plugins: [ElementPlus], stubs: { transition: false, 'transition-group': false } } }); wrappers.push(wrapper);
    const selectRealOption = async id => {
      await wrapper.get('[data-testid="roster-school"] .el-select__wrapper').trigger('click'); await flushPromises();
      const option = wrapper.findAllComponents({ name: 'ElOption' }).find(item => item.props('value') === id);
      expect(option).toBeDefined(); await option.trigger('click'); await flushPromises();
    };
    try {
      await flushPromises(); await selectRealOption('10');
      for (const [kind, label] of [['enrollments', '学籍关系'], ['memberships', '入班历史'], ['assignments', '任教关系']]) {
        const target = wrapper.findAll('[role="tab"]').find(item => item.text() === label);
        await target.trigger('click'); await flushPromises();
        await button(wrapper, '查看').trigger('click'); await flushPromises(); expect(wrapper.get('[data-testid="roster-detail"]').text()).toContain('101');
        await button(wrapper, '关闭详情').trigger('click'); await flushPromises();
      }
      await selectRealOption('20'); expect(wrapper.get('[data-testid="roster-records"]').text()).toContain('201');
      const requests = probe.request.mock.calls.length;
      await selectRealOption('30');
      expect(wrapper.text()).toContain('该学校的执行许可尚未就绪'); expect(wrapper.find('[data-testid="roster-records"]').exists()).toBe(false);
      expect(wrapper.find('[data-testid="roster-detail"]').exists()).toBe(false); expect(probe.request.mock.calls).toHaveLength(requests);
      // Let real ElementPlus debounce/frame work run after table unmount. Vitest
      // treats any uncaught rejection as a failure; no pageerror category is filtered.
      await new Promise(resolve => setTimeout(resolve, 700)); await flushPromises();
      expect(wrapper.find('[data-testid="roster-records"]').exists()).toBe(false);
    } finally { wrapper.unmount(); wrappers.pop(); container.remove(); }
  });

});
