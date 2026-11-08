import test from 'node:test';
import assert from 'node:assert/strict';
import {ReleaseConsole} from '../../integration/frontend-release/controller.ts';
import {ObservationConsole} from '../../integration/frontend-release/observation.ts';
const plan={environment:'test',stableRelease:'v1',candidateRelease:'v2',stableDigest:'a'.repeat(64),candidateDigest:'b'.repeat(64),schemaVersion:1,stableSchemaMin:0,stableSchemaMax:2,candidateSchemaMin:1,candidateSchemaMax:3};
const release=()=>({revision:3,phase:'CANARY',basisPoints:1000,plan,pending:{operationId:'p1',action:'EXPAND'},supportsFencedContainment:true,capabilities:['ABORT','EXPAND']});
const auto=()=>({revision:4,status:'ENABLED',owner:['tenant','u1'],pending:{operationId:'p1'}});
function fixture(kind){let key='u1',identity=()=>{},permission=()=>{},grants=['fcp:release:view','fcp:release:manage'],finish,defer=()=>false;const calls=[];
 const host={shell:'admin',identityKey:()=>key,permissions:()=>grants,observeIdentity(fn){identity=fn;return()=>{}},observePermissions(fn){permission=fn;return()=>{}},request(path,method){calls.push({path,method,key});if(defer(path,method))return new Promise(r=>finish=r);return Promise.resolve({code:200,data:path.includes('/automation/')?auto():release()})}};
 const c=kind==='containment'?new ReleaseConsole(host):new ObservationConsole(host,()=>true,()=>{},()=>true);
 return {c,calls,defer(fn){defer=fn},settle(data,change){finish({code:200,data});queueMicrotask(()=>{if(change==='identity'){key='u2';identity()}else{grants=[];permission();grants=['fcp:release:view','fcp:release:manage'];permission()}})}};
}
for(const kind of ['containment','safety'])for(const change of ['identity','permissions'])test(`${kind}: post-request validation microtask ${change} cannot commit old state or start next request`,async()=>{
 const f=fixture(kind);await f.c.load();if(kind==='containment')await f.c.reviewContainment();f.defer((_,method)=>method==='POST');const work=kind==='containment'?f.c.contain(true):f.c.safety(true);await new Promise(r=>setImmediate(r));const count=f.calls.length;
 f.settle({revision:99,phase:'ABORTED',privateOldUser:'must-not-reappear'},change);await work;
 assert.equal(f.c.data.state,null);if(kind==='safety')assert.equal(f.c.data.release,null);assert.equal(f.calls.length,count);assert.equal(f.c.data.unknown,change==='permissions');assert.equal(f.c.data.busy,false);
});
for(const kind of ['containment','safety'])test(`${kind}: first read continuation cannot start second request after identity switches`,async()=>{
 const f=fixture(kind);f.defer(()=>true);const work=f.c.load();f.settle(kind==='containment'?release():auto(),'identity');await work;assert.equal(f.c.data.state,null);assert.equal(f.calls.length,1);
});
for(const kind of ['containment','safety'])test(`${kind}: safety preflight await cannot POST after permission revocation and regrant`,async()=>{
 const f=fixture(kind);await f.c.load();if(kind==='containment')await f.c.reviewContainment();f.defer(()=>true);const work=kind==='containment'?f.c.contain(true):f.c.safety(true);const count=f.calls.length;f.settle(kind==='containment'?release():auto(),'permissions');await work;assert.equal(f.c.data.state,null);assert.equal(f.calls.length,count);assert.equal(f.calls.some(c=>c.method==='POST'),false);assert.equal(f.c.data.unknown,true);
});
import {ReleasePermissionHost} from '../../integration/frontend-release/permission-host.ts';
for(const action of ['recover','reconcile','execute','confirmPlan','contain','load-first','load-second'])test(`real permission projection: ${action} outer continuation cannot unlock unknown after regrant`,async()=>{
 let changed=()=>{},finish,posts=0,defer=false;let server={...release(),supportsRecovery:true,capabilities:['ABORT','EXPAND']};
 if(['execute','confirmPlan'].includes(action))delete server.pending;
 const host={shell:'admin',identityKey:()=> 'u1',permissions:()=>['*:*:*'],observeIdentity:()=>()=>{},observePermissions(fn){changed=fn;return()=>{}},request(path,method){
  if(path==='/fcp/ui/session')return Promise.resolve({code:200,data:{releasePermissions:['fcp:release:view','fcp:release:manage']}});
  if(method==='POST')posts++;
  if(defer&&(method==='POST'||action==='load-first'&&path==='/fcp/releases/state'||action==='load-second'&&path==='/fcp/releases/versions'))return new Promise(r=>finish=r);
  return Promise.resolve({code:200,data:server});
 }};
 const projected=new ReleasePermissionHost(host);await projected.refresh();const c=new ReleaseConsole(projected);await c.load();
 if(action==='confirmPlan')c.review(plan);if(action==='contain')await c.reviewContainment();defer=true;
 const work=action==='recover'?c.recover(true):action==='reconcile'?c.reconcile():action==='execute'?c.execute('EXPAND',2000,true):action==='confirmPlan'?c.confirmPlan(true):action==='contain'?c.contain(true):c.load();
 await new Promise(r=>setImmediate(r));server={revision:9,phase:'CANARY',capabilities:['ABORT','EXPAND']};finish({code:200,data:server});queueMicrotask(changed);await work;
 assert.equal(c.data.state,null);assert.equal(c.data.unknown,true);const previousPosts=posts;defer=false;await projected.refresh();await c.load();assert.equal(c.data.unknown,true);await assert.rejects(()=>c.execute('EXPAND',3000,true),/RECONCILE_REQUIRED/);assert.equal(posts,previousPosts);c.dispose();projected.dispose();
});
