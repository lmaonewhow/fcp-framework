import { withResponseTrace } from './trace.js';
import type { TraceResponse } from './trace.js';
/** Per-tab trusted release context. No tokens in URLs, cookies, plugin manifests or persisted duplicate fields. */
const KEY = 'FCP-Release-Context-v1';
const TOKEN = 'FCP-Host-Token';
export interface ReleaseContext {releaseId:string;affinity:string;bundleDigest?:string;tokenHash:string}
const token = () => localStorage.getItem(TOKEN) || '';
const hash = async (text:string) => Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(text)))).map(b=>b.toString(16).padStart(2,'0')).join('');
const loadedBundle = () => document.querySelector<HTMLMetaElement>('meta[name="fcp-ui-bundle"]')?.content || 'embedded';
function stored():ReleaseContext|null {try{const value=JSON.parse(sessionStorage.getItem(KEY)||'null');return value&&typeof value.releaseId==='string'&&typeof value.affinity==='string'&&typeof value.tokenHash==='string'?value:null;}catch{return null;}}
export function clearReleaseContext():void {sessionStorage.removeItem(KEY);}
export async function getReleaseAffinity():Promise<string|null> {
  const original=token(),value=stored();if(!original||!value){clearReleaseContext();return null;}
  if(value.tokenHash!==await hash(original)||token()!==original){clearReleaseContext();return null;}
  return value.affinity;
}
export async function rememberReleaseContext(value:{releaseId:string;affinity:string;bundleDigest?:string},originalToken=token()):Promise<void> {
  if(!originalToken||!value.releaseId||!value.affinity)throw new Error('RELEASE_CONTEXT_INVALID');
  const old=stored();
  if(old?.bundleDigest&&old.bundleDigest!=='embedded'&&old.releaseId!==value.releaseId)throw new Error('UI_RELEASE_REFRESH_REQUIRED');
  const tokenHash=await hash(originalToken);if(token()!==originalToken)throw new Error('STALE_IDENTITY');
  sessionStorage.setItem(KEY,JSON.stringify({...value,bundleDigest:value.bundleDigest??old?.bundleDigest,tokenHash}));
}
let dirty=false,installed=false;
export function markUiSaved():void {dirty=false;}
export function installUiReleaseGuard():void {
  if(installed)return;installed=true;
  document.addEventListener('input',()=>{dirty=true;},true);
  window.addEventListener('beforeunload',event=>{if(dirty){event.preventDefault();event.returnValue='';}});
  window.addEventListener('storage',event=>{if(event.key===TOKEN||event.key===null)clearReleaseContext();});
}
export async function ensureUiRelease(shell:'admin'|'teacher',clientId:string,targetPath=window.location.pathname):Promise<boolean> {
  installUiReleaseGuard();
  // Never make recovery governance depend on a healthy business release. Native RBAC still applies.
  if(shell==='admin' && /^\/admin\/fcp\/releases\/?$/.test(targetPath))return false;
  const original=token();if(!original){clearReleaseContext();return false;}
  let traceResponse: TraceResponse | undefined;
  try {
  const response=await fetch('/fcp/ui/releases/bootstrap?shell='+shell,{cache:'no-store',credentials:'same-origin',headers:{Authorization:'Bearer '+original,clientid:clientId}});
  traceResponse={headers:response.headers};
  if(!response.ok)throw new Error('UI_RELEASE_UNAVAILABLE');
  const result=await response.json();traceResponse.data=result;if(result.code!==200)throw new Error('UI_RELEASE_UNAVAILABLE');
  if(token()!==original)throw new Error('STALE_IDENTITY');
  const value=result.data;
  if(value?.mode==='EMBEDDED' && value.shell===shell && ['DISABLED','NOT_CONFIGURED'].includes(value.reason)) {
    if(loadedBundle()!=='embedded')throw new Error('UI_RELEASE_UNAVAILABLE');
    clearReleaseContext();return false;
  }
  if(!value||value.mode!=='RELEASE'||value.shell!==shell||typeof value.releaseId!=='string'||typeof value.affinity!=='string'||!/^[a-f0-9]{64}$/.test(value.bundleDigest))throw new Error('UI_RELEASE_INVALID');
  const current=stored(),bundle=loadedBundle();
  if(bundle!==value.bundleDigest||(current&&current.releaseId!==value.releaseId)) {
    if(dirty&&!window.confirm('系统版本已更新。当前有未保存内容，是否放弃并加载新版本？'))return false;
    // Context is replaced only by a fresh authenticated launcher. Never silently move a pending write.
    dirty=false;clearReleaseContext();window.location.reload();return true;
  }
  await rememberReleaseContext(value,original);return false;
  } catch (error) {
    // A late response must not attach a previous identity's diagnostics.
    if(token()===original && error instanceof Error) withResponseTrace(error,traceResponse);
    throw error;
  }
}
