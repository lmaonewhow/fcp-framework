import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import type { FoundationCapabilities } from './contracts';
import {
  commandName,
  type School,
  type Term,
  type Organization,
  type SchoolClass,
  type Entity,
  type EntityKind,
  type BusinessOperation
} from './business';
import { listSchools, listOrganizations, listTerms, listClasses, getEntity, writeEntity, lookupOperation, FoundationError } from './businessApi';
import { requiredText } from './scalars';
interface Pending {
  intentId: string;
  schoolId: string;
  kind: EntityKind;
  action: 'create' | 'rename';
  path: string;
  body: Record<string, unknown>;
}
export function useWorkspace(capabilities: FoundationCapabilities, storageKey: string) {
  const schools = ref<School[]>([]),
    terms = ref<Term[]>([]),
    organizations = ref<Organization[]>([]),
    classes = ref<SchoolClass[]>([]);
  const schoolId = ref(''),
    termId = ref(''),
    kind = ref<EntityKind>('organizations');
  const schoolCursor = ref<string | null>(null),
    termCursor = ref<string | null>(null),
    cursor = ref<string | null>(null);
  const authorityValid = ref(false);
  const busy = ref(false),
    saving = ref(false),
    error = ref(''),
    notice = ref('');
  const detailSchool = ref(''),
    detailKind = ref<EntityKind>('organizations');
  const canRename = computed(
    () =>
      Boolean(detail.value) &&
      detailSchool.value === schoolId.value &&
      detailSchool.value !== '' &&
      detailKind.value === kind.value &&
      allowed(kind.value === 'organizations' ? 'orgRename' : 'classRename')
  );
  const detail = ref<Entity | null>(null),
    formOpen = ref(false),
    editing = ref(false),
    rootSchool = ref(false);
  const pending = ref<Pending | null>(null),
    persistenceError = ref(false);
  const form = ref({ name: '', code: '', parentId: '', orgKind: 'DEPARTMENT', validFrom: '', gradeCode: '' });
  const selectedSchool = computed(() => schools.value.find((item) => item.id === schoolId.value));
  const selectedTerm = computed(() => terms.value.find((item) => item.id === termId.value));
  const rows = computed(() => (kind.value === 'organizations' ? organizations.value : classes.value));
  let generation = 0,
    controller: AbortController | undefined;
  function allowed(operation: BusinessOperation): boolean {
    return authorityValid.value && capabilities.operations[operation] === true && selectedSchool.value?.operations[operation] === true;
  }
  function clearSelection() {
    detail.value = null;
    formOpen.value = false;
    cursor.value = null;
    organizations.value = [];
    classes.value = [];
  }
  function invalidateIdentity() {
    authorityValid.value = false;
    cancelRead();
    schools.value = [];
    schoolId.value = '';
    terms.value = [];
    termId.value = '';
    clearSelection();
  }
  function cancelRead() {
    generation++;
    controller?.abort();
    controller = undefined;
    busy.value = false;
  }
  async function read(action: (signal: AbortSignal, current: () => boolean) => Promise<void>) {
    cancelRead();
    const current = generation;
    controller = new AbortController();
    busy.value = true;
    error.value = '';
    try {
      await action(controller.signal, () => current === generation);
    } catch {
      if (current === generation) {
        authorityValid.value = false;
        schools.value = [];
        schoolId.value = '';
        terms.value = [];
        termId.value = '';
        schoolCursor.value = null;
        termCursor.value = null;
        clearSelection();
        error.value = '读取失败或权限已变更，请重新检查。旧数据已关闭，不能据此提交。';
      }
    } finally {
      if (current === generation) busy.value = false;
    }
  }
  async function loadSchools(more = false) {
    if (saving.value) return;
    const after = more ? schoolCursor.value : null;
    if (!more) {
      authorityValid.value = false;
      schools.value = [];
      schoolId.value = '';
      termId.value = '';
      terms.value = [];
      clearSelection();
    }
    await read(async (signal, current) => {
      const result = await listSchools(signal, after);
      if (current()) {
        authorityValid.value = true;
        schools.value = more ? [...schools.value, ...result.items] : result.items;
        schoolCursor.value = result.nextAfterId;
      }
    });
  }
  async function chooseSchool() {
    if (saving.value) return;
    clearSelection();
    terms.value = [];
    termId.value = '';
    termCursor.value = null;
    await read(async (signal, current) => {
      if (!selectedSchool.value) return;
      if (allowed('termList')) {
        const result = await listTerms(schoolId.value, signal);
        if (!current()) return;
        terms.value = result.items;
        termCursor.value = result.nextAfterId;
      }
      if (kind.value === 'organizations' && allowed('orgList')) {
        const result = await listOrganizations(schoolId.value, signal);
        if (current()) {
          organizations.value = result.items;
          cursor.value = result.nextAfterId;
        }
      }
    });
  }
  async function moreTerms() {
    if (!termCursor.value || saving.value || !allowed('termList')) return;
    await read(async (signal, current) => {
      const result = await listTerms(schoolId.value, signal, termCursor.value);
      if (current()) {
        terms.value.push(...result.items);
        termCursor.value = result.nextAfterId;
      }
    });
  }
  async function loadRows(more = false) {
    if (saving.value) return;
    const after = more ? cursor.value : null;
    detail.value = null;
    formOpen.value = false;
    if (!more) {
      organizations.value = [];
      classes.value = [];
      cursor.value = null;
    }
    await read(async (signal, current) => {
      if (kind.value === 'organizations' && allowed('orgList')) {
        const result = await listOrganizations(schoolId.value, signal, after);
        if (current()) {
          organizations.value = more ? [...organizations.value, ...result.items] : result.items;
          cursor.value = result.nextAfterId;
        }
      } else if (kind.value === 'classes' && allowed('classList') && selectedTerm.value) {
        const result = await listClasses(schoolId.value, termId.value, signal, after);
        if (current()) {
          classes.value = more ? [...classes.value, ...result.items] : result.items;
          cursor.value = result.nextAfterId;
        }
      }
    });
  }
  async function showDetail(row: Entity) {
    if (saving.value || !allowed(kind.value === 'organizations' ? 'orgDetail' : 'classDetail')) return;
    detail.value = null;
    formOpen.value = false;
    await read(async (signal, current) => {
      const result = await getEntity(kind.value, row.id, signal);
      if (current()) {
        detail.value = result;
        detailSchool.value = schoolId.value;
        detailKind.value = kind.value;
      }
    });
  }
  function openForm(rename = false, newSchool = false) {
    if (!authorityValid.value || busy.value || saving.value || pending.value || persistenceError.value) return;
    if (
      newSchool
        ? !capabilities.operations.schoolCreate
        : !allowed(kind.value === 'organizations' ? (rename ? 'orgRename' : 'orgCreate') : rename ? 'classRename' : 'classCreate')
    )
      return;
    if (rename && !canRename.value) return;
    if (!rename && !newSchool && kind.value === 'classes' && !selectedTerm.value) return;
    editing.value = rename;
    rootSchool.value = newSchool;
    form.value = {
      name: rename ? detail.value!.name : '',
      code: '',
      parentId: schoolId.value,
      orgKind: newSchool ? 'SCHOOL' : 'DEPARTMENT',
      validFrom: '',
      gradeCode: ''
    };
    error.value = '';
    notice.value = '';
    formOpen.value = true;
  }
  function closeForm() {
    if (!saving.value) formOpen.value = false;
  }
  function savePending(value: Pending | null) {
    if (value) sessionStorage.setItem(storageKey, JSON.stringify(value));
    else sessionStorage.removeItem(storageKey);
    pending.value = value;
  }
  async function submit() {
    if (!authorityValid.value || saving.value || busy.value || pending.value || persistenceError.value || !formOpen.value) return;
    const targetKind = rootSchool.value ? 'organizations' : kind.value;
    if (
      rootSchool.value
        ? !capabilities.operations.schoolCreate
        : !allowed(targetKind === 'organizations' ? (editing.value ? 'orgRename' : 'orgCreate') : editing.value ? 'classRename' : 'classCreate')
    )
      return;
    let body: Record<string, unknown>, path: string;
    try {
      const name = commandName(form.value.name, targetKind);
      const intentId = crypto.randomUUID();
      if (editing.value) {
        if (!detail.value || !canRename.value) return;
        body = { intentId, name, expectedRowVersion: detail.value.rowVersion };
        path = `/${targetKind}/${detail.value.id}/rename`;
      } else if (targetKind === 'organizations') {
        const code = requiredText(form.value.code, 64),
          instant = form.value.validFrom;
        if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,6})?Z$/.test(instant)) throw new Error('请输入明确的UTC时刻，例如 2026-11-08T00:00:00Z。');
        if (!rootSchool.value && form.value.parentId !== schoolId.value && !organizations.value.some((item) => item.id === form.value.parentId))
          throw new Error('请选择当前学校的父组织。');
        body = {
          intentId,
          name,
          parentId: rootSchool.value ? null : form.value.parentId,
          orgCode: code,
          orgKind: rootSchool.value ? 'SCHOOL' : form.value.orgKind,
          validFrom: instant
        };
        path = rootSchool.value ? '/schools' : `/schools/${schoolId.value}/organizations`;
      } else {
        if (!selectedTerm.value) throw new Error('请选择当前学校的学期。');
        body = {
          intentId,
          name,
          termId: termId.value,
          classCode: requiredText(form.value.code, 64),
          gradeCode: requiredText(form.value.gradeCode, 40)
        };
        path = `/schools/${schoolId.value}/classes`;
      }
      try {
        savePending({
          intentId,
          path,
          kind: targetKind,
          action: editing.value ? 'rename' : 'create',
          body,
          schoolId: rootSchool.value ? '' : schoolId.value
        });
      } catch {
        persistenceError.value = true;
        throw new Error('无法保存操作核对标识，已阻止提交。请允许会话存储后重新进入页面。');
      }
    } catch (failure) {
      error.value = failure instanceof Error ? failure.message : '请检查表单。';
      return;
    }
    saving.value = true;
    error.value = '';
    notice.value = '';
    const mutation = new AbortController();
    controller = mutation;
    try {
      const receipt = await writeEntity(targetKind, path!, body!, mutation.signal);
      const result = receipt.record;
      detailSchool.value = rootSchool.value ? '' : schoolId.value;
      detailKind.value = targetKind;
      savePending(null);
      formOpen.value = false;
      detail.value = result;
      notice.value = receipt.awaitingActivation
        ? `学校已创建，等待部署许可后可管理基础资料。学校编号：${result.id}。`
        : `已保存。记录编号：${result.id}，版本：${result.rowVersion}。请刷新列表查看最新数据。`;
    } catch (failure) {
      if (failure instanceof FoundationError && !failure.uncertain) {
        savePending(null);
        formOpen.value = false;
        detail.value = null;
        if (failure.code.includes('FORBIDDEN') || failure.code === 'HTTP_403') invalidateIdentity();
        error.value =
          failure.code.includes('409') || failure.code.includes('CONFLICT')
            ? '记录版本或请求内容已冲突。请刷新并重新打开详情，核对后再修改。'
            : '服务器拒绝了操作。请重新检查权限、学校、学期及表单约束后再提交。';
      } else error.value = '提交结果未确认。请保留当前操作标识，先核对原操作结果；不要再次新建或修改。';
    } finally {
      saving.value = false;
      controller = undefined;
    }
  }
  async function checkPending() {
    if (!pending.value || busy.value || saving.value) return;
    const prior = pending.value;
    await read(async (signal, current) => {
      const result = await lookupOperation(prior.kind, prior.action, prior.schoolId, prior.intentId, signal);
      if (!current()) return;
      if (result) {
        detailSchool.value = prior.schoolId;
        detailKind.value = prior.kind;
        savePending(null);
        formOpen.value = false;
        detail.value = result;
        notice.value =
          prior.path === '/schools'
            ? `学校已创建，等待部署许可后可管理基础资料。学校编号：${result.id}。`
            : `原操作已提交。记录编号：${result.id}。请刷新列表查看。`;
      } else notice.value = '原操作结果仍未确认，不能视为未执行。请稍后再次核对或联系管理员，写入继续关闭。';
    });
  }
  onMounted(() => {
    try {
      const raw = sessionStorage.getItem(storageKey);
      if (raw) {
        const value = JSON.parse(raw);
        if (
          !value ||
          typeof value.intentId !== 'string' ||
          typeof value.path !== 'string' ||
          typeof value.schoolId !== 'string' ||
          !['organizations', 'classes'].includes(value.kind) ||
          !['create', 'rename'].includes(value.action) ||
          !value.body ||
          value.body.intentId !== value.intentId
        )
          throw new Error();
        pending.value = value;
      }
    } catch {
      persistenceError.value = true;
      error.value = '无法读取上次操作状态，写入保持关闭，请联系管理员核对。';
    }
    void loadSchools();
  });
  onBeforeUnmount(cancelRead);
  return {
    canRename,
    invalidateIdentity,
    authorityValid,
    schools,
    terms,
    organizations,
    classes,
    schoolId,
    termId,
    kind,
    schoolCursor,
    termCursor,
    cursor,
    busy,
    saving,
    error,
    notice,
    detail,
    formOpen,
    editing,
    rootSchool,
    pending,
    persistenceError,
    form,
    selectedSchool,
    selectedTerm,
    rows,
    allowed,
    loadSchools,
    chooseSchool,
    moreTerms,
    loadRows,
    showDetail,
    openForm,
    closeForm,
    submit,
    checkPending
  };
}
