<script setup lang="ts">
import Workspace from './generated/Workspace.vue';
import { operationLabels } from '@/fcp/education/contracts';
import { useFoundation } from '@/fcp/education/useFoundation';
const buildId = String(import.meta.env.VITE_FCP_BUILD_ID || '');
const { capabilities, busy, error, reload, cancel } = useFoundation();
const readinessLabels: Record<string, string> = {
  NOT_IMPLEMENTED: '尚未实现',
  NOT_RUN: '尚未验收',
  BLOCKED: '尚未具备开放条件'
};
const blockerLabels: Record<string, string> = {
  SCHOOL_AUTHORIZATION_NOT_IMPLEMENTED: '尚未接入逐学校管理授权。',
  REVOCATION_PROTOCOL_NOT_IMPLEMENTED: '尚未完成权限撤销与旧请求失效保护。',
  TRANSACTION_PROTOCOL_NOT_IMPLEMENTED: '尚未完成原子写入、审计及并发冲突保护。',
  NATIVE_ACCEPTANCE_NOT_RUN: '尚未完成真实身份、数据库与浏览器验收。'
};
const readinessLabel = (value: string) => readinessLabels[value] || '尚未确认';
</script>
<template>
  <main class="app-container education-foundation" data-testid="education-foundation" :data-build-id="buildId">
    <el-card shadow="never">
      <template #header><h1>智慧云校 · 基础资料</h1></template>
      <p>管理端组织、班级和学期基础资料。可用范围以服务器当前授权为准。</p>
      <div class="education-actions">
        <el-button data-testid="education-refresh" :disabled="busy" :loading="busy" @click="reload">重新检查</el-button>
        <el-button v-if="busy" data-testid="education-cancel" @click="cancel">取消检查</el-button>
      </div>
      <p v-if="busy" role="status">正在核对服务能力…</p>
      <el-alert v-else-if="error" data-testid="education-error" :title="error" type="error" :closable="false" show-icon />
      <Workspace v-else-if="capabilities?.status === 'READY'" :capabilities="capabilities" />
      <template v-else-if="capabilities">
        <el-alert title="基础资料尚未开放" type="warning" :closable="false" show-icon />
        <dl class="education-readiness">
          <dt>学校管理授权</dt>
          <dd>{{ readinessLabel(capabilities.readiness.schoolAuthorization) }}</dd>
          <dt>写入事务与并发保护</dt>
          <dd>{{ readinessLabel(capabilities.readiness.transactionProtocol) }}</dd>
          <dt>真实环境验收</dt>
          <dd>{{ readinessLabel(capabilities.readiness.nativeAcceptance) }}</dd>
        </dl>
        <h2>开放前需要解决</h2>
        <ul data-testid="education-blockers">
          <li v-for="(reason, index) in capabilities.blockers" :key="index">
            {{ blockerLabels[reason] || '服务返回了未识别的阻断原因，请联系管理员。' }}
          </li>
        </ul>
        <p v-if="!capabilities.blockers.length">服务未提供具体原因，请联系管理员确认。</p>
        <h2>当前操作范围</h2>
        <ul class="education-operations">
          <li v-for="(label, key) in operationLabels" :key="key">{{ label }}：未开放</li>
        </ul>
        <p>学期状态不允许自由修改；人员敏感资料、授权变更和删除操作不在此阶段开放。</p>
      </template>
      <p v-else role="status">尚未确认可用性。点击“重新检查”获取最新状态。</p>
    </el-card>
  </main>
</template>
<style scoped>
h1 {
  margin: 0;
  font-size: 20px;
}
h2 {
  font-size: 16px;
  margin-top: 24px;
}
p,
li,
dd,
dt {
  line-height: 1.8;
}
.education-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin: 16px 0;
}
.education-readiness {
  display: grid;
  grid-template-columns: minmax(130px, 1fr) 2fr;
  max-width: 600px;
  gap: 8px;
}
.education-readiness dd {
  margin: 0;
}
.education-operations {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 8px;
  padding-left: 20px;
}
</style>
