import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';
let checks = 0;
const ok = (name, run) => { run(); checks++; console.log('PASS', name); };
const store = new Map();
globalThis.localStorage = {
  getItem: key => store.get(key) ?? null,
  setItem: (key, value) => { store.set(key, value); },
  removeItem: key => { store.delete(key); },
  clear: () => store.clear()
};
let destination = '', reloads = 0;
const listeners=[];
globalThis.window={ location:{replace:value=>{destination=value;},reload:()=>{reloads++;}},
 addEventListener:(event,fn)=>{listeners.push([event,fn]);} };
const m=await import(pathToFileURL(process.argv[2]));
const legacy = new Map([['token','stale-native-token'],['theme','dark']]);
const native = {get:k=>legacy.get(k),set:(k,v)=>legacy.set(k,v),remove:k=>legacy.delete(k),clear:()=>legacy.clear()};
const bridge=m.bridgeStorage(native);
ok('no fallback to stale native token',()=>assert.equal(bridge.get('token'),null));
m.writeToken('real-host-session');
ok('shared token reaches native storage API',()=>assert.equal(bridge.get('token'),'real-host-session'));
bridge.set('token','new-session');
ok('native login writes shared key',()=>assert.equal(m.readToken(),'new-session'));
ok('other native storage remains intact',()=>assert.equal(bridge.get('theme'),'dark'));
bridge.set('theme','light');
ok('other native write preserved',()=>assert.equal(legacy.get('theme'),'light'));
bridge.remove('token');
ok('native logout clears shared token',()=>assert.equal(m.readToken(),''));
ok('logout never resurrects stale token',()=>assert.equal(bridge.get('token'),null));
ok('invalid token type rejected',()=>assert.throws(()=>bridge.set('token',{}),TypeError));
m.writeToken('x');bridge.clear();
ok('clear removes shared and native state',()=>{assert.equal(m.readToken(),'');assert.equal(legacy.size,0);});
m.writeToken('x');
let response={code:200,data:{allowedShells:['teacher'],home:'https://evil.example'}};
let lastRequest;
globalThis.fetch=async(url,options)=>{lastRequest={url,options};return {ok:true,json:async()=>response};};
assert.equal(await m.ensureShell('admin','native-client'),true);checks++;
ok('redirect stays on fixed trusted host path',()=>assert.equal(destination,'/teacher/fcp/school'));
ok('token stays in Authorization header',()=>{assert.equal(lastRequest.url,'/fcp/ui/session');assert.equal(lastRequest.options.headers.Authorization,'Bearer x');});
ok('native client binding is sent',()=>assert.equal(lastRequest.options.headers.clientid,'native-client'));
response={code:200,data:{allowedShells:['admin','teacher']}};
assert.equal(await m.ensureShell('admin','native-client'),false);checks++;
response={code:200,data:{allowedShells:['https://evil.example']}};
await assert.rejects(()=>m.ensureShell('admin','native-client'));checks++;
response={code:403};await assert.rejects(()=>m.ensureShell('admin','native-client'));checks++;
await assert.rejects(()=>m.ensureShell('admin',''));checks++;
m.installSessionSync();m.installSessionSync();
ok('session synchronization installed once',()=>assert.equal(listeners.length,1));
listeners[0][1]({key:m.TOKEN_KEY,storageArea:localStorage});
ok('cross-tab auth change resets native stores',()=>assert.equal(reloads,1));
listeners[0][1]({key:'theme',storageArea:localStorage});
ok('theme change does not force reload',()=>assert.equal(reloads,1));
listeners[0][1]({key:null,storageArea:localStorage});
ok('cross-tab storage clear forces logout reload',()=>assert.equal(reloads,2));
console.log(`RESULT ${checks} JavaScript unit checks PASS; fetch is a test double, not a real HTTP/backend test`);
