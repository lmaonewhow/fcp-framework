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
test('native Blob download retains ID without copying binary content',()=>{
 const blob=new Blob(['not uploaded']);trace.rememberResponseTrace(blob,response());
 assert.equal(trace.responseError('download rejected',{data:blob}).traceId,id);
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
  const success=response({code:200,data:'ok'});success.request={responseType:'json'};
  trace.clearTraceNotice();assert.equal((await n.responseHooks[0](success)).data,'ok');assert.equal(text(),'');
 } else {
  await n.hooks.onBackendFail(logical,{});assert.match(text(),new RegExp(id));assert.equal(logical.data.msg,'Denied');
  const failure=Object.assign(new Error('HTTP failure'),{response:response(),code:'HTTP'});
  n.hooks.onError(failure);assert.equal(failure.traceId,id);assert.equal(n.messages.at(-1),'HTTP failure');assert.match(text(),new RegExp(id));
  trace.clearTraceNotice();n.hooks.onError(Object.assign(new Error('cancelled'),{code:'CANCELED',response:response()}));assert.equal(text(),'');
 }
});
