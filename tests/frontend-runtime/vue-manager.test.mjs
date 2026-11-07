/** Vue management component lifecycle contract. Explicit transport doubles, not native authentication. */
import test from 'node:test';import assert from 'node:assert/strict';
import {createRenderer,defineComponent,h,nextTick,reactive} from 'vue';import {createRouter,createMemoryHistory} from 'vue-router';import {usePluginManager} from './usePluginManager.mjs';
const renderer=createRenderer({createElement:tag=>({tag,children:[]}),createText:text=>({text}),createComment:text=>({text}),insert:(n,p)=>p.children?.push(n),remove:()=>{},setText:(n,t)=>{n.text=t},setElementText:(n,t)=>{n.text=t},parentNode:()=>null,nextSibling:()=>null,patchProp:(n,k,_o,v)=>{n[k]=v}});
const tick=async()=>{await new Promise(r=>setTimeout(r,0));await nextTick()};
const plugin={pluginId:'demo',version:'1.0.0',digest:'a'.repeat(64),state:'INSTALLED',installation:'INSTALLED',deployment:'UNKNOWN',desired:'DISABLED',effective:'DISABLED',revision:3};
async function setup(shell,t,permissions=['manage','install']){
 let changed=()=>{},key='first',state;const calls=[];const context=reactive({userId:'user',tenantId:'000000',language:'en',permissions});const native=reactive(permissions.map(p=>'fcp:plugin:'+p));
 const envelope=data=>shell==='admin'?{code:200,data}:{data,error:null};
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/fcp/plugins',component:defineComponent({render:()=>null})},{path:'/other',component:defineComponent({render:()=>null})}]});await router.push('/fcp/plugins');await router.isReady();
 let handler=async(path)=>{if(path==='/fcp/plugins')return {revision:3,plugins:[plugin]};throw new Error('Unexpected endpoint '+path)};
 const host={shell,identityKey:()=>key,controlPermissions:()=>native,observeIdentity:fn=>{changed=fn;return()=>{}},request:async(path,method,body,signal,options)=>{calls.push({path,method,body,headers:options?.headers,signal});return envelope(await handler(path,body,options));}};
 const app=renderer.createApp(defineComponent({setup(){state=usePluginManager(host,()=>context,()=>{});return()=>h('div')}}));app.use(router);app.mount({children:[]});t.after(()=>app.unmount());await tick();
 return {state,calls,router,context,native,handler:fn=>{handler=fn},change:()=>{key='second';changed()}};
}
for(const shell of ['admin','teacher']){
 test(`${shell} manager: permission gate and install confirmation do not grant or enable`,async t=>{
  const a=await setup(shell,t);let uploads=0;
  a.handler(async(path,body,options)=>{if(path==='/fcp/plugins')return {revision:3,plugins:[plugin]};if(path==='/fcp/packages'){uploads++;assert.ok(body instanceof ArrayBuffer);return {pluginId:'demo',version:'1.0.0',digest:options.headers['X-Fcp-Digest'],state:'INSTALLED',revision:4}};throw new Error('Unexpected mutation')});
  await a.state.selectFile(new File(['signed-package-unit-fixture'],'plugin.zip'));assert.match(a.state.upload.value.digest,/^[0-9a-f]{64}$/);await a.state.install();assert.equal(uploads,0);
  a.state.trustAcknowledged.value=true;await a.state.install();assert.equal(uploads,1);assert.equal(a.state.pending.value,null);assert.match(a.state.notice.value,/不代表授权或启用/);assert.equal(a.calls.filter(c=>c.method==='POST').length,1);
  const blocked=await setup(shell,t,[]);await blocked.state.selectFile(new File(['x'],'x.zip'));await blocked.state.install();assert.equal(blocked.calls.length,0);assert.equal(blocked.state.canManage.value,false);assert.equal(blocked.state.canInstall.value,false);
 });
 test(`${shell} manager: uncertain binary install retries identical bytes, digest and key`,async t=>{
  const a=await setup(shell,t);let fail=true;
  a.handler(async(path,_body,options)=>{if(path==='/fcp/plugins')return {revision:3,plugins:[plugin]};if(fail)throw new Error('connection lost');return {pluginId:'demo',version:'1.0.0',digest:options.headers['X-Fcp-Digest'],state:'INSTALLED',revision:4}});
  await a.state.selectFile(new File(['package bytes'],'x.zip'));a.state.trustAcknowledged.value=true;await a.state.install();assert.ok(a.state.pending.value);const first=a.calls.find(c=>c.path==='/fcp/packages');
  await a.state.selectFile(new File(['different'],'y.zip'));assert.equal(a.state.upload.value.name,'x.zip');await a.router.push('/other');assert.equal(a.router.currentRoute.value.path,'/fcp/plugins');
  fail=false;await a.state.retry();const requests=a.calls.filter(c=>c.path==='/fcp/packages');assert.equal(requests.length,2);assert.deepEqual(requests[1].headers,first.headers);assert.deepEqual(new Uint8Array(requests[1].body),new Uint8Array(first.body));assert.equal(a.state.pending.value,null);
 });
 test(`${shell} manager: cascade plan requires complete list and drain cancel is explicit`,async t=>{
  const a=await setup(shell,t);a.handler(async(path,body)=>{
   if(path==='/fcp/plugins')return {revision:3,plugins:[plugin]};
   if(path.endsWith('/plans'))return {planId:'plan',digest:'plan-digest',pluginId:'demo',action:'DISABLE',affectedPlugins:['demo','dependent'],expectedRevision:3};
   if(path.endsWith('/confirm')){assert.deepEqual(body.affectedPlugins,['demo','dependent']);assert.match(body.idempotencyKey,/^[0-9a-f-]{36}$/);return {operationId:'op',phase:'DRAINING',outcome:'DRAIN_PENDING',plugins:['demo','dependent']}};
   if(path.endsWith('/decision'))return {operationId:'op',phase:'COMPLETE',outcome:'APPLIED',plugins:['demo','dependent']};throw new Error('Unexpected endpoint');
  });
  await a.state.preview(plugin,'disable');a.state.approvedIds.value=['demo'];await a.state.confirm();assert.equal(a.calls.filter(c=>c.path.endsWith('/confirm')).length,0);
  a.state.approvedIds.value=['demo','dependent'];await a.state.confirm();assert.equal(a.state.operation.value.phase,'DRAINING');await a.state.decide('cancel');assert.equal(a.calls.filter(c=>c.path.endsWith('/decision')).length,0);
  a.state.cancelAcknowledged.value=true;await a.state.decide('cancel');assert.equal(a.state.operation.value.phase,'COMPLETE');assert.equal(a.calls.filter(c=>c.path.endsWith('/decision')).length,1);
 });
 test(`${shell} manager: identity change discards upload and cannot retry old operation`,async t=>{
  const a=await setup(shell,t);let complete;
  a.handler(async(path,_body,options)=>path==='/fcp/plugins'?{revision:3,plugins:[plugin]}:new Promise(r=>complete=()=>r({pluginId:'demo',version:'1.0.0',digest:options.headers['X-Fcp-Digest'],state:'INSTALLED',revision:4})));
  await a.state.selectFile(new File(['bytes'],'x.zip'));a.state.trustAcknowledged.value=true;const inflight=a.state.install();await tick();a.change();complete();await inflight;
  assert.equal(a.state.upload.value,null);assert.equal(a.state.pending.value,null);assert.equal(a.state.plugins.value.length,0);assert.match(a.state.notice.value,/原身份核对/);await a.state.retry();assert.equal(a.calls.filter(c=>c.method==='POST').length,1);
 });
}

for(const shell of ['admin','teacher']) test(`${shell} manager: platform grants use declared options and exact scope confirmation`,async t=>{
 const a=await setup(shell,t);a.state.setGrantTarget('tenant-a');
 a.handler(async(path,body)=>{
  if(path==='/fcp/plugins')return {revision:3,plugins:[plugin]};
  if(path.includes('/grant-options'))return {targetTenant:'tenant-a',revision:3,capabilities:[{capabilityId:'demo.run',permission:'fcp:plugin:demo.run',sideEffects:true}],currentGrants:[]};
  if(path.endsWith('/grants/plans')){assert.deepEqual(body,{targetTenant:'tenant-a',expectedRevision:3,grants:[{capabilityId:'demo.run',dataScope:'class:class-a',allow:true}]});return {planId:'grant-plan',digest:'grant-digest',affectedPlugins:['demo'],targetTenant:'tenant-a',changes:[{capabilityId:'demo.run',operation:'invoke',permission:'fcp:plugin:demo.run',dataScope:'class:class-a',allow:true}],expectedRevision:3};}
  if(path.endsWith('/confirm'))return {operationId:'grant.receipt',phase:'COMPLETE',outcome:'APPLIED',plugins:['demo']};throw new Error('unexpected request');
 });
 await a.state.loadGrantOptions('demo');a.state.grantCapability.value='undeclared';a.state.grantScope.value='class:class-a';a.state.addGrant();assert.equal(a.state.grants.value.length,0);
 a.state.grantCapability.value='demo.run';a.state.grantScope.value='tenant:wrong';a.state.addGrant();assert.equal(a.state.grants.value.length,0);
 a.state.grantScope.value='class:class-a';a.state.addGrant();await a.state.previewGrants();assert.equal(a.state.plan.value.action,'GRANT');a.state.approvedIds.value=['demo'];await a.state.confirm();assert.equal(a.calls.filter(c=>c.path.endsWith('/confirm')).length,0);
 a.state.grantAcknowledged.value=true;await a.state.confirm();assert.equal(a.state.operation.value.operationId,'grant.receipt');assert.equal(a.calls.filter(c=>c.path.endsWith('/confirm')).length,1);assert.equal(a.calls.filter(c=>c.body?.action==='enable').length,0);
});
for(const shell of ['admin','teacher']) test(`${shell} manager: unknown drain decision is queried, not blindly replayed`,async t=>{
 const a=await setup(shell,t);a.state.operation.value={operationId:'op',phase:'DRAINING',outcome:'DRAIN_PENDING',plugins:['demo']};
 a.handler(async(path)=>{if(path.endsWith('/decision'))throw new Error('decision response lost');if(path==='/fcp/operations/op')return {operationId:'op',phase:'COMPLETE',outcome:'APPLIED',plugins:['demo']};if(path==='/fcp/plugins')return {revision:3,plugins:[plugin]};throw new Error('unexpected request')});
 await a.state.decide('wait');assert.equal(a.state.pending.value.kind,'decision');await a.state.retry();assert.equal(a.calls.filter(c=>c.path.endsWith('/decision')).length,1);await a.state.checkOperation();assert.equal(a.state.pending.value,null);assert.equal(a.state.operation.value.phase,'COMPLETE');
});

test('manager clears server-authoritative context change even before native store notification',async t=>{
 const a=await setup('admin',t);await a.state.selectFile(new File(['bytes'],'x.zip'));assert.ok(a.state.upload.value);a.context.tenantId='other';assert.equal(a.state.upload.value,null);assert.equal(a.state.canInstall.value,false);assert.equal(a.state.plugins.value.length,0);
});

for(const shell of ['admin','teacher']) test(`${shell} manager: native wildcard requires explicit server control permission`,async t=>{
 const allowed=await setup(shell,t);allowed.native.splice(0,allowed.native.length,'*:*:*');assert.equal(allowed.state.canManage.value,true);assert.equal(allowed.state.canInstall.value,true);
 const denied=await setup(shell,t,[]);denied.native.push('*:*:*');assert.equal(denied.state.canManage.value,false);assert.equal(denied.state.canInstall.value,false);await denied.state.selectFile(new File(['bytes'],'x.zip'));assert.equal(denied.calls.length,0);
});
for(const shell of ['admin','teacher']) test(`${shell} manager: uninstall requires digest consent and unknown outcome is queried`,async t=>{
 const a=await setup(shell,t);let requests=0;
 a.handler(async(path,body)=>{if(path==='/fcp/plugins')return {revision:4,plugins:[{...plugin,state:'UNINSTALLED',installation:'UNINSTALLED'}]};if(path.endsWith('/uninstall')){requests++;assert.deepEqual(body,{expectedRevision:3,confirmedDigest:plugin.digest});throw new Error('uninstall response lost')};if(path==='/fcp/plugins/demo')return {revision:4,installation:'UNINSTALLED',deployment:'STOPPED',desired:'DISABLED',effective:'DISABLED'};throw new Error('unexpected endpoint')});
 a.state.previewUninstall(plugin);await a.state.uninstall();assert.equal(requests,0);a.state.uninstallAcknowledged.value=true;await a.state.uninstall();assert.equal(requests,1);assert.equal(a.state.pending.value.kind,'uninstall');await a.state.retry();assert.equal(requests,1);await a.state.checkUninstall();assert.equal(a.state.pending.value,null);assert.equal(a.state.uninstallCandidate.value,null);assert.match(a.state.notice.value,/查询确认/);
});
