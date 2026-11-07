<script setup lang="ts">
import type { Identity } from './runtime';
import type { NativePluginHost } from './usePlugins';
import { useVersionManager } from './useVersionManager';
const props=defineProps<{host:NativePluginHost;identity:Identity;plugins:readonly {pluginId:string;state:string}[];blocked:boolean}>();
const emit=defineEmits<{changed:[]}>();
const manager=useVersionManager(props.host,()=>props.identity,()=>props.blocked,()=>emit('changed'));
const {allowed,busy,error,notice,pluginId,action,targetDigest,selectedPackage,stageAcknowledged,versions,plan,operation,pending,confirmedTenants,confirmedDependants,confirmedDowntime,confirmedDataRetention,loadVersions,changeTarget,changeAction,selectFile,stage,preview,confirm,query,retry,abandon}=manager;
function fileChanged(event:Event){const input=event.target as HTMLInputElement;void selectFile(input.files?.[0]??null);input.value='';}
</script>
<template>
 <section v-if="allowed" class="version-manager" data-testid="version-manager">
  <h3>显式升级与软件版本回退</h3><p>暂存、预览和确认是独立操作。所有租户必须已明确禁用并排空，服务端才允许切换；界面不会代为停用、授权或启用。软件版本回退不等于数据库或业务数据回滚。</p>
  <p v-if="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
  <section class="version-panel"><h4>仅验证暂存签名版本</h4><input type="file" accept=".zip,application/zip" :disabled="busy || Boolean(pending) || blocked" aria-label="选择待暂存签名版本" @change="fileChanged" />
   <template v-if="selectedPackage"><p>{{ selectedPackage.name }} · {{ selectedPackage.size }} 字节</p><p class="version-digest">SHA-256：{{ selectedPackage.digest }}</p><p>摘要不等于发布者已受信任。服务端必须验证平台已信任的发布者签名；可信插件执行不提供恶意代码沙箱。</p><label><input v-model="stageAcknowledged" type="checkbox" :disabled="busy || Boolean(pending) || blocked" />我核对了摘要并理解可信发布者风险，仅验证暂存，不切换版本。</label><component :is="host.button" :disabled="busy || Boolean(pending) || blocked || !stageAcknowledged" @click="stage">确认仅暂存此摘要</component></template>
  </section>
  <section class="version-panel"><h4>选择服务端已验证版本</h4><label>插件 <select :value="pluginId" :disabled="busy || Boolean(pending) || blocked" @change="loadVersions(($event.target as HTMLSelectElement).value)"><option value="">请选择已安装插件</option><option v-for="plugin in plugins.filter(p=>p.state==='INSTALLED')" :key="plugin.pluginId" :value="plugin.pluginId">{{ plugin.pluginId }}</option></select></label>
   <component :is="host.button" :disabled="busy || Boolean(pending) || blocked || !pluginId" @click="loadVersions()">刷新实际版本</component>
   <template v-if="versions"><p>当前 {{ versions.current.version }} / {{ versions.current.state }} / 控制版本 {{ versions.revision }}</p><p class="version-digest">当前摘要：{{ versions.current.digest }}</p>
    <label>明确动作 <select :value="action" :disabled="busy || Boolean(pending) || blocked" @change="changeAction(($event.target as HTMLSelectElement).value as 'UPGRADE'|'ROLLBACK')"><option value="UPGRADE">升级软件版本</option><option value="ROLLBACK">回退软件版本（不回滚数据）</option></select></label>
    <label>确切目标 <select :value="targetDigest" :disabled="busy || Boolean(pending) || blocked" @change="changeTarget(($event.target as HTMLSelectElement).value)"><option value="">请选择验证版本与摘要</option><option v-for="version in versions.versions" :key="version.digest" :value="version.digest">{{ version.version }} · {{ version.digest }}</option></select></label>
    <component :is="host.button" :disabled="busy || Boolean(pending) || blocked || !targetDigest" @click="preview">仅预览版本切换影响</component>
   </template>
  </section>
  <section v-if="plan" class="version-panel" data-testid="version-plan"><h4>版本计划：{{ plan.action }} · {{ plan.pluginId }}</h4><p>从 {{ plan.fromVersion }} 到 {{ plan.targetVersion }}</p><p class="version-digest">原摘要：{{ plan.fromDigest }}<br />目标摘要：{{ plan.targetDigest }}<br />计划摘要：{{ plan.digest }}</p><p>预期控制版本 {{ plan.expectedRevision }}</p>
   <p>逐项确认受影响租户：</p><label v-for="tenant in plan.affectedTenants" :key="tenant"><input v-model="confirmedTenants" type="checkbox" :value="tenant" :disabled="busy || Boolean(pending)" />{{ tenant }}</label><p v-if="!plan.affectedTenants.length">服务端计划未列出租户。</p>
   <p>逐项确认依赖插件：</p><label v-for="plugin in plan.dependentPlugins" :key="plugin"><input v-model="confirmedDependants" type="checkbox" :value="plugin" :disabled="busy || Boolean(pending)" />{{ plugin }}</label><p v-if="!plan.dependentPlugins.length">服务端计划未列出依赖插件。</p>
   <label><input v-model="confirmedDowntime" type="checkbox" :disabled="busy || Boolean(pending)" />我确认确切目标摘要与受控停机；所有租户必须已禁用并排空，成功后仍保持禁用，不自动授权或启用。</label>
   <label><input v-model="confirmedDataRetention" type="checkbox" :disabled="busy || Boolean(pending)" />我理解这只切换软件版本，不回滚业务数据；结构不兼容或缺少存储适配会由服务端拒绝，不绕过检查。</label>
   <component :is="host.button" :disabled="busy || Boolean(pending) || blocked || !confirmedDowntime || !confirmedDataRetention || confirmedTenants.length!==plan.affectedTenants.length || confirmedDependants.length!==plan.dependentPlugins.length" @click="confirm">确认确切目标、停机与完整影响列表</component>
  </section>
  <section v-if="pending && !busy" class="version-panel" role="alert"><p>原版本请求的结果尚未确定。保留同一请求、同一摘要和幂等键；不要改选其他版本重复执行。</p><component :is="host.button" :disabled="blocked" @click="retry">显式重试原请求以取得持久回执</component><component :is="host.button" v-if="operation" @click="query">查询已知操作回执</component><component :is="host.button" @click="abandon">明确放弃本页请求信息</component><p>若首次响应丢失而尚无操作编号，同键重试用于取回原回执，不在浏览器猜测操作编号。</p></section>
  <section v-if="operation" class="version-panel" data-testid="version-operation"><h4>持久操作回执</h4><p>{{ operation.operationId }} · {{ operation.phase }} / {{ operation.outcome }}</p><p>{{ operation.fromVersion }} → {{ operation.targetVersion }}</p><p class="version-digest">{{ operation.targetDigest }}</p><p v-if="operation.phase!=='COMPLETE' || operation.outcome!=='APPLIED'">尚未确认完成，保持禁用并核对服务端状态。UNKNOWN不等于成功。</p><component :is="host.button" :disabled="busy || !pending" @click="query">查询实际回执</component></section>
 </section>
</template>
<style scoped>
.version-manager{display:grid;gap:16px}.version-panel{border:1px solid #aaa;padding:16px;display:grid;gap:10px}.version-panel label{display:block}.version-panel select{max-width:100%}.version-digest{font-family:monospace;overflow-wrap:anywhere}.version-manager [role=alert]{color:#b42318}
</style>
