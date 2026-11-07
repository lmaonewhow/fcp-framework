import test from 'node:test';
import assert from 'node:assert/strict';
import {ObservationConsole,policy,percentageStages} from '../../integration/frontend-release/observation.ts';
const rules=()=>({stages:[2000,5000,10000],minimumDwellMillis:60000,minimumSamples:100,maximumAgeMillis:120000,maximumErrorRate:0.01,maximumP95Millis:1000});
function fixture(shell='admin'){
 let listener=()=>{},permissionListener=()=>{},identity='one',permissions=['fcp:release:view','fcp:release:manage'],blocked=false;
 const calls=[];let automation={revision:4,status:'ENABLED'},release={revision:7,phase:'CANARY',basisPoints:1000};
 let handler=async path=>path==='/fcp/releases/state'?release:automation;
 const host={shell,identityKey:()=>identity,permissions:()=>permissions,observeIdentity(fn){listener=fn;return()=>{listener=()=>{}}},observePermissions(fn){permissionListener=fn;return()=>{permissionListener=()=>{}}},async request(...args){calls.push(args);const result=await handler(...args);return shell==='admin'?{code:200,data:result}:{error:null,data:result}}};
 const manager=new ObservationConsole(host,()=>blocked);
 return {manager,calls,setHandler(fn){handler=fn},setAutomation(v){automation=v},setRelease(v){release=v},setBlocked(v){blocked=v},permissions(v){permissions=v;listener()},refreshPermissions(v){permissions=v;permissionListener()},switchIdentity(){identity='two';listener()}};
}
test('policy projection strips any supplied sample and rejects invalid thresholds',()=>{
 assert.deepEqual(policy({...rules(),sample:{requests:999}}),rules());
 for(const p of [{...rules(),stages:[2000,1000,10000]},{...rules(),stages:[2000]},{...rules(),minimumDwellMillis:999},{...rules(),minimumSamples:NaN},{...rules(),maximumErrorRate:Infinity},{...rules(),maximumP95Millis:0}])assert.throws(()=>policy(p));
});
test('both shell responses load only read endpoints, never tick automatically',async()=>{
 for(const shell of ['admin','teacher']){const f=fixture(shell);await f.manager.load();assert.equal(f.manager.data.state.revision,4);assert.deepEqual(f.calls.map(c=>[c[0],c[1]]),[['/fcp/releases/automation/state','GET'],['/fcp/releases/state','GET']]);}
});
test('view-only cannot enable disable tick; wildcard alone cannot read',async()=>{
 const f=fixture();f.permissions(['fcp:release:view']);await f.manager.load();await assert.rejects(()=>f.manager.enable(rules(),true),/PERMISSION_DENIED/);await assert.rejects(()=>f.manager.disable(true),/PERMISSION_DENIED/);await assert.rejects(()=>f.manager.tick(true),/PERMISSION_DENIED/);assert.equal(f.calls.length,2);
 f.permissions(['*:*:*']);await assert.rejects(()=>f.manager.load(),/PERMISSION_DENIED/);
});
test('enable sends exactly policy and automation revision after current release read',async()=>{
 const f=fixture();await f.manager.load();await assert.rejects(()=>f.manager.enable(rules(),false),/CONFIRMATION_REQUIRED/);await f.manager.enable({...rules(),sample:{requests:999}},true);assert.deepEqual(f.calls.at(-1).slice(0,3),['/fcp/releases/automation/enable','POST',{expectedRevision:4,...rules()}]);assert.equal(f.calls.at(-2)[0],'/fcp/releases/state');
});
test('disable sends CAS only and tick sends empty object, never browser metrics',async()=>{
 const f=fixture();await f.manager.load();await f.manager.disable(true);assert.deepEqual(f.calls.at(-1).slice(0,3),['/fcp/releases/automation/disable','POST',{expectedRevision:4}]);await f.manager.tick(true);const ticks=f.calls.filter(c=>c[0].endsWith('/tick'));assert.equal(ticks.length,1);assert.deepEqual(ticks[0][2],{});
});
test('pending automation blocks enable disable but explicit tick settles only with release terminal',async()=>{
 const f=fixture();f.setAutomation({revision:5,status:'ENABLED',pending:{operationId:'original'}});await f.manager.load();await assert.rejects(()=>f.manager.enable(rules(),true),/AUTOMATION_PENDING/);await assert.rejects(()=>f.manager.disable(true),/AUTOMATION_PENDING/);await f.manager.tick(true);assert.equal(f.calls.filter(c=>c[0].endsWith('/tick')).length,1);
});
test('release pending and external unknown block all mutations',async()=>{
 const f=fixture();f.setRelease({revision:8,phase:'CANARY',pending:{operationId:'deployment'}});await f.manager.load();await assert.rejects(()=>f.manager.tick(true),/RECONCILE_REQUIRED/);assert.equal(f.calls.length,2);
 const g=fixture();await g.manager.load();g.setBlocked(true);await assert.rejects(()=>g.manager.enable(rules(),true),/RECONCILE_REQUIRED/);assert.equal(g.calls.length,2);
});
test('release becomes pending after refresh: fresh preflight prevents tick',async()=>{
 const f=fixture();await f.manager.load();f.setRelease({revision:8,phase:'CANARY',pending:{operationId:'x'}});await f.manager.tick(true);assert.equal(f.calls.filter(c=>c[1]==='POST').length,0);assert.equal(f.manager.data.unknown,true);
});
test('unknown mutation blocks repeat even after readonly refresh',async()=>{
 const f=fixture();await f.manager.load();f.setHandler(async(path,method)=>{if(method==='POST')throw new Error('timeout');return {revision:8,phase:'CANARY',basisPoints:1000}});await f.manager.tick(true);assert.equal(f.manager.data.unknown,true);await f.manager.load();await assert.rejects(()=>f.manager.tick(true));assert.equal(f.calls.filter(c=>c[1]==='POST').length,1);
});
test('busy duplicate click sends only one tick',async()=>{
 const f=fixture();await f.manager.load();let finish;f.setHandler(async(path,method)=>method==='POST'?new Promise(r=>finish=r):{revision:7,phase:'CANARY',basisPoints:1000});const first=f.manager.tick(true);await new Promise(r=>setImmediate(r));await f.manager.tick(true);assert.equal(f.calls.filter(c=>c[1]==='POST').length,1);finish({revision:5,status:'ENABLED'});await first;
});
test('404 unavailable does not retry or become uncertain execution',async()=>{
 const f=fixture();f.setHandler(async()=>{throw {response:{status:404}}});await f.manager.load();assert.equal(f.manager.data.unavailable,true);assert.equal(f.manager.data.unknown,false);assert.equal(f.calls.length,1);
});
test('401 clears identity-bound data, late responses after identity change are discarded',async()=>{
 const f=fixture();await f.manager.load();f.setHandler(async()=>{throw {response:{status:401}}});await f.manager.load();assert.equal(f.manager.data.state,null);assert.match(f.manager.data.error,/登录/);
 const g=fixture();let finish;g.setHandler(()=>new Promise(r=>finish=r));const read=g.manager.load();const signal=g.calls[0][3];g.switchIdentity();assert.equal(signal.aborted,true);finish({revision:999,status:'ENABLED'});await read;assert.equal(g.manager.data.state,null);assert.equal(g.calls.length,1);
});
test('permission reset and disposal prevent all future mutation',async()=>{
 const f=fixture();await f.manager.load();f.permissions([]);assert.equal(f.manager.data.state,null);await assert.rejects(()=>f.manager.disable(true),/PERMISSION_DENIED/);f.manager.dispose();await assert.rejects(()=>f.manager.load(),/PERMISSION_DENIED/);
});

test('percentage input converts decimals exactly without silent rounding',()=>{assert.deepEqual(percentageStages('20.01, 50.29,100'),[2001,5029,10000]);for(const value of ['', '10.001,100', '1e2', '-1,100', '20,,100'])assert.throws(()=>percentageStages(value));});

test('permission refresh aborts in-flight write and preserves uncertainty after regrant',async()=>{
 const f=fixture();await f.manager.load();let finish;f.setHandler(async(path,method)=>method==='POST'?new Promise(r=>finish=r):{revision:7,phase:'CANARY',basisPoints:1000});const work=f.manager.tick(true);await new Promise(r=>setImmediate(r));const signal=f.calls.at(-1)[3];f.refreshPermissions([]);assert.equal(signal.aborted,true);assert.equal(f.manager.data.unknown,true);assert.equal(f.manager.data.state,null);f.refreshPermissions(['fcp:release:view','fcp:release:manage']);assert.equal(f.manager.data.unknown,true);finish({revision:99,status:'ENABLED'});await work;assert.equal(f.manager.data.state,null);assert.equal(f.manager.data.unknown,true);
});
test('permission regrant cannot clear existing unknown result but true identity change can',async()=>{
 const f=fixture();await f.manager.load();f.setHandler(async(path,method)=>{if(method==='POST')throw new Error('lost');return {revision:7,phase:'CANARY',basisPoints:1000}});await f.manager.tick(true);f.refreshPermissions([]);f.refreshPermissions(['fcp:release:view','fcp:release:manage']);assert.equal(f.manager.data.unknown,true);f.switchIdentity();assert.equal(f.manager.data.unknown,false);
});
test('permission event during readonly fetch clears snapshot without inventing write uncertainty',async()=>{
 const f=fixture();let finish;f.setHandler(()=>new Promise(r=>finish=r));const read=f.manager.load();f.refreshPermissions([]);finish({revision:9,status:'ENABLED'});await read;assert.equal(f.manager.data.state,null);assert.equal(f.manager.data.unknown,false);
});
