import { schema } from '@/api/fcp/education/generated/roster.schema';
import { generatedOperation } from '@/fcp/education/generatedSchema';
import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref } from 'vue';
import * as api from '@/api/fcp/education/generated/roster.api';
import { detailOperation, listOperation, type Capabilities, type Kind, type ReadOperation, type Relation, type School } from './contracts';
export function useRoster() {
  const capabilities = ref<Capabilities | null>(null),
    schools = ref<School[]>([]),
    rows = ref<Relation[]>([]),
    detail = ref<Relation | null>(null);
  const schoolId = ref(''),
    kind = ref<Kind>('enrollments'),
    schoolCursor = ref<string | null>(null),
    cursor = ref<string | null>(null);
  const schoolsLoaded = ref(false);
  const busy = ref(false),
    loaded = ref(false),
    error = ref(''),
    notice = ref(''),
    identityValid = ref(true);
  const selectedSchool = computed(() => schools.value.find((item) => item.id === schoolId.value));
  let generation = 0,
    controller: AbortController | undefined;
  const allowed = (operation: ReadOperation) =>
    generatedOperation(schema, operation) &&
    identityValid.value &&
    capabilities.value?.status === 'READY' &&
    capabilities.value.operations[operation] === true &&
    selectedSchool.value?.readiness === 'READY' &&
    selectedSchool.value.operations[operation] === true;
  function clearRecords() {
    rows.value = [];
    detail.value = null;
    cursor.value = null;
    loaded.value = false;
  }
  function stop() {
    generation++;
    controller?.abort();
    controller = undefined;
    busy.value = false;
  }
  function clearAll() {
    clearRecords();
    schools.value = [];
    schoolsLoaded.value = false;
    schoolId.value = '';
    schoolCursor.value = null;
    capabilities.value = null;
  }
  function invalidateIdentity() {
    stop();
    clearAll();
    identityValid.value = false;
    error.value = '';
    notice.value = '';
  }
  function cancel() {
    stop();
    clearRecords();
    notice.value = '本次查询已取消，原记录已清除。';
  }
  async function run(task: (signal: AbortSignal, current: () => boolean) => Promise<void>) {
    const token = ++generation,
      pending = new AbortController();
    controller = pending;
    busy.value = true;
    error.value = '';
    notice.value = '';
    const current = () => generation === token && identityValid.value;
    try {
      await task(pending.signal, current);
    } catch {
      if (current()) {
        clearAll();
        error.value = '无法确认当前名册权限或读取结果，已清除学校、列表和详情。请重新检查；该错误不代表没有记录。';
      }
    } finally {
      if (current()) {
        busy.value = false;
        controller = undefined;
      }
    }
  }
  async function reload() {
    if (busy.value || !identityValid.value) return;
    clearAll();
    await run(async (signal, current) => {
      const caps = await api.getCapabilities(signal);
      if (!current()) return;
      capabilities.value = caps;
      if (caps.status !== 'READY' || !caps.operations.schoolList) return;
      const result = await api.listSchools(signal);
      if (current()) {
        schools.value = result.items;
        schoolsLoaded.value = true;
        schoolCursor.value = result.nextAfterId;
      }
    });
  }
  async function moreSchools() {
    if (busy.value || !identityValid.value || !schoolCursor.value || capabilities.value?.status !== 'READY') return;
    const after = schoolCursor.value;
    await run(async (signal, current) => {
      const result = await api.listSchools(signal, after);
      if (current()) {
        schools.value.push(...result.items);
        schoolCursor.value = result.nextAfterId;
      }
    });
  }
  async function loadRows(more = false) {
    if (busy.value || !allowed(listOperation[kind.value]) || (more && !cursor.value)) return;
    const sid = schoolId.value,
      tab = kind.value,
      after = more ? cursor.value : null;
    detail.value = null;
    if (!more) clearRecords();
    await run(async (signal, current) => {
      const result = await api.listRelations(sid, tab, signal, after);
      if (current() && sid === schoolId.value && tab === kind.value) {
        rows.value = more ? [...rows.value, ...result.items] : result.items;
        cursor.value = result.nextAfterId;
        loaded.value = true;
      }
    });
  }
  async function chooseSchool(value: string) {
    stop();
    clearRecords();
    error.value = '';
    notice.value = '';
    schoolId.value = schools.value.some((item) => item.id === value) ? value : '';
    await loadRows();
  }
  async function chooseKind(value: Kind) {
    stop();
    clearRecords();
    kind.value = value;
    await loadRows();
  }
  async function showDetail(row: Relation) {
    if (busy.value || !allowed(detailOperation[kind.value]) || !rows.value.some((item) => item.id === row.id)) return;
    detail.value = null;
    const sid = schoolId.value,
      tab = kind.value;
    await run(async (signal, current) => {
      const result = await api.getRelation(sid, tab, row.id, signal);
      if (current() && sid === schoolId.value && tab === kind.value) detail.value = result;
    });
  }
  function closeDetail() {
    if (busy.value) stop();
    detail.value = null;
  }
  onMounted(reload);
  onActivated(reload);
  onDeactivated(() => {
    stop();
    clearAll();
  });
  onBeforeUnmount(() => {
    stop();
    clearAll();
  });
  return {
    capabilities,
    schools,
    rows,
    detail,
    schoolId,
    kind,
    schoolCursor,
    cursor,
    busy,
    loaded,
    error,
    notice,
    identityValid,
    selectedSchool,
    schoolsLoaded,
    allowed,
    reload,
    moreSchools,
    loadRows,
    chooseSchool,
    chooseKind,
    showDetail,
    closeDetail,
    cancel,
    invalidateIdentity
  };
}
