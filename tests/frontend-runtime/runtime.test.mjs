import test from 'node:test';
import assert from 'node:assert/strict';
const { UiRuntime, validateCatalog, createPlusUiAdapter, createSoybeanAdapter } = await import(process.env.FCP_UI_MODULE ?? '../../integration/frontend-runtime/index.ts');
const page = () => ({id:'demo:home',pluginId:'demo',path:'/fcp/plugins/demo/home',title:'Demo',permissions:['demo.read'],components:['text@1','stack@1','button@1'],capabilities:['demo.read'],content:{type:'stack',children:[{type:'text',text:'Hello'},{type:'button',text:'Read',capabilityId:'demo.read'}]}});
const catalog = (revision=1) => ({contractVersion:'1.0',schemaVersion:1,revision,pages:[page()],slots:[{id:'demo:status',pluginId:'demo',slotId:'dashboard.status',order:0,permissions:['demo.read'],capabilities:[],components:['text@1'],content:{type:'text',text:'Ready'}}]});
function setup(soybean=false) {
  const state = {identity:{userId:'user',tenantId:'tenant',language:'en',permissions:['demo.read']},catalog:null,path:'/fcp/plugins/demo/home',restored:null,dirty:[],clears:0,listener:null,unsubscribed:false,request:async()=>soybean?{data:42,error:null}:{code:200,data:42}};
  const bindings = {components:['text@1','stack@1','button@1','table@1','field@1'],slots:{'dashboard.status':{components:['text@1'],inputSchema:{},outputSchema:{}}},context:{get:()=>state.identity,subscribe:fn=>{state.listener=fn;return()=>{state.unsubscribed=true}}},replaceCatalog:c=>{state.catalog=c},currentPath:()=>state.path,restoreDeepLink:p=>{state.restored=p},notifyDirty:ids=>state.dirty.push(...ids),clearCaches:()=>{state.clears++},render:n=>n,request:(...args)=>state.request(...args)};
  const adapter = (soybean?createSoybeanAdapter:createPlusUiAdapter)(bindings);
  return {state,adapter,runtime:new UiRuntime(adapter)};
}
for (const soybean of [false,true]) {
  const name=soybean?'soybean':'plus-ui';
  test(`${name}: same configuration installs, restores deep link, invokes, removes slots/pages`,async()=>{
    const {state,runtime}=setup(soybean); assert.equal(runtime.applyRevision(catalog()),true); assert.equal(state.restored,state.path); assert.equal(await runtime.invoke('demo:home','demo.read',{}),42);
    let disposed=0; runtime.trackSubscription('demo:home',()=>disposed++); runtime.trackSubscription('demo:status',()=>disposed++);runtime.markDirty('demo:home');
    assert.equal(runtime.applyRevision({...catalog(2),pages:[],slots:[]}),true); assert.equal(disposed,2);assert.deepEqual(state.dirty,['demo:home']); assert.equal(state.restored,null); assert.equal(state.catalog.slots.length,0);
    await assert.rejects(runtime.invoke('demo:home','demo.read',{}),{code:'PLUGIN_DISABLED'}); assert.equal(runtime.applyRevision(catalog(1)),false);
  });
  test(`${name}: identity epoch aborts requests, disposes subscriptions, clears tenant cache`,async()=>{
    const {state,runtime}=setup(soybean);runtime.applyRevision(catalog());let resolve,signal,disposed=0;
    state.request=(_id,_input,options)=>{signal=options.signal;return new Promise(r=>resolve=r)};
    const pending=runtime.invoke('demo:home','demo.read',{});runtime.trackSubscription('demo:home',()=>disposed++);
    state.identity={...state.identity,tenantId:'other'};state.listener(); assert.equal(signal.aborted,true);assert.equal(disposed,1);assert.equal(state.clears,1);assert.equal(runtime.snapshot.pages.length,0);
    resolve(soybean?{data:'old tenant',error:null}:{code:200,data:'old tenant'});await assert.rejects(pending,{code:'STALE_VERSION'});assert.equal(runtime.applyRevision(catalog(8),0),false);
    runtime.dispose();assert.equal(state.unsubscribed,true);
  });
  test(`${name}: normalize distinct error codes`,async()=>{
    const {state,runtime}=setup(soybean);runtime.applyRevision(catalog());
    for(const code of ['401','403','PLUGIN_DISABLED','STALE_VERSION','MISSING_CAPABILITY','VALIDATION']) {
      state.request=async()=>soybean?{data:null,error:{code,message:'failure'}}:{code,msg:'failure'};
      await assert.rejects(runtime.invoke('demo:home','demo.read',{}),{code:code==='401'?'UNAUTHENTICATED':code==='403'?'FORBIDDEN':code});
    }
  });
}
test('strict schema rejects scripts, raw HTML, URLs, unknown components, namespaces, conflicts and dependencies atomically',()=>{
 const {runtime,adapter,state}=setup();runtime.applyRevision(catalog());const initial=state.catalog;
 const mutations=[c=>c.pages[0].content={type:'script'},c=>c.pages[0].content={type:'text',text:'<img onerror=alert(1)>'},c=>c.pages[0].content={type:'text',text:'${window.token}'},c=>c.pages[0].content.url='https://evil.test',c=>c.pages[0].path='/admin',c=>c.pages.push(page()),c=>c.pages[0].id='other:home',c=>c.pages[0].components.push('unknown@1'),c=>c.slots[0].slotId='body',c=>c.slots[0].components=['button@1'],c=>c.schemaVersion=99,c=>c.contractVersion='2',c=>c.pages[0].content.children[1].capabilityId='https://evil.test',c=>c.pages[0].html='x'];
 for(const mutate of mutations){const c=catalog(2);mutate(c);assert.throws(()=>runtime.applyRevision(c));assert.equal(state.catalog,initial);assert.equal(runtime.snapshot.revision,1)}
 assert.throws(()=>validateCatalog({...catalog(),revision:NaN},adapter));
});
test('host route conflict rolls back; permissions filter and catalog immutability',()=>{
 const {adapter,runtime,state}=setup();runtime.applyRevision(catalog());adapter.replaceCatalog=()=>{throw new Error('route conflict')};assert.throws(()=>runtime.applyRevision(catalog(2)),/route conflict/);assert.equal(runtime.snapshot.revision,1);
 assert.throws(()=>runtime.snapshot.pages.push(page()),TypeError);
 const other=setup();other.state.identity.permissions=[];other.runtime.applyRevision(catalog());assert.equal(other.state.catalog.pages.length,0);
});
test('late refresh cannot restore disabled revision or old identity',async()=>{
 const {runtime,state}=setup();runtime.applyRevision(catalog());let finish;
 const refresh=runtime.refresh(()=>new Promise(resolve=>finish=resolve));runtime.applyRevision({...catalog(3),pages:[],slots:[]});finish(catalog(2));assert.equal(await refresh,false);assert.equal(runtime.snapshot.pages.length,0);
 const second=runtime.refresh(()=>new Promise(resolve=>finish=resolve));state.listener();finish(catalog(4));assert.equal(await second,false);
});
test('throwing dirty notification/disposer cannot veto disable; cleanup runs once',()=>{
 const {adapter,runtime}=setup();runtime.applyRevision(catalog());runtime.markDirty('demo:home');let count=0;
 runtime.trackSubscription('demo:home',()=>{throw new Error('bad disposer')});const stop=runtime.trackSubscription('demo:home',()=>count++);adapter.notifyDirty=()=>{throw new Error('notification')};
 runtime.applyRevision({...catalog(2),pages:[],slots:[]});stop();assert.equal(count,1);assert.equal(runtime.snapshot.pages.length,0);
});
test('dotted and underscored plugin IDs plus permission-checked slot capability actions',async()=>{
 const {adapter,runtime,state}=setup();adapter.slots['dashboard.status'].components.push('button@1');
 const c=catalog();c.pages[0].pluginId='roster.business_v1';c.pages[0].id='roster.business_v1:home';c.pages[0].path='/fcp/plugins/roster.business_v1/home';
 c.slots[0].components=['button@1'];c.slots[0].capabilities=['demo.read'];c.slots[0].content={type:'button',text:'Read',capabilityId:'demo.read'};
 runtime.applyRevision(c);assert.equal(await runtime.invoke('demo:status','demo.read',{}),42);
 state.identity.permissions=[];await assert.rejects(runtime.invoke('demo:status','demo.read',{}),{code:'FORBIDDEN'});state.listener();runtime.applyRevision(c);assert.equal(runtime.snapshot.slots.length,0);
});
test('stale rejected transport does not expose prior-identity error',async()=>{
 const {runtime,state}=setup();runtime.applyRevision(catalog());let reject;state.request=()=>new Promise((_resolve,r)=>reject=r);
 const pending=runtime.invoke('demo:home','demo.read',{});state.listener();reject(new Error('prior tenant private error'));await assert.rejects(pending,{code:'STALE_VERSION',message:'Discarded stale response'});
});
test('example catalog remains executable by either adapter and no framework imports exist',async()=>{
 const fs=await import('node:fs/promises');const raw=await fs.readFile(new URL('../../integration/frontend-runtime/example-catalog.json',import.meta.url),'utf8');
 for(const soybean of [false,true]){const {adapter}=setup(soybean);assert.equal(validateCatalog(JSON.parse(raw),adapter).contractVersion,'1.0')}
 const source=await fs.readFile(new URL('../../integration/frontend-runtime/index.ts',import.meta.url),'utf8');assert.doesNotMatch(source,/\bimport\b.*(?:vue|react|school|store)/i);assert.doesNotMatch(source,/\beval\s*\(|new Function|innerHTML/);
});

test('shared Java/frontend node boundary corpus',async()=>{
 const fs=await import('node:fs/promises'); const cases=JSON.parse(await fs.readFile(new URL('../runtime-packages/ui-boundaries.json',import.meta.url),'utf8'));
 const {adapter}=setup(); for(const item of cases){const c=catalog();c.pages[0].components=['text@1','field@1','button@1','table@1','stack@1'];c.pages[0].capabilities=['echo.run'];c.pages[0].content=item.content; if(item.valid) assert.doesNotThrow(()=>validateCatalog(c,adapter),item.name); else assert.throws(()=>validateCatalog(c,adapter),undefined,item.name);}
});
test('runtime delegates only active validated content to renderer and forwards idempotency key',async()=>{
 const {runtime,adapter,state}=setup();runtime.applyRevision(catalog());
 adapter.render=(node,ownerId)=>({node,ownerId});assert.equal(runtime.render('demo:home').ownerId,'demo:home');
 let key;state.request=async(_cap,_input,options)=>{key=options.idempotencyKey;return {code:200,data:42}};
 assert.equal(await runtime.invoke('demo:home','demo.read',{}, {idempotencyKey:'fixed-key'}),42);assert.equal(key,'fixed-key');
 runtime.applyRevision({...catalog(2),pages:[],slots:[]});assert.throws(()=>runtime.render('demo:home'),{code:'PLUGIN_DISABLED'});
});

test('Soybean bootstrap routes do not eagerly import layout/store modules',async()=>{
 const fs=await import('node:fs/promises');const source=await fs.readFile(new URL('../../integration/soybean/src/views/fcp/plugins/routes.ts',import.meta.url),'utf8');
 assert.doesNotMatch(source,/^\s*import\s+(?!type\b)[^\n]*(?:layouts|store|PluginWorkspace|usePlugins)/m);
 assert.match(source,/component:\s*\(\)\s*=>\s*import\('@\/layouts\/base-layout\/index\.vue'\)/);
 assert.match(source,/component:\s*\(\)\s*=>\s*import\('\.\/index\.vue'\)/);
});
