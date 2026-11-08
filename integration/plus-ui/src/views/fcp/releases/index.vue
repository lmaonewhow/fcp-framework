<script setup lang="ts">
import { watch } from 'vue';
import ReleaseConsole from '@/fcp/release/ReleaseConsole.vue';
import type { ReleaseHost } from '@/fcp/release/controller';
import request from '@/utils/request';
import { responseError } from '@/fcp/trace';
import { useUserStore } from '@/store/modules/user';
const user = useUserStore();
const identityKey = () => JSON.stringify([user.userId,user.tenantId,user.token]);
const host: ReleaseHost = {
  shell:'admin',identityKey,permissions:()=>user.permissions,
  observeIdentity: changed => watch(identityKey,changed,{flush:'sync'}),
  observePermissions: changed => watch(() => JSON.stringify(user.permissions),changed,{flush:'sync'}),
  request: async (url,method,data,signal) => {
    try { return await request({url,method,data,signal,headers:{repeatSubmit:false}}); }
    catch (error) {
      const body = (error as {response?:{data?:unknown}} | null)?.response?.data;
      if (body && typeof body === 'object') {
        const response = (error as {response?:{headers?:unknown;data?:unknown}}).response;
        const envelope = body as {msg?:unknown};
        return {...body, msg: responseError(typeof envelope.msg === 'string' ? envelope.msg : '请求失败', response).message};
      }
      throw error;
    }
  }
};
</script>
<template><ReleaseConsole :host="host" /></template>
