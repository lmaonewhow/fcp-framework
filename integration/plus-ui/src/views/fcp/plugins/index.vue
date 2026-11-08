<script setup lang="ts">
import { watch } from 'vue';
import { responseError } from '@/fcp/trace';
import { ElButton, ElInput } from 'element-plus';
import PluginWorkspace from '@/fcp/PluginWorkspace.vue';
import type { NativePluginHost } from '@/fcp/usePlugins';
import request from '@/utils/request';
import { useUserStore } from '@/store/modules/user';
const user = useUserStore();
const identityKey = () => JSON.stringify([user.userId,user.tenantId,user.token]);
const principalKey = () => user.token && user.userId != null && user.tenantId != null ? JSON.stringify([String(user.tenantId),String(user.userId)]) : null;
const host: NativePluginHost = {
  shell:'admin',button:ElButton,input:ElInput,fieldValueProp:'modelValue',fieldUpdateProp:'onUpdate:modelValue',identityKey,principalKey,controlPermissions:()=>user.permissions,
  fieldAttributes: label => ({ariaLabel:label}),
  observeIdentity: changed => watch(identityKey,changed,{flush:'sync'}),
  observePermissions: changed => watch(() => JSON.stringify(user.permissions),changed,{flush:'sync'}),
  request: async (url,method,data,signal,options) => {
    try { return await request({url,method,data,signal,headers:{repeatSubmit:false,...options?.headers}}); }
    catch (failure) {
      const body = (failure as {response?:{data?:unknown}} | null)?.response?.data;
      if (body && typeof body === 'object') {
        const response = (failure as {response?:{headers?:unknown;data?:unknown}}).response;
        const message = (body as {msg?:string}).msg || '宿主请求失败';
        return {...body,msg:responseError(message,response).message};
      }
      throw failure;
    }
  }
};
</script>
<template><PluginWorkspace :host="host" /></template>
