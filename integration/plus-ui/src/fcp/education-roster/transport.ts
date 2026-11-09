import request from '@/utils/request';
export class RosterError extends Error {
  constructor(public status: number | undefined) {
    super('ROSTER_READ_FAILED');
  }
}
export async function callRoster(path: string, signal: AbortSignal): Promise<unknown> {
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
      url: path,
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
