import type { ReleaseHost } from './controller';
type ObservationHost = ReleaseHost & { observePermissions?(changed: () => void): () => void };
export interface ObservationPolicy {
  stages: number[]; minimumDwellMillis: number; minimumSamples: number;
  maximumAgeMillis: number; maximumErrorRate: number; maximumP95Millis: number;
}
export interface ObservationState { busy: boolean; unknown: boolean; unavailable: boolean; error: string; notice: string; state: Record<string,unknown>|null; release: Record<string,unknown>|null }
const object=(v:unknown):Record<string,unknown>=>{if(!v||typeof v!=='object'||Array.isArray(v))throw new Error('INVALID_RESPONSE');return v as Record<string,unknown>};
const integer=(v:unknown):v is number=>typeof v==='number'&&Number.isSafeInteger(v)&&v>=0;
export function percentageStages(text:string):number[] {
  return text.split(',').map(raw=>{const value=raw.trim();if(!/^\d{1,3}(?:\.\d{1,2})?$/.test(value))throw new Error('INVALID_PERCENTAGE');const [whole,fraction='']=value.split('.');return Number(whole)*100+Number(fraction.padEnd(2,'0'));});
}
export function policy(input:ObservationPolicy):ObservationPolicy {
  const stages=[...input.stages];
  if(!stages.length||stages.length>20||stages.at(-1)!==10000||stages.some((n,i)=>!integer(n)||n<1||n>10000||(i>0&&n<=stages[i-1]!)))throw new Error('INVALID_POLICY');
  if(!integer(input.minimumDwellMillis)||input.minimumDwellMillis<1000||input.minimumDwellMillis>86400000||!integer(input.minimumSamples)||input.minimumSamples<1||!integer(input.maximumAgeMillis)||input.maximumAgeMillis<1||!Number.isFinite(input.maximumErrorRate)||input.maximumErrorRate<0||input.maximumErrorRate>1||!Number.isFinite(input.maximumP95Millis)||input.maximumP95Millis<=0)throw new Error('INVALID_POLICY');
  // Explicit projection forbids browser-supplied metrics or extra sample fields.
  return {stages,minimumDwellMillis:input.minimumDwellMillis,minimumSamples:input.minimumSamples,maximumAgeMillis:input.maximumAgeMillis,maximumErrorRate:input.maximumErrorRate,maximumP95Millis:input.maximumP95Millis};
}
export class ObservationConsole {
  readonly data:ObservationState={busy:false,unknown:false,unavailable:false,error:'',notice:'',state:null,release:null};
  private safetyAllowed:()=>boolean;private host:ObservationHost; private blocked:()=>boolean;private changed:(s:ObservationState)=>void;private epoch=0;private alive=true;private requests=new Set<AbortController>();private stop:()=>void;private stopPermissions:()=>void;private mutationInFlight=false;
  constructor(host:ObservationHost,blocked:()=>boolean,changed:(s:ObservationState)=>void=()=>{},safetyAllowed:()=>boolean=()=>false){this.safetyAllowed=safetyAllowed;this.host=host;this.blocked=blocked;this.changed=changed;this.stop=host.observeIdentity(()=>this.reset());this.stopPermissions=host.observePermissions?.(()=>this.permissionsChanged())??(()=>{});}
  get canView(){return this.host.permissions().includes('fcp:release:view')}
  get canManage(){return this.canView&&this.host.permissions().includes('fcp:release:manage')}
  get pending(){return this.data.state?.pending!=null}
  get canCheck(){return this.canManage&&!this.data.busy&&!this.data.unknown&&!this.data.unavailable&&!this.blocked()&&this.data.state!=null&&this.data.release!=null}
  get canSafety(){return this.canManage&&!this.data.busy&&!this.data.unknown&&!this.data.unavailable&&this.safetyAllowed()&&this.data.state?.status==='ENABLED'&&this.data.state.pending!=null&&this.data.release?.pending!=null&&this.data.release.supportsFencedContainment===true}
  get canAct(){return this.canCheck&&this.data.release?.pending==null}
  private emit(){this.changed({...this.data})}
  private clear(unknown:boolean,notice:string){this.epoch++;for(const c of this.requests)c.abort();this.requests.clear();this.mutationInFlight=false;Object.assign(this.data,{busy:false,unknown,unavailable:false,error:'',notice,state:null,release:null});this.emit()}
  reset(){this.clear(false,'身份已改变，旧观察结果已清除。请回原身份核对已提交操作。')}
  private permissionsChanged(){this.clear(this.data.unknown||this.mutationInFlight,'权限已刷新，旧快照和确认已清除。未知写入仍锁定，请核对原操作。')}
  dispose(){this.alive=false;this.stop();this.stopPermissions();this.reset()}
  private require(manage=false){if(!this.alive||!(manage?this.canManage:this.canView))throw new Error('PERMISSION_DENIED')}
  private async request(path:string,method:'GET'|'POST',body?:unknown){
    const mine=this.epoch,key=this.host.identityKey(),c=new AbortController();this.requests.add(c);
    try{
      let raw:Record<string,unknown>;
      try{raw=object(await this.host.request('/fcp/releases'+path,method,body,c.signal))}
      catch(error){const e=error as {response?:{status?:number};status?:number};if(e?.response?.status===404||e?.status===404)throw new Error('UNAVAILABLE');if(e?.response?.status===401||e?.status===401)throw new Error('UNAUTHENTICATED');throw error}
      if(!this.alive||mine!==this.epoch||key!==this.host.identityKey()||c.signal.aborted)throw new Error('STALE_IDENTITY');
      const detail=raw.error&&typeof raw.error==='object'?object(raw.error):raw;
      if(raw.code===404||detail.status===404||detail.statusCode===404||detail.code===404)throw new Error('UNAVAILABLE');
      if(raw.code===401||detail.status===401||detail.statusCode===401||detail.code===401)throw new Error('UNAUTHENTICATED');
      if(this.host.shell==='admin'?raw.code!==200:raw.error!=null)throw new Error('SERVER_REJECTED');
      const data=object(raw.data);if(!integer(data.revision))throw new Error('INVALID_RESPONSE');return data;
    }finally{this.requests.delete(c)}
  }
  private async run(mutation:boolean,fn:(guard:()=>void)=>Promise<void>,containmentCheck=false,safetyOnly=false){
    this.require(mutation);if(this.data.busy)return;
    if(mutation&&(!(safetyOnly?this.canSafety:containmentCheck?this.canCheck:this.canAct)))throw new Error('RECONCILE_REQUIRED');
    const mine=this.epoch,identity=this.host.identityKey();const guard=()=>{if(!this.alive||mine!==this.epoch||identity!==this.host.identityKey())throw new Error('STALE_OPERATION');this.require(mutation)};this.mutationInFlight=mutation;this.data.busy=true;this.data.error='';this.emit();
    try{guard();await fn(guard)}
    catch(error){if(mine!==this.epoch||!this.alive)return;const code=error instanceof Error?error.message:'';
      if(code==='UNAUTHENTICATED'){this.permissionsChanged();this.data.error='登录已失效，请重新登录并读取状态。';this.emit();return}
      if(code==='UNAVAILABLE'){this.data.unavailable=true;this.data.error='';this.data.notice='观察能力未启用或接口不可用，请检查服务端配置。';}
      else{if(mutation)this.data.unknown=true;this.data.error=mutation?'操作结果未确认，已禁止重复动作。请先只读核对观察及发布状态。':'未能读取状态，请检查连接后手动刷新。';}
    }finally{if(mine===this.epoch&&this.alive){this.mutationInFlight=false;this.data.busy=false;this.emit()}}
  }
  async load(){return this.run(false,async guard=>{const state=await this.request('/automation/state','GET');guard();const release=await this.request('/state','GET');guard();this.data.state=state;this.data.release=release;this.data.unavailable=false;this.data.notice=this.data.unknown?'已读回状态，未知请求仍未自动解锁；请由发布管理员核对服务端操作记录。':'只读状态已更新。没有自动执行观察或放量。';})}
  private async releaseReady(guard:()=>void,containmentCheck=false){const release=await this.request('/state','GET');guard();this.data.release=release;if(this.blocked()||(!containmentCheck&&(release.pending!=null||release.phase==='UNKNOWN'||release.phase==='PENDING')))throw new Error('RELEASE_PENDING');return release}
  async enable(input:ObservationPolicy,confirmed:boolean){
    if(!confirmed)throw new Error('CONFIRMATION_REQUIRED');const p=policy(input);if(this.pending)throw new Error('AUTOMATION_PENDING');
    return this.run(true,async guard=>{const release=await this.releaseReady(guard);guard();if(release.phase!=='CANARY'||p.stages[0]! <= Number(release.basisPoints))throw new Error('CONFIRMED_CANARY_REQUIRED');{const value=await this.request('/automation/enable','POST',{expectedRevision:this.data.state!.revision,...p});guard();this.data.state=value;}this.data.notice='观察策略已启用。没有启动后台定时器；每一步由管理员确认检查。';})
  }
  async disable(confirmed:boolean){if(!confirmed)throw new Error('CONFIRMATION_REQUIRED');if(this.pending)throw new Error('AUTOMATION_PENDING');return this.run(true,async guard=>{await this.releaseReady(guard);guard();{const value=await this.request('/automation/disable','POST',{expectedRevision:this.data.state!.revision});guard();this.data.state=value;}this.data.notice='观察策略已停用，当前发布流量不因此回退。';})}
  async safety(confirmed:boolean){
    if(!confirmed)throw new Error('CONFIRMATION_REQUIRED');
    const reviewed=this.data.state,reviewedRelease=this.data.release;
    return this.run(true,async guard=>{
      const state=await this.request('/automation/state','GET');guard();const release=await this.request('/state','GET');guard();
      this.require(true);
      const same=(a:unknown,b:unknown):boolean=>{if(a===b)return true;if(!a||!b||typeof a!=='object'||typeof b!=='object')return false;const x=a as Record<string,unknown>,y=b as Record<string,unknown>;return Object.keys(x).length===Object.keys(y).length&&Object.keys(x).every(k=>same(x[k],y[k]));};
      this.data.state=state;this.data.release=release;
      if(!this.safetyAllowed()||!reviewed||!reviewedRelease||state.status!=='ENABLED'||state.pending==null||release.pending==null||release.supportsFencedContainment!==true||state.revision!==reviewed.revision||release.revision!==reviewedRelease.revision||!same(state.pending,reviewed.pending)||!same(state.owner,reviewed.owner)||!same(release.plan,reviewedRelease.plan)||!same(release.pending,reviewedRelease.pending)){
        this.data.notice='观察或发布状态已变化，未执行安全检查。请重新读取并确认。';return;
      }
      const pending=object(release.pending);if(typeof pending.operationId!=='string'||!pending.operationId)throw new Error('INVALID_PENDING');
      {const value=await this.request('/automation/safety','POST',{expectedRevision:state.revision,expectedReleaseRevision:release.revision,pendingOperationId:pending.operationId});guard();this.data.state=value;}
      {const value=await this.request('/state','GET');guard();this.data.release=value;}
      this.data.notice='已检查服务器安全信号；健康时不会放量。若已封禁，请核对实际入口关闭状态。';
    },false,true);
  }
  async tick(confirmed:boolean){if(!confirmed)throw new Error('CONFIRMATION_REQUIRED');if(this.data.state?.status!=='ENABLED')throw new Error('NOT_ENABLED');return this.run(true,async guard=>{await this.releaseReady(guard,true);guard();{const value=await this.request('/automation/tick','POST',{});guard();this.data.state=value;}{const value=await this.request('/state','GET');guard();this.data.release=value;}this.data.notice=this.pending?'当前观察步骤尚待发布对账，未允许新推进。':'本次服务器观察检查已返回，请核对策略和实际发布状态。';},true)}
}
