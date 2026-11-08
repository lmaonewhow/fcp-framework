import request from '@/utils/request';
import { organization, schoolClass, school, term, page, type EntityKind, type Entity } from './business';
import { uint64 } from './scalars';
const prefix = '/fcp/education/foundation';
export class FoundationError extends Error {
  constructor(
    public code: string,
    public uncertain = false
  ) {
    super(code);
  }
}
async function call(path: string, signal: AbortSignal, body?: Record<string, unknown>): Promise<unknown> {
  let observed: { status?: number; code?: number; error?: string } | undefined;
  const defaults = request.defaults?.transformResponse;
  const transforms = Array.isArray(defaults) ? defaults : defaults ? [defaults] : [];
  const capture = (data: unknown, _headers: unknown, status?: number) => {
    if (data && typeof data === 'object') {
      const value = data as { code?: unknown; error?: unknown };
      observed = {
        status,
        code: typeof value.code === 'number' ? value.code : undefined,
        error: typeof value.error === 'string' ? value.error : undefined
      };
    }
    return data;
  };
  try {
    const envelope = (await request({
      transformResponse: [...transforms, capture],
      url: prefix + path,
      method: body ? 'post' : 'get',
      ...(body ? { data: body } : {}),
      signal
    })) as unknown as {
      code: number;
      error?: string;
      data: unknown;
    };
    if (envelope.code !== 200)
      throw new FoundationError(envelope.error || `HTTP_${envelope.code}`, Boolean(body) && !(envelope.code >= 400 && envelope.code < 500));
    return envelope.data;
  } catch (error) {
    if (error instanceof FoundationError) throw error;
    const response = (error as { response?: { status?: number; data?: { code?: number; error?: string } } })?.response;
    const status =
      response?.status && response.status >= 400
        ? response.status
        : response?.data?.code || (observed?.status && observed.status >= 400 ? observed.status : observed?.code);
    throw new FoundationError(
      response?.data?.error || observed?.error || `HTTP_${status || 'UNKNOWN'}`,
      Boolean(body) && !(status && status >= 400 && status < 500)
    );
  }
}
const query = (afterId?: string | null, termId?: string) => {
  const params = new URLSearchParams({ limit: '50' });
  if (afterId) params.set('afterId', uint64(afterId));
  if (termId) params.set('termId', uint64(termId));
  return '?' + params.toString();
};
export const listSchools = async (signal: AbortSignal, afterId?: string | null) => page(await call('/schools' + query(afterId), signal), school);
export const listOrganizations = async (schoolId: string, signal: AbortSignal, afterId?: string | null) =>
  page(await call(`/schools/${uint64(schoolId)}/organizations` + query(afterId), signal), organization);
export const listTerms = async (schoolId: string, signal: AbortSignal, afterId?: string | null) =>
  page(await call(`/schools/${uint64(schoolId)}/terms` + query(afterId), signal), term);
export const listClasses = async (schoolId: string, termId: string, signal: AbortSignal, afterId?: string | null) =>
  page(await call(`/schools/${uint64(schoolId)}/classes` + query(afterId, termId), signal), schoolClass);
export async function getEntity(kind: EntityKind, id: string, signal: AbortSignal): Promise<Entity> {
  const value = await call(`/${kind}/${uint64(id)}`, signal);
  return kind === 'organizations' ? organization(value) : schoolClass(value);
}
export async function writeEntity(
  kind: EntityKind,
  path: string,
  body: Record<string, unknown>,
  signal: AbortSignal
): Promise<{ record: Entity; awaitingActivation: boolean }> {
  const value = (await call(path, signal, body)) as {
    operation?: { intentId?: unknown; outcome?: unknown };
    record?: unknown;
    activation?: { status?: unknown; schoolId?: unknown };
  };
  if (value?.operation?.intentId !== body.intentId || value.operation.outcome !== 'COMMITTED') throw new FoundationError('INVALID_RECEIPT', true);
  try {
    const record = kind === 'organizations' ? organization(value.record) : schoolClass(value.record);
    if (path === '/schools' && (value.activation?.status !== 'EXECUTION_GRANT_REQUIRED' || value.activation.schoolId !== record.id))
      throw new Error('Invalid activation');
    return { record, awaitingActivation: path === '/schools' };
  } catch {
    throw new FoundationError('INVALID_RECEIPT', true);
  }
}
export async function lookupOperation(
  kind: EntityKind,
  action: 'create' | 'rename',
  schoolId: string,
  intentId: string,
  signal: AbortSignal
): Promise<Entity | null> {
  if (!/^[0-9a-f-]{36}$/i.test(intentId)) throw new FoundationError('INVALID_INTENT');
  const path = schoolId ? `/schools/${uint64(schoolId)}/operations/${kind}/${action}/${intentId}` : `/operations/schools/create/${intentId}`;
  const value = (await call(path, signal)) as { operation?: { intentId?: unknown; outcome?: unknown }; record?: unknown };
  if (value?.operation?.intentId !== intentId) throw new FoundationError('INVALID_RECEIPT');
  if (value.operation.outcome === 'UNKNOWN') return null;
  if (value.operation.outcome !== 'COMMITTED') throw new FoundationError('INVALID_RECEIPT');
  return kind === 'organizations' ? organization(value.record) : schoolClass(value.record);
}
