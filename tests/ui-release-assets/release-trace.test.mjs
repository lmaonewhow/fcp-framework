/** Real compiled modules + DOM; HTTP responses are unit doubles, not native acceptance. */
import test,{beforeEach} from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {readFileSync,writeFileSync,copyFileSync} from 'node:fs';
import {resolve,join} from 'node:path';
import {webcrypto} from 'node:crypto';
import {compiled,compiledUrl} from './compiled-modules.mjs';
const require=createRequire(resolve(process.env.FCP_FRONTEND_DEPS || 'tests/frontend','package.json'));
const {JSDOM}=require('jsdom');
const ID='1234567890abcdef1234567890abcdef';
const session=await import(compiledUrl('release-session.js'));
const trace=await import(compiledUrl('trace.js'));
const launcher=readFileSync('integration/frontend-shared/ui-launcher.mjs','utf8').replace('__FCP_SHELL__','"admin"').replace('__FCP_CLIENT_ID__','"test-client"');
copyFileSync(join(compiled,'trace.js'),join(compiled,'release-trace.mjs'));
let dom,loaded=0,clipboard='';
beforeEach(()=>{
 trace.clearTraceNotice();dom?.window.close();
 dom=new JSDOM('<html><head></head><body></body></html>',{url:'http://localhost/admin/fcp/plugins'});
 for(const key of ['window','document','localStorage','sessionStorage','navigator'])Object.defineProperty(globalThis,key,{value:dom.window[key],configurable:true});
 Object.defineProperty(globalThis,'crypto',{value:webcrypto,configurable:true});
 globalThis.location=dom.window.location;
 Object.defineProperty(navigator,'clipboard',{value:{writeText:async id=>{clipboard=id}},configurable:true});
 localStorage.setItem('FCP-Host-Token','unit-token');clipboard='';
});
const response=(status,body,id=ID)=>({ok:status===200,status,headers:new Headers(id?{'X-Trace-Id':id}:{}),json:async()=>body});
const panel=()=>document.querySelector('[data-testid="request-trace"]');
async function launch(fetcher){globalThis.fetch=fetcher;const name=`launcher-${++loaded}.mjs`;writeFileSync(join(compiled,name),launcher);await import(compiledUrl(name));
 for(let i=0;i<100&&!document.querySelector('button');i++)await new Promise(r=>setTimeout(r,2));
 assert.match(document.body.textContent,/当前系统版本不可用/);
}
test('release bootstrap HTTP failure retains response ID and one copyable notice',async()=>{
 fetch=async()=>response(503,{});
 await assert.rejects(session.ensureUiRelease('admin','client'),e=>e.traceId===ID);
 await assert.rejects(session.ensureUiRelease('admin','client'),e=>e.traceId===ID);
 assert.equal(document.querySelectorAll('[data-testid="request-trace"]').length,1);
 panel().querySelector('button').click();await Promise.resolve();assert.equal(clipboard,ID);
});
test('body trace fallback and invalid IDs are strictly handled',async()=>{
 fetch=async()=>response(200,{code:500,traceId:ID},'bad');
 await assert.rejects(session.ensureUiRelease('admin','client'),e=>e.traceId===ID);
 fetch=async()=>response(503,{},'0'.repeat(32));
 await assert.rejects(session.ensureUiRelease('admin','client'),e=>!e.traceId);assert.equal(panel(),null);
});
test('network rejection clears old notice without trusting injected error properties',async()=>{
 trace.showTraceNotice(ID);fetch=async()=>{throw new Error('network')};
 await assert.rejects(session.ensureUiRelease('admin','client'),/network/);assert.equal(panel(),null);
});
test('late old-identity error never installs its response ID',async()=>{
 fetch=async()=>{localStorage.setItem('FCP-Host-Token','other');return response(503,{})};
 await assert.rejects(session.ensureUiRelease('admin','client'));assert.equal(panel(),null);
});
test('launcher renders fallback before showing current response notice',async()=>{
 await launch(async url=>url==='/fcp/ui/session'?response(200,{code:200,data:{allowedShells:['admin']}}):response(503,{}));
 assert.equal(panel()?.dataset.traceId,ID);panel().querySelector('button').click();await Promise.resolve();assert.equal(clipboard,ID);
});
test('launcher discards prior success ID when next fetch is rejected',async()=>{
 await launch(async url=>{if(url==='/fcp/ui/session')return response(200,{code:200,data:{allowedShells:['admin']}});throw Object.assign(new Error('network'),{traceId:ID})});
 assert.equal(panel(),null);
});
test('launcher body fallback ID survives failure DOM replacement',async()=>{
 await launch(async url=>url==='/fcp/ui/session'?response(200,{code:200,data:{allowedShells:['admin']}}):response(200,{code:500,traceId:ID},'bad'));
 assert.equal(panel()?.dataset.traceId,ID);
});
test('native packaging copies fixed same-origin diagnostic module and requires JAR entries',()=>{
 const native=readFileSync('tools/native.py','utf8');
 assert.match(launcher,/from '\.\/release-trace\.mjs'/);
 const config=readFileSync('config/application-fcp.yml','utf8');
 assert(config.includes('    - /admin/**'));assert(config.includes('    - /teacher/**'));
 const web=readFileSync('integration/ruoyi/src/main/java/org/dromara/fcp/school/FcpWebConfiguration.java','utf8');
 assert(web.includes('Resource resource = super.getResource(path,location)'));
 assert(web.includes('resource.isReadable()'));
 assert(native.includes("shutil.copy2(ROOT/'development-host/src/main/resources/dev-ui/trace.js',resources/'static'/name/'release-trace.mjs')"));
 for(const shell of ['admin','teacher'])assert(native.includes(`BOOT-INF/classes/static/${shell}/release-trace.mjs`));
 assert.equal(readFileSync(join(compiled,'trace.js'),'utf8'),readFileSync('development-host/src/main/resources/dev-ui/trace.js','utf8'));
});
