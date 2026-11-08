<script setup lang="ts">
import {computed,onBeforeUnmount,ref,shallowRef,watch} from 'vue';
import {PluginReleaseManager,type PluginReleaseHost,type ManagerState,type GrantTarget} from './pluginReleaseManager';
const props=defineProps<{host:PluginReleaseHost}>();
const state=shallowRef<ManagerState>({busy:false,error:'',notice:'',unknown:false,state:null,status:null,grant:null});
const manager=new PluginReleaseManager(props.host,s=>{state.value=s;});
const draft=ref(''),releaseId=ref(''),action=ref<'activate'|'drain'|'disable'|'stop'>('activate'),confirmed=ref(false),reconciled=ref(false),error=ref('');
const taskKey=ref(''),taskReason=ref(''),taskConfirmation=ref('');
const target=ref<GrantTarget>({pluginId:'',version:'',digest:'',targetTenant:'',dataScope:'',principalKind:'USER',principalId:'*'});
const capability=ref(''),allow=ref(true),expiry=ref(''),digestConfirmation=ref(''),grantConfirmed=ref(false);
const locked=computed(()=>state.value.busy||state.value.unknown);
const releases=computed(()=>Object.keys((state.value.state?.releases??{}) as Record<string,unknown>));
const pretty=(v:unknown)=>JSON.stringify(v,null,2);
async function safe(work:()=>unknown){error.value='';try{await work();}catch(e){error.value=e instanceof Error?e.message:'请检查输入';}}
watch([draft,releaseId,action,()=>state.value.state?.revision],()=>{confirmed.value=false;reconciled.value=false;});
watch(target,()=>{manager.clearGrant();capability.value='';digestConfirmation.value='';grantConfirmed.value=false;},{deep:true});
watch([capability,allow,expiry],()=>{digestConfirmation.value='';grantConfirmed.value=false;});
const clearForm=()=>{taskKey.value='';taskReason.value='';taskConfirmation.value='';draft.value='';releaseId.value='';confirmed.value=false;reconciled.value=false;target.value={pluginId:'',version:'',digest:'',targetTenant:'',dataScope:'',principalKind:'USER',principalId:'*'};capability.value='';expiry.value='';digestConfirmation.value='';grantConfirmed.value=false;error.value='';};
let permissionsKey=JSON.stringify([...new Set(props.host.permissions())].sort());
const stop=props.host.observeIdentity(()=>{permissionsKey=JSON.stringify([...new Set(props.host.permissions())].sort());clearForm();});
const stopPermissions=props.host.observePermissions?.(()=>{const next=JSON.stringify([...new Set(props.host.permissions())].sort());if(next!==permissionsKey){permissionsKey=next;clearForm();}})??(()=>{});
onBeforeUnmount(()=>{stop();stopPermissions();manager.dispose();});
</script>
<template>
 <section aria-label="运行时多版本插件管理">
  <h2>运行时多版本插件</h2>
  <p>不依赖插件目录或已发布路由，可在首次部署时登记组合。须启用 fcp.plugin.release.enabled 并使用共享发布路由；旧 Exchange 调度适配器不可同时启用。插件不能修改宿主源码或直接执行数据库变更。</p>
  <p v-if="!manager.allowed">需要 fcp:release:manage 权限。</p>
  <template v-else>
   <button :disabled="state.busy" @click="safe(()=>manager.load())">加载/刷新插件发布状态</button>
   <p role="alert" v-if="error||state.error">{{error||state.error}}</p><p role="status" v-if="state.notice">{{state.notice}}</p>
   <div v-if="state.unknown" role="alert">
    <p>写入结果尚不确定，已禁止重发。请刷新并检查原组合、运行状态或审批记录；未知进程启动需要运维核对，不能假定未执行。</p>
    <label><input type="checkbox" v-model="reconciled" :disabled="state.busy||!state.state" />已人工核对原操作的实际终态，允许下一次独立操作</label>
    <button :disabled="state.busy||!reconciled||!state.state" @click="safe(()=>manager.acknowledgeReconciled(reconciled))">确认核对结果</button>
   </div>
   <details><summary>组合、任务与操作状态（修订 {{state.state?.revision??'未加载'}}）</summary><pre>{{pretty(state.state)}}</pre></details>
   <fieldset :disabled="locked">
    <legend>取消尚未开始的固定版本任务</legend>
    <p>仅 executionTracking=1 且 PINNED 可取消。EXECUTING、UNKNOWN 和旧格式任务必须对账，不能以取消掩盖未知写入；记录不会删除。</p>
    <label>任务记录键（从上方状态读取）<input v-model="taskKey" /></label>
    <label>取消原因<input v-model="taskReason" maxlength="500" /></label>
    <label>再次填写完整任务键以确认<input v-model="taskConfirmation" /></label>
    <button :disabled="!taskReason||taskKey!==taskConfirmation||taskKey.length!==64" @click="safe(()=>manager.cancelTask(taskKey,taskReason,taskConfirmation))">确认取消未执行任务</button>
   </fieldset>
   <fieldset :disabled="locked">
    <legend>登记不可变组合</legend>
    <p>先通过系统版本提供者登记同 ID 的系统版本，并上传验证插件包。格式：id 与 plugins；plugins 的每个键为插件 ID，值含 pluginId、version、digest。必须与系统版本的全部 PLUGIN 产物完全一致。</p>
    <label>组合 JSON<textarea v-model="draft" rows="7" spellcheck="false" /></label>
    <label><input type="checkbox" v-model="confirmed" />已核对组合及包摘要，或下方所选生命周期操作及其目标</label>
    <button :disabled="!confirmed||!state.state" @click="safe(()=>manager.register(JSON.parse(draft),confirmed))">登记组合（不自动授权）</button>
    <h3>生命周期</h3>
    <label>发布组合<select v-model="releaseId"><option value="">请选择</option><option v-for="id in releases" :key="id" :value="id">{{id}}</option></select></label>
    <label>操作<select v-model="action"><option value="activate">激活已登记版本</option><option value="drain">关闭新任务入口，排空已有任务</option><option value="disable">禁用该组合</option><option value="stop">停止已排空进程</option></select></label>
    <p>激活不等于获批或切换灰度流量。排空保留已有固定版本任务；停止须无在途调用、无待完成任务。保留数据，不硬删除或回滚数据库。</p>
    <button :disabled="!confirmed||!releaseId||!state.state" @click="safe(()=>manager.lifecycle(releaseId,action,confirmed))">执行所选操作：{{releaseId}} / {{action}}</button>
   </fieldset>
   <button :disabled="state.busy||!releaseId" @click="safe(()=>manager.inspect(releaseId))">查询所选组合实际进程与排空状态</button><pre v-if="state.status">{{pretty(state.status)}}</pre>
   <fieldset :disabled="state.busy">
    <legend>读取确切版本审批</legend>
    <label>插件 ID<input v-model="target.pluginId" /></label><label>版本<input v-model="target.version" /></label><label>SHA-256 摘要<input v-model="target.digest" size="68" /></label>
    <label>目标租户<input v-model="target.targetTenant" /></label><label>数据范围<input v-model="target.dataScope" placeholder="tenant:租户 或 class:班级" /></label>
    <label>主体类型<select v-model="target.principalKind"><option>USER</option><option>TASK</option></select></label><label>主体 ID<input v-model="target.principalId" /></label>
    <p>USER 主体 ID 必须为 *；TASK 必须填实际服务端任务 ID、设置到期时间，并具有基础 USER 版本审批。用户自身权限仍实时校验。</p>
    <button @click="safe(()=>manager.options(target))">读取该摘要的能力与审批</button>
   </fieldset>
   <fieldset v-if="state.grant" :disabled="locked">
    <legend>逐能力确认审批</legend><pre>{{pretty(state.grant.target)}}</pre>
    <label>能力 / 操作<select v-model="capability"><option value="">请选择</option><option v-for="o in state.grant.options" :key="o.capability" :value="o.capability">{{o.capability}} / {{o.operation}} / {{o.permission}} / 修订{{o.revision}} / {{o.allowed?'已授权':'未授权'}} / 到期{{o.expiresAt??'无'}}</option></select></label>
    <label>变更<select v-model="allow"><option :value="true">授权</option><option :value="false">撤销</option></select></label>
    <label>到期时间（ISO 8601，含时区；USER 可留空）<input v-model="expiry" placeholder="2026-12-01T00:00:00Z" /></label>
    <label>手动填写完整摘要以确认<input v-model="digestConfirmation" autocomplete="off" size="68" /></label>
    <label><input type="checkbox" v-model="grantConfirmed" />确认以上插件、版本、摘要、租户、主体、数据范围、能力、操作和到期时间</label>
    <button :disabled="!capability||!grantConfirmed||digestConfirmation!==state.grant.target.digest" @click="safe(()=>manager.grant(capability,allow,expiry||null,digestConfirmation,grantConfirmed))">提交确切版本审批</button>
   </fieldset>
  </template>
 </section>
</template>
<style scoped>
fieldset{border:1px solid #8886;padding:16px;margin:16px 0}label{display:block;margin:10px 0}input,select,button{padding:6px;margin:4px;max-width:100%;box-sizing:border-box}textarea{display:block;width:100%;font-family:monospace}pre{white-space:pre-wrap;overflow-wrap:anywhere;max-height:320px;overflow:auto}button:disabled{opacity:.5}[role=alert]{color:#b42318}
</style>
