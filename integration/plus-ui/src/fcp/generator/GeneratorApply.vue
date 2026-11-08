<template>
  <el-dialog data-testid="generator-apply" v-model="visible" title="生成并应用到开发工作区" width="85%" top="5vh" append-to-body :before-close="close" :close-on-click-modal="false" :close-on-press-escape="!mutating" :show-close="!mutating">
    <el-alert type="info" :closable="false" title="仅写入固定开发工作区，不部署生产、不热加载。应用后仍需构建与发布；SQL 仅保存为待审文件，不执行。" />
    <p v-if="workspace">开发工作区：{{ workspace }}</p>
    <el-alert v-if="notice" :title="notice" :type="noticeType" :closable="false" show-icon />
    <el-tabs v-model="tab">
      <el-tab-pane label="变更预览" name="plan">
        <p>首次遇到已有文件且没有生成基线时会冲突；未手改可更新，生成未变则保留手改，双边变更会阻止整批应用。</p>
        <el-button data-testid="generator-refresh" :disabled="busy || !enabled" @click="preview">重新预览最新变更</el-button>
        <p v-if="plan">计划：{{ plan.planId }} · 有效期至：{{ plan.expiresAt }}</p>
        <el-alert v-for="(conflict, index) in plan?.conflicts || []" :key="index" type="error" :closable="false" :title="typeof conflict === 'string' ? conflict : `${conflict.path}：${conflict.reason}`" />
        <el-table data-testid="generator-plan-files" v-loading="busy && !mutating" :data="plan?.files || []" row-key="path" max-height="460">
          <el-table-column type="expand">
            <template #default="scope">
              <div class="generator-diff">
                <section><strong>当前内容（预览）</strong><pre>{{ scope.row.currentPreview ?? '无内容' }}</pre></section>
                <section><strong>生成内容（预览）</strong><pre>{{ scope.row.candidatePreview ?? '无内容' }}</pre></section>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="path" label="工作区相对路径" min-width="280" />
          <el-table-column label="状态" width="140"><template #default="scope">{{ fileLabel(scope.row.status) }}</template></el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane data-testid="generator-history" label="操作历史与撤销" name="history">
        <el-button :disabled="busy || !enabled" @click="refreshHistory">刷新历史</el-button>
        <el-table :data="history" empty-text="暂无已读取的操作记录" max-height="460">
          <el-table-column prop="operationId" label="操作编号" min-width="190" />
          <el-table-column prop="planId" label="计划编号" min-width="190" />
          <el-table-column prop="createdAt" label="时间" min-width="180" />
          <el-table-column label="状态" width="120"><template #default="scope">{{ operationLabel(scope.row.status) }}</template></el-table-column>
          <el-table-column label="操作" width="100"><template #default="scope"><el-button v-if="['applied', 'pending', 'rolling_back'].includes(scope.row.status)" :data-testid="`generator-rollback-${scope.row.operationId}`" link type="danger" :disabled="busy" @click="undo(scope.row)">{{ scope.row.status === 'applied' ? '撤销' : '恢复回滚' }}</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
    <template #footer>
      <el-button :disabled="mutating" @click="close()">{{ busy ? '取消预览并关闭' : '关闭' }}</el-button>
      <el-button data-testid="generator-apply-confirm" v-if="tab === 'plan'" type="primary" :disabled="busy || !canApply" :loading="mutating" @click="apply">确认应用</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue';
import { ElMessageBox } from 'element-plus';
import * as api from './api';
import type { FileStatus, GeneratorOperation, GeneratorPlan } from './types';

const visible = ref(false);
const enabled = ref(false);
const workspace = ref('');
const busy = ref(false);
const mutating = ref(false);
const tab = ref('plan');
const notice = ref('');
const noticeType = ref<'info' | 'warning' | 'error' | 'success'>('info');
const plan = ref<GeneratorPlan>();
const history = ref<GeneratorOperation[]>([]);
let selected: string[] = [];
let generation = 0;
let controller: AbortController | undefined;
const canApply = computed(() => enabled.value && plan.value?.applicable && !plan.value.conflicts.length && !plan.value.files.some((file) => file.status === 'conflict'));
const fileLabel = (status: FileStatus) => ({ create: '新增', update: '更新', unchanged: '无变化', preserve_manual: '保留手改', conflict: '冲突' })[status] || status;
const operationLabel = (status: string) => ({ applied: '已应用', noop: '无需变更', rolled_back: '已撤销', pending: '应用待恢复', rolling_back: '回滚待恢复' })[status] || status;
function report(message: string, type: typeof noticeType.value = 'warning') { notice.value = message; noticeType.value = type; }
function cancelRead() { generation++; controller?.abort(); controller = undefined; }
function close(done?: () => void) { if (mutating.value) return; cancelRead(); busy.value = false; visible.value = false; done?.(); }
function errorText(error: unknown) { return error instanceof Error ? error.message : '请求未完成，请检查权限、连接和服务配置。'; }

async function show(tableIds: Array<string | number>) {
  if (busy.value || visible.value || !tableIds.length) return;
  cancelRead(); selected = tableIds.map(String); plan.value = undefined; history.value = [];
  enabled.value = false; workspace.value = ''; tab.value = 'plan'; notice.value = ''; visible.value = true; busy.value = true;
  const token = generation; controller = new AbortController();
  try {
    const config = await api.getConfig(controller.signal);
    if (token !== generation) return;
    enabled.value = config.enabled; workspace.value = config.workspace;
    if (!config.enabled) report('此功能尚未启用，请部署者配置固定开发目录并启用后使用；仍可关闭窗口下载 ZIP。');
  } catch {
    if (token === generation) report('无法读取开发工作区配置。功能可能尚未启用，请部署者检查配置与访问权限；仍可下载 ZIP。');
  } finally { if (token === generation) busy.value = false; }
  if (token === generation && enabled.value) await preview();
}
async function preview() {
  if (busy.value || !enabled.value) return;
  busy.value = true; plan.value = undefined; notice.value = ''; tab.value = 'plan';
  const token = generation; controller = new AbortController();
  try {
    const result = await api.createPlan(selected, controller.signal);
    if (token === generation) { plan.value = result; if (!result.applicable) report('此计划不可应用，请先处理冲突或配置问题，再重新预览。'); }
  } catch (error) { if (token === generation) report(errorText(error), 'error'); }
  finally { if (token === generation) busy.value = false; }
}
async function loadHistory(token: number) {
  const result = await api.getOperations(controller?.signal);
  if (token === generation) {
    history.value = result.operations;
    if (result.recoveryRequired) { plan.value = undefined; report('检测到未完成的写入，请先对待恢复记录执行恢复回滚，再重新预览。'); }
  }
}
async function refreshHistory() {
  if (busy.value || !enabled.value) return;
  busy.value = true; const token = generation; controller = new AbortController();
  try { await loadHistory(token); } catch (error) { if (token === generation) report(errorText(error), 'error'); }
  finally { if (token === generation) busy.value = false; }
}
async function mutate(kind: 'apply' | 'rollback', id: string) {
  if (busy.value) return;
  busy.value = true; mutating.value = true;
  const token = generation;
  try {
    await ElMessageBox.confirm(kind === 'apply' ? '确认将此预览写入固定开发工作区？已有文件将按生成基线校验；不执行 SQL、不发布。' : '确认撤销该操作？仅恢复仍与该次应用一致的文件；后续手改会阻止撤销。', kind === 'apply' ? '确认应用' : '确认撤销', { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' });
  } catch { busy.value = false; mutating.value = false; return; }
  if (token !== generation || !visible.value) { busy.value = false; mutating.value = false; return; }
  plan.value = undefined;
  try {
    const result = kind === 'apply' ? await api.applyPlan(id) : await api.rollback(id);
    if (token === generation) report(`${operationLabel(result.status)}。请在操作历史中核对；后续变更需重新预览。`, 'success');
  } catch (error) {
    if (token === generation) report(`操作未确认：${errorText(error)} 请先核对历史和工作区，不要直接重试；旧计划已停用。`, 'error');
  } finally {
    if (token === generation) {
      tab.value = 'history';
      try { await loadHistory(token); } catch { report(`${notice.value} 历史读取失败，请稍后刷新核对。`, 'warning'); }
      busy.value = false; mutating.value = false;
    }
  }
}
const apply = () => { if (canApply.value && plan.value) return mutate('apply', plan.value.planId); };
const undo = (operation: GeneratorOperation) => mutate('rollback', operation.operationId);
onBeforeUnmount(cancelRead);
defineExpose({ show });
</script>

<style scoped>
.generator-diff { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; padding: 16px; }
.generator-diff pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 360px; overflow: auto; font-size: 12px; }
@media (max-width: 700px) { .generator-diff { grid-template-columns: 1fr; } }
</style>
