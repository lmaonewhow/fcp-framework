import { expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { h } from 'vue';
import { ElTable, ElTableColumn } from 'element-plus';

it('locates an exact ID cell even when native ElementPlus adjacent cells have no word boundary', async () => {
  // JSDOM has no animation-frame clock; execute layout callbacks synchronously.
  vi.stubGlobal('requestAnimationFrame', callback => { callback(0); return 0; });
  const id = '9007199254740993';
  const wrapper = mount(ElTable, {
    props: { data: [{ id, name: 'Browser synthetic organization' }, { id: id + '0', name: 'Other synthetic row' }] },
    slots: { default: () => [h(ElTableColumn, { prop: 'id', label: '编号' }), h(ElTableColumn, { prop: 'name', label: '名称' })] }
  });
  try {
    await flushPromises();
    const rows = wrapper.findAll('tbody tr');
    expect(rows).toHaveLength(2);
    // Reproduces the failed browser predicate using actual ElementPlus DOM.
    expect(new RegExp('\\b' + id + '\\b').test(rows[0].element.textContent)).toBe(false);
    const exact = rows.filter(row => row.get('td:first-child .cell').element.textContent.trim() === id);
    expect(exact).toHaveLength(1);
    expect(exact[0].text()).toContain('Browser synthetic organization');
  } finally { wrapper.unmount(); vi.unstubAllGlobals(); }
});
