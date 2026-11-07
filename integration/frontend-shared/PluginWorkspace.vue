<script setup lang="ts">
import { computed, defineComponent, h, ref } from 'vue';
import type { VNode } from 'vue';
import type { UiNode } from './runtime';
import { usePlugins } from './usePlugins';
import PluginManager from './PluginManager.vue';
import type { NativePluginHost } from './usePlugins';
const props = defineProps<{host: NativePluginHost}>();
const workspace = usePlugins(props.host,renderNode);
const {identityView,catalog,error,notice,busy,fields,results,pending,retry,abandon,selected,isDeepLink,edit,invoke,refresh,navigate} = workspace;
const activeTab=ref<'pages'|'manage'>('pages');
const showManagement=computed(()=>['manage','install'].some(permission=>identityView.value.permissions.includes(permission)&&(props.host.controlPermissions?.()??[]).some(p=>p==='fcp:plugin:'+permission||p==='*:*:*')));
function renderNode(node: UiNode, id: string): VNode {
  if (node.type === 'stack') return h('div',{class:'fcp-plugin-stack'},(node.children ?? []).map(child => renderNode(child,id)));
  if (node.type === 'text') return h('p',node.text ?? '');
  if (node.type === 'field') return h('label',{class:'fcp-plugin-field'},[
    h('span',node.text ?? node.field ?? ''),
    h(props.host.input,{...props.host.fieldAttributes(node.text ?? node.field ?? ''),[props.host.fieldValueProp]:fields.value[id]?.[node.field ?? ''] ?? '',[props.host.fieldUpdateProp]:(value:string) => {if(node.field) edit(id,node.field,value);},disabled:busy.value || Boolean(pending.value)})
  ]);
  if (node.type === 'button') return h(props.host.button,{disabled:busy.value || Boolean(pending.value) || !node.capabilityId,onClick:() => {if(node.capabilityId) void invoke(id,node.capabilityId);}},() => node.text ?? '执行');
  // Results are rendered as literal text cells, never as markup, URLs or script.
  const value = results.value[id];
  const rows: [string,unknown][] = value && typeof value === 'object' ? Object.entries(value) : value === undefined ? [] : [['result',value]];
  return h('table',{class:'fcp-plugin-table'},[h('caption',node.text ?? '调用结果'),h('thead',h('tr',[h('th','字段'),h('th','值')])),h('tbody',rows.map(([key,item]) => h('tr',[h('td',key),h('td',typeof item === 'string' ? item : JSON.stringify(item))])))]);
}
const Configuration = defineComponent({props:{id:{type:String,required:true}},setup:p => () => workspace.render(p.id) as VNode});
</script>
<template>
  <main class="fcp-plugin-workspace" data-testid="fcp-plugin-workspace">
    <header><h1>配置插件工作台</h1><component :is="host.button" @click="refresh">刷新目录</component><span>目录版本 {{ catalog.revision }}</span></header>
    <p v-if="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <section v-if="pending && !busy" role="alert" data-testid="plugin-pending-action">
      <p>上次操作的结果尚未确认。重试将使用相同输入与幂等键；放弃不会撤销可能已完成的操作。</p>
      <component :is="host.button" @click="retry">重试同一请求</component>
      <component :is="host.button" @click="abandon">明确放弃重试信息</component>
    </section>
    <nav aria-label="工作台区域"><component :is="host.button" @click="activeTab='pages'">插件页面</component><component :is="host.button" v-if="showManagement" @click="activeTab='manage'">安装与权限管理</component></nav>
    <PluginManager v-if="showManagement" v-show="activeTab==='manage'" :host="host" :identity="identityView" @changed="refresh" />
    <div v-show="activeTab==='pages' || !showManagement">
    <nav aria-label="插件页面"><component :is="host.button" v-for="page in catalog.pages" :key="page.id" @click="navigate(page.path)">{{ page.title }}</component></nav>
    <section aria-label="工作台状态槽位" data-slot="dashboard.status"><Configuration v-for="slot in catalog.slots.filter(s => s.slotId === 'dashboard.status')" :id="slot.id" :key="slot.id" /></section>
    <section v-if="selected" :key="selected.id" data-testid="plugin-page"><h2>{{ selected.title }}</h2><Configuration :id="selected.id" /></section>
    <p v-else-if="isDeepLink" role="status">该插件页面不存在、已停用或当前身份无权访问。</p>
    <p v-else>请选择已授权的插件页面。插件启停后目录会自动更新。</p>
    </div>
  </main>
</template>
<style scoped>
.fcp-plugin-workspace{padding:24px;display:grid;gap:20px}.fcp-plugin-workspace header,.fcp-plugin-workspace nav{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.fcp-plugin-workspace h1{font-size:22px;margin:0}.fcp-plugin-workspace [role=alert]{color:#b42318}.fcp-plugin-workspace [role=status]{padding:12px;border:1px solid currentColor}.fcp-plugin-workspace :deep(.fcp-plugin-stack){display:grid;gap:12px}.fcp-plugin-workspace :deep(.fcp-plugin-field){display:grid;gap:6px;max-width:480px}.fcp-plugin-workspace :deep(.fcp-plugin-table){border-collapse:collapse;width:100%}.fcp-plugin-workspace :deep(td),.fcp-plugin-workspace :deep(th){padding:8px;border:1px solid #aaa;text-align:left;white-space:pre-wrap;overflow-wrap:anywhere}
</style>
