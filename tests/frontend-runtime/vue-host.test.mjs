/** Real Vue reactivity/lifecycle + Vue Router in a custom renderer. No DOM/browser/authentication claim. */
import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
const {createRenderer,defineComponent,nextTick,h} = await import('vue');
const {createRouter,createMemoryHistory} = await import('vue-router');
const {usePlugins} = await import('./usePlugins.mjs');
globalThis.document = {title:'',querySelector:()=>null};
const storage=()=>{const data=new Map();return {getItem:k=>data.get(k)??null,setItem:(k,v)=>data.set(k,v),removeItem:k=>data.delete(k)}};
globalThis.localStorage=storage();globalThis.sessionStorage=storage();
const projection=(catalog,context)=>({catalog,context,projectionFingerprint:createHash('sha256').update(JSON.stringify([catalog,context.userId,context.tenantId,context.language,[...context.permissions].sort()])).digest('hex')});
const renderer = createRenderer({
 createElement:tag=>({tag,children:[]}),createText:text=>({text}),createComment:text=>({text}),
 insert:(node,parent)=>parent.children?.push(node),remove:()=>{},setText:(node,text)=>{node.text=text},setElementText:(node,text)=>{node.text=text},
 parentNode:()=>null,nextSibling:()=>null,patchProp:(node,key,_old,value)=>{node[key]=value}
});
const wait = async()=>{await new Promise(resolve=>setTimeout(resolve,0));await nextTick()};
const cat = revision=>({contractVersion:'1.0',schemaVersion:1,revision,pages:[{id:'demo:home',pluginId:'demo',path:'/fcp/plugins/demo/home',title:'Demo',permissions:['demo.read'],components:['text@1','field@1'],capabilities:['demo.run'],content:{type:'field',field:'value',text:'Value'}}],slots:[]});
for(const shell of ['admin','teacher']) test(`${shell} Vue binding: refresh, fields, stale response, identity, unmount`,async(t)=>{
 let identityChanged,stop=0,pending,signal;
 let context={userId:'user',tenantId:'tenant',language:'en',permissions:['demo.read']},catalog=cat(1),key='first';
 const envelope=data=>shell==='admin'?{code:200,data}:{data,error:null};
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/fcp/plugins/:plugin/:page',meta:{fcpPluginContainer:true},component:defineComponent({render:()=>null})}]});
 await router.push('/fcp/plugins/demo/home');await router.isReady();
 let state;
 const host={shell,button:defineComponent({}),input:defineComponent({}),fieldValueProp:'value',fieldUpdateProp:'onUpdate:value',identityKey:()=>key,observeIdentity:fn=>{identityChanged=fn;return()=>stop++},request:async(path,_method,input,requestSignal)=>{
   if(path==='/fcp/ui/catalog')return envelope(projection(catalog,context));
   assert.equal(path,'/fcp/capabilities/demo.run/invoke');assert.equal(input.expectedRevision,1);assert.deepEqual(input.input,{value:'old tenant'});signal=requestSignal;return new Promise(resolve=>pending=()=>resolve(envelope({secret:'old tenant result'})));
 }};
 const app=renderer.createApp(defineComponent({setup(){state=usePlugins(host);return()=>h('div')}}));app.use(router);app.mount({children:[]});t.after(()=>app.unmount());await wait();
 assert.equal(state.selected.value.id,'demo:home');assert.equal(document.title,'Demo');
 state.edit('demo:home','value','old tenant');assert.equal(state.fields.value['demo:home'].value,'old tenant');
 context={permissions:[...context.permissions],language:context.language,tenantId:context.tenantId,userId:context.userId};await state.refresh();assert.equal(state.fields.value['demo:home'].value,'old tenant');
 const invoke=state.invoke('demo:home','demo.run');await wait();key='second';identityChanged();assert.equal(signal.aborted,true);assert.deepEqual(state.fields.value,{});assert.equal(state.catalog.value.pages.length,0);pending();await invoke;assert.deepEqual(state.results.value,{});
 context={...context,tenantId:'next'};catalog=cat(2);await state.refresh();assert.equal(state.catalog.value.revision,2);
 state.edit('demo:home','value','unsaved');catalog={...cat(3),pages:[]};await state.refresh();assert.equal(state.selected.value,undefined);assert.match(state.notice.value,/未保存/);assert.deepEqual(state.fields.value,{});
 app.unmount();assert.equal(stop,1);assert.equal(state.catalog.value.pages.length,0);
});
for(const shell of ['admin','teacher']) test(`${shell} Vue binding: explicit retry preserves key and exact input`,async(t)=>{
 const calls=[];let state,identityChanged;let succeed=false;
 const context={userId:'user',tenantId:'tenant',language:'en',permissions:['demo.read']};
 const envelope=data=>shell==='admin'?{code:200,data}:{data,error:null};
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/fcp/plugins/:plugin/:page',meta:{fcpPluginContainer:true},component:defineComponent({render:()=>null})}]});
 await router.push('/fcp/plugins/demo/home');await router.isReady();
 const host={shell,button:defineComponent({}),input:defineComponent({}),fieldValueProp:'value',fieldUpdateProp:'onUpdate:value',identityKey:()=> 'identity',observeIdentity:fn=>{identityChanged=fn;return()=>{}},request:async(path,_method,input)=>{
   if(path==='/fcp/ui/catalog')return envelope(projection(cat(1),context));
   calls.push(structuredClone(input));if(!succeed)throw new Error('Network result unknown');return envelope({value:'confirmed'});
 }};
 const app=renderer.createApp(defineComponent({setup(){state=usePlugins(host);return()=>h('div')}}));app.use(router);app.mount({children:[]});t.after(()=>app.unmount());await wait();
 state.edit('demo:home','value','first');await state.invoke('demo:home','demo.run');
 assert.equal(calls.length,1);assert.match(calls[0].idempotencyKey,/^[0-9a-f]{8}-[0-9a-f-]{27}$/);assert.ok(state.pending.value);
 await router.push('/fcp/plugins/demo/other');assert.equal(router.currentRoute.value.path,'/fcp/plugins/demo/home');
 state.edit('demo:home','value','changed');assert.equal(state.fields.value['demo:home'].value,'first');await state.invoke('demo:home','demo.run');assert.equal(calls.length,1);
 succeed=true;await state.retry();assert.deepEqual(calls[1],calls[0]);assert.equal(state.pending.value,null);assert.deepEqual(state.results.value['demo:home'],{value:'confirmed'});
 succeed=false;state.edit('demo:home','value','second');await state.invoke('demo:home','demo.run');assert.notEqual(calls[2].idempotencyKey,calls[0].idempotencyKey);state.abandon();assert.ok(state.pending.value);state.abandon(true);assert.equal(state.pending.value,null);assert.match(state.notice.value,/不会撤销/);
 state.edit('demo:home','value','third');await state.invoke('demo:home','demo.run');identityChanged();assert.equal(state.pending.value,null);assert.deepEqual(state.fields.value,{});assert.match(state.notice.value,/核对原操作结果/);await state.retry();assert.equal(calls.length,4);
});
