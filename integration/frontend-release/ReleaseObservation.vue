<script setup lang="ts">
import { onBeforeUnmount, ref, shallowRef, watch } from 'vue';
import type { ReleaseHost } from './controller';
import { ObservationConsole, percentageStages, type ObservationState } from './observation';
const props=defineProps<{host:ReleaseHost & {observePermissions?(changed:()=>void):()=>void};blocked:boolean;serverPendingSafety?:boolean}>();
const emit=defineEmits<{changed:[]}>();
const state=shallowRef<ObservationState>({busy:false,unknown:false,unavailable:false,error:'',notice:'',state:null,release:null});
const manager=new ObservationConsole(props.host,()=>props.blocked,next=>{state.value=next},()=>props.serverPendingSafety===true);
const stages=ref(''),dwell=ref(60),samples=ref(100),age=ref(120),errorRate=ref(1),p95=ref(1000),confirmed=ref(false),safetyConfirmed=ref(false),localError=ref('');
const pretty=(value:unknown)=>JSON.stringify(value,null,2);
watch([stages,dwell,samples,age,errorRate,p95],()=>{confirmed.value=false});
watch(()=>[state.value.state,state.value.release,props.serverPendingSafety],()=>{confirmed.value=false;safetyConfirmed.value=false});
watch(()=>props.blocked,()=>{confirmed.value=false});
const stop=props.host.observeIdentity(()=>{stages.value='';confirmed.value=false;safetyConfirmed.value=false;localError.value=''});
const stopPermissions=props.host.observePermissions?.(()=>{confirmed.value=false;localError.value='';})??(()=>{});
async function run(fn:()=>Promise<void>,mutation=false){localError.value='';try{await fn();if(mutation){confirmed.value=false;emit('changed')}}catch{localError.value='请先读取状态、核对权限和输入，并确认本次操作。'}}
async function enable(){await run(()=>manager.enable({stages:percentageStages(stages.value),minimumDwellMillis:dwell.value*1000,minimumSamples:samples.value,maximumAgeMillis:age.value*1000,maximumErrorRate:errorRate.value/100,maximumP95Millis:p95.value},confirmed.value),true)}
onBeforeUnmount(()=>{stop();stopPermissions();manager.dispose()});
</script>
<template>
  <details class="observation-panel" aria-label="服务器观察与分阶段推进">
    <summary>服务器观察与分阶段推进</summary>
    <p>指标仅由服务器可信观察源采集。这里配置判定门槛，不提交请求成功率、延迟或其他样本。没有无人值守定时器，每次检查均由管理员触发。</p>
    <p v-if="!manager.canView" role="alert">需要发布查询权限。</p>
    <template v-else>
      <button type="button" :disabled="state.busy" @click="run(()=>manager.load())">读取观察状态</button>
      <p v-if="state.error || localError" role="alert">{{ localError || state.error }}</p>
      <p v-if="state.notice" role="status">{{ state.notice }}</p>
      <p v-if="state.unknown" role="alert">上次操作结果未知，已禁止重复动作。只读刷新不会自动解除锁定。</p>
      <p v-if="state.state">状态：{{ state.state.status }}；观察策略修订：{{ state.state.revision }}；当前步骤：{{ state.state.step ?? '未开始' }}</p>
      <p v-if="state.state?.blockedReason" role="alert">安全检查受阻：{{ state.state.blockedReason }}。未确认入口已封禁，请在发布控制台处理。</p>
      <p v-if="manager.pending" role="alert">观察步骤待决。可确认“对账当前观察步骤”检查可信安全信号；发现事故时请求独立安全封禁。普通待决执行仍需发布控制台对账，不会重复推进。</p>
      <pre v-if="state.state">{{ pretty(state.state) }}</pre>
      <section v-if="manager.canSafety" aria-label="待决发布安全检查">
        <p>仅检查已存在步骤的服务器安全信号。健康时不会继续放量；确认安全异常时可能封禁全部受管新入口，不回切旧版本。</p>
        <label><input v-model="safetyConfirmed" type="checkbox" :disabled="state.busy" />确认检查当前待决步骤的安全信号，不推进普通灰度</label>
        <button type="button" :disabled="!safetyConfirmed || state.busy" @click="run(()=>manager.safety(safetyConfirmed),true)">仅检查待决步骤安全信号</button>
      </section>
      <fieldset v-if="manager.canManage" :disabled="state.busy || state.unknown || state.unavailable || props.blocked">
        <legend>观察策略</legend>
        <p>先完成手动灰度。后续比例严格递增，大于当前候选比例，最后为100%。门槛只是配置，不是实测结果。</p>
        <label>后续比例（%，英文逗号分隔）<input v-model="stages" type="text" placeholder="例如：20,50,100" /></label>
        <label>每阶段最短观察时间（秒）<input v-model.number="dwell" type="number" min="1" max="86400" /></label>
        <label>最少请求样本数<input v-model.number="samples" type="number" min="1" step="1" /></label>
        <label>样本最大年龄（秒）<input v-model.number="age" type="number" min="0.001" step="0.001" /></label>
        <label>最大错误率（%）<input v-model.number="errorRate" type="number" min="0" max="100" step="0.01" /></label>
        <label>最大P95延迟（毫秒）<input v-model.number="p95" type="number" min="0.001" step="0.001" /></label>
        <p>缺失、过期或不足的样本会暂停推进。超限或确认的安全异常可封禁全部受管新入口，不会自动回切旧版本。</p>
        <label><input v-model="confirmed" type="checkbox" />确认本次所选动作及当前策略；检查可能推进流量、暂停或封禁入口</label>
        <button type="button" :disabled="!confirmed || !manager.canAct || manager.pending" @click="enable">启用此策略</button>
        <button type="button" :disabled="!confirmed || !manager.canAct || manager.pending" @click="run(()=>manager.disable(confirmed),true)">停用策略（不改变当前流量）</button>
        <button type="button" :disabled="!confirmed || !manager.canCheck || state.state?.status !== 'ENABLED'" @click="run(()=>manager.tick(confirmed),true)">{{ manager.pending ? '对账当前观察步骤' : '检查服务器指标并推进一步' }}</button>
      </fieldset>
    </template>
  </details>
</template>
<style scoped>
.observation-panel{border:1px solid #8886;border-radius:8px;margin:20px 0;padding:18px}.observation-panel summary{font-weight:600;cursor:pointer}.observation-panel label{display:block;margin:10px 0}.observation-panel input:not([type=checkbox]){display:block;border:1px solid #888;padding:6px;max-width:100%}.observation-panel button{border:1px solid #888;border-radius:4px;padding:7px 10px;margin:4px}.observation-panel button:disabled{opacity:.5;cursor:not-allowed}.observation-panel pre{white-space:pre-wrap;overflow-wrap:anywhere;max-height:350px;overflow:auto}.observation-panel [role=alert]{color:#b42318}.observation-panel fieldset{border:1px solid #8886;padding:12px}
</style>
