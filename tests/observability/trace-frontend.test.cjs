// Explicit HTTP/DOM doubles. Not a browser, authenticated native-host or performance test.
const {test, after} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const vm = require('node:vm');
const {createRequire} = require('node:module');
const {execFileSync} = require('node:child_process');
const tools = createRequire(path.resolve(process.argv[2] || '.', 'package.json'));
const ts = tools('typescript');
const {JSDOM} = tools('jsdom');
const root = path.resolve(__dirname, '../..');
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'fcp-trace-ui-'));
const dom = new JSDOM('<!doctype html><body></body>', {url:'http://localhost/'});
global.document = dom.window.document;
Object.defineProperty(global, 'navigator', {configurable:true,value:dom.window.navigator});
let copied = [];
Object.defineProperty(navigator, 'clipboard', {configurable:true,value:{writeText:async value => copied.push(value)}});
const source = fs.readFileSync(path.join(root,'integration/frontend-shared/trace.ts'),'utf8');
const compiled = ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}}).outputText;
const shared = {exports:{}};
vm.runInThisContext(`(function(exports){${compiled}\n})`)(shared.exports);
const trace = shared.exports;
const id = '0123456789abcdef0123456789abcdef', other = 'abcdef0123456789abcdef0123456789';
const response = (data={code:500,msg:'Denied'}, value=id) => ({headers:{'x-trace-id':value},data});
const text = () => document.body.textContent;
after(() => {trace.clearTraceNotice();dom.window.close();fs.rmSync(temp,{recursive:true,force:true});});
test('strict compiler and development bundle have identical output',()=>{
 execFileSync(process.execPath,[tools.resolve('typescript/bin/tsc'),path.join(root,'integration/frontend-shared/trace.ts'),'--target','ES2022','--module','ES2022','--lib','ES2022,DOM','--strict','--outDir',temp]);
 assert.equal(fs.readFileSync(path.join(temp,'trace.js'),'utf8'),fs.readFileSync(path.join(root,'development-host/src/main/resources/dev-ui/trace.js'),'utf8'));
});
test('accept only a nonzero 32-hex response ID; reject request/header injection',()=>{
 for(const bad of ['',null,42,{},'a'.repeat(31),'a'.repeat(33),'0'.repeat(32),id+'\n','<img src=x onerror=alert(1)>',' '+id,`${id},${other}`]) assert.equal(trace.validTraceId(bad),'');
 assert.equal(trace.validTraceId(id.toUpperCase()),id);
 assert.equal(trace.responseTraceId({headers:new Headers({'X-Trace-Id':id}),data:{traceId:other}}),id);
 assert.equal(trace.responseTraceId(response({traceId:other},'invalid')),other);
 assert.equal(trace.responseTraceId({config:{headers:{'X-Trace-Id':id}},data:{data:{traceId:id}}}),'');
});
test('error retains ID and copy panel is bounded, safe and dismissible',async()=>{
 trace.clearTraceNotice();copied=[];
 const err=trace.responseError('Operation failed',response());
 assert.equal(err.traceId,id);assert.match(err.message,/Trace ID: /);
 assert.equal(trace.withResponseTrace(err,response()),err);
 assert.equal(err.message.split(id).length,2);
 for(let i=0;i<100;i++)trace.showTraceNotice(i%2?id:other);
 assert.equal(document.querySelectorAll('[data-testid="request-trace"]').length,1);
 assert.equal(document.querySelectorAll('script,img').length,0);
 assert.equal(document.activeElement,document.body);
 document.querySelector('button').click();await new Promise(resolve=>setImmediate(resolve));
 assert.deepEqual(copied,[id]);assert.match(text(),/已复制/);
 document.querySelectorAll('button')[1].click();assert.equal(text(),'');
});
test('clipboard rejection leaves selectable ID; no network, storage or payload collection',async()=>{
 trace.showTraceNotice(id);navigator.clipboard.writeText=async()=>{throw new Error('blocked');};
 document.querySelector('button').click();await new Promise(resolve=>setImmediate(resolve));
 assert.match(text(),/手动复制/);assert.equal(document.querySelector('code').textContent,id);
 for(const api of ['fetch(', 'XMLHttpRequest', 'sendBeacon', 'localStorage', 'sessionStorage', 'setInterval'])assert.equal(source.includes(api),false,api);
 navigator.clipboard.writeText=async value=>copied.push(value);trace.clearTraceNotice();
});
test('malformed/non-JSON response retains header; missing response never fabricates ID',async()=>{
 await assert.rejects(()=>trace.readResponseJson(new Response('<html>gateway</html>',{status:502,headers:{'X-Trace-Id':id}})),e=>e.traceId===id&&e.message.includes(id));
 trace.clearTraceNotice();assert.equal(trace.responseError('offline').traceId,undefined);assert.equal(text(),'');
 const body=await trace.readResponseJson(new Response(JSON.stringify({code:403,traceId:id})));
 assert.equal(trace.responseError('Denied',{data:body}).traceId,id);
});
test('a new network or invalid-ID error clears the previous notice without changing errors',()=>{
 const previous=trace.responseError('Previous failure',response());
 const previousMessage=previous.message;
 const offline=new Error('Network Error');
 assert.equal(trace.withResponseTrace(offline),offline);
 assert.equal(offline.message,'Network Error');assert.equal(offline.traceId,undefined);assert.equal(text(),'');
 assert.equal(previous.traceId,id);assert.equal(previous.message,previousMessage);
 for(const value of ['bad', '0'.repeat(32), id+'\n']) {
  trace.showTraceNotice(id);
  const failure=trace.responseError('Invalid response trace',response({},value));
  assert.equal(failure.traceId,undefined);assert.equal(failure.message,'Invalid response trace');assert.equal(text(),'');
 }
});
test('HTML without a response ID clears the previous notice',async()=>{
 trace.showTraceNotice(id);
 await assert.rejects(()=>trace.readResponseJson(new Response('<html>gateway</html>',{status:502})),error=>error.traceId===undefined&&!error.message.includes('Trace ID:'));
 assert.equal(text(),'');
});
test('concurrent failures use completion order; stale clipboard completion cannot restore a notice',async()=>{
 const deferred=()=>{let resolve;const promise=new Promise(done=>resolve=done);return {promise,resolve};};
 const pendingCopy=deferred();navigator.clipboard.writeText=()=>pendingCopy.promise;
 const first=trace.responseError('First completed failure',response());
 const panel=document.querySelector('[data-testid="request-trace"]');
 const copy=panel.querySelector('button');copy.click();
 trace.withResponseTrace(first,response());assert.equal(document.querySelector('button'),copy);
 const pendingWithoutId=deferred(),pendingWithId=deferred();
 const withoutId=pendingWithoutId.promise.then(()=>trace.responseError('Late network error'));
 const withId=pendingWithId.promise.then(()=>trace.responseError('Other response',response({},other)));
 pendingWithId.resolve();const second=await withId;
 assert.equal(document.querySelector('code').textContent,other);
 pendingWithoutId.resolve();const latest=await withoutId;assert.equal(text(),'');
 pendingCopy.resolve();await new Promise(resolve=>setImmediate(resolve));assert.equal(text(),'');
 assert.equal(first.traceId,id);assert.equal(second.traceId,other);assert.equal(latest.traceId,undefined);
 // A subsequently completed valid response may display its own ID again.
 trace.responseError('Next failure',response());assert.equal(document.querySelector('code').textContent,id);
 navigator.clipboard.writeText=async value=>copied.push(value);trace.clearTraceNotice();
});
test('native Blob download retains ID without copying binary content',()=>{
 const blob=new Blob(['not uploaded']);trace.rememberResponseTrace(blob,response());
 assert.equal(trace.responseError('download rejected',{data:blob}).traceId,id);
});
test('outer catches preserve traced errors and never clear a newer notice twice',()=>{
 const earlier=trace.responseError('Earlier untraced error');
 const latest=trace.responseError('Later traced error',response({},other));
 const panel=document.querySelector('[data-testid="request-trace"]');
 trace.clearUntracedErrorNotice(earlier);
 assert.equal(document.querySelector('[data-testid="request-trace"]'),panel);
 trace.clearUntracedErrorNotice(latest);assert.equal(document.querySelector('code').textContent,other);
 const network=new Error('Offline');trace.clearUntracedErrorNotice(network);assert.equal(text(),'');
 trace.responseError('Next response',response());trace.clearUntracedErrorNotice(network);
 assert.equal(document.querySelector('code').textContent,id);
 const retained=Object.assign(new Error('Already correlated'),{traceId:other});
 trace.clearUntracedErrorNotice(retained);assert.equal(document.querySelector('code').textContent,id);
 assert.equal(retained.message,'Already correlated');assert.equal(retained.traceId,other);
 trace.clearUntracedErrorNotice(Object.assign(new Error('Bad ID'),{traceId:'<invalid>'}));assert.equal(text(),'');
});
function ingestionClient(fetch) {
 let unmount;
 const sandbox={exports:{},Error,AbortController,fetch,crypto:global.crypto,
  require:name=>name==='vue'?{ref:value=>({value}),computed:get=>({get value(){return get();}}),onMounted:()=>{},onBeforeUnmount:fn=>unmount=fn}
   :name==='./session'?{readToken:()=> 'fixture-token'}:trace};
 const code=fs.readFileSync(path.join(root,'integration/frontend-shared/useIngestion.ts'),'utf8').replaceAll('import.meta.env',"({VITE_APP_CLIENT_ID:'fixture-client'})");
 vm.runInNewContext(ts.transpileModule(code,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}}).outputText,sandbox);
 return {client:sandbox.exports.useIngestion(),unmount:()=>unmount()};
}
test('actual ingestion catch clears raw fetch errors, preserves traced errors and ignores unmount',async()=>{
 trace.responseError('Old error',response());
 const offline=new Error('Network unavailable');
 const {client}=ingestionClient(async()=>{throw offline;});
 await client.reload();assert.equal(text(),'');assert.equal(client.error.value,offline.message);
 assert.equal(offline.traceId,undefined);
 const correlated=ingestionClient(async()=>new Response(JSON.stringify({code:500,msg:'Rejected'}),{status:500,headers:{'X-Trace-Id':other}}));
 await correlated.client.reload();assert.equal(document.querySelector('code').textContent,other);assert.match(correlated.client.error.value,new RegExp(other));
 let reject;const pending=new Promise((_,no)=>reject=no);
 const detached=ingestionClient(()=>pending),work=detached.client.reload();detached.unmount();
 trace.responseError('Another current page error',response());reject(new Error('Old page disconnected'));await work;
 assert.equal(document.querySelector('code').textContent,id);trace.clearTraceNotice();
});
function developmentClient(fetch) {
 const code=fs.readFileSync(path.join(root,'development-host/src/main/resources/dev-ui/app.js'),'utf8');
 const ast=ts.createSourceFile('app.js',code,ts.ScriptTarget.ES2022,true,ts.ScriptKind.JS);
 // Execute the actual API/run functions, without starting app event handlers or timers.
 const functions=ast.statements.filter(n=>ts.isFunctionDeclaration(n)&&['api','run'].includes(n.name?.text)).map(n=>n.getText(ast)).join('\n');
 const errorElement={textContent:''};
 const sandbox={...trace,Error,AbortController,ArrayBuffer,Uint8Array,fetch,requests:new Set(),
  $:()=>errorElement,updateControls:()=>{},clearIdentity:()=>{throw new Error('not used by this test');}};
 vm.runInNewContext("let epoch=0,busy=false,token='fixture-token';\n"+functions+'\nthis.actions={api,run,setEpoch:value=>epoch=value};',sandbox);
 return {...sandbox.actions,errorElement};
}
test('actual development run clears raw API/download rejection but retains trace and newer identity',async()=>{
 const offline=new Error('Network unavailable');const dev=developmentClient(async()=>{throw offline;});
 trace.responseError('Old error',response());await dev.run(()=>dev.api('/fixture'));
 assert.equal(text(),'');assert.equal(dev.errorElement.textContent,offline.message);assert.equal(offline.traceId,undefined);
 trace.responseError('Old download error',response());await dev.run(async()=>{throw new Error('Download connection lost');});assert.equal(text(),'');
 const correlated=developmentClient(async()=>new Response(JSON.stringify({code:500,msg:'Rejected'}),{status:500,headers:{'X-Trace-Id':other}}));
 await correlated.run(()=>correlated.api('/fixture'));assert.equal(document.querySelector('code').textContent,other);assert.match(correlated.errorElement.textContent,new RegExp(other));
 trace.clearTraceNotice();
 let reject;const late=new Promise((_,no)=>reject=no);
 const old=developmentClient(()=>late),work=old.run(()=>old.api('/fixture'));
 old.setEpoch(1);trace.responseError('New identity error',response());reject(new Error('Old identity disconnected'));await work;
 assert.equal(document.querySelector('code').textContent,id);
 await dev.run(async()=>{throw Object.assign(new Error('Cancelled'),{name:'AbortError'});});assert.equal(document.querySelector('code').textContent,id);trace.clearTraceNotice();
});

// Supply exact source repositories to execute patched native interceptor functions.
function native(name) {
 const repository=process.env[name==='admin'?'FCP_TRACE_ADMIN_SOURCE':'FCP_TRACE_TEACHER_SOURCE'];
 const manifest=JSON.parse(fs.readFileSync(path.join(root,'integration/request-trace/manifest.json'))).upstreams[name];
 const original=execFileSync('git',['show',`${manifest.commit}:${manifest.path}`],{cwd:repository,encoding:'utf8'});
 const dir=path.join(temp,name);fs.mkdirSync(path.dirname(path.join(dir,manifest.path)),{recursive:true});
 fs.writeFileSync(path.join(dir,manifest.path),original);
 if(!process.env.TRACE_BASELINE)execFileSync('git',['apply',path.join(root,`integration/request-trace/${name}.patch`)],{cwd:dir});
 let hooks, responseHooks, messages=[];
 const noop=()=>{};
 const axios={defaults:{headers:{}},create:()=>({interceptors:{request:{use:noop},response:{use:(...h)=>responseHooks=h}}})};
 const env={VITE_SERVICE_SUCCESS_CODE:'200'};
 const imports={
  '@/fcp/trace':trace,axios:{default:axios},'@sa/axios':{REQUEST_CANCELED_CODE:'CANCELED',BACKEND_ERROR_CODE:'BACKEND',createFlatRequest:(_,h)=>{hooks=h;return {state:{errMsgStack:[]},cancelAllRequest:noop};}},
  '@/enums/RespEnum':{HttpStatus:{SUCCESS:200,SERVER_ERROR:500,WARN:601}},'@/utils/errorCode':{errorCode:{default:'Failure'}},
  '@/utils/service':{getServiceBaseURL:()=>({baseURL:'/api'})},'@/store/modules/auth':{useAuthStore:()=>({resetStore:noop})},
  '@/utils/storage':{localStg:{get:()=>null}},'./shared':{showErrorMsg:(_,message)=>messages.push(message)}
 };
 const code=ts.transpileModule(fs.readFileSync(path.join(dir,manifest.path),'utf8').replaceAll('import.meta.env','__env'),{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}}).outputText;
 const sandbox={exports:{},__env:env,require:key=>imports[key]||{},ElMessage:opts=>messages.push(opts.message),ElNotification:{error:noop},ElMessageBox:{confirm:()=>Promise.reject()},window:{location:{pathname:'/login'},removeEventListener:noop},console};
 vm.runInNewContext(code,sandbox);
 return {hooks,responseHooks,messages};
}
for(const name of ['admin','teacher'])test(`${name}: actual pinned native error interceptors retain response trace`,{skip:!process.env[name==='admin'?'FCP_TRACE_ADMIN_SOURCE':'FCP_TRACE_TEACHER_SOURCE']},async()=>{
 trace.clearTraceNotice();const n=native(name);
 const logical=response();logical.request={responseType:'json'};
 if(name==='admin') {
  await assert.rejects(()=>n.responseHooks[0](logical),e=>e.traceId===id&&e.message.includes(id));
  const failure=Object.assign(new Error('Request failed with status code 403'),{response:response({code:403,msg:'Denied'})});
  await assert.rejects(()=>n.responseHooks[1](failure),e=>e.traceId===id);
  assert.match(n.messages.at(-1),new RegExp(id));
  const offline=new Error('Network Error');
  await assert.rejects(()=>n.responseHooks[1](offline),error=>error===offline&&error.traceId===undefined);
  assert.equal(text(),'');assert.equal(offline.message,'Network Error');
  const success=response({code:200,data:'ok'});success.request={responseType:'json'};
  trace.clearTraceNotice();assert.equal((await n.responseHooks[0](success)).data,'ok');assert.equal(text(),'');
 } else {
  await n.hooks.onBackendFail(logical,{});assert.match(text(),new RegExp(id));assert.equal(logical.data.msg,'Denied');
  const failure=Object.assign(new Error('HTTP failure'),{response:response(),code:'HTTP'});
  n.hooks.onError(failure);assert.equal(failure.traceId,id);assert.equal(n.messages.at(-1),'HTTP failure');assert.match(text(),new RegExp(id));
  const offline=Object.assign(new Error('Network Error'),{code:'HTTP'});
  n.hooks.onError(offline);assert.equal(offline.traceId,undefined);assert.equal(text(),'');assert.equal(n.messages.at(-1),'Network Error');
  trace.showTraceNotice(id);const notice=document.querySelector('[data-testid="request-trace"]');
  n.hooks.onError(Object.assign(new Error('cancelled'),{code:'CANCELED',response:response()}));
  assert.equal(document.querySelector('[data-testid="request-trace"]'),notice);trace.clearTraceNotice();
 }
});
