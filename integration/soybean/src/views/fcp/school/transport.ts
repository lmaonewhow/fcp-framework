import {request} from '@/service/request';
import type {Transport} from './bridge';
// ruoyi-plus-soybean uses createFlatRequest, which already unwraps the host's data field.
export const transport:Transport=async <T>(url:string,method:'GET'|'POST',data?:unknown):Promise<T>=>{
  const {data:result,error}=await request<T>({url,method,data});
  if(error)throw new Error(error.message||'Host request failed');
  return result as T;
};
