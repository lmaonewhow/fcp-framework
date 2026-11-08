// Isolated logic/component tests. Not a real-browser or full vue-tsc substitute.
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
// Arguments accept package directories (or module names resolvable from here).
const { parse, compileScript, compileTemplate } = require(process.argv[2] || '@vue/compiler-sfc');
const ts = require(process.argv[3] || 'typescript');
const source=fs.readFileSync(path.join(root, 'integration/plus-ui/src/fcp/generator/GeneratorApply.vue'),'utf8');
const {descriptor,errors}=parse(source);assert.equal(errors.length,0);
const script=compileScript(descriptor,{id:'generator'});
assert.equal(compileTemplate({source:descriptor.template.content,filename:'GeneratorApply.vue',id:'generator',compilerOptions:{bindingMetadata:script.bindings}}).errors.length,0);
const raw=descriptor.scriptSetup.content.replace(/^import .*;$/gm,'');
const js=ts.transpileModule(raw,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}}).outputText;
const ready={planId:'p',applicable:true,files:[],conflicts:[],expiresAt:'later'};
function harness(api,confirm=async()=>{}){
 const c={api,AbortController,ref:v=>({value:v}),computed:f=>({get value(){return f();}}),onBeforeUnmount:fn=>{c.unmount=fn;},defineExpose:()=>{},ElMessageBox:{confirm}};
 vm.createContext(c);vm.runInContext(js+';globalThis.test={show,close,preview,apply,undo,refreshHistory,plan,history,visible,busy,notice,workspace,unmount:globalThis.unmount};',c);return c.test;
}
const tick=()=>new Promise(r=>setImmediate(r));
(async()=>{
 let calls=0;const a=harness({getConfig:async()=>{calls++;return{enabled:false,workspace:'dev'}}});assert.equal(calls,0);await a.show(['1']);assert.equal(calls,1);assert.match(a.notice.value,/尚未启用/);assert.equal(a.plan.value,undefined);console.log('PASS lazy configuration and disabled mode');
 let resolveOld;let n=0;const b=harness({getConfig:()=>++n===1?new Promise(r=>resolveOld=r):Promise.resolve({enabled:false,workspace:'new'})});const old=b.show(['1']);b.close();await b.show(['2']);resolveOld({enabled:true,workspace:'old'});await old;assert.equal(b.workspace.value,'new');console.log('PASS stale response cannot pollute reopened dialog');
 let applied=0,hist=0;let finish;const c=harness({getConfig:async()=>({enabled:true,workspace:'dev'}),createPlan:async()=>ready,applyPlan:()=>{applied++;return new Promise(r=>finish=r)},getOperations:async()=>{hist++;return{operations:[]}}});await c.show(['1']);const p=c.apply();await tick();c.apply();c.close();assert.equal(c.visible.value,true);assert.equal(applied,1);finish({status:'applied'});await p;assert.equal(hist,1);assert.equal(c.plan.value,undefined);console.log('PASS confirmation mutation lock, no duplicate apply, refresh history');
 const d=harness({getConfig:async()=>({enabled:true,workspace:'dev'}),createPlan:async()=>ready,applyPlan:async()=>{throw Error('network')},getOperations:async()=>({operations:[{operationId:'o',status:'applied'}]})});await d.show(['1']);await d.apply();assert.equal(d.plan.value,undefined);assert.equal(d.history.value.length,1);assert.match(d.notice.value,/不要直接重试/);console.log('PASS uncertain mutation invalidates plan and reads history');
 const e=harness({getConfig:async()=>({enabled:true,workspace:'dev'}),createPlan:async()=>({...ready,conflicts:['conflict']}),applyPlan:()=>{throw Error('must not run')}});await e.show(['1']);assert.equal(e.apply(),undefined);console.log('PASS conflicts disable apply');
 const f=harness({getConfig:async()=>({enabled:true,workspace:'dev'}),createPlan:async()=>ready},async()=>{throw Error('cancel')});await f.show(['1']);await f.apply();assert.equal(f.plan.value.planId,'p');assert.equal(f.busy.value,false);console.log('PASS confirmation cancel preserves plan');
 let confirmResolve, lateCalls=0;
 const g=harness({getConfig:async()=>({enabled:true,workspace:'dev'}),createPlan:async()=>ready,applyPlan:async()=>{lateCalls++;}},()=>new Promise(r=>confirmResolve=r));
 await g.show(['1']);const late=g.apply();g.unmount();confirmResolve();await late;
 assert.equal(lateCalls,0);console.log('PASS unmount during confirmation does not start mutation');
 const h=harness({getConfig:async()=>({enabled:true,workspace:'dev'}),createPlan:async()=>ready,getOperations:async()=>({operations:[{operationId:'pending',status:'pending'}],recoveryRequired:true})});
 await h.show(['1']);await h.refreshHistory();assert.equal(h.plan.value,undefined);assert.match(h.notice.value,/恢复回滚/);console.log('PASS recovery history invalidates preview');
 // Compile all three TS sources. This catches syntax, not project type errors.
 for (const name of ['api.ts','types.ts']) {
   const output=ts.transpileModule(fs.readFileSync(path.join(root,'integration/plus-ui/src/fcp/generator',name),'utf8'),{reportDiagnostics:true,compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}});
   assert.equal(output.diagnostics.length,0);
 }
 // Exercise the actual API module against the native interceptor's R<T> contract.
 let response={code:200,data:{enabled:true,workspace:'dev'}}, captured;
 const transport={exports:{},require:()=>({default:async options=>{captured=options;return response;}})};
 vm.createContext(transport);
 const apiCode=ts.transpileModule(fs.readFileSync(path.join(root,'integration/plus-ui/src/fcp/generator/api.ts'),'utf8'),{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}}).outputText;
 vm.runInContext(apiCode,transport);
 assert.equal((await transport.exports.getConfig()).workspace,'dev');
 response={code:500,msg:'权限不足',data:null};
 await assert.rejects(transport.exports.createPlan(['001']),/权限不足/);
 response={code:200,data:{status:'rolled_back'}};
 await transport.exports.rollback('id/escape');
 assert.equal(captured.url,'/fcp/generator/operations/id%2Fescape/rollback');
 assert.equal(captured.method,'post');
 assert.equal(JSON.stringify(captured.data),'{}');
 console.log('PASS API R<T> unwrap, business failure and encoded mutation URL');
 console.log('PASS Vue SFC compiled; full vue-tsc and real browser NOT_RUN');
})().catch(e=>{console.error(e);process.exitCode=1});
