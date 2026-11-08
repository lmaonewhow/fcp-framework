import request from '@/utils/request';
import type { GeneratorOperation, GeneratorPlan } from './types';

// The native interceptor unwraps AxiosResponse, leaving the R<T> envelope.
async function call<T>(method: 'get' | 'post', path: string, data?: unknown, signal?: AbortSignal): Promise<T> {
  const result = (await request({ url: `/fcp/generator${path}`, method, data, signal, headers: path === '/plans' ? { repeatSubmit: false } : undefined })) as unknown as {
    code: number;
    msg?: string;
    data: T;
  };
  if (result.code !== 200) throw new Error(result.msg || '操作未完成');
  return result.data;
}
export const getConfig = (signal?: AbortSignal) => call<{ enabled: boolean; workspace: string }>('get', '/config', undefined, signal);
export const createPlan = (tableIds: string[], signal?: AbortSignal) => call<GeneratorPlan>('post', '/plans', { tableIds }, signal);
export const applyPlan = (id: string) => call<GeneratorOperation>('post', `/plans/${encodeURIComponent(id)}/apply`, {});
export const getOperations = (signal?: AbortSignal) => call<{ operations: GeneratorOperation[]; recoveryRequired?: boolean }>('get', '/operations', undefined, signal);
export const rollback = (id: string) => call<GeneratorOperation>('post', `/operations/${encodeURIComponent(id)}/rollback`, {});
