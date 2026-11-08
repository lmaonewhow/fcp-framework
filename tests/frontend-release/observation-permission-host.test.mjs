import test from 'node:test';
import assert from 'node:assert/strict';
import {pathToFileURL} from 'node:url';
import {ObservationConsole} from '../../integration/frontend-release/observation.ts';
// An alternate source path permits verifying this isolated second PR against the first PR's exact host implementation.
const source=process.env.FCP_PERMISSION_HOST_SOURCE?pathToFileURL(process.env.FCP_PERMISSION_HOST_SOURCE):new URL('../../integration/frontend-release/permission-host.ts',import.meta.url);
const {ReleasePermissionHost}=await import(source.href);
function fixture(){
 let listener=()=>{},identity='native-user-one',grants=['fcp:release:view','fcp:release:manage'],projectionFailure=false;
 const calls=[];let write=async()=>({revision:5,status:'ENABLED'});
 const native={shell:'admin',identityKey:()=>identity,permissions:()=>['*:*:*'],observeIdentity(fn){listener=fn;return()=>{listener=()=>{}}},async request(path,method,body,signal){calls.push({path,method,body,signal});if(path==='/fcp/ui/session'){if(projectionFailure)throw new Error('projection lost');return {code:200,data:{releasePermissions:grants}}}if(path==='/fcp/releases/state')return {code:200,data:{revision:7,phase:'CANARY',basisPoints:1000}};if(method==='POST')return {code:200,data:await write()};return {code:200,data:{revision:4,status:'ENABLED'}}}};
 const host=new ReleasePermissionHost(native),manager=new ObservationConsole(host,()=>false);
 return {host,manager,calls,setWrite(fn){write=fn},setGrants(v){grants=v},failProjection(){projectionFailure=true},identity(){identity='native-user-two';listener()}};
}
test('actual permission host unchanged grants refresh preserves in-flight write',async()=>{
 const f=fixture();await f.host.refresh();await f.manager.load();let finish;f.setWrite(()=>new Promise(r=>finish=r));const tick=f.manager.tick(true);await new Promise(r=>setImmediate(r));const post=f.calls.find(c=>c.method==='POST');const key=f.host.identityKey();await f.host.refresh();assert.equal(f.host.identityKey(),key);assert.equal(post.signal.aborted,false);assert.equal(f.manager.data.busy,true);assert.equal(f.manager.data.unknown,false);finish({revision:5,status:'ENABLED'});await tick;assert.equal(f.manager.data.state.revision,5);f.manager.dispose();f.host.dispose();
});
test('actual permission host lost write then refresh cannot unlock uncertainty',async()=>{
 const f=fixture();await f.host.refresh();await f.manager.load();f.setWrite(async()=>{throw new Error('reply lost')});await f.manager.tick(true);assert.equal(f.manager.data.unknown,true);await f.host.refresh();assert.equal(f.manager.data.unknown,true);await assert.rejects(()=>f.manager.tick(true),/RECONCILE_REQUIRED/);assert.equal(f.calls.filter(c=>c.method==='POST').length,1);f.manager.dispose();f.host.dispose();
});
test('actual permission host revoke and restore cannot discard in-flight write uncertainty',async()=>{
 const f=fixture();await f.host.refresh();await f.manager.load();let finish;f.setWrite(()=>new Promise(r=>finish=r));const tick=f.manager.tick(true);await new Promise(r=>setImmediate(r));const post=f.calls.find(c=>c.method==='POST');f.setGrants([]);await f.host.refresh();assert.equal(post.signal.aborted,true);assert.equal(f.manager.data.state,null);assert.equal(f.manager.data.unknown,true);f.setGrants(['fcp:release:view','fcp:release:manage']);await f.host.refresh();finish({revision:99,status:'ENABLED'});await tick;await f.manager.load();assert.equal(f.manager.data.unknown,true);await assert.rejects(()=>f.manager.tick(true),/RECONCILE_REQUIRED/);f.identity();assert.equal(f.manager.data.unknown,false);f.manager.dispose();f.host.dispose();
});
test('actual permission projection failure clears snapshot and preserves pending write uncertainty',async()=>{
 const f=fixture();await f.host.refresh();await f.manager.load();let finish;f.setWrite(()=>new Promise(r=>finish=r));const tick=f.manager.tick(true);await new Promise(r=>setImmediate(r));f.failProjection();await assert.rejects(()=>f.host.refresh());assert.equal(f.manager.canView,false);assert.equal(f.manager.data.state,null);assert.equal(f.manager.data.unknown,true);finish({revision:99,status:'ENABLED'});await tick;assert.equal(f.manager.data.state,null);f.manager.dispose();f.host.dispose();
});
