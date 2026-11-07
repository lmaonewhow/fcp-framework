<script setup lang="ts">
import { watch } from 'vue';
import { ElButton, ElInput } from 'element-plus';
import PluginWorkspace from '@/fcp/PluginWorkspace.vue';
import type { NativePluginHost } from '@/fcp/usePlugins';
import request from '@/utils/request';
import { useUserStore } from '@/store/modules/user';
const user = useUserStore();
const identityKey = () => JSON.stringify([user.userId,user.tenantId,user.token,user.permissions]);
const host: NativePluginHost = {
  shell:'admin',button:ElButton,input:ElInput,fieldValueProp:'modelValue',fieldUpdateProp:'onUpdate:modelValue',identityKey,controlPermissions:()=>user.permissions,
  fieldAttributes: label => ({ariaLabel:label}),
  observeIdentity: changed => watch(identityKey,changed,{flush:'sync'}),
  request: async (url,method,data,signal,options) => {
    try { return await request({url,method,data,signal,headers:{repeatSubmit:false,...options?.headers}}); }
    catch (failure) {
      const body = (failure as {response?:{data?:unknown}} | null)?.response?.data;
      if (body) return body;
      throw failure;
    }
  }
};
</script>
<template><PluginWorkspace :host="host" /></template>
