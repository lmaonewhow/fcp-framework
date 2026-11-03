/** FCP UI 0.4 reference SDK. No framework/runtime dependency; never evaluates schema code. */
export class FcpError extends Error {
  constructor(code,message,status=0){super(message);this.code=code;this.status=status;}
}
export function element(tag,text='',className='') {
  const node=document.createElement(tag);if(text!==null)node.textContent=text;if(className)node.className=className;return node;
}
export function createClient({base='/api',capability='',fetcher=globalThis.fetch.bind(globalThis),headers=()=>({})}={}) {
  let snapshot=null;const abort=new AbortController();
  async function request(path,{method='GET',body}={}) {
    const response=await fetcher(base+path,{method,credentials:'same-origin',cache:'no-store',signal:abort.signal,
      headers:{'Content-Type':'application/json',...(snapshot?.csrf?{'X-FCP-CSRF':snapshot.csrf}:{}),...headers()},
      ...(body===undefined?{}:{body:JSON.stringify(body)})});
    const result=await response.json();
    if(!response.ok||result.code!==200)throw new FcpError(result.error||result.data?.fcpCode||'HTTP_ERROR',result.message||result.msg||'请求失败',response.status);
    return result.data;
  }
  function envelope(input){
    if(!snapshot)throw new FcpError('NOT_READY','请先加载当前身份');
    return {input,generation:snapshot.generation,instance:snapshot.instance,expectedCaller:{...snapshot.caller}};
  }
  return {
    get snapshot(){return snapshot;},
    async refresh(){snapshot=await request('/bootstrap');return snapshot;},
    login:async(username,password)=>request('/login',{method:'POST',body:{username,password}}),
    call:(operation,input={})=>request('/'+encodeURIComponent(capability)+'/'+encodeURIComponent(operation),{method:'POST',body:envelope(input)}),
    compose:async(enabled)=>{await request('/composition',{method:'POST',body:envelope({enabled})});snapshot=await request('/bootstrap');return snapshot;},
    metrics:()=>request('/runtime'),logout:()=>request('/logout',{method:'POST',body:{}}),
    dispose(){abort.abort();snapshot=null;}
  };
}
export class UiCatalog {
  #entries=new Map();
  register(id,loader){if(!/^[a-z][a-z0-9.-]{0,99}$/.test(id)||this.#entries.has(id)||typeof loader!=='function')throw new Error('Invalid or duplicate UI entry');this.#entries.set(id,loader);return this;}
  async mount(id,container,context,signal){
    const loader=this.#entries.get(id);if(!loader)throw new FcpError('UI_CATALOG','未安装的界面插件');
    const plugin=await loader();if(signal?.aborted)return ()=>{};
    const dispose=await plugin.mount(container,context,signal);
    if(typeof dispose!=='function')throw new Error('UI plugins must return a disposer');
    if(signal?.aborted){dispose();return ()=>{};}return dispose;
  }
}
export function validateSchema(schema){
  if(!schema||Object.keys(schema).some(k=>!['fields','required'].includes(k))||!Array.isArray(schema.fields)||schema.fields.length>32)throw new Error('Invalid form schema');
  const names=new Set();
  for(const field of schema.fields){
    if(!field||!/^[a-zA-Z][a-zA-Z0-9]{0,63}$/.test(field.name)||names.has(field.name)||!['string','integer','number'].includes(field.type))throw new Error('Unsupported schema field');
    if(Object.keys(field).some(k=>!['name','type','label','minLength','maxLength','minimum','maximum','enum'].includes(k)))throw new Error('Unsupported form keyword');
    if(field.label!==undefined&&(typeof field.label!=='string'||field.label.length>200))throw new Error('Invalid label');
    if(field.enum&&(!Array.isArray(field.enum)||field.enum.length>100||field.enum.some(x=>typeof x!=='string'||x.length>200)))throw new Error('Invalid enum');
    for(const k of ['minLength','maxLength'])if(field[k]!==undefined&&(!Number.isInteger(field[k])||field[k]<0||field[k]>4096))throw new Error('Invalid length constraint');
    for(const k of ['minimum','maximum'])if(field[k]!==undefined&&!Number.isFinite(field[k]))throw new Error('Invalid numeric constraint');
    if(field.minLength>field.maxLength||field.minimum>field.maximum)throw new Error('Inverted range');
    names.add(field.name);
  }
  if(schema.required!==undefined&&(!Array.isArray(schema.required)||schema.required.some(k=>!names.has(k))))throw new Error('Invalid required fields');
  return structuredClone(schema);
}
/** UI subset only, not the complete JSON Schema standard. Server contracts remain authoritative. */
export function mountSchemaForm(container,rawSchema,{onSubmit,signal,initialValues={},hints={},multiline=[],enumFilter=()=>true,formatEnum=(_,v)=>v,onChange=()=>{},submitLabel='保存'}={}){
  const schema=validateSchema(rawSchema),form=element('form');form.className='schema-form';
  const controls=new Map();
  for(const field of schema.fields){
    const label=element('label',field.label||field.name);const input=element(field.enum?'select':multiline.includes(field.name)?'textarea':'input');
    input.name=field.name;input.id='field-'+field.name;label.htmlFor=input.id;
    if(field.enum){for(const value of field.enum){if(!enumFilter(field.name,value))continue;const option=element('option',formatEnum(field.name,value));option.value=value;input.append(option);}}
    else if(field.type==='integer'||field.type==='number'){input.type='number';input.step=field.type==='integer'?'1':'any';if(field.minimum!==undefined)input.min=String(field.minimum);if(field.maximum!==undefined)input.max=String(field.maximum);}
    else {if(input.tagName==='INPUT')input.type='text';input.maxLength=field.maxLength??4096;if(field.minLength)input.minLength=field.minLength;}
    if(initialValues[field.name]!==undefined)input.value=String(initialValues[field.name]);
    input.required=(schema.required||[]).includes(field.name);if(hints[field.name])input.placeholder=hints[field.name];
    input.addEventListener('input',()=>onChange(field.name,field.type==='string'?input.value:Number(input.value)),{signal});
    label.append(input);form.append(label);controls.set(field.name,input);
  }
  const submit=element('button',submitLabel,'primary');submit.type='submit';submit.dataset.fcpSubmit='true';
  const message=element('p','','form-message');message.setAttribute('role','status');
  form.append(submit,message);container.replaceChildren(form);let pending=null;
  form.addEventListener('submit',async(event)=>{
    event.preventDefault();if(submit.disabled)return;
    const data={};for(const field of schema.fields){const value=controls.get(field.name).value;data[field.name]=field.type==='string'?value:Number(value);}
    const fingerprint=JSON.stringify(data);
    if(pending&&pending.fingerprint!==fingerprint){message.textContent='上次提交结果尚未确认，请先核对记录。';return;}
    if(!pending)pending={fingerprint,key:crypto.randomUUID()};submit.disabled=true;message.textContent='正在保存…';
    try{await onSubmit(data,pending.key);if(signal?.aborted)return;pending=null;message.textContent='已保存';}
    catch(error){if(signal?.aborted)return;message.textContent=error.message;if(error instanceof FcpError&&[400,401,403,404,409].includes(error.status))pending=null;}
    finally{if(!signal?.aborted)submit.disabled=false;}
  },{signal});
  return ()=>form.remove();
}
export function createWasmWorker({url,sha256,workerUrl='/plugins/wasm-worker.js',timeoutMs=3000,maxPending=16}){
  if(!Number.isInteger(maxPending)||maxPending<1||maxPending>64||!Number.isFinite(timeoutMs)||timeoutMs<100||timeoutMs>10000)throw new Error('Invalid Wasm worker limits');
  let worker=null,sequence=0,disposed=false;const pending=new Map();
  function terminate(reason){worker?.terminate();worker=null;for(const task of pending.values()){clearTimeout(task.timer);task.reject(new FcpError('WASM_UNAVAILABLE',reason));}pending.clear();}
  function get(){if(!worker){worker=new Worker(workerUrl,{type:'module'});worker.onmessage=event=>{const task=pending.get(event.data.id);if(!task)return;pending.delete(event.data.id);clearTimeout(task.timer);if(event.data.error)task.reject(new Error(event.data.error));else task.resolve(event.data.result);};worker.onerror=()=>terminate('Wasm worker failed');}return worker;}
  return {call:async(name,args)=>new Promise((resolve,reject)=>{if(disposed)return reject(new FcpError('CLOSED','Wasm plugin has been disposed'));if(pending.size>=maxPending)return reject(new FcpError('BUSY','Wasm pending-call limit reached'));const target=get(),id=++sequence;const timer=setTimeout(()=>terminate('Wasm execution deadline exceeded'),timeoutMs);pending.set(id,{resolve,reject,timer});target.postMessage({id,url,sha256,name,args});}),dispose:()=>{disposed=true;terminate('Wasm plugin unmounted');}};
}
