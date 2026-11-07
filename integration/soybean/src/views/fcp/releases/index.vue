<script setup lang="ts">
import { watch } from 'vue';
import ReleaseConsole from '@/fcp/release/ReleaseConsole.vue';
import type { ReleaseHost } from '@/fcp/release/controller';
import { request } from '@/service/request';
import { useAuthStore } from '@/store/modules/auth';
const auth = useAuthStore();
const identityKey = () => JSON.stringify([auth.token,auth.userInfo.user?.userId,auth.userInfo.user?.tenantId]);
const host: ReleaseHost = {
  shell:'teacher',identityKey,permissions:()=>auth.userInfo.permissions,
  observeIdentity: changed => watch(identityKey,changed,{flush:'sync'}),
  observePermissions: changed => watch(() => JSON.stringify(auth.userInfo.permissions),changed,{flush:'sync'}),
  request: (url,method,data,signal) => request({url,method,data,signal,headers:{repeatSubmit:false}})
};
</script>
<template><ReleaseConsole :host="host" /></template>
