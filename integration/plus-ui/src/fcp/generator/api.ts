import request from '@/utils/request';
import type { EducationModule, EducationProfile, GeneratorOperation, GeneratorPlan } from './types';

// The native interceptor unwraps AxiosResponse, leaving the R<T> envelope.
async function call<T>(method: 'get' | 'post', path: string, data?: unknown, signal?: AbortSignal): Promise<T> {
  const result = (await request({
    url: `/fcp/generator${path}`,
    method,
    data,
    signal,
    headers: path.endsWith('/plans') ? { repeatSubmit: false } : undefined
  })) as unknown as {
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
export const getOperations = (signal?: AbortSignal) =>
  call<{ operations: GeneratorOperation[]; recoveryRequired?: boolean }>('get', '/operations', undefined, signal);
export const rollback = (id: string) => call<GeneratorOperation>('post', `/operations/${encodeURIComponent(id)}/rollback`, {});

export async function getEducationProfile(signal?: AbortSignal): Promise<EducationProfile> {
  const result = await call<EducationProfile>('get', '/profiles/education-p042-v1', undefined, signal);
  if (
    result?.profileId !== 'education-p042-v1' ||
    typeof result.version !== 'string' ||
    !result.version ||
    !Array.isArray(result.modules) ||
    result.modules.length !== 2 ||
    !['foundation', 'roster'].every((name) => result.modules.filter((module) => module.module === name).length === 1) ||
    !result.modules.every(
      (module) =>
        module &&
        module.sourceId === `education:p042:${module.module}` &&
        Array.isArray(module.resources) &&
        module.resources.every(
          (resource) =>
            resource &&
            typeof resource.id === 'string' &&
            typeof resource.table === 'string' &&
            typeof resource.label === 'string' &&
            Array.isArray(resource.allowedOperations) &&
            resource.allowedOperations.every((operation) => typeof operation === 'string')
        )
    ) ||
    !result.inputDigests ||
    typeof result.inputDigests !== 'object' ||
    Array.isArray(result.inputDigests) ||
    !Object.values(result.inputDigests).every((value) => typeof value === 'string')
  ) {
    throw new Error('教育生成规则响应不受支持，请检查服务版本后重试。');
  }
  return result;
}
export const createEducationPlan = (version: string, modules: EducationModule[], signal?: AbortSignal) =>
  call<GeneratorPlan>('post', '/profiles/education-p042-v1/plans', { profileId: 'education-p042-v1', version, modules }, signal);
