import { readResponseJson, responseError } from './trace.js';
import {computed,onBeforeUnmount,onMounted,ref} from 'vue';
import {readToken} from './session';

type Plugin={id:string;title:string;kind:'business'|'connector'|'adapter';entity:string;enabled:boolean;effective:boolean;revision:number;blockedReason:string};
type Catalog={instance:string;generation:number;caller:{scope:string;subject:string};plugins:Plugin[];entities:string[];classes:string[];allClasses:boolean;canImport:boolean;canManage:boolean};
type Dataset={records:Record<string,string>[];summary:Record<string,number>;version:number;pid:number};
type Receipt={version:number;imported:number;inserted:number;updated:number;unchanged:number;replayed:boolean};
type ImportInput={plugin:string;classId:string;payload:Record<string,string>;expectedVersion:number;requestKey:string};

/** Same-origin native authentication; failed imports retain their exact idempotent request. */
export function useIngestion(){
  const state=ref<Catalog|null>(null),entity=ref('roster'),classId=ref(''),busy=ref(false),error=ref(''),notice=ref('');
  const rows=ref<Record<string,string>[]>([]),summary=ref<Record<string,number>>({}),version=ref(0),encoding=ref('utf-8-sig'),file=ref<File|null>(null);
  const pending=ref<ImportInput|null>(null);let pendingCaller='',alive=true;
  const controller=new AbortController();
  const plugins=computed(()=>state.value?.plugins.filter(p=>p.entity===entity.value)||[]);
  const active=(kind:string)=>plugins.value.some(p=>p.kind===kind&&p.effective);
  const columns=computed(()=>entity.value==='roster'?['studentId','name','classId']:['studentId','classId','day','status']);
  const labels:Record<string,string>={studentId:'学号',name:'姓名',classId:'班级',day:'日期',status:'状态',students:'学生数',classes:'班级数',records:'考勤记录',present:'正常',late:'迟到',absent:'缺勤',leave:'请假',attendanceRate:'出勤率 (%)'};
  async function request<T>(path:string,input?:unknown):Promise<T>{
    const token=readToken();if(!token)throw new Error('请先完成若依宿主登录');
    const clientId=String(import.meta.env.VITE_APP_CLIENT_ID||'');if(!clientId)throw new Error('构建缺少宿主客户端标识');
    const response=await fetch('/fcp/ingestion'+path,{method:input===undefined?'GET':'POST',cache:'no-store',credentials:'same-origin',redirect:'error',signal:controller.signal,
      headers:{Authorization:`Bearer ${token}`,clientid:clientId,'Content-Type':'application/json'},body:input===undefined?undefined:JSON.stringify(input)});
    if(token!==readToken())throw new Error('登录身份已经变化，请重新打开页面');
    const body=await readResponseJson(response);if(!response.ok||body?.code!==200)throw responseError(typeof body?.msg==='string'?body.msg:'宿主请求失败',{headers:response.headers,data:body});
    return body.data as T;
  }
  function envelope(input:unknown){if(!state.value)throw new Error('请先加载插件状态');return {input,instance:state.value.instance,generation:state.value.generation,expectedCaller:state.value.caller};}
  async function data(){
    rows.value=[];summary.value={};version.value=0;
    if(!classId.value||!active('business'))return;
    const result=await request<Dataset>(`/data/${encodeURIComponent(entity.value)}?classId=${encodeURIComponent(classId.value)}`);
    if(alive){rows.value=result.records;summary.value=result.summary;version.value=result.version;}
  }
  async function refresh(){
    const next=await request<Catalog>('/bootstrap');if(!alive)return;
    state.value=next;
    if(!next.entities.includes(entity.value))entity.value=next.entities[0]||'roster';
    if(!classId.value||(!next.allClasses&&!next.classes.includes(classId.value)))classId.value=next.classes[0]||(next.allClasses?'class-a':'');
    if(pending.value&&pendingCaller!==JSON.stringify(next.caller)){pending.value=null;throw new Error('当前身份与待重试导入不一致，已清除待重试请求');}
    await data();
  }
  async function run(action:()=>Promise<void>){if(busy.value)return;busy.value=true;error.value='';try{await action();}catch(e){if(alive)error.value=e instanceof Error?e.message:'操作失败';}finally{if(alive)busy.value=false;}}
  function toggle(plugin:Plugin){return run(async()=>{state.value=await request<Catalog>('/state',envelope({plugin:plugin.id,enabled:!plugin.enabled,revision:plugin.revision}));await data();});}
  async function commit(input:ImportInput){
    const result=await request<Receipt>('/imports',envelope(input));pending.value=null;
    notice.value=`导入完成：新增 ${result.inserted}，更新 ${result.updated}，未变化 ${result.unchanged}。数据版本 ${result.version}${result.replayed?'，已返回同一请求的历史结果':''}。`;
    await refresh();
  }
  function ingest(kind:'connector'|'adapter'){return run(async()=>{
    if(pending.value)throw new Error('上次导入结果尚未确认，请先重试同一请求或明确放弃重试');
    if(!state.value?.canImport||!active(kind)||!classId.value)throw new Error('请检查导入权限、班级及插件启用状态');
    let payload:Record<string,string>={};
    if(kind==='adapter'){
      if(!file.value||file.value.size>131072)throw new Error('请选择不超过 128 KiB 的 CSV 文件');
      const bytes=new Uint8Array(await file.value.arrayBuffer());let binary='';
      for(let i=0;i<bytes.length;i+=8192)binary+=String.fromCharCode(...bytes.subarray(i,i+8192));
      payload={content:btoa(binary),encoding:encoding.value};
    }
    const input={plugin:`${entity.value}.${kind}`,classId:classId.value,payload,expectedVersion:version.value,requestKey:crypto.randomUUID()};
    pending.value=input;pendingCaller=JSON.stringify(state.value.caller);await commit(input);
  });}
  function retry(){return run(async()=>{if(!pending.value)return;await refresh();if(pending.value)await commit(pending.value);});}
  function abandon(){pending.value=null;notice.value='已放弃本页的重试信息。请刷新并核对数据，之前的请求可能已经提交。';}
  function selectFile(event:Event){file.value=(event.target as HTMLInputElement).files?.[0]||null;}
  onMounted(()=>run(refresh));onBeforeUnmount(()=>{alive=false;controller.abort();pending.value=null;});
  return {state,entity,classId,busy,error,notice,rows,summary,version,encoding,file,pending,plugins,columns,labels,active,toggle,ingest,retry,abandon,selectFile,reload:()=>run(refresh)};
}
