import test from 'node:test';
import assert from 'node:assert/strict';
import {ReleaseConsole, parsePlan} from '../../integration/frontend-release/controller.ts';

const plan = () => ({environment:'test',stableRelease:'v1',candidateRelease:'v2',stableDigest:'a'.repeat(64),candidateDigest:'b'.repeat(64),schemaVersion:1,stableSchemaMin:0,stableSchemaMax:2,candidateSchemaMin:1,candidateSchemaMax:3});
function fixture(shell='admin') {
  let identity='tenant-a/user-a', listener=()=>{}, permissions=['fcp:release:view','fcp:release:manage'];
  const calls=[];
  let handler=async path=>path.endsWith('/versions')?{provider:'local',artifacts:[],releases:[]}:{revision:1,phase:'EMPTY',basisPoints:0,capabilities:['DEPLOY','CANARY','EXPAND','PAUSE','ABORT','PROMOTE','ROLLBACK','RETIRE']};
  const host={shell,identityKey:()=>identity,permissions:()=>permissions,observeIdentity(fn){listener=fn;return()=>{listener=()=>{}}},async request(...args){calls.push(args);const result=await handler(...args);return shell==='admin'?{code:200,data:result}:{data:result,error:null}}};
  const console=new ReleaseConsole(host);
  return {console,calls,host,setHandler(fn){handler=fn},switchIdentity(){identity='tenant-b/user-b';listener()},permissions(p){permissions=p;listener()}};
}
test('plan validation enforces schema, immutable digest and safe integer versions',()=>{
  assert.deepEqual(parsePlan(plan()),plan());
  for(const invalid of [{...plan(),candidateRelease:'v1'},{...plan(),candidateDigest:'no'},{...plan(),schemaVersion:NaN},{...plan(),schemaVersion:1.5},{...plan(),extra:true}])assert.throws(()=>parsePlan(invalid));
});
test('both host envelope formats load state and versions',async()=>{
  for(const shell of ['admin','teacher']) {const f=fixture(shell);await f.console.load();assert.equal(f.console.data.state.revision,1);assert.equal(f.console.data.versions.provider,'local');assert.equal(f.calls.length,2);f.console.dispose()}
});
test('requires explicit permissions, does not honor wildcard',async()=>{
  const f=fixture();f.permissions(['*:*:*']);await assert.rejects(()=>f.console.load(),/PERMISSION_DENIED/);assert.equal(f.calls.length,0);
});
test('view-only role cannot create or execute plans',async()=>{
  const f=fixture();f.permissions(['fcp:release:view']);await f.console.load();assert.throws(()=>f.console.review(plan()),/PERMISSION_DENIED/);await assert.rejects(()=>f.console.execute('DEPLOY',0,true),/PERMISSION_DENIED/);
});
test('confirmation sends only reviewed plan with CAS revision and unique operation',async()=>{
  const f=fixture();await f.console.load();f.console.review(plan());await assert.rejects(()=>f.console.confirmPlan(false),/CONFIRMATION_REQUIRED/);
  f.setHandler(async()=>({revision:2,phase:'PLANNED'}));await f.console.confirmPlan(true);
  const [path,method,body]=f.calls.at(-1);assert.equal(path,'/fcp/releases/plans');assert.equal(method,'POST');assert.equal(body.expectedRevision,1);assert.deepEqual(body.plan,plan());assert.match(body.operationId,/^[a-f0-9-]{36}$/);assert.equal(f.console.data.review,null);
});
test('duplicate click while busy does not submit twice',async()=>{
  const f=fixture();await f.console.load();let release;f.setHandler(()=>new Promise(resolve=>release=resolve));
  const first=f.console.execute('DEPLOY',0,true);await f.console.execute('DEPLOY',0,true);assert.equal(f.calls.filter(x=>x[1]==='POST').length,1);release({revision:2,phase:'READY'});await first;
});
test('network uncertainty blocks new mutation and read cannot erase uncertainty',async()=>{
  const f=fixture();await f.console.load();f.setHandler(async()=>{throw new Error('network')});await f.console.execute('DEPLOY',0,true);
  assert.equal(f.console.data.unknown,true);await assert.rejects(()=>f.console.execute('DEPLOY',0,true),/RECONCILE_REQUIRED/);
  f.setHandler(async()=>({revision:2,phase:'READY'}));await f.console.load();assert.equal(f.console.data.unknown,true);await f.console.reconcile();assert.equal(f.console.data.unknown,false);
});
test('pending server result locks further writes until observed',async()=>{
  const f=fixture();await f.console.load();f.setHandler(async()=>({revision:2,phase:'PLANNED',pending:{operationId:'x'}}));await f.console.execute('DEPLOY',0,true);assert.equal(f.console.data.unknown,true);
  await f.console.reconcile();assert.equal(f.console.data.unknown,true);
  f.setHandler(async()=>({revision:3,phase:'READY',observed:{complete:true}}));await f.console.reconcile();assert.equal(f.console.data.unknown,false);
});
test('identity switch aborts request, clears state and ignores late response',async()=>{
  const f=fixture();let release;f.setHandler(()=>new Promise(resolve=>release=resolve));const loading=f.console.load();const signal=f.calls[0][3];f.switchIdentity();assert.equal(signal.aborted,true);release({revision:999});await loading;
  assert.equal(f.console.data.state,null);assert.equal(f.console.data.versions,null);assert.equal(f.console.data.busy,false);assert.equal(f.calls.length,1);
});
test('permissions changing clears review even when user identifier remains same',async()=>{
  const f=fixture();await f.console.load();f.console.review(plan());f.permissions(['fcp:release:view']);assert.equal(f.console.data.review,null);assert.equal(f.console.data.state,null);
});
test('revision change invalidates confirmed preview',async()=>{
  const f=fixture();await f.console.load();f.console.review(plan());f.setHandler(async()=>({revision:2}));await f.console.load();assert.equal(f.console.data.review,null);
});
test('invalid action or fraction never sends mutation',async()=>{
  const f=fixture();await f.console.load();await assert.rejects(()=>f.console.execute('DELETE',0,true),/INVALID_ACTION/);await assert.rejects(()=>f.console.execute('CANARY',NaN,true),/INVALID_ACTION/);await assert.rejects(()=>f.console.execute('CANARY',10001,true),/INVALID_ACTION/);assert.equal(f.calls.length,2);
});
test('unsupported executor capability is disabled and rejected before transport',async()=>{
  const f=fixture();f.setHandler(async()=>({revision:1,capabilities:[]}));await f.console.load();assert.equal(f.console.supports('DEPLOY'),false);await assert.rejects(()=>f.console.execute('DEPLOY',0,true),/UNSUPPORTED_ACTION/);assert.equal(f.calls.length,2);
});
test('disposed console rejects new operations and discards pending response',async()=>{
  const f=fixture();let release;f.setHandler(()=>new Promise(resolve=>release=resolve));const pending=f.console.load();f.console.dispose();release({revision:1});await pending;assert.equal(f.console.data.state,null);await assert.rejects(()=>f.console.load(),/PERMISSION_DENIED/);
});
test('loading a pending operation locks mutations even in a fresh browser session',async()=>{
  const f=fixture();f.setHandler(async()=>({revision:3,pending:{operationId:'original'},capabilities:['DEPLOY']}));await f.console.load();assert.equal(f.console.data.unknown,true);await assert.rejects(()=>f.console.execute('DEPLOY',0,true),/RECONCILE_REQUIRED/);
});
test('safe recovery requires explicit advertised capability, pending state, and confirmation',async()=>{
  const f=fixture();await f.console.load();assert.equal(f.console.canRecover,false);await assert.rejects(()=>f.console.recover(true),/SAFE_RECOVERY_UNAVAILABLE/);
  f.setHandler(async()=>({revision:3,pending:{operationId:'original'},supportsRecovery:false}));await f.console.load();assert.equal(f.console.canRecover,false);
  f.setHandler(async()=>({revision:3,pending:{operationId:'original'},supportsRecovery:true}));await f.console.load();assert.equal(f.console.canRecover,true);await assert.rejects(()=>f.console.recover(false),/CONFIRMATION_REQUIRED/);
});
test('safe recovery sends CAS only without generating a second operation id',async()=>{
  const f=fixture();f.setHandler(async()=>({revision:3,pending:{operationId:'original'},supportsRecovery:true}));await f.console.load();
  f.setHandler(async()=>({revision:4,phase:'READY',supportsRecovery:true}));await f.console.recover(true);
  const [path,method,body]=f.calls.at(-1);assert.equal(path,'/fcp/releases/recover');assert.equal(method,'POST');assert.deepEqual(body,{expectedRevision:3});assert.equal(f.console.data.unknown,false);assert.equal(f.console.canRecover,false);
});
test('unknown recovery result remains blocked and is not retried automatically',async()=>{
  const f=fixture();f.setHandler(async()=>({revision:3,pending:{operationId:'original'},supportsRecovery:true}));await f.console.load();await f.console.recover(true);assert.equal(f.console.data.unknown,true);
  const count=f.calls.length;f.setHandler(async()=>{throw new Error('timeout')});await f.console.recover(true);assert.equal(f.calls.length,count+1);assert.equal(f.console.data.unknown,true);assert.equal(f.calls.filter(c=>c[0].endsWith('/operations')).length,0);
});
test('late safe recovery response cannot cross identity boundary',async()=>{
  const f=fixture();f.setHandler(async()=>({revision:3,pending:{operationId:'original'},supportsRecovery:true}));await f.console.load();let finish;f.setHandler(()=>new Promise(resolve=>finish=resolve));const recovery=f.console.recover(true);f.switchIdentity();finish({revision:4,phase:'READY'});await recovery;assert.equal(f.console.data.state,null);assert.equal(f.console.canRecover,false);
});

// Real native adapters report the super-admin wildcard, while FCP requires explicit JDBC grants.
import {ReleasePermissionHost} from '../../integration/frontend-release/permission-host.ts';
function nativeProjection(shell='admin',grants=['fcp:release:view','fcp:release:manage']) {
  let identity='native-user-1', changed=()=>{}, permissionsChanged=()=>{};
  let response=async()=>grants;
  let releaseResponse=async path=>path.endsWith('/versions')?{releases:[{id:'ci-system-release'}]}:{revision:1,phase:'EMPTY',capabilities:['DEPLOY']};
  const calls=[];
  const host={shell,identityKey:()=>identity,permissions:()=>['*:*:*'],observeIdentity(fn){changed=fn;return()=>{}},observePermissions(fn){permissionsChanged=fn;return()=>{}},
    async request(path,method,body,signal){calls.push({path,method,body,signal});const data=path==='/fcp/ui/session'?{releasePermissions:await response()}:await releaseResponse(path,method,body,signal);
      return shell==='admin'?{code:200,data}:{data,error:null};}};
  const projected=new ReleasePermissionHost(host), console=new ReleaseConsole(projected);
  return {projected,console,calls,switchIdentity(){identity='native-user-2';changed()},respond(fn){response=fn},releaseRespond(fn){releaseResponse=fn},nativePermissionChange(){permissionsChanged()}};
}
test('native wildcard is not a grant but explicit server projection loads both consoles',async()=>{
  for(const shell of ['admin','teacher']) {
    const f=nativeProjection(shell);assert.equal(f.console.canView,false);
    await f.projected.refresh();await f.console.load();
    assert.equal(f.console.canManage,true);assert.equal(f.console.data.versions.releases[0].id,'ci-system-release');
    assert.deepEqual(f.calls.map(c=>c.path),['/fcp/ui/session','/fcp/releases/state','/fcp/releases/versions']);
    f.console.dispose();f.projected.dispose();
  }
});
test('server permission projection never derives grants from wildcard or foreign tenant',async()=>{
  for(const grants of [[],['fcp:release:view']]) {
    const f=nativeProjection('admin',grants);await f.projected.refresh();assert.equal(f.console.canView,grants.length===1);assert.equal(f.console.canManage,false);
  }
  for(const grants of [['*:*:*'],['fcp:release:view','other'],['fcp:release:view','fcp:release:view'],null]) {
    const f=nativeProjection('admin',grants);await assert.rejects(()=>f.projected.refresh(),/INVALID_PERMISSION_PROJECTION/);assert.equal(f.console.canView,false);
  }
});
test('native identity changes abort late permission projection and clear release state',async()=>{
  const f=nativeProjection();await f.projected.refresh();await f.console.load();let resolve;
  f.respond(()=>new Promise(done=>{resolve=done}));const loading=f.projected.refresh();const signal=f.calls.at(-1).signal;
  f.switchIdentity();resolve(['fcp:release:view','fcp:release:manage']);await assert.rejects(()=>loading,/STALE_IDENTITY/);
  assert.equal(signal.aborted,true);assert.equal(f.console.canView,false);assert.equal(f.console.data.versions,null);
});
test('permission refresh failure revokes old projection and cannot retain management controls',async()=>{
  const f=nativeProjection();await f.projected.refresh();await f.console.load();assert.equal(f.console.canManage,true);
  f.respond(async()=>{throw new Error('network')});await assert.rejects(()=>f.projected.refresh(),/network/);
  assert.equal(f.console.canManage,false);assert.equal(f.console.data.state,null);
});


test('same grants refresh preserves native identity, reviewed state and UNKNOWN write latch',async()=>{
  for(const shell of ['admin','teacher']) {
    const f=nativeProjection(shell);await f.projected.refresh();await f.console.load();
    const identity=f.projected.identityKey();let changes=0;f.projected.observeIdentity(()=>changes++);
    f.releaseRespond(async(path,method)=>{if(method==='POST')throw Error('response lost');return {revision:1,phase:'READY',capabilities:['DEPLOY']}});
    await f.console.execute('DEPLOY',0,true);assert.equal(f.console.data.unknown,true);
    f.respond(async()=>['fcp:release:manage','fcp:release:view']);
    await f.projected.refresh();await f.console.load();
    assert.equal(f.projected.identityKey(),identity);assert.equal(changes,0);assert.equal(f.console.data.unknown,true);
    await assert.rejects(()=>f.console.execute('DEPLOY',0,true),/RECONCILE_REQUIRED/);
    assert.equal(f.calls.filter(c=>c.method==='POST').length,1);
  }
});
test('ordinary permission refresh keeps the reviewed plan and emits no identity transition',async()=>{
  const f=nativeProjection();await f.projected.refresh();await f.console.load();f.console.review(plan());
  const reviewed=f.console.data.review;let identities=0,permissions=0;
  f.projected.observeIdentity(()=>identities++);f.projected.observePermissions(()=>permissions++);
  f.respond(async()=>['fcp:release:manage','fcp:release:view']);await f.projected.refresh();
  assert.equal(f.console.data.review,reviewed);assert.equal(identities,0);assert.equal(permissions,0);
});
test('permission failure and reauthorization never settle an UNKNOWN mutation',async()=>{
  const f=nativeProjection();await f.projected.refresh();await f.console.load();
  f.releaseRespond(async(path,method)=>{if(method==='POST')throw Error('response lost');return {revision:1,phase:'READY',capabilities:['DEPLOY']}});
  await f.console.execute('DEPLOY',0,true);
  f.respond(async()=>{throw Error('permission unavailable')});await assert.rejects(()=>f.projected.refresh());
  assert.equal(f.console.canView,false);assert.equal(f.console.data.state,null);assert.equal(f.console.data.unknown,true);
  f.respond(async()=>['fcp:release:view','fcp:release:manage']);await f.projected.refresh();await f.console.load();
  assert.equal(f.console.canManage,true);assert.equal(f.console.data.unknown,true);
  await assert.rejects(()=>f.console.execute('DEPLOY',0,true),/RECONCILE_REQUIRED/);
  assert.equal(f.calls.filter(c=>c.method==='POST').length,1);
});
test('same grants refresh while a mutation is running does not abort or unlock it',async()=>{
  const f=nativeProjection();await f.projected.refresh();await f.console.load();let finish;
  f.releaseRespond(()=>new Promise(done=>finish=done));const writing=f.console.execute('DEPLOY',0,true);
  const signal=f.calls.at(-1).signal;await f.projected.refresh();
  assert.equal(signal.aborted,false);assert.equal(f.console.data.busy,true);
  await f.console.execute('DEPLOY',0,true);assert.equal(f.calls.filter(c=>c.method==='POST').length,1);
  finish({revision:2,phase:'READY',capabilities:['DEPLOY']});await writing;
  assert.equal(f.console.data.unknown,false);assert.equal(f.console.data.state.revision,2);
});
test('revoking authorization during a dispatched mutation preserves uncertainty through regrant',async()=>{
  for(const revoke of ['server','native','failure']) {
    const f=nativeProjection();await f.projected.refresh();await f.console.load();let finish;
    f.releaseRespond(()=>new Promise(done=>finish=done));const writing=f.console.execute('DEPLOY',0,true);const signal=f.calls.at(-1).signal;
    if(revoke==='native')f.nativePermissionChange();
    else {f.respond(async()=>{if(revoke==='failure')throw Error('permission unavailable');return ['fcp:release:view']});if(revoke==='failure')await assert.rejects(()=>f.projected.refresh());else await f.projected.refresh()}
    assert.equal(signal.aborted,true);assert.equal(f.console.canManage,false);assert.equal(f.console.data.state,null);assert.equal(f.console.data.unknown,true);
    finish({revision:999,phase:'READY'});await writing;assert.equal(f.console.data.state,null);
    f.respond(async()=>['fcp:release:view','fcp:release:manage']);await f.projected.refresh();
    f.releaseRespond(async()=>({revision:2,phase:'READY',capabilities:['DEPLOY']}));await f.console.load();
    assert.equal(f.console.data.unknown,true);await assert.rejects(()=>f.console.execute('DEPLOY',0,true),/RECONCILE_REQUIRED/);
    await f.console.reconcile();assert.equal(f.console.data.unknown,false);
  }
});
test('superseded permission failures cannot revoke a newer successful projection',async()=>{
  const f=nativeProjection();await f.projected.refresh();let fail;
  f.respond(()=>new Promise((_,reject)=>fail=reject));const old=f.projected.refresh();const oldSignal=f.calls.at(-1).signal;
  f.respond(async()=>['fcp:release:view','fcp:release:manage']);await f.projected.refresh();
  fail(Error('old network failure'));await assert.rejects(()=>old);
  assert.equal(oldSignal.aborted,true);assert.equal(f.console.canManage,true);
});
test('superseded successful projection cannot restore revoked permissions',async()=>{
  const f=nativeProjection();await f.projected.refresh();let finish;
  f.respond(()=>new Promise(done=>finish=done));const old=f.projected.refresh();
  f.respond(async()=>[]);await f.projected.refresh();finish(['fcp:release:view','fcp:release:manage']);
  await assert.rejects(()=>old,/STALE_IDENTITY/);assert.equal(f.console.canView,false);
});
const containmentState=()=>({revision:3,phase:'CANARY',basisPoints:1000,plan:plan(),pending:{operationId:'deploy-original',action:'EXPAND'},supportsFencedContainment:true,capabilities:['ABORT','EXPAND','ROLLBACK']});
test('pending unknown permits only separately reviewed fenced ABORT zero',async()=>{
 const f=fixture();f.setHandler(async()=>containmentState());await f.console.load();assert.equal(f.console.data.unknown,true);await assert.rejects(()=>f.console.execute('EXPAND',2000,true),/RECONCILE_REQUIRED/);await assert.rejects(()=>f.console.execute('ROLLBACK',0,true),/RECONCILE_REQUIRED/);await f.console.reviewContainment();await assert.rejects(()=>f.console.contain(false),/CONFIRMATION_REQUIRED/);await f.console.contain(true);const posts=f.calls.filter(c=>c[1]==='POST');assert.equal(posts.length,1);assert.equal(posts[0][2].action,'ABORT');assert.equal(posts[0][2].basisPoints,0);assert.equal(posts[0][2].expectedRevision,3);assert.equal(f.console.data.unknown,true);assert.equal(f.console.canContain,false);
});
test('containment requires fenced support and ABORT capability, no fallback',async()=>{
 for(const override of [{supportsFencedContainment:false},{capabilities:['EXPAND']},{pending:null},{plan:{bad:true}}]){const f=fixture();f.setHandler(async()=>({...containmentState(),...override}));await f.console.load();assert.equal(f.console.canContain,false);await f.console.reviewContainment();assert.equal(f.console.data.containmentReview,null);assert.equal(f.calls.filter(c=>c[1]==='POST').length,0)}
});
test('containment fresh CAS, pending or plan change requires new confirmation',async()=>{
 for(const override of [{revision:4},{plan:{...plan(),candidateRelease:'v3'}},{pending:{operationId:'new-op',action:'EXPAND'}},{supportsFencedContainment:false}]){const f=fixture();f.setHandler(async()=>containmentState());await f.console.load();await f.console.reviewContainment();f.setHandler(async()=>({...containmentState(),...override}));await f.console.contain(true);assert.equal(f.calls.filter(c=>c[1]==='POST').length,0);assert.equal(f.console.data.containmentReview,null);assert.equal(f.console.data.unknown,true)}
});
test('containment lost response stays locked, no second emergency request',async()=>{
 const f=fixture();f.setHandler(async()=>containmentState());await f.console.load();await f.console.reviewContainment();f.setHandler(async(path,method)=>{if(method==='POST')throw new Error('response lost');return containmentState()});await f.console.contain(true);assert.equal(f.console.data.unknown,true);await f.console.load();assert.equal(f.console.canContain,false);await assert.rejects(()=>f.console.reviewContainment(),/RECONCILE_REQUIRED/);assert.equal(f.calls.filter(c=>c[1]==='POST').length,1);
});
test('containment busy duplicate and native identity change cannot resend or expose response',async()=>{
 const f=fixture();f.setHandler(async()=>containmentState());await f.console.load();await f.console.reviewContainment();let finish;f.setHandler(async(path,method)=>method==='POST'?new Promise(r=>finish=r):containmentState());const first=f.console.contain(true);await f.console.contain(true);await new Promise(r=>setImmediate(r));assert.equal(f.calls.filter(c=>c[1]==='POST').length,1);f.switchIdentity();finish({revision:8,phase:'ABORTED'});await first;assert.equal(f.console.data.state,null);assert.equal(f.console.data.containmentReview,null);
});
test('containment permission revocation during fresh read prevents POST',async()=>{
 const f=fixture();f.setHandler(async()=>containmentState());await f.console.load();await f.console.reviewContainment();let finish;f.setHandler(()=>new Promise(r=>finish=r));const operation=f.console.contain(true);f.permissions(['fcp:release:view']);finish(containmentState());await operation;assert.equal(f.calls.filter(c=>c[1]==='POST').length,0);assert.equal(f.console.data.containmentReview,null);
});
test('real permission projection invalidates emergency review without clearing original unknown',async()=>{
 const f=nativeProjection();await f.projected.refresh();f.releaseRespond(async()=>containmentState());await f.console.load();await f.console.reviewContainment();f.respond(async()=>['fcp:release:view']);await f.projected.refresh();assert.equal(f.console.data.containmentReview,null);assert.equal(f.console.data.unknown,true);f.respond(async()=>['fcp:release:view','fcp:release:manage']);await f.projected.refresh();await assert.rejects(()=>f.console.contain(true),/CONFIRMATION_REQUIRED/);assert.equal(f.calls.filter(c=>c.method==='POST').length,0);
});
