import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import request, { isRelogin } from '@/utils/request';
import { listRelations, getCapabilities, getRelation } from '@/fcp/education-roster/api';
import { row, caps, page } from './fixtures.mjs';
// Native request.ts and its real Axios transforms/interceptors execute unchanged.
// Only unrelated identity/cache/UI dependencies and the network adapter are isolated.
vi.mock('@/store/modules/user', () => ({ useUserStore: () => ({ userId: 'unit', tenantId: 'unit', token: '', logout: async () => {} }) }));
vi.mock('@/utils/auth', () => ({ getToken: () => '' }));
vi.mock('@/utils/ruoyi', () => ({ tansParams: () => '', blobValidate: () => true }));
vi.mock('@/plugins/cache', () => ({ default: { session: { getJSON: () => undefined, setJSON: () => {} } } }));
vi.mock('@/lang', () => ({ getLanguage: () => 'zh_CN' }));
vi.mock('@/utils/crypto', () => ({ encryptBase64: vi.fn(), encryptWithAes: vi.fn(), generateAesKey: vi.fn(), decryptWithAes: vi.fn(), decryptBase64: vi.fn() }));
vi.mock('@/utils/jsencrypt', () => ({ encrypt: vi.fn(), decrypt: vi.fn() }));
vi.mock('@/router/index.ts', () => ({ default: { replace: vi.fn(), currentRoute: { value: { fullPath: '/' } } } }));
const previousAdapter = request.defaults.adapter;
beforeEach(() => {
  globalThis.ElMessage = Object.assign(vi.fn(), { error: vi.fn() });
  globalThis.ElNotification = { error: vi.fn() };
  globalThis.ElMessageBox = { confirm: () => Promise.reject(new Error('unit dismissal')) };
  isRelogin.show = false;
});
afterEach(() => { request.defaults.adapter = previousAdapter; });
function reply(status, code, error) {
  request.defaults.adapter = async config => {
    const response = { status, statusText: 'unit', headers: {}, config, request: {}, data: JSON.stringify({ code, msg: error, error }) };
    if (status >= 400) throw Object.assign(new Error(`Request failed with status code ${status}`), { response, config });
    return response;
  };
}
describe('roster with actual pinned plus-ui Axios interceptor, synthetic network adapter', () => {
  it.each([400, 401, 403, 409, 500])('HTTP200/non200 envelope %s is a failure, never an empty list', async code => {
    reply(200, code, 'PRIVATE_ERROR');
    await expect(listRelations('10', 'enrollments', new AbortController().signal)).rejects.toMatchObject({ status: code, message: 'ROSTER_READ_FAILED' });
  });
  it.each([400, 403, 409, 500])('preserves actual HTTP %s after native transport rejects', async code => {
    reply(code, code, 'PRIVATE_ERROR');
    await expect(listRelations('10', 'enrollments', new AbortController().signal)).rejects.toMatchObject({ status: code });
  });
  it('does not convert malformed success or network error into []', async () => {
    request.defaults.adapter = async config => ({ status: 200, statusText: 'unit', headers: {}, config, request: {}, data: '{}' });
    await expect(listRelations('10', 'enrollments', new AbortController().signal)).rejects.toThrow();
    request.defaults.adapter = async () => { throw new Error('network'); };
    await expect(getCapabilities(new AbortController().signal)).rejects.toMatchObject({ status: undefined });
  });
  it('uses only roster endpoints and retains string IDs after the native JSON transform', async () => {
    const calls = [];
    request.defaults.adapter = async config => {
      calls.push(config);
      return { status: 200, statusText: 'unit', headers: {}, config, request: {}, data: JSON.stringify({ code: 200, data: config.url.endsWith('/capabilities') ? caps : config.url.endsWith('/9007199254740993') ? row('9007199254740993') : page([row('9007199254740993')]) }) };
    };
    await getCapabilities(new AbortController().signal);
    const listed = await listRelations('10', 'enrollments', new AbortController().signal, '9007199254740992');
    expect(listed.items[0].id).toBe('9007199254740993');
    const detail = await getRelation('10', 'enrollments', listed.items[0].id, new AbortController().signal);
    expect(detail.rowVersion).toBe('9007199254740993');
    expect(calls.map(c => c.url)).toEqual(['/fcp/education/roster/capabilities', '/fcp/education/roster/schools/10/enrollments?limit=50&afterId=9007199254740992', '/fcp/education/roster/schools/10/enrollments/9007199254740993']);
    expect(calls.every(c => c.method === 'get' && c.data === undefined)).toBe(true);
  });
  it('rejects a mismatched detail identity rather than opening another record', async () => {
    request.defaults.adapter = async config => ({ status: 200, statusText: 'unit', headers: {}, config, request: {}, data: JSON.stringify({ code: 200, data: row('2') }) });
    await expect(getRelation('10', 'enrollments', '1', new AbortController().signal)).rejects.toThrow();
  });
});
