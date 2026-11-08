import request from '@/utils/request';
import { organization, schoolClass, type EntityKind, type Entity } from './business';
export class FoundationError extends Error {
  constructor(
    public code: string,
    public uncertain = false
  ) {
    super(code);
  }
}
export async function callFoundation(path: string, signal: AbortSignal, body?: Record<string, unknown>): Promise<unknown> {
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
      url: path,
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
    const response = (
      error as {
        response?: {
          status?: number;
          data?: { code?: number; error?: string };
        };
      }
    )?.response;
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

/** Manual receipt protocol: a successful envelope alone never proves a commit. */
export function parseWriteReceipt(
  kind: EntityKind,
  path: string,
  body: Record<string, unknown>,
  input: unknown
): { record: Entity; awaitingActivation: boolean } {
  const value = input as {
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

export function parseOperationReceipt(kind: EntityKind, intentId: string, input: unknown): Entity | null {
  const value = input as {
    operation?: { intentId?: unknown; outcome?: unknown };
    record?: unknown;
  };
  if (value?.operation?.intentId !== intentId) throw new FoundationError('INVALID_RECEIPT');
  if (value.operation.outcome === 'UNKNOWN') return null;
  if (value.operation.outcome !== 'COMMITTED') throw new FoundationError('INVALID_RECEIPT');
  return kind === 'organizations' ? organization(value.record) : schoolClass(value.record);
}

/** Capability read preserves the original native request shape; no mutation uncertainty applies. */
export async function callFoundationCapabilities(path: string, signal: AbortSignal): Promise<unknown> {
  const response = (await request({
    url: path,
    method: 'get',
    signal
  })) as unknown as { code: number; data: unknown };
  if (response.code !== 200) throw new Error('无法读取基础资料可用性，请确认登录及访问权限后刷新。');
  return response.data;
}
