import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import Editor from '@/components/Editor/index.vue';
import FileUpload from '@/components/FileUpload/index.vue';

const probes = vi.hoisted(() => ({ listByIds: vi.fn(), delOss: vi.fn() }));

vi.mock('@/utils/request', () => ({ globalHeaders: () => ({}) }));
vi.mock('@/api/system/oss', () => ({ listByIds: probes.listByIds, delOss: probes.delOss }));

const wrappers = [];
const passthrough = tag => defineComponent({
  inheritAttrs: true,
  setup(_, { slots }) { return () => h(tag, slots.default?.()); }
});
const global = {
  stubs: { ElUpload: passthrough('div'), ElButton: passthrough('button'), ElLink: passthrough('a') },
  mocks: { $modal: { msgError: vi.fn(), closeLoading: vi.fn() } }
};

beforeEach(() => {
  probes.listByIds.mockReset().mockImplementation(async id => ({ data: [{ originalName: 'server.pdf', url: '/server.pdf', ossId: id }] }));
  probes.delOss.mockReset().mockResolvedValue({});
});
afterEach(() => { while (wrappers.length) wrappers.pop().unmount(); vi.restoreAllMocks(); });

describe('native components: isolated DOM interaction (API/selection doubles; real Quill toolbar; not native E2E)', () => {
  it('the image-toolbar action calls the current editor instance', async () => {
    const wrapper = mount(Editor, { global, props: { modelValue: '' } });
    wrappers.push(wrapper);
    await flushPromises();
    const quill = wrapper.findComponent({ name: 'QuillEditor' }).vm.getQuill();
    const format = vi.spyOn(quill, 'format').mockImplementation(() => undefined);
    // JSDOM does not implement browser text-selection geometry. Keep the real
    // Quill component and toolbar, and double only selection/final formatting.
    vi.spyOn(quill, 'getSelection').mockReturnValue({ index: 0, length: 0 });
    vi.spyOn(quill, 'focus').mockImplementation(() => {});
    const action = wrapper.get('button.ql-image');
    action.element.classList.add('ql-active');
    await action.trigger('click');
    expect(format).toHaveBeenCalledExactlyOnceWith('image', true);
  });

  it.each([
    ['single descriptor', { name: 'report.pdf', url: '/report.pdf', ossId: '41' }],
    ['descriptor array', [{ name: 'report.pdf', url: '/report.pdf', ossId: '41' }]]
  ])('%s displays and deletes the actual item without an ID lookup', async (_, value) => {
    const wrapper = mount(FileUpload, { global, props: { modelValue: value } });
    wrappers.push(wrapper);
    await flushPromises();
    expect(wrapper.get('a').text()).toBe('report.pdf');
    expect(wrapper.get('a').attributes('href')).toBe('/report.pdf');
    expect(probes.listByIds).not.toHaveBeenCalled();
    const remove = wrapper.findAll('button').find(button => button.text() === '删除');
    expect(remove).toBeDefined();
    await remove.trigger('click');
    expect(probes.delOss).toHaveBeenCalledExactlyOnceWith('41');
    expect(wrapper.emitted('update:modelValue')).toEqual([['']]);
  });

  it('an ID string still loads server metadata before rendering', async () => {
    const wrapper = mount(FileUpload, { global, props: { modelValue: '41,42' } });
    wrappers.push(wrapper);
    await flushPromises();
    expect(probes.listByIds).toHaveBeenCalledExactlyOnceWith('41,42');
    expect(wrapper.get('a').text()).toBe('server.pdf');
  });
});
