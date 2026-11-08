import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import request, { isRelogin } from '@/utils/request';
import { writeEntity } from '@/fcp/education/businessApi';
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
const body = { intentId: '10203040-5060-4070-8090-aabbccddeeff', expectedRowVersion: '0', name: '校' };
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
describe('education with actual pinned plus-ui response interceptor', () => {
  it.each([400, 401, 403, 409])('retains definite HTTP200 envelope code %s after native interceptor rejects Error', async code => {
    reply(200, code, 'EDUCATION_REJECTED');
    await expect(writeEntity('organizations', '/organizations/1/rename', body, new AbortController().signal)).rejects.toMatchObject({ code: 'EDUCATION_REJECTED', uncertain: false });
  });
  it.each([400, 403, 409])('retains real HTTP %s domain rejection', async code => {
    reply(code, code, 'EDUCATION_REJECTED');
    await expect(writeEntity('organizations', '/organizations/1/rename', body, new AbortController().signal)).rejects.toMatchObject({ code: 'EDUCATION_REJECTED', uncertain: false });
  });
  it('keeps server-500 envelope uncertain', async () => {
    reply(200, 500, 'COMMIT_UNKNOWN');
    await expect(writeEntity('organizations', '/organizations/1/rename', body, new AbortController().signal)).rejects.toMatchObject({ code: 'COMMIT_UNKNOWN', uncertain: true });
  });
  it('keeps a malformed successful HTTP response uncertain', async () => {
    request.defaults.adapter = async config => ({ status: 200, statusText: 'unit', headers: {}, config, request: {}, data: '{}' });
    await expect(writeEntity('organizations', '/organizations/1/rename', body, new AbortController().signal)).rejects.toMatchObject({ uncertain: true });
  });
  it('keeps a network failure uncertain without fabricating response metadata', async () => {
    request.defaults.adapter = async config => { throw Object.assign(new Error('Network Error'), { config }); };
    await expect(writeEntity('organizations', '/organizations/1/rename', body, new AbortController().signal)).rejects.toMatchObject({ code: 'HTTP_UNKNOWN', uncertain: true });
  });
});
