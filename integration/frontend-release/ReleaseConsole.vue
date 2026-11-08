<script setup lang="ts">
import PluginReleaseManager from '@/fcp/PluginReleaseManager.vue';
import ReleaseObservation from './ReleaseObservation.vue';
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue';
import { ReleasePermissionHost } from './permission-host';
import { ReleaseConsole, type ReleaseHost, type ConsoleState, type ReleaseAction } from './controller';
const props = defineProps<{host: ReleaseHost}>();
const state = shallowRef<ConsoleState>({busy:false,error:'',notice:'',unknown:false,state:null,versions:null,review:null});
const permissionHost = new ReleasePermissionHost(props.host);
const console = new ReleaseConsole(permissionHost, next => { state.value = next; });
const draft = ref(''), confirmed = ref(false), actionConfirmed = ref(false), recoveryConfirmed = ref(false), containmentConfirmed = ref(false), localError = ref('');
const action = ref<ReleaseAction>('DEPLOY'), percentage = ref(1);
const actions: {value: ReleaseAction; label: string}[] = [
  {value:'DEPLOY',label:'部署候选'}, {value:'CANARY',label:'开始灰度'}, {value:'EXPAND',label:'扩大比例'},
  {value:'PAUSE',label:'暂停扩量（保留当前流量）'}, {value:'ABORT',label:'紧急封禁发布入口（不回切旧版）'},
  {value:'PROMOTE',label:'全量发布'}, {value:'ROLLBACK',label:'回退稳定版'}, {value:'RETIRE',label:'退役旧版本'}
];
const pretty = (value: unknown) => JSON.stringify(value, null, 2);
watch(draft, () => { confirmed.value = false; console.clearReview(); });
watch([action,percentage], () => { actionConfirmed.value = false; });
watch(() => state.value.review, () => { confirmed.value = false; });
watch(() => state.value.state?.revision, () => { actionConfirmed.value = false; recoveryConfirmed.value = false; });
watch(() => state.value.containmentReview, () => { containmentConfirmed.value = false; });
watch(() => state.value.state?.pending, () => { recoveryConfirmed.value = false; });
const clearInputs = () => {draft.value='';confirmed.value=false;actionConfirmed.value=false;recoveryConfirmed.value=false;containmentConfirmed.value=false;localError.value='';};
const stop = permissionHost.observeIdentity(clearInputs);
const stopPermissions = permissionHost.observePermissions(clearInputs);
async function safely(work: () => unknown) { localError.value='';try {await work();} catch {localError.value='请先加载状态并检查输入、权限和确认项。';} }
async function load() { await permissionHost.refresh(); await console.load(); }
function review() { return safely(() => console.review(JSON.parse(draft.value))); }
function execute() {
  const current = Number(state.value.state?.basisPoints ?? 0);
  const bp = action.value === 'PROMOTE' ? 10000 : ['ABORT','ROLLBACK','DEPLOY'].includes(action.value) ? 0 : ['PAUSE','RETIRE'].includes(action.value) ? current : Math.round(percentage.value * 100);
  return safely(() => console.execute(action.value,bp,actionConfirmed.value));
}
onMounted(() => { void safely(load); });
onBeforeUnmount(() => {stop();stopPermissions();console.dispose();permissionHost.dispose();});
</script>
<template>
  <main class="fcp-release-console" aria-label="系统发布与版本管理">
    <h1>系统发布、版本与灰度</h1>
    <p>管理系统产物及发布组合，不管理源码分支。部署能力由当前执行适配器提供。</p>
    <PluginReleaseManager :host="permissionHost" />
    <ReleaseObservation :host="permissionHost" :blocked="state.busy || state.unknown || !state.state || state.state.pending != null" :server-pending-safety="!state.busy && !!state.state?.pending && state.state.supportsFencedContainment === true" @changed="safely(() => console.load())" />
    <button type="button" :disabled="state.busy" @click="safely(load)">刷新状态</button>
    <p v-if="state.error || localError" role="alert">{{ localError || state.error }}</p>
    <p v-if="!console.canView" role="alert">当前账号没有发布查询权限。</p>
    <template v-else>
      <div class="toolbar">
        <button v-if="console.canManage" type="button" :disabled="state.busy" @click="safely(() => console.reconcile())">对账执行结果</button>
        <span>修订：{{ state.state?.revision ?? '未加载' }}</span>
      </div>
      <p v-if="state.notice" role="status">{{ state.notice }}</p>
      <p v-if="state.unknown" role="alert">存在未确认结果，已阻止再次变更。请对账，不能重复部署或重发写入。</p>
      <section v-if="console.canContain" aria-label="紧急封禁全部受管新入口">
        <h2>紧急封禁全部受管新入口</h2>
        <p>仅在执行器支持隔离旧代次时可用。此操作关闭全部受管新入口，不回切旧版本，不撤销已执行效果。不会解锁或重试其他未知操作。</p>
        <button type="button" :disabled="state.busy" @click="safely(() => console.reviewContainment())">读取待决操作并预览封禁</button>
        <template v-if="state.containmentReview">
          <p>环境：{{ state.containmentReview.plan.environment }}；待决操作：{{ state.containmentReview.pendingOperationId }}；修订：{{ state.containmentReview.revision }}</p>
          <label><input v-model="containmentConfirmed" type="checkbox" :disabled="state.busy" />确认关闭全部受管新入口；不回切旧版、不撤销已执行效果</label>
          <button type="button" :disabled="state.busy || !containmentConfirmed" @click="safely(() => console.contain(containmentConfirmed))">确认紧急封禁入口</button>
        </template>
      </section>
      <section v-if="console.canRecover" aria-label="安全恢复未决发布操作">
        <h2>安全恢复未决操作</h2>
        <p>仅支持安全恢复协议的执行器可用。服务端先核实原操作是否被接收，只有明确未接收时才原子认领同一条命令；普通超时或找不到记录不等于未执行。</p>
        <label><input v-model="recoveryConfirmed" type="checkbox" :disabled="state.busy" />确认对当前未决操作执行安全恢复检查，不创建新的发布操作</label>
        <button type="button" :disabled="state.busy || !recoveryConfirmed" @click="safely(() => console.recover(recoveryConfirmed))">安全恢复原操作</button>
      </section>
      <section aria-label="发布实际状态">
        <h2>发布状态</h2>
        <dl>
          <dt>阶段</dt><dd>{{ state.state?.phase ?? '未加载' }}</dd>
          <dt>候选流量</dt><dd>{{ Number(state.state?.basisPoints ?? 0) / 100 }}%</dd>
        </dl>
        <p>“已提交”不是“部署完成”。以下分别展示期望状态与执行器实际观测。</p>
        <h3>期望状态</h3><pre>{{ pretty(state.state?.desired ?? null) }}</pre>
        <h3>实际观测</h3><pre>{{ pretty(state.state?.observed ?? null) }}</pre>
        <details><summary>计划、能力与审计明细</summary><pre>{{ pretty(state.state) }}</pre></details>
      </section>
      <section aria-label="系统版本">
        <h2>版本与产物</h2>
        <p>版本清单绑定宿主、前端、插件、服务、配置及数据库兼容声明。登记不等于已部署。</p>
        <pre>{{ pretty(state.versions) }}</pre>
      </section>
      <section v-if="console.canManage" aria-label="创建发布计划">
        <h2>创建发布计划</h2>
        <p>填写已登记稳定版、候选版和清单摘要。数据库版本必须处于双方兼容窗口；此处不执行数据库降级。</p>
        <details><summary>计划字段说明</summary><p>environment、stableRelease、candidateRelease、stableDigest、candidateDigest、schemaVersion、stableSchemaMin、stableSchemaMax、candidateSchemaMin、candidateSchemaMax，全部必填。摘要为64位小写 SHA-256，版本窗口为非负整数。</p></details>
        <label for="release-plan">发布计划 JSON</label>
        <textarea id="release-plan" v-model="draft" rows="12" spellcheck="false" :disabled="state.busy || state.unknown" />
        <button type="button" :disabled="state.busy || state.unknown || !state.state" @click="review">检查并预览</button>
        <div v-if="state.review">
          <p>将基于修订 {{ state.review.revision }} 提交以下计划：</p><pre>{{ pretty(state.review.plan) }}</pre>
          <label><input v-model="confirmed" type="checkbox" :disabled="state.busy" />我已核对环境、产物摘要及数据库兼容范围</label>
          <button type="button" :disabled="state.busy || state.unknown || !confirmed" @click="safely(() => console.confirmPlan(confirmed))">提交计划</button>
        </div>
      </section>
      <section v-if="console.canManage" aria-label="执行发布">
        <h2>执行发布</h2>
        <p>执行器未声明支持的动作将由服务端拒绝。暂停扩量不会停止现有流量；回退不会撤销数据库写入。</p>
        <label for="release-action">操作</label>
        <select id="release-action" v-model="action" :disabled="state.busy || state.unknown"><option v-for="item in actions" :key="item.value" :value="item.value" :disabled="!console.supports(item.value)">{{ item.label }}{{ console.supports(item.value) ? '' : '（当前执行器不支持）' }}</option></select>
        <label v-if="action === 'CANARY' || action === 'EXPAND'" for="release-percent">候选比例（%）<input id="release-percent" v-model.number="percentage" type="number" min="0.01" max="99.99" step="0.01" :disabled="state.busy || state.unknown" /></label>
        <label><input v-model="actionConfirmed" type="checkbox" :disabled="state.busy || state.unknown" />确认执行所选操作，并核对当前计划与目标环境</label>
        <button type="button" :disabled="state.busy || state.unknown || !state.state || !actionConfirmed || !console.supports(action)" @click="execute">执行并查看结果</button>
      </section>
    </template>
  </main>
</template>
<style scoped>
.fcp-release-console{padding:24px;max-width:1100px;margin:auto;line-height:1.6}.fcp-release-console section{border:1px solid #aaa6;border-radius:8px;padding:18px;margin:20px 0}.toolbar{display:flex;gap:12px;align-items:center;flex-wrap:wrap}label{display:block;margin:12px 0}textarea{display:block;width:100%;font-family:monospace;border:1px solid #888;padding:8px}button,select,input[type=number]{border:1px solid #888;border-radius:4px;padding:7px 12px;margin:4px}button:disabled{opacity:.5;cursor:not-allowed}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#8881;padding:12px;max-height:400px;overflow:auto}dt{font-weight:bold}dd{margin:0 0 8px}h1{font-size:24px}h2{font-size:20px}h3{font-size:16px}[role=alert]{color:#b42318}input[type=checkbox]{margin-right:8px}
</style>
