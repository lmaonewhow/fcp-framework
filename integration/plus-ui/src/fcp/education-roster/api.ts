import request from '@/utils/request';
import { capabilities, school, relation, page, id, type Kind } from './contracts';
const prefix = '/fcp/education/roster';
export class RosterError extends Error {
  constructor(public status: number | undefined) {
    super('ROSTER_READ_FAILED');
  }
}
async function call(path: string, signal: AbortSignal): Promise<unknown> {
  let observed: number | undefined;
  const defaults = request.defaults?.transformResponse;
  const transforms = Array.isArray(defaults) ? defaults : defaults ? [defaults] : [];
  const capture = (data: unknown, _headers: unknown, status?: number) => {
    const code = data && typeof data === 'object' ? (data as { code?: unknown }).code : undefined;
    observed = status && status >= 400 ? status : typeof code === 'number' ? code : undefined;
    return data;
  };
  try {
    const envelope = (await request({
      url: prefix + path,
      method: 'get',
      signal,
      transformResponse: [...transforms, capture]
    })) as unknown as { code?: number; data?: unknown };
    if (envelope?.code !== 200) throw new RosterError(envelope?.code);
    return envelope.data;
  } catch (error) {
    if (error instanceof RosterError) throw error;
    const response = (error as { response?: { status?: number; data?: { code?: number } } })?.response;
    throw new RosterError(response?.status && response.status >= 400 ? response.status : response?.data?.code || observed);
  }
}
function query(afterId?: string | null) {
  const parameters = new URLSearchParams({ limit: '50' });
  if (afterId) parameters.set('afterId', id(afterId));
  return '?' + parameters.toString();
}
export const getCapabilities = async (signal: AbortSignal) => capabilities(await call('/capabilities', signal));
export const listSchools = async (signal: AbortSignal, afterId?: string | null) =>
  page(await call('/schools' + query(afterId), signal), school, afterId);
export const listRelations = async (schoolId: string, kind: Kind, signal: AbortSignal, afterId?: string | null) =>
  page(await call(`/schools/${id(schoolId)}/${kind}` + query(afterId), signal), (row) => relation(row, kind, schoolId), afterId);
export async function getRelation(schoolId: string, kind: Kind, recordId: string, signal: AbortSignal) {
  const row = relation(await call(`/schools/${id(schoolId)}/${kind}/${id(recordId)}`, signal), kind, schoolId);
  if (row.id !== recordId) throw new RosterError(undefined);
  return row;
}
