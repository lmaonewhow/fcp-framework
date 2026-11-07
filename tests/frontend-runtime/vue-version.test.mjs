/** Real Vue/Vue Router lifecycle with explicit endpoint doubles; not native authentication acceptance. */
import test from 'node:test';import assert from 'node:assert/strict';
import {createRenderer,defineComponent,h,nextTick,reactive} from 'vue';import {createRouter,createMemoryHistory} from 'vue-router';import {useVersionManager} from './useVersionManager.mjs';
const renderer=createRenderer({createElement:tag=>({tag,children:[]}),createText:text=>({text}),createComment:text=>({text}),insert:(n,p)=>p.children?.push(n),remove:()=>{},setText:(n,t)=>{n.text=t},setElementText:(n,t)=>{n.text=t},parentNode:()=>null,nextSibling:()=>null,patchProp:(n,k,_o,v)=>{n[k]=v}});
const tick=async()=>{await new Promise(r=>setTimeout(r,0));await nextTick()};const A='a'.repeat(64),B='b'.repeat(64),D='d'.repeat(64);
const catalog=(target='2.0.0')=>({pluginId:'demo',revision:3,current:{state:'INSTALLED',version:'1.0.0',digest:A},versions:[{version:'1.0.0',digest:A,state:'VERIFIED'},{version:target,digest:B,state:'VERIFIED'}]});
const plan=(action='UPGRADE',target='2.0.0')=>({planId:'version-plan',digest:D,pluginId:'demo',action,fromVersion:'1.0.0',fromDigest:A,targetVersion:target,targetDigest:B,expectedRevision:3,affectedTenants:['tenant-a','tenant-b'],dependentPlugins:['consumer']});
const receipt=(p,phase='COMPLETE',outcome='APPLIED')=>({operationId:'version.receipt',pluginId:p.pluginId,action:p.action,fromVersion:p.fromVersion,fromDigest:p.fromDigest,targetVersion:p.targetVersion,targetDigest:p.targetDigest,phase,outcome,revision:4});
async function setup(shell,t,permissions=['install']){
 let state,key='identity',change=()=>{},blocked=false;const calls=[],context=reactive({userId:'admin',tenantId:'000000',language:'en',permissions}),native=reactive(['*:*:*']);
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/fcp/plugins',component:defineComponent({render:()=>null})},{path:'/elsewhere',component:defineComponent({render:()=>null})}]});await router.push('/fcp/plugins');await router.isReady();
 let handler=async()=>{throw new Error('Unexpected endpoint')};const host={shell,controlPermissions:()=>native,identityKey:()=>key,observeIdentity:fn=>{change=fn;return()=>{}},request:async(path,method,body,signal,options)=>{calls.push({path,method,body,signal,headers:options?.headers});const data=await handler(path,body,options);return shell==='admin'?{code:200,data}:{data,error:null}}};
 const app=renderer.createApp(defineComponent({setup(){state=useVersionManager(host,()=>context,()=>blocked,()=>{});return()=>h('div')}}));app.use(router);app.mount({children:[]});t.after(()=>app.unmount());await tick();
 return {state,calls,router,context,native,handler:fn=>{handler=fn},block:()=>{blocked=true},change:()=>{key='new';change()}};
}
function acknowledge(s){s.confirmedTenants.value=['tenant-a','tenant-b'];s.confirmedDependants.value=['consumer'];s.confirmedDowntime.value=true;s.confirmedDataRetention.value=true;}
for(const shell of ['admin','teacher']){
 test(`${shell} version: signed-package staging is separate from switching and gated`,async t=>{
  const a=await setup(shell,t);a.handler(async(path,_body,options)=>{assert.equal(path,'/fcp/packages/staged');assert.equal(options.headers['X-Fcp-Revision'],undefined);return {pluginId:'demo',version:'2.0.0',digest:options.headers['X-Fcp-Digest'],state:'VERIFIED',replayed:false}});
  await a.state.selectFile(new File(['signed fixture'],'next.zip'));await a.state.stage();assert.equal(a.calls.length,0);a.state.stageAcknowledged.value=true;await a.state.stage();assert.equal(a.calls.length,1);assert.equal(a.state.pending.value,null);assert.match(a.state.notice.value,/未切换/);assert.ok(a.calls[0].body instanceof ArrayBuffer);
  const denied=await setup(shell,t,[]);assert.equal(denied.state.allowed.value,false);await denied.state.selectFile(new File(['x'],'x.zip'));await denied.state.stage();assert.equal(denied.calls.length,0);a.context.tenantId='tenant-a';assert.equal(a.state.allowed.value,false);
 });
 for(const action of ['UPGRADE','ROLLBACK'])test(`${shell} version: ${action} requires exact target, complete impact and downtime consent`,async t=>{
  const a=await setup(shell,t),target=action==='UPGRADE'?'2.0.0':'0.9.0',p=plan(action,target);
  a.handler(async(path,body)=>{if(path.endsWith('/versions'))return catalog(target);if(path.endsWith('/version-plans')){assert.deepEqual(body,{action,targetVersion:target,targetDigest:B,expectedRevision:3});return p;}if(path.endsWith('/confirm')){assert.equal(body.digest,D);assert.equal(body.confirmedDowntime,true);assert.match(body.idempotencyKey,/^[0-9a-f-]{36}$/);return receipt(p);}throw new Error('Unexpected mutation')});
  await a.state.loadVersions('demo');a.state.changeAction(action);a.state.changeTarget(B);await a.state.preview();a.state.confirmedTenants.value=['tenant-a'];await a.state.confirm();assert.equal(a.calls.filter(c=>c.path.endsWith('/confirm')).length,0);
  acknowledge(a.state);await a.state.confirm();assert.equal(a.state.operation.value.phase,'COMPLETE');assert.equal(a.state.pending.value,null);assert.match(a.state.notice.value,/仍保持禁用/);assert.equal(a.calls.filter(c=>/enable|grants|\/plans$/.test(c.path)&&!c.path.endsWith('version-plans')).length,0);
 });
 test(`${shell} version: lost confirmation recovers same key after restart, UNKNOWN is not success`,async t=>{
  const a=await setup(shell,t),p=plan();let confirmations=0;
  a.handler(async(path)=>{if(path.endsWith('/versions'))return catalog();if(path.endsWith('/version-plans'))return p;if(path.endsWith('/confirm')){if(++confirmations===1)throw new Error('host restarted before response');return receipt(p,'UNKNOWN','OUTCOME_UNKNOWN');}if(path==='/fcp/version-operations/version.receipt')return receipt(p);throw new Error('Unexpected endpoint')});
  await a.state.loadVersions('demo');a.state.changeTarget(B);await a.state.preview();acknowledge(a.state);await a.state.confirm();assert.equal(a.state.operation.value,null);assert.ok(a.state.pending.value);assert.equal(a.calls.filter(c=>c.path.includes('/version-operations/')).length,0);
  const first=a.calls.find(c=>c.path.endsWith('/confirm'));a.state.changeTarget(A);assert.equal(a.state.targetDigest.value,B);await a.router.push('/elsewhere');assert.equal(a.router.currentRoute.value.path,'/fcp/plugins');
  await a.state.retry();const second=a.calls.filter(c=>c.path.endsWith('/confirm'))[1];assert.deepEqual(second.body,first.body);assert.equal(second.path,first.path);assert.equal(a.state.operation.value.phase,'UNKNOWN');assert.ok(a.state.pending.value);
  await a.state.query();assert.equal(a.state.pending.value,null);assert.equal(a.state.operation.value.outcome,'APPLIED');
 });
 test(`${shell} version: incompatible schema fails closed without automatic disable`,async t=>{
  const a=await setup(shell,t);a.handler(async path=>{if(path.endsWith('/versions'))return catalog();throw new Error('SCHEMA_INCOMPATIBLE: no safe downgrade')});await a.state.loadVersions('demo');a.state.changeTarget(B);await a.state.preview();assert.equal(a.state.plan.value,null);assert.match(a.state.error.value,/SCHEMA_INCOMPATIBLE/);assert.equal(a.calls.filter(c=>c.method==='POST').length,1);await a.state.confirm();assert.equal(a.calls.filter(c=>c.path.endsWith('/confirm')).length,0);
 });
 test(`${shell} version: mismatched receipt is not accepted as success`,async t=>{
  const a=await setup(shell,t),p=plan();a.handler(async path=>path.endsWith('/versions')?catalog():path.endsWith('/version-plans')?p:{...receipt(p),targetDigest:A});await a.state.loadVersions('demo');a.state.changeTarget(B);await a.state.preview();acknowledge(a.state);await a.state.confirm();assert.equal(a.state.operation.value,null);assert.ok(a.state.pending.value);assert.match(a.state.error.value,/确切目标不一致/);
 });
 test(`${shell} version: identity epoch aborts staging and discards late receipts`,async t=>{
  const a=await setup(shell,t);let finish;a.handler(async(_path,_body,options)=>new Promise(resolve=>finish=()=>resolve({pluginId:'demo',version:'2.0.0',digest:options.headers['X-Fcp-Digest'],state:'VERIFIED',replayed:false})));
  await a.state.selectFile(new File(['signed fixture'],'next.zip'));a.state.stageAcknowledged.value=true;const pending=a.state.stage();await tick();a.change();assert.equal(a.calls[0].signal.aborted,true);finish();await pending;assert.equal(a.state.pending.value,null);assert.equal(a.state.selectedPackage.value,null);assert.match(a.state.notice.value,/原身份/);await a.state.retry();assert.equal(a.calls.length,1);
 });
}
