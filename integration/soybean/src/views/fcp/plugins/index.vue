<script setup lang="ts">
import { watch } from 'vue';
import { NButton, NInput } from 'naive-ui';
import PluginWorkspace from '@/fcp/PluginWorkspace.vue';
import type { NativePluginHost } from '@/fcp/usePlugins';
import { request } from '@/service/request';
import { useAuthStore } from '@/store/modules/auth';
const auth = useAuthStore();
const identityKey = () => JSON.stringify([auth.token,auth.userInfo.user?.userId,auth.userInfo.user?.tenantId]);
const principalKey = () => auth.token && auth.userInfo.user?.userId != null && auth.userInfo.user?.tenantId != null ? JSON.stringify([String(auth.userInfo.user.tenantId),String(auth.userInfo.user.userId)]) : null;
const host: NativePluginHost = {
  shell:'teacher',button:NButton,input:NInput,fieldValueProp:'value',fieldUpdateProp:'onUpdate:value',identityKey,principalKey,controlPermissions:()=>auth.userInfo.permissions,
  fieldAttributes: label => ({inputProps:{'aria-label':label}}),
  observeIdentity: changed => watch(identityKey,changed,{flush:'sync'}),
  observePermissions: changed => watch(() => JSON.stringify(auth.userInfo.permissions),changed,{flush:'sync'}),
  request: (url,method,data,signal,options) => request({url,method,data,signal,headers:{repeatSubmit:false,...options?.headers}})
};
</script>
<template><PluginWorkspace :host="host" /></template>
