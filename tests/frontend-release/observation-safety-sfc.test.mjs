/** Actual compiled ReleaseObservation SFC and Vue lifecycle; endpoint doubles, not native browser acceptance. */
import test from 'node:test';import assert from 'node:assert/strict';import {createRequire} from 'node:module';import {mkdtemp,writeFile,rm,readFile} from 'node:fs/promises';import {tmpdir} from 'node:os';import {join,resolve} from 'node:path';import {pathToFileURL} from 'node:url';
const modules=process.env.FCP_VUE_TOOLS;if(!modules)throw new Error('FCP_VUE_TOOLS must name real Vue/compiler-sfc/TypeScript node_modules');
const require=createRequire(resolve(modules,'../package.json'));const {parse,compileScript}=require('@vue/compiler-sfc');const ts=require('typescript');const {JSDOM}=require('jsdom');const dom=new JSDOM('<html><body></body></html>');for(const name of ['window','document','Element','HTMLElement','SVGElement','Node','Event'])globalThis[name]=dom.window[name];const vue=require('vue');
const root=resolve('integration/frontend-release'),temp=await mkdtemp(join(tmpdir(),'fcp-containment-sfc-'));
const descriptor=parse(await readFile(join(root,'ReleaseObservation.vue'),'utf8')).descriptor;
let code=compileScript(descriptor,{id:'containment-test',inlineTemplate:true}).content;
code=code.replace(/import PluginReleaseManager[^;]*;/,"const PluginReleaseManager={render:()=>null};").replace(/import ReleaseObservation[^;]*;/,"const ReleaseObservation={render:()=>null};");
code=code.replace(/from '\.\/observation'/g,`from '${pathToFileURL(join(root,'observation.ts')).href}'`);
code=code.replace(/from ['"]vue['"]/g,`from '${pathToFileURL(require.resolve('vue/dist/vue.runtime.esm-bundler.js')).href}'`).replace(/from '\.\/controller'/g,`from '${pathToFileURL(join(root,'controller.ts')).href}'`).replace(/from '\.\/permission-host'/g,`from '${pathToFileURL(join(root,'permission-host.ts')).href}'`);
await writeFile(join(temp,'console.mjs'),ts.transpileModule(code,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText);const {default:Component}=await import(pathToFileURL(join(temp,'console.mjs')).href);
const flush=async()=>{await new Promise(r=>setImmediate(r));await vue.nextTick()};
const plan={environment:'test',stableRelease:'v1',candidateRelease:'v2',stableDigest:'a'.repeat(64),candidateDigest:'b'.repeat(64),schemaVersion:1,stableSchemaMin:0,stableSchemaMax:2,candidateSchemaMin:1,candidateSchemaMax:3};

test('compiled observation SFC exposes only confirmed safety while parent unknown blocks normal controls',async()=>{
 let revision=7,posts=[];
 const host={shell:'admin',identityKey:()=> 'user',permissions:()=>['fcp:release:view','fcp:release:manage'],observeIdentity:()=>()=>{},async request(path,method,body){if(method==='POST')posts.push({path,body});return {code:200,data:path==='/fcp/releases/state'?{revision,phase:'CANARY',plan:{candidateRelease:'r2'},supportsFencedContainment:true,pending:{operationId:'core-step'}}:{revision:4,status:'ENABLED',owner:['tenant','user'],pending:{operationId:'auto-step'}}}}};
 const container=document.createElement('div'),app=vue.createApp(Component,{host,blocked:true,serverPendingSafety:true});document.body.append(container);app.mount(container);await flush();
 const button=label=>[...container.querySelectorAll('button')].find(n=>n.textContent===label);button('读取观察状态').click();await flush();await flush();
 assert.equal(container.querySelector('fieldset').disabled,true);assert.equal(button('仅检查待决步骤安全信号').disabled,true);
 const check=()=>{const box=container.querySelector('[aria-label="待决发布安全检查"] input');box.checked=true;box.dispatchEvent(new Event('change'));};check();await flush();revision=8;button('仅检查待决步骤安全信号').click();await flush();await flush();assert.equal(posts.length,0);assert.equal(button('仅检查待决步骤安全信号').disabled,true);
 check();await flush();button('仅检查待决步骤安全信号').click();await flush();await flush();assert.deepEqual(posts,[{path:'/fcp/releases/automation/safety',body:{expectedRevision:4,expectedReleaseRevision:8,pendingOperationId:'core-step'}}]);assert.equal(container.querySelector('fieldset').disabled,true);app.unmount();
});
test('compiled observation SFC never offers exception for ordinary unknown without server pending safety grant',async()=>{
 const host={shell:'admin',identityKey:()=> 'user',permissions:()=>['fcp:release:view','fcp:release:manage'],observeIdentity:()=>()=>{},async request(path){return {code:200,data:path==='/fcp/releases/state'?{revision:7,phase:'CANARY',supportsFencedContainment:false,pending:{operationId:'p'}}:{revision:4,status:'ENABLED',pending:{operationId:'a'}}}}};
 const container=document.createElement('div'),app=vue.createApp(Component,{host,blocked:true,serverPendingSafety:false});document.body.append(container);app.mount(container);container.querySelector('button').click();await flush();await flush();assert.equal(container.querySelector('[aria-label="待决发布安全检查"]'),null);assert.equal(container.querySelector('fieldset').disabled,true);app.unmount();
});
test.after(async()=>rm(temp,{recursive:true,force:true}));
