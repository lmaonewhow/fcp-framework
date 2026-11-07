import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import type { NativePluginHost } from './usePlugins';
import type { Identity } from './runtime';
export interface ManagedPlugin {pluginId:string;version:string;digest:string;state:string;installation:string;deployment:string;desired:string;effective:string;revision:number}
export interface Grant {capabilityId:string;dataScope:string;allow:boolean}
interface GrantOption {capabilityId:string;permission:string;sideEffects:boolean}
interface GrantChange {capabilityId:string;operation:string;permission:string;dataScope:string;allow:boolean}
interface GrantOptions {targetTenant:string;revision:number;capabilities:GrantOption[];currentGrants:GrantChange[]}
export interface ManagementPlan {planId:string;digest:string;pluginId:string;action:string;affectedPlugins:string[];expectedRevision:number;targetTenant?:string;changes?:GrantChange[]}
export interface ManagedOperation {operationId:string;phase:string;outcome:string;plugins:string[]}
interface Upload {name:string;size:number;digest:string;bytes:ArrayBuffer}
interface PendingMutation {path:string;body:unknown;headers?:Record<string,string>;permission:'manage'|'install';kind:'upload'|'operation'|'decision'|'uninstall';pluginId?:string;key:string;epoch:number}
const identifier=/^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$/;
function record(value:unknown):Record<string,unknown>{if(!value||typeof value!=='object'||Array.isArray(value))throw new Error('宿主响应格式错误');return value as Record<string,unknown>}
function text(value:unknown):value is string{return typeof value==='string'&&value.length>0}
function safeRevision(value:unknown):value is number{return Number.isSafeInteger(value)&&(value as number)>=0}
function stringList(value:unknown):value is string[]{return Array.isArray(value)&&value.every(text)}
export function usePluginManager(host:NativePluginHost,context:()=>Identity,changed:()=>void) {
 const route=useRoute(),router=useRouter();
 const canManage=computed(()=>context().permissions.includes('manage')&&(host.controlPermissions?.()??[]).some(p=>p==='fcp:plugin:manage'||p==='*:*:*'));
 const canInstall=computed(()=>context().tenantId==='000000'&&context().permissions.includes('install')&&(host.controlPermissions?.()??[]).some(p=>p==='fcp:plugin:install'||p==='*:*:*'));
 const plugins=shallowRef<ManagedPlugin[]>([]),revision=ref(-1),busy=ref(false),error=ref(''),notice=ref('');
 const uninstallCandidate=shallowRef<ManagedPlugin|null>(null),uninstallAcknowledged=ref(false);
 const upload=shallowRef<Upload|null>(null),trustAcknowledged=ref(false),plan=shallowRef<ManagementPlan|null>(null),approvedIds=ref<string[]>([]);
 const cancelAcknowledged=ref(false);
 const operation=shallowRef<ManagedOperation|null>(null),pending=shallowRef<PendingMutation|null>(null);
 const grantPlugin=ref(''),grantCapability=ref(''),grantScope=ref(''),targetTenant=ref(''),grantAllow=ref(true),grants=ref<Grant[]>([]);
 const grantOptions=shallowRef<GrantOptions|null>(null),grantAcknowledged=ref(false);
 let alive=true,epoch=0,fileSequence=0;const controllers=new Set<AbortController>();let poll:ReturnType<typeof setInterval>|undefined;
 function requirePermission(permission:'manage'|'install') {if(!(permission==='manage'?canManage.value:canInstall.value))throw new Error('当前宿主身份没有此管理权限');}
 function reset(){epoch++;fileSequence++;for(const c of controllers)c.abort();controllers.clear();if(pending.value)notice.value='身份或权限已变化，待确认操作可能已经完成。请回到原身份核对，不跨身份重试。';pending.value=null;uninstallCandidate.value=null;uninstallAcknowledged.value=false;upload.value=null;trustAcknowledged.value=false;plan.value=null;approvedIds.value=[];plugins.value=[];operation.value=null;cancelAcknowledged.value=false;grants.value=[];grantPlugin.value='';grantCapability.value='';grantScope.value='';targetTenant.value='';grantOptions.value=null;grantAcknowledged.value=false;revision.value=-1;}
 const stopIdentity=host.observeIdentity(reset);
 const stopPermissions=watch(()=>JSON.stringify([context().userId,context().tenantId,context().language,context().permissions.slice().sort()]),reset,{flush:'sync'});
 async function request(path:string,method:'GET'|'POST',body?:unknown,headers?:Record<string,string>) {
  const mine=epoch,key=host.identityKey(),controller=new AbortController();controllers.add(controller);
  try {
   const raw=record(await host.request(path,method,body,controller.signal,{headers}));
   if(!alive||controller.signal.aborted||mine!==epoch||key!==host.identityKey())throw new Error('身份已变化，已丢弃旧管理响应');
   if(host.shell==='admin'?raw.code!==200:raw.error!=null){const failure=raw.error&&typeof raw.error==='object'?record(raw.error):raw;throw new Error(String(failure.message??failure.msg??'宿主拒绝管理请求'));}
   return raw.data;
  } finally {controllers.delete(controller);}
 }
 async function read(action:()=>Promise<void>){if(busy.value)return;busy.value=true;error.value='';try{await action();}catch(e){if(alive)error.value=e instanceof Error?e.message:'管理请求失败';}finally{if(alive)busy.value=false;}}
 async function load(){
  if(!canManage.value&&!canInstall.value)return;
  const data=record(await request('/fcp/plugins','GET'));
  if(!safeRevision(data.revision)||!Array.isArray(data.plugins))throw new Error('插件列表响应无效');
  for(const item of data.plugins){const p=record(item);if(!text(p.pluginId)||!identifier.test(p.pluginId)||!safeRevision(p.revision)||!['version','digest','state','installation','deployment','desired','effective'].every(k=>text(p[k])))throw new Error('插件状态响应无效');}
  revision.value=data.revision;plugins.value=data.plugins as ManagedPlugin[];
 }
 function refresh(){return read(load)}
 async function selectFile(file:File|null){
  if(pending.value||busy.value){error.value='请先处理待确认操作，再更换软件包';return;}
  upload.value=null;trustAcknowledged.value=false;error.value='';const sequence=++fileSequence,mine=epoch;
  if(!file)return;
  try{requirePermission('install');if(file.size<=0||file.size>64*1024*1024)throw new Error('请选择不超过64MiB的签名插件ZIP包');const bytes=await file.arrayBuffer();const digest=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),b=>b.toString(16).padStart(2,'0')).join('');if(alive&&sequence===fileSequence&&mine===epoch)upload.value={name:file.name,size:bytes.byteLength,digest,bytes};}
  catch(e){if(alive&&mine===epoch)error.value=e instanceof Error?e.message:'无法读取软件包';}
 }
 function parsePlan(raw:unknown){const p=record(raw);if(!text(p.planId)||!text(p.digest)||!text(p.pluginId)||!text(p.action)||!stringList(p.affectedPlugins)||!p.affectedPlugins.length||new Set(p.affectedPlugins).size!==p.affectedPlugins.length||!safeRevision(p.expectedRevision))throw new Error('计划响应不完整，不允许确认');plan.value=p as unknown as ManagementPlan;approvedIds.value=[];grantAcknowledged.value=false;}
 async function preview(plugin:ManagedPlugin,action:'enable'|'disable') {await read(async()=>{requirePermission('manage');if(pending.value)throw new Error('请先处理待确认操作');plan.value=null;parsePlan(await request(`/fcp/plugins/${encodeURIComponent(plugin.pluginId)}/plans`,'POST',{action,expectedRevision:revision.value}));});}
 function acceptOperation(raw:unknown){const op=record(raw);if(!text(op.operationId)||!text(op.phase)||!text(op.outcome)||!stringList(op.plugins))throw new Error('操作响应不完整，请按相同请求核对结果');operation.value=op as unknown as ManagedOperation;cancelAcknowledged.value=false;}
 async function send(attempt:PendingMutation){
  if(busy.value||!alive)return;busy.value=true;error.value='';
  try{requirePermission(attempt.permission);if(attempt.epoch!==epoch)throw new Error('禁止跨身份重试');const data=await request(attempt.path,'POST',attempt.body,attempt.headers);if(!alive||attempt.epoch!==epoch||pending.value?.key!==attempt.key)return;
   if(attempt.kind==='upload'){const receipt=record(data);if(receipt.state!=='INSTALLED'||!text(receipt.pluginId)||!text(receipt.version)||receipt.digest!==attempt.headers?.['X-Fcp-Digest']||!safeRevision(receipt.revision))throw new Error('安装回执不完整，请核对结果');notice.value=`软件包 ${receipt.pluginId} 已安装。安装不代表授权或启用，请分别预览确认。`;upload.value=null;trustAcknowledged.value=false;}
   else if(attempt.kind==='uninstall'){const receipt=record(data);if(receipt.pluginId!==attempt.pluginId||receipt.state!=='UNINSTALLED'||receipt.dataRetained!==true||!safeRevision(receipt.revision))throw new Error('卸载回执不完整，请先查询实际安装状态');notice.value=`插件 ${receipt.pluginId} 已卸载，业务数据保留。`;uninstallCandidate.value=null;uninstallAcknowledged.value=false;grantOptions.value=null;grants.value=[];grantPlugin.value='';}
   else {acceptOperation(data);notice.value='服务端已受理操作，请查看实际阶段和结果。';plan.value=null;approvedIds.value=[];}
   pending.value=null;changed();await load();
  }catch(e){if(alive)error.value=e instanceof Error?e.message:'结果未确认，请核对后显式重试';}finally{if(alive)busy.value=false;}
 }
 async function install(){
  try{requirePermission('install');if(pending.value||busy.value)throw new Error('请先处理待确认操作');if(!upload.value||!trustAcknowledged.value||!safeRevision(revision.value))throw new Error('请核对文件摘要并明确确认可信发布者风险');const key=crypto.randomUUID(),u=upload.value;const attempt:PendingMutation={path:'/fcp/packages',body:u.bytes.slice(0),headers:{'Content-Type':'application/octet-stream','X-Fcp-Digest':u.digest,'Idempotency-Key':key,'X-Fcp-Revision':String(revision.value)},permission:'install',kind:'upload',key,epoch};pending.value=attempt;await send(attempt);}
  catch(e){error.value=e instanceof Error?e.message:'安装无法开始';}
 }
 async function confirm(){
  try{const permission=plan.value?.action==='GRANT'?'install':'manage';requirePermission(permission);if(pending.value||busy.value)throw new Error('请先处理待确认操作');const p=plan.value;if(p?.action==='GRANT'&&!grantAcknowledged.value)throw new Error('请确认目标租户、权限与每项数据范围');if(!p||p.affectedPlugins.length!==approvedIds.value.length||!p.affectedPlugins.every(id=>approvedIds.value.includes(id)))throw new Error('必须逐项确认完整影响列表');const key=crypto.randomUUID();const attempt:PendingMutation={path:`/fcp/plans/${encodeURIComponent(p.planId)}/confirm`,body:{digest:p.digest,affectedPlugins:[...p.affectedPlugins],idempotencyKey:key},permission,kind:'operation',key,epoch};pending.value=attempt;await send(attempt);}
  catch(e){error.value=e instanceof Error?e.message:'确认无法开始';}
 }
 async function checkOperation(){if(!operation.value||busy.value)return;await read(async()=>{acceptOperation(await request(`/fcp/operations/${encodeURIComponent(operation.value!.operationId)}`,'GET'));if(operation.value?.phase==='COMPLETE'){if(pending.value?.kind==='decision')pending.value=null;await load();changed();}});}
 async function decide(decision:'wait'|'cancel'){
  try{requirePermission('manage');if(decision==='cancel'&&!cancelAcknowledged.value)throw new Error('请先确认取消活动调用的影响');if(!operation.value||pending.value||busy.value)throw new Error('没有可决策的操作，或已有待确认请求');const key=crypto.randomUUID();const attempt:PendingMutation={path:`/fcp/operations/${encodeURIComponent(operation.value.operationId)}/decision`,body:{decision},permission:'manage',kind:'decision',key,epoch};pending.value=attempt;await send(attempt);}
  catch(e){error.value=e instanceof Error?e.message:'决策无法开始';}
 }
 function previewUninstall(plugin:ManagedPlugin){try{requirePermission('install');if(busy.value||pending.value)throw new Error('请先处理待确认操作');if(plugin.state!=='INSTALLED')throw new Error('插件当前不是已安装状态');uninstallCandidate.value={...plugin,revision:revision.value};uninstallAcknowledged.value=false;plan.value=null;}catch(e){error.value=e instanceof Error?e.message:'无法预览卸载';}}
 async function uninstall(){try{requirePermission('install');if(busy.value||pending.value)throw new Error('请先处理待确认操作');const candidate=uninstallCandidate.value;if(!candidate||!uninstallAcknowledged.value)throw new Error('请明确确认软件包摘要、所有租户禁用排空和保留业务数据');const attempt:PendingMutation={path:`/fcp/plugins/${encodeURIComponent(candidate.pluginId)}/uninstall`,body:{expectedRevision:candidate.revision,confirmedDigest:candidate.digest},permission:'install',kind:'uninstall',pluginId:candidate.pluginId,key:crypto.randomUUID(),epoch};pending.value=attempt;await send(attempt);}catch(e){error.value=e instanceof Error?e.message:'卸载无法开始';}}
 function cancelUninstall(){if(!busy.value&&!pending.value){uninstallCandidate.value=null;uninstallAcknowledged.value=false;}}
 async function checkUninstall(){if(pending.value?.kind!=='uninstall'||busy.value)return;await read(async()=>{const attempt=pending.value!;const result=record(await request(`/fcp/plugins/${encodeURIComponent(attempt.pluginId!)}`,'GET'));if(result.installation==='UNINSTALLED'){pending.value=null;uninstallCandidate.value=null;uninstallAcknowledged.value=false;notice.value='已查询确认该插件当前处于卸载状态，本页待确认记录已清除。';await load();changed();}else{notice.value=`查询到当前安装状态：${String(result.installation??'未知')}。没有重放卸载；如需新操作，请先核对并明确放弃旧请求，再重新预览。`;}});}
 function validChanges(raw:unknown):raw is GrantChange[]{return Array.isArray(raw)&&raw.every(item=>{const c=record(item);return ['capabilityId','operation','permission','dataScope'].every(k=>text(c[k]))&&typeof c.allow==='boolean';});}
 async function loadGrantOptions(pluginId=grantPlugin.value){await read(async()=>{
  requirePermission('install');if(pending.value)throw new Error('请先处理待确认操作');if(!identifier.test(pluginId))throw new Error('请选择已安装插件');
  const target=targetTenant.value||context().tenantId||'';
  if(target!==context().tenantId&&context().tenantId!=='000000')throw new Error('仅平台身份可显式选择其他目标租户');
  grantOptions.value=null;grants.value=[];plan.value=null;
  const raw=record(await request(`/fcp/plugins/${encodeURIComponent(pluginId)}/grant-options?targetTenant=${encodeURIComponent(target)}`,'GET'));
  if(!text(raw.targetTenant)||raw.targetTenant!==target||!safeRevision(raw.revision)||!Array.isArray(raw.capabilities)||!validChanges(raw.currentGrants))throw new Error('授权选项响应无效');
  for(const value of raw.capabilities){const option=record(value);if(!text(option.capabilityId)||!identifier.test(option.capabilityId)||!text(option.permission)||typeof option.sideEffects!=='boolean')throw new Error('能力授权选项无效');}
  grantPlugin.value=pluginId;targetTenant.value=raw.targetTenant;grantOptions.value=raw as unknown as GrantOptions;grants.value=[];grantCapability.value='';grantScope.value='';plan.value=null;
 });}
 function setGrantTarget(value:string){if(busy.value||pending.value)return;targetTenant.value=value;grantOptions.value=null;grants.value=[];plan.value=null;}
 function addGrant(){try{requirePermission('install');if(busy.value||pending.value)throw new Error('请先处理待确认操作');if(!grantOptions.value||!grantOptions.value.capabilities.some(c=>c.capabilityId===grantCapability.value))throw new Error('请选择软件包声明的能力');const scope=grantScope.value;if(scope!==`tenant:${grantOptions.value.targetTenant}`&&!/^class:[A-Za-z0-9_.-]{1,100}$/.test(scope))throw new Error('数据范围必须明确为目标租户或一个班级');if(grants.value.length>=128)throw new Error('单次最多128项授权变更');const entry={capabilityId:grantCapability.value,dataScope:scope,allow:grantAllow.value};grants.value=grants.value.filter(g=>g.capabilityId!==entry.capabilityId||g.dataScope!==entry.dataScope);grants.value.push(entry);plan.value=null;}catch(e){error.value=e instanceof Error?e.message:'授权选择无效';}}
 function removeGrant(index:number){if(!busy.value&&!pending.value){grants.value.splice(index,1);plan.value=null;}}
 async function previewGrants(){await read(async()=>{
  requirePermission('install');if(pending.value)throw new Error('请先处理待确认操作');const options=grantOptions.value;if(!options||!grants.value.length)throw new Error('请先加载目标租户的授权选项并明确选择变更');
  plan.value=null;
  const raw=record(await request(`/fcp/plugins/${encodeURIComponent(grantPlugin.value)}/grants/plans`,'POST',{grants:grants.value.map(g=>({...g})),expectedRevision:options.revision,targetTenant:options.targetTenant}));
  if(raw.targetTenant!==options.targetTenant||!validChanges(raw.changes)||!Array.isArray(raw.affectedPlugins)||raw.affectedPlugins.length!==1||raw.affectedPlugins[0]!==grantPlugin.value)throw new Error('服务端授权计划与所选目标不一致');
  parsePlan({...raw,pluginId:grantPlugin.value,action:'GRANT'});
 });}
 function retry(){if(pending.value?.kind==='uninstall'){error.value='卸载接口没有同键回执，请先查询实际安装状态，不直接重复发送。';return Promise.resolve();}if(pending.value?.kind==='decision'){error.value='决策接口没有幂等回执，请先查询操作状态。结果不明时不要直接重复发送。';return Promise.resolve();}if(pending.value)return send(pending.value);return Promise.resolve();}
 function abandon(){if(busy.value)return;pending.value=null;notice.value='已放弃本页重试信息，这不会撤销服务端可能完成的操作。请先刷新状态核对。';}
 function cancelPlan(){if(!pending.value&&!busy.value){plan.value=null;approvedIds.value=[];}}
 const stopNavigation=router.beforeEach(to=>{if(to.path==='/login'){reset();return true;}if(pending.value&&to.path!==route.path){error.value='请先核对或明确放弃待确认管理操作';return false;}return true;});
 const beforeUnload=(event:BeforeUnloadEvent)=>{if(pending.value){event.preventDefault();event.returnValue='';}};
 onMounted(()=>{void refresh();poll=setInterval(()=>{if(operation.value&&operation.value.phase!=='COMPLETE')void checkOperation();},3000);if(typeof window!=='undefined')window.addEventListener('beforeunload',beforeUnload);});
 onBeforeUnmount(()=>{alive=false;reset();stopIdentity();stopPermissions();stopNavigation();if(poll)clearInterval(poll);if(typeof window!=='undefined')window.removeEventListener('beforeunload',beforeUnload);});
 return {canManage,canInstall,plugins,revision,busy,error,notice,upload,trustAcknowledged,plan,approvedIds,operation,cancelAcknowledged,pending,uninstallCandidate,uninstallAcknowledged,previewUninstall,uninstall,cancelUninstall,checkUninstall,grantPlugin,grantCapability,grantScope,grantAllow,targetTenant,grants,grantOptions,grantAcknowledged,loadGrantOptions,setGrantTarget,addGrant,removeGrant,previewGrants,refresh,selectFile,preview,install,confirm,checkOperation,decide,retry,abandon,cancelPlan};
}
