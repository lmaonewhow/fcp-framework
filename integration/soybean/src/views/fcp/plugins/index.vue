<script setup lang="ts">
import { watch } from 'vue';
import { NButton, NInput } from 'naive-ui';
import PluginWorkspace from '@/fcp/PluginWorkspace.vue';
import type { NativePluginHost } from '@/fcp/usePlugins';
import { request } from '@/service/request';
import { useAuthStore } from '@/store/modules/auth';
const auth = useAuthStore();
const identityKey = () => JSON.stringify([auth.token,auth.userInfo.user,auth.userInfo.permissions]);
const host: NativePluginHost = {
  shell:'teacher',button:NButton,input:NInput,fieldValueProp:'value',fieldUpdateProp:'onUpdate:value',identityKey,controlPermissions:()=>auth.userInfo.permissions,
  observeIdentity: changed => watch(identityKey,changed,{flush:'sync'}),
  request: (url,method,data,signal,options) => request({url,method,data,signal,headers:{repeatSubmit:false,...options?.headers}})
};
</script>
<template><PluginWorkspace :host="host" /></template>
