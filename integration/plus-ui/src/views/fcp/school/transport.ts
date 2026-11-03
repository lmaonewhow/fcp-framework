import request from '@/utils/request';
import type {Transport} from './bridge';
// plus-ui's interceptor returns the host envelope {code, msg, data}.
export const transport:Transport=async <T>(url:string,method:'GET'|'POST',data?:unknown):Promise<T>=>{
  try {
    const response=await request({url,method,data}) as unknown as {code:number;data:T;msg?:string};
    if(response.code!==200)throw new Error(response.msg||'Host request failed');
    return response.data;
  } catch(failure) {
    // Axios rejects non-2xx responses before the host-envelope interceptor runs.
    // Preserve the native FCP reason for the page while keeping the rejection.
    const response=(failure as {response?:{data?:{msg?:unknown}}}|null)?.response?.data;
    if(typeof response?.msg==='string' && response.msg)throw new Error(response.msg);
    throw failure;
  }
};
