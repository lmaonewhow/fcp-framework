import { describe, expect, it } from 'vitest';
import { createRouter, createMemoryHistory } from 'vue-router';

describe('the pinned router retains navigation serialization', () => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/workflow/design/index', component: { render: () => null } }]
  });
  it.each([true, false])('explicit disabled=%s string produces the same URL and query', disabled => {
    const legacy = router.resolve({ path: '/workflow/design/index', query: { disabled, definitionId: '17' } });
    const compatible = router.resolve({ path: '/workflow/design/index', query: { disabled: String(disabled), definitionId: '17' } });
    expect(compatible.fullPath).toBe(legacy.fullPath);
    expect(compatible.query).toEqual(legacy.query);
  });
  it('dropping the ignored derived fullPath field preserves tag navigation', () => {
    const location = { path: '/workflow/design/index', query: { definitionId: '17' } };
    expect(router.resolve(location).fullPath).toBe(router.resolve({ ...location, fullPath: '/unused-derived-value' }).fullPath);
  });
});
