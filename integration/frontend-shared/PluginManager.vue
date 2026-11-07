<script setup lang="ts">
import { ref } from 'vue';
import VersionManager from './VersionManager.vue';
import type { Identity } from './runtime';
import type { NativePluginHost } from './usePlugins';
import { usePluginManager } from './usePluginManager';
const props=defineProps<{host:NativePluginHost;identity:Identity}>();
const emit=defineEmits<{changed:[]}>();
const versionOpen=ref(false);
const manager=usePluginManager(props.host,()=>props.identity,()=>emit('changed'));
const {canManage,canInstall,plugins,revision,busy,error,notice,upload,trustAcknowledged,plan,approvedIds,operation,cancelAcknowledged,pending,uninstallCandidate,uninstallAcknowledged,previewUninstall,uninstall,cancelUninstall,checkUninstall,grantPlugin,grantCapability,grantScope,grantAllow,targetTenant,grants,grantOptions,grantAcknowledged,loadGrantOptions,setGrantTarget,addGrant,removeGrant,previewGrants,refresh,selectFile,preview,install,confirm,checkOperation,decide,retry,abandon,cancelPlan}=manager;
function fileChanged(event:Event){const input=event.target as HTMLInputElement;void selectFile(input.files?.[0]??null);input.value='';}
</script>
<template>
 <section class="fcp-plugin-manager" data-testid="plugin-manager">
  <header><h2>插件管理</h2><component :is="host.button" :disabled="busy" @click="refresh">刷新管理状态</component><span>控制版本 {{ revision }}</span></header>
  <component :is="host.button" v-if="canInstall" @click="versionOpen=!versionOpen">{{ versionOpen ? '收起版本管理' : '显式升级与版本回退' }}</component>
  <VersionManager v-if="canInstall" v-show="versionOpen" :host="host" :identity="identity" :plugins="plugins" :blocked="busy || Boolean(pending)" @changed="refresh();emit('changed')" />
  <p>安装、平台授权、租户启用是独立操作。管理接口会按当前真实身份重新鉴权，不会因页面可见而授权。</p>
  <p v-if="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
  <section v-if="pending && !busy" role="alert"><p>管理操作结果尚未确认。仅显式重试原请求；放弃重试信息不会撤销已完成的操作。</p><p v-if="pending.kind==='decision'">决策接口没有幂等回执，请先查询实际状态，不要直接重复发送。</p><component :is="host.button" :disabled="['decision','uninstall'].includes(pending.kind)" @click="retry">重试同一管理请求</component><template v-if="pending.kind==='uninstall'"><p>卸载结果不明时先查询实际安装状态，不直接重试。</p><component :is="host.button" @click="checkUninstall">核对实际卸载状态</component></template><component :is="host.button" @click="abandon">明确放弃重试信息</component></section>
  <section v-if="canInstall" class="manager-panel" data-testid="plugin-install">
   <h3>安装签名软件包</h3><input type="file" accept=".zip,application/zip" :disabled="busy || Boolean(pending)" aria-label="选择签名插件ZIP包" @change="fileChanged" />
   <template v-if="upload"><p>文件：{{ upload.name }} · {{ upload.size }} 字节</p><p class="digest">SHA-256：{{ upload.digest }}</p>
    <p>仅允许平台已信任发布者签名的软件包。摘要用于核对文件，不等于发布者已获信任；服务端将验证签名、发布者与包内容。插件运行属于可信代码执行，不提供恶意代码沙箱。</p>
    <label><input v-model="trustAcknowledged" type="checkbox" :disabled="busy || Boolean(pending)" />我已核对文件摘要，理解可信发布者风险，并确认仅安装，不自动授权或启用。</label>
    <component :is="host.button" :disabled="busy || Boolean(pending) || !trustAcknowledged" @click="install">确认安装此摘要的软件包</component>
   </template>
  </section>
  <table class="manager-table"><caption>已安装插件与当前租户状态</caption><thead><tr><th>插件 / 版本</th><th>安装</th><th>进程</th><th>期望 / 实际</th><th v-if="canManage || canInstall">独立管理动作</th></tr></thead><tbody><tr v-for="plugin in plugins" :key="plugin.pluginId"><td>{{ plugin.pluginId }} / {{ plugin.version }}<small class="digest">{{ plugin.digest }}</small></td><td>{{ plugin.installation }}</td><td>{{ plugin.deployment }}</td><td>{{ plugin.desired }} / {{ plugin.effective }}</td><td v-if="canManage || canInstall"><component :is="host.button" v-if="canManage" :disabled="busy || Boolean(pending)" @click="preview(plugin,'enable')">预览启用影响</component><component :is="host.button" v-if="canManage" :disabled="busy || Boolean(pending)" @click="preview(plugin,'disable')">预览停用影响</component><component :is="host.button" v-if="canInstall" :disabled="busy || Boolean(pending)" @click="loadGrantOptions(plugin.pluginId)">加载平台授权选项</component><component :is="host.button" v-if="canInstall && plugin.state==='INSTALLED'" :disabled="busy || Boolean(pending)" @click="previewUninstall(plugin)">预览保留数据卸载</component></td></tr></tbody></table>
  <section v-if="uninstallCandidate" class="manager-panel" data-testid="plugin-uninstall"><h3>明确确认卸载：{{ uninstallCandidate.pluginId }} / {{ uninstallCandidate.version }}</h3><p class="digest">确认软件包摘要：{{ uninstallCandidate.digest }}</p><p>仅在所有租户均已禁用并排空调用后，服务端才允许卸载。本操作不会自动级联停用，不删除业务数据，也不等于已经清理数据或备份。</p><label><input v-model="uninstallAcknowledged" type="checkbox" :disabled="busy || Boolean(pending)" />我确认卸载上述确切软件包，理解业务数据保留且所有租户必须先禁用并排空。</label><component :is="host.button" :disabled="busy || Boolean(pending) || !uninstallAcknowledged" @click="uninstall">确认保留数据卸载</component><component :is="host.button" :disabled="busy || Boolean(pending)" @click="cancelUninstall">取消卸载预览</component></section>
  <section v-if="canInstall" class="manager-panel" data-testid="plugin-grants">
   <h3>独立平台授权</h3><p>先从插件行加载包声明的能力，再明确数据范围。授权或撤销均需预览和确认，不会自动启用插件。</p>
   <label>目标租户 <input :value="targetTenant" :placeholder="identity.tenantId || ''" :disabled="busy || Boolean(pending) || identity.tenantId!=='000000'" @input="setGrantTarget(($event.target as HTMLInputElement).value)" /></label>
   <component :is="host.button" v-if="grantPlugin" :disabled="busy || Boolean(pending)" @click="loadGrantOptions()">重新加载 {{ grantPlugin }} 的目标租户授权</component>
   <template v-if="grantOptions"><p>授权目标：{{ grantPlugin }} / 租户 {{ grantOptions.targetTenant }} / 控制版本 {{ grantOptions.revision }}</p>
    <table class="manager-table"><caption>现有平台授权</caption><thead><tr><th>能力 / 操作</th><th>宿主权限</th><th>数据范围</th><th>状态</th></tr></thead><tbody><tr v-for="(grant,index) in grantOptions.currentGrants" :key="index"><td>{{ grant.capabilityId }} / {{ grant.operation }}</td><td>{{ grant.permission }}</td><td>{{ grant.dataScope }}</td><td>{{ grant.allow ? '已授权' : '已撤销' }}</td></tr></tbody></table>
    <label>包声明能力 <select v-model="grantCapability" :disabled="busy || Boolean(pending)"><option value="">请选择能力</option><option v-for="option in grantOptions.capabilities" :key="option.capabilityId" :value="option.capabilityId">{{ option.capabilityId }} · {{ option.permission }}{{ option.sideEffects ? ' · 有副作用' : '' }}</option></select></label>
    <label>明确数据范围 <input v-model="grantScope" :disabled="busy || Boolean(pending)" :placeholder="'tenant:'+grantOptions.targetTenant+' 或 class:班级ID'" /></label>
    <label>变更类型 <select v-model="grantAllow" :disabled="busy || Boolean(pending)"><option :value="true">授权所选能力和范围</option><option :value="false">撤销所选能力和范围</option></select></label>
    <component :is="host.button" :disabled="busy || Boolean(pending)" @click="addGrant">加入明确变更列表</component>
    <ul><li v-for="(grant,index) in grants" :key="index">{{ grant.allow ? '授权' : '撤销' }} {{ grant.capabilityId }} · {{ grant.dataScope }} <component :is="host.button" :disabled="busy || Boolean(pending)" @click="removeGrant(index)">移除此项</component></li></ul>
    <component :is="host.button" :disabled="busy || Boolean(pending) || !grants.length" @click="previewGrants">仅预览授权变更，不立即授权</component>
   </template>
  </section>
  <section v-if="plan" class="manager-panel" data-testid="plugin-plan">
   <h3>确认计划：{{ plan.action }} · {{ plan.pluginId }}</h3><p>预期控制版本 {{ plan.expectedRevision }}；计划摘要 <span class="digest">{{ plan.digest }}</span></p>
   <template v-if="plan.action==='GRANT'"><p>目标租户：{{ plan.targetTenant }}</p><table class="manager-table"><caption>服务端生成的确切授权变更</caption><thead><tr><th>动作</th><th>能力 / 操作</th><th>宿主权限</th><th>数据范围</th></tr></thead><tbody><tr v-for="(change,index) in plan.changes" :key="index"><td>{{ change.allow ? '授权' : '撤销' }}</td><td>{{ change.capabilityId }} / {{ change.operation }}</td><td>{{ change.permission }}</td><td>{{ change.dataScope }}</td></tr></tbody></table><label><input v-model="grantAcknowledged" type="checkbox" :disabled="busy || Boolean(pending)" />我确认上述目标租户、每项权限、操作与数据范围；这一步不启用插件。</label></template>
   <p>以下依赖或被依赖插件均受影响。请逐项确认完整列表；目录变化或计划过期后必须重新预览。</p>
   <label v-for="id in plan.affectedPlugins" :key="id"><input v-model="approvedIds" type="checkbox" :value="id" :disabled="busy || Boolean(pending)" />{{ id }}</label>
   <component :is="host.button" :disabled="busy || Boolean(pending) || approvedIds.length!==plan.affectedPlugins.length || (plan.action==='GRANT' && !grantAcknowledged)" @click="confirm">确认完整影响列表并执行</component><component :is="host.button" :disabled="busy || Boolean(pending)" @click="cancelPlan">取消此预览</component>
  </section>
  <section v-if="operation" class="manager-panel" data-testid="plugin-operation"><h3>服务端操作状态</h3><p>操作 {{ operation.operationId }} · 阶段 {{ operation.phase }} · 结果 {{ operation.outcome }}</p><p>影响插件：{{ operation.plugins.join('、') }}</p><component :is="host.button" :disabled="busy" @click="checkOperation">查询实际操作状态</component>
   <template v-if="canManage && operation.phase==='DRAINING'"><p>停用正在等待活动调用结束。继续等待或请求取消调用都由服务端记录决策，取消调用不回滚已经提交的事务。</p><component :is="host.button" :disabled="busy || Boolean(pending)" @click="decide('wait')">明确继续等待</component><label><input v-model="cancelAcknowledged" type="checkbox" :disabled="busy || Boolean(pending)" />我确认取消仍在执行的调用，继续停用；已提交事务不会因此回滚。</label><component :is="host.button" :disabled="busy || Boolean(pending) || !cancelAcknowledged" @click="decide('cancel')">确认取消活动调用</component></template>
  </section>
 </section>
</template>
<style scoped>
.fcp-plugin-manager{display:grid;gap:18px}.fcp-plugin-manager header{display:flex;gap:12px;align-items:center;flex-wrap:wrap}.manager-panel{border:1px solid #aaa;padding:16px;display:grid;gap:12px}.manager-panel label{display:block}.manager-table{width:100%;border-collapse:collapse}.manager-table td,.manager-table th{text-align:left;border:1px solid #aaa;padding:10px}.digest{overflow-wrap:anywhere;font-family:monospace}small.digest{display:block;max-width:360px}.fcp-plugin-manager [role=alert]{color:#b42318}
</style>
