import { onBeforeUnmount, onMounted, ref } from 'vue';
import { getFoundationCapabilities } from './api';
import type { FoundationCapabilities } from './contracts';
export function useFoundation() {
  const capabilities = ref<FoundationCapabilities | null>(null);
  const busy = ref(false);
  const error = ref('');
  let controller: AbortController | undefined;
  let generation = 0;
  function cancel() {
    generation++;
    controller?.abort();
    controller = undefined;
    busy.value = false;
    capabilities.value = null;
  }
  async function reload() {
    if (busy.value) return;
    const current = ++generation;
    const pending = new AbortController();
    controller = pending;
    busy.value = true;
    capabilities.value = null;
    error.value = '';
    try {
      const result = await getFoundationCapabilities(pending.signal);
      if (current === generation) capabilities.value = result;
    } catch {
      // Never render raw server exceptions, credentials, or stale successful grants.
      if (current === generation) error.value = '无法确认基础资料访问权限或服务可用性，所有操作保持关闭。请确认已登录并具有访问权限后重试。';
    } finally {
      if (current === generation) {
        busy.value = false;
        controller = undefined;
      }
    }
  }
  onMounted(reload);
  onBeforeUnmount(cancel);
  return { capabilities, busy, error, reload, cancel };
}
