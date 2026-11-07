import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import type { NativePluginHost } from './usePlugins';
import type { Identity } from './runtime';
export interface VerifiedVersion {version:string;digest:string;state:'VERIFIED'}
export interface VersionCatalog {pluginId:string;revision:number;current:{state:string;version:string;digest:string};versions:VerifiedVersion[]}
export interface VersionPlan {planId:string;digest:string;pluginId:string;action:'UPGRADE'|'ROLLBACK';fromVersion:string;fromDigest:string;targetVersion:string;targetDigest:string;expectedRevision:number;affectedTenants:string[];dependentPlugins:string[]}
export interface VersionReceipt {operationId:string;pluginId:string;action:'UPGRADE'|'ROLLBACK';fromVersion:string;fromDigest:string;targetVersion:string;targetDigest:string;phase:'INTENT'|'SWITCHED'|'COMPLETE'|'UNKNOWN';outcome:'PENDING'|'APPLIED'|'OUTCOME_UNKNOWN';revision:number}
interface SelectedPackage {name:string;size:number;digest:string;bytes:ArrayBuffer}
interface PendingVersion {kind:'stage'|'confirm';path:string;body:unknown;headers?:Record<string,string>;key:string;epoch:number;plan?:VersionPlan}
const digestPattern=/^[a-f0-9]{64}$/;
const idPattern=/^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$/;
const versionPattern=/^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$/;
function object(raw:unknown):Record<string,unknown>{if(!raw||typeof raw!=='object'||Array.isArray(raw))throw new Error('版本接口响应必须是对象');return raw as Record<string,unknown>}
const text=(v:unknown):v is string=>typeof v==='string'&&v.length>0&&v.length<=256;
const revision=(v:unknown):v is number=>Number.isSafeInteger(v)&&(v as number)>=0;
const digest=(v:unknown):v is string=>typeof v==='string'&&digestPattern.test(v);
const version=(v:unknown):v is string=>typeof v==='string'&&v.length<=100&&versionPattern.test(v);
const ids=(v:unknown):v is string[]=>Array.isArray(v)&&v.length<=512&&v.every(text)&&new Set(v).size===v.length;
const terminal=(r:VersionReceipt)=>r.phase==='COMPLETE'&&r.outcome==='APPLIED';
export function useVersionManager(host:NativePluginHost,context:()=>Identity,blocked:()=>boolean,changed:()=>void){
 const route=useRoute(),router=useRouter();
 const allowed=computed(()=>context().tenantId==='000000'&&context().permissions.includes('install')&&(host.controlPermissions?.()??[]).some(p=>p==='fcp:plugin:install'||p==='*:*:*'));
 const busy=ref(false),error=ref(''),notice=ref(''),pluginId=ref(''),action=ref<'UPGRADE'|'ROLLBACK'>('UPGRADE'),targetDigest=ref('');
 const selectedPackage=shallowRef<SelectedPackage|null>(null),stageAcknowledged=ref(false),versions=shallowRef<VersionCatalog|null>(null),plan=shallowRef<VersionPlan|null>(null),operation=shallowRef<VersionReceipt|null>(null),pending=shallowRef<PendingVersion|null>(null);
 const confirmedTenants=ref<string[]>([]),confirmedDependants=ref<string[]>([]),confirmedDowntime=ref(false),confirmedDataRetention=ref(false);
 let epoch=0,alive=true,fileSequence=0;const controllers=new Set<AbortController>();
 function reset(){epoch++;fileSequence++;for(const controller of controllers)controller.abort();controllers.clear();if(pending.value)notice.value='身份或权限已变化，已清除待确认版本操作。请回到原身份查询结果，不能跨身份重试。';pending.value=null;operation.value=null;selectedPackage.value=null;stageAcknowledged.value=false;versions.value=null;pluginId.value='';targetDigest.value='';clearPlan();}
 function clearPlan(){plan.value=null;confirmedTenants.value=[];confirmedDependants.value=[];confirmedDowntime.value=false;confirmedDataRetention.value=false;}
 function requireAllowed(mutation=false){if(!allowed.value)throw new Error('仅平台000000且服务端明确授权的安装管理员可管理版本');if(mutation&&blocked())throw new Error('请先完成其他待确认管理操作');}
 const stopIdentity=host.observeIdentity(reset),stopContext=watch(()=>JSON.stringify([context().userId,context().tenantId,context().language,[...context().permissions].sort()]),reset,{flush:'sync'});
 async function request(path:string,method:'GET'|'POST',body?:unknown,headers?:Record<string,string>){
  requireAllowed();const mine=epoch,key=host.identityKey(),controller=new AbortController();controllers.add(controller);
  try{const raw=object(await host.request(path,method,body,controller.signal,{headers}));if(!alive||controller.signal.aborted||mine!==epoch||key!==host.identityKey())throw new Error('身份已变化，旧版本响应已丢弃');if(host.shell==='admin'?raw.code!==200:raw.error!=null){const failure=raw.error&&typeof raw.error==='object'?object(raw.error):raw;throw new Error(String(failure.message??failure.msg??'版本操作被服务端拒绝'));}return raw.data;}finally{controllers.delete(controller);}
 }
 async function read(work:()=>Promise<void>){if(busy.value)return;busy.value=true;error.value='';try{await work();}catch(e){if(alive)error.value=e instanceof Error?e.message:'版本请求失败';}finally{if(alive)busy.value=false;}}
 async function fetchVersions(id=pluginId.value){
  if(!idPattern.test(id)||id.length>128)throw new Error('请选择已有插件');const raw=object(await request(`/fcp/plugins/${encodeURIComponent(id)}/versions`,'GET')),current=object(raw.current);
  if(raw.pluginId!==id||!revision(raw.revision)||!text(current.state)||!version(current.version)||!digest(current.digest)||!Array.isArray(raw.versions)||raw.versions.length>1024)throw new Error('版本目录响应不完整');
  const seen=new Set<string>();for(const item of raw.versions){const v=object(item);if(!version(v.version)||!digest(v.digest)||v.state!=='VERIFIED'||seen.has(v.digest))throw new Error('版本目录包含未验证或重复条目');seen.add(v.digest);}
  versions.value=raw as unknown as VersionCatalog;pluginId.value=id;
 }
 function loadVersions(id=pluginId.value){return read(async()=>{requireAllowed();if(pending.value)throw new Error('先处理原版本操作，再切换目标');clearPlan();targetDigest.value='';await fetchVersions(id);});}
 function changeTarget(value:string){if(busy.value||pending.value)return;targetDigest.value=value;clearPlan();}
 function changeAction(value:'UPGRADE'|'ROLLBACK'){if(busy.value||pending.value)return;action.value=value;clearPlan();}
 async function selectFile(file:File|null){
  if(busy.value||pending.value||blocked()){error.value='先处理待确认操作，再更换软件包';return;}selectedPackage.value=null;stageAcknowledged.value=false;error.value='';const sequence=++fileSequence,mine=epoch;
  if(!file)return;
  try{requireAllowed(true);if(file.size<=0||file.size>64*1024*1024)throw new Error('签名软件包必须介于1字节和64MiB之间');const bytes=await file.arrayBuffer();const hash=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),b=>b.toString(16).padStart(2,'0')).join('');if(alive&&mine===epoch&&sequence===fileSequence)selectedPackage.value={name:file.name,size:bytes.byteLength,digest:hash,bytes};}catch(e){if(alive&&mine===epoch)error.value=e instanceof Error?e.message:'无法读取软件包';}
 }
 function parseReceipt(raw:unknown,expected:VersionPlan):VersionReceipt{
  const r=object(raw);if(!text(r.operationId)||!revision(r.revision)||!['INTENT','SWITCHED','COMPLETE','UNKNOWN'].includes(String(r.phase))||!['PENDING','APPLIED','OUTCOME_UNKNOWN'].includes(String(r.outcome)))throw new Error('版本操作回执不完整');
  for(const key of ['pluginId','action','fromVersion','fromDigest','targetVersion','targetDigest'] as const)if(r[key]!==expected[key])throw new Error('版本回执与已确认的确切目标不一致');
  if((r.phase==='COMPLETE')!==(r.outcome==='APPLIED'))throw new Error('版本回执终态不一致');return r as unknown as VersionReceipt;
 }
 async function send(attempt:PendingVersion){
  if(busy.value||!alive)return;busy.value=true;error.value='';
  try{requireAllowed(true);if(attempt.epoch!==epoch)throw new Error('禁止跨身份重试版本操作');const data=await request(attempt.path,'POST',attempt.body,attempt.headers);if(!alive||attempt.epoch!==epoch||pending.value?.key!==attempt.key)return;
   if(attempt.kind==='stage'){const receipt=object(data);if(!text(receipt.pluginId)||!idPattern.test(receipt.pluginId)||!version(receipt.version)||receipt.digest!==attempt.headers?.['X-Fcp-Digest']||receipt.state!=='VERIFIED'||typeof receipt.replayed!=='boolean')throw new Error('暂存验证回执不完整，请按原请求核对');pending.value=null;selectedPackage.value=null;stageAcknowledged.value=false;notice.value=`版本 ${receipt.pluginId} ${receipt.version} 已验证暂存，未切换当前版本、未授权或启用。`;if(pluginId.value===receipt.pluginId)await fetchVersions();}
   else {const result=parseReceipt(data,attempt.plan!);operation.value=result;if(terminal(result)){pending.value=null;clearPlan();notice.value='软件版本切换已完成，仍保持禁用；没有回滚业务数据、自动授权或自动启用。';changed();await fetchVersions(result.pluginId);}else notice.value='版本操作尚未完成，请查询实际回执，不要选择其他版本重复执行。';}
  }catch(e){if(alive)error.value=e instanceof Error?e.message:'版本结果未知，请保留原请求核对';}finally{if(alive)busy.value=false;}
 }
 async function stage(){try{requireAllowed(true);if(busy.value||pending.value)throw new Error('请先处理待确认操作');const file=selectedPackage.value;if(!file||!stageAcknowledged.value)throw new Error('请确认软件包摘要和可信发布者风险');const key=crypto.randomUUID(),attempt:PendingVersion={kind:'stage',path:'/fcp/packages/staged',body:file.bytes.slice(0),headers:{'Content-Type':'application/octet-stream','X-Fcp-Digest':file.digest,'Idempotency-Key':key},key,epoch};pending.value=attempt;await send(attempt);}catch(e){error.value=e instanceof Error?e.message:'无法暂存';}}
 async function preview(){await read(async()=>{
  requireAllowed(true);if(pending.value)throw new Error('请先处理原版本操作');const catalog=versions.value,target=catalog?.versions.find(v=>v.digest===targetDigest.value);if(!catalog||!target)throw new Error('请选择服务端已验证的确切版本摘要');clearPlan();
  const raw=object(await request(`/fcp/plugins/${encodeURIComponent(catalog.pluginId)}/version-plans`,'POST',{action:action.value,targetVersion:target.version,targetDigest:target.digest,expectedRevision:catalog.revision}));
  if(!text(raw.planId)||!digest(raw.digest)||raw.pluginId!==catalog.pluginId||raw.action!==action.value||raw.fromVersion!==catalog.current.version||raw.fromDigest!==catalog.current.digest||raw.targetVersion!==target.version||raw.targetDigest!==target.digest||raw.expectedRevision!==catalog.revision||!ids(raw.affectedTenants)||!ids(raw.dependentPlugins))throw new Error('版本计划与所选版本或影响范围不一致');plan.value=raw as unknown as VersionPlan;
 });}
 async function confirm(){try{requireAllowed(true);if(busy.value||pending.value)throw new Error('请先处理待确认操作');const p=plan.value;if(!p||!confirmedDowntime.value||!confirmedDataRetention.value||p.affectedTenants.length!==confirmedTenants.value.length||!p.affectedTenants.every(t=>confirmedTenants.value.includes(t))||p.dependentPlugins.length!==confirmedDependants.value.length||!p.dependentPlugins.every(t=>confirmedDependants.value.includes(t)))throw new Error('请确认停机、数据不回滚及完整租户和依赖影响列表');const key=crypto.randomUUID();const attempt:PendingVersion={kind:'confirm',path:`/fcp/version-plans/${encodeURIComponent(p.planId)}/confirm`,body:{digest:p.digest,idempotencyKey:key,confirmedDowntime:true},key,epoch,plan:JSON.parse(JSON.stringify(p)) as VersionPlan};pending.value=attempt;await send(attempt);}catch(e){error.value=e instanceof Error?e.message:'无法确认版本计划';}}
 async function query(){if(busy.value||!operation.value||!pending.value?.plan)return;await read(async()=>{const attempt=pending.value!,result=parseReceipt(await request(`/fcp/version-operations/${encodeURIComponent(operation.value!.operationId)}`,'GET'),attempt.plan!);operation.value=result;if(terminal(result)){pending.value=null;clearPlan();notice.value='持久回执确认版本切换完成，插件保持禁用，业务数据未回滚。';changed();await fetchVersions(result.pluginId);}});}
 function retry(){if(pending.value)return send(pending.value);return Promise.resolve();}
 function abandon(){if(busy.value)return;pending.value=null;clearPlan();notice.value='已放弃本页原请求信息，不会撤销已提交的版本操作。请先查询服务端当前版本和操作回执，再制定新计划。';}
 const stopNavigation=router.beforeEach(to=>{if(to.path==='/login'){reset();return true;}if(pending.value&&to.path!==route.path){error.value='请先查询或明确放弃待确认的版本操作';return false;}return true;});
 const beforeUnload=(event:BeforeUnloadEvent)=>{if(pending.value){event.preventDefault();event.returnValue='';}};
 if(typeof window!=='undefined')window.addEventListener('beforeunload',beforeUnload);
 onBeforeUnmount(()=>{alive=false;reset();stopIdentity();stopContext();stopNavigation();if(typeof window!=='undefined')window.removeEventListener('beforeunload',beforeUnload);});
 return {allowed,busy,error,notice,pluginId,action,targetDigest,selectedPackage,stageAcknowledged,versions,plan,operation,pending,confirmedTenants,confirmedDependants,confirmedDowntime,confirmedDataRetention,loadVersions,changeTarget,changeAction,selectFile,stage,preview,confirm,query,retry,abandon};
}
