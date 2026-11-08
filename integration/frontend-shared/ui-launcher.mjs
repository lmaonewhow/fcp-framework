import { responseTraceId, showTraceNotice } from './release-trace.mjs';
/* Trusted host entry. SHELL and CLIENT_ID are emitted by the pinned native build. */
const SHELL = __FCP_SHELL__, CLIENT_ID = __FCP_CLIENT_ID__;
const KEY='FCP-Release-Context-v1',TOKEN='FCP-Host-Token';
const sha=async text=>Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(text)))).map(x=>x.toString(16).padStart(2,'0')).join('');
let traceResponse, originalToken;
async function observedFetch(...args) {
  traceResponse=undefined;
  const response=await fetch(...args);
  traceResponse={headers:response.headers};
  return response;
}
async function load() {
  const token=localStorage.getItem(TOKEN)||'';originalToken=token;let url='/'+SHELL+'/embedded-index.html',expected=null,context=null;
  // Recovery governance uses the embedded trusted shell, still protected by native permissions.
  if(token && !/^\/admin\/fcp\/releases\/?$/.test(location.pathname)) {
    const session=await observedFetch('/fcp/ui/session',{cache:'no-store',credentials:'same-origin',headers:{Authorization:'Bearer '+token,clientid:CLIENT_ID}});
    if(session.ok){const body=await session.json();traceResponse.data=body;const allowed=body.data?.allowedShells,home=body.data?.home;
      if(body.code===200&&Array.isArray(allowed)&&!allowed.includes(SHELL)){
        if(typeof home!=='string'||!/^\/(admin|teacher)\/fcp\/(school|ingestion|plugins|releases)$/.test(home)||!allowed.includes(home.split('/')[1]))throw new Error('SHELL_NOT_ALLOWED');
        if((localStorage.getItem(TOKEN)||'')!==token)throw new Error('IDENTITY_CHANGED');location.replace(home);return;
      }
    }
    const response=await observedFetch('/fcp/ui/releases/bootstrap?shell='+SHELL,{cache:'no-store',credentials:'same-origin',headers:{Authorization:'Bearer '+token,clientid:CLIENT_ID}});
    if(!response.ok)throw new Error('UI_RELEASE_NOT_READY');
    const body=await response.json();traceResponse.data=body;if(body.code!==200)throw new Error('UI_RELEASE_NOT_READY');
    const m=body.data;
    if(m?.mode==='EMBEDDED' && m.shell===SHELL && ['DISABLED','NOT_CONFIGURED'].includes(m.reason)) {
      // Explicit authenticated state, never infer fallback from an HTTP error.
    } else {
      if(!m||m.mode!=='RELEASE'||m.shell!==SHELL||!/^[a-f0-9]{64}$/.test(m.bundleDigest)||!/^[a-f0-9]{64}$/.test(m.indexDigest)||m.indexUrl!=='/fcp/ui/assets/'+m.bundleDigest+'/'+SHELL+'/index.html'||typeof m.affinity!=='string'||typeof m.releaseId!=='string')throw new Error('UI_RELEASE_MANIFEST_INVALID');
      url=m.indexUrl;expected=m.indexDigest;context={releaseId:m.releaseId,affinity:m.affinity,bundleDigest:m.bundleDigest,tokenHash:await sha(token)};
    }
  }
  const response=await observedFetch(url,{cache:expected?'default':'no-store',credentials:'same-origin'});
  if(!response.ok||!response.headers.get('content-type')?.includes('text/html'))throw new Error('UI_ENTRY_UNAVAILABLE');
  const html=await response.text();if(html.length>4*1024*1024||!html.includes('<head>')||(expected&&await sha(html)!==expected))throw new Error('UI_ENTRY_INTEGRITY');
  if((localStorage.getItem(TOKEN)||'')!==token)throw new Error('IDENTITY_CHANGED');
  if(context)sessionStorage.setItem(KEY,JSON.stringify(context));else sessionStorage.removeItem(KEY);
  document.open();document.write(html);document.close();
}
load().catch(()=>{document.body.replaceChildren();const message=document.createElement('p');message.textContent='当前系统版本不可用或身份已变化。未自动回切旧版，请刷新或联系管理员。';const retry=document.createElement('button');retry.textContent='重新检查';retry.onclick=()=>location.reload();document.body.append(message,retry);if((localStorage.getItem(TOKEN)||'')===originalToken)showTraceNotice(responseTraceId(traceResponse));});
