import request from '@/utils/request';
import { parseCapabilities } from './contracts';
export async function getFoundationCapabilities(signal: AbortSignal) {
  // Use the native authenticated client. No tenant, actor, epoch or grant supplied by the page.
  const response = (await request({ url: '/fcp/education/foundation/capabilities', method: 'get', signal })) as unknown as {
    code: number;
    data: unknown;
  };
  if (response.code !== 200) throw new Error('无法读取基础资料可用性，请确认登录及访问权限后刷新。');
  return parseCapabilities(response.data);
}
