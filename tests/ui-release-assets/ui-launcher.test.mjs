import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {runInNewContext} from 'node:vm';
import {webcrypto} from 'node:crypto';
const source=readFileSync(new URL('../../integration/frontend-shared/ui-launcher.mjs',import.meta.url),'utf8').replace('__FCP_SHELL__','"admin"').replace('__FCP_CLIENT_ID__','"test-client"');
async function launch(status,data){
 const calls=[],writes=[];let error=false;const memory=new Map([['FCP-Host-Token','test-token']]);
 const context={crypto:webcrypto,TextEncoder,localStorage:{getItem:k=>memory.get(k)},sessionStorage:{setItem(){},removeItem(){}},location:{pathname:'/admin/fcp/plugins',reload(){},replace(){}},document:{open(){},write:x=>writes.push(x),close(){},createElement:()=>({}),body:{replaceChildren:()=>{error=true},append(){}}},fetch:async url=>{
  calls.push(url);
  if(url==='/fcp/ui/session')return {ok:true,json:async()=>({code:200,data:{allowedShells:['admin']}})};
  if(url.startsWith('/fcp/ui/releases/bootstrap'))return {ok:status===200,status,json:async()=>({code:status,data})};
  if(url==='/admin/embedded-index.html')return {ok:true,headers:{get:()=> 'text/html'},text:async()=>'<html><head></head><body>embedded</body></html>'};
  throw new Error('unexpected URL');
 }};
 await runInNewContext(source,context);
 return {calls,writes,error};
}
test('explicit embedded manifest loads trusted builtin UI without HTTP errors',async()=>{for(const reason of ['DISABLED','NOT_CONFIGURED']){const r=await launch(200,{mode:'EMBEDDED',shell:'admin',reason});assert.equal(r.error,false);assert.equal(r.writes.length,1);assert(r.calls.includes('/admin/embedded-index.html'));}});
test('HTTP errors never downgrade launcher into builtin UI',async()=>{for(const status of [401,404,409,503]){const r=await launch(status,{});assert(r.error);assert.equal(r.writes.length,0);assert(!r.calls.includes('/admin/embedded-index.html'));}});
test('invalid embedded state cannot become a release bypass',async()=>{for(const data of [{mode:'EMBEDDED',shell:'admin',reason:'STOPPED'},{mode:'EMBEDDED',shell:'teacher',reason:'DISABLED'},{shell:'admin'}]){const r=await launch(200,data);assert(r.error);assert.equal(r.writes.length,0);}});
