<script setup lang="ts">
import { ref, watch } from 'vue';
import { useUserStore } from '@/store/modules/user';
import type { FoundationCapabilities } from './contracts';
import { useWorkspace } from './useWorkspace';
const props = defineProps<{ capabilities: FoundationCapabilities }>();
const user = useUserStore();
// Persistence is scoped to the native identity, never used as authorization.
const key = 'education-foundation-pending:' + JSON.stringify([user.tenantId, user.userId]);
const {
  canRename,
  invalidateIdentity,
  authorityValid,
  schools,
  terms,
  organizations,
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
} = useWorkspace(props.capabilities, key);
const identityValid = ref(true);
watch(
  () => 'education-foundation-pending:' + JSON.stringify([user.tenantId, user.userId]),
  (value) => {
    if (value !== key) {
      identityValid.value = false;
      invalidateIdentity();
    }
  },
  { flush: 'sync' }
);
const detailLabels: Record<string, string> = {
  id: '编号',
  name: '名称',
  parentId: '父组织编号',
  schoolId: '学校编号',
  termId: '学期编号',
  orgCode: '稳定编码',
  classCode: '稳定编码',
  orgKind: '组织类型',
  gradeCode: '年级编码',
  validFrom: '生效时刻（UTC）',
  validTo: '失效时刻（UTC）',
  createdAt: '创建时刻（UTC）',
  rowVersion: '当前版本'
};
</script>
<template>
  <el-alert v-if="!identityValid" title="登录身份已变化，请重新进入基础资料页面。" type="warning" :closable="false" />
  <section v-else data-testid="education-workspace">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="notice" :title="notice" type="info" :closable="false" />
    <el-alert v-if="persistenceError" title="无法读取或保存会话操作状态，写入已关闭，请联系管理员核对。" type="error" :closable="false" />
    <el-card v-if="pending" data-testid="education-pending" shadow="never">
      <p>上次提交结果尚未确认。不要重复提交；刷新或离开后，此操作标识仍会保留。</p>
      <p>操作标识：{{ pending.intentId }}</p>
      <el-button :disabled="busy || saving" @click="checkPending">核对原操作结果</el-button>
    </el-card>
    <el-space wrap class="toolbar">
      <el-button :disabled="busy || saving" @click="loadSchools(false)">刷新学校和权限</el-button>
      <el-button
        v-if="capabilities.operations.schoolCreate"
        :disabled="!authorityValid || busy || saving || !!pending || persistenceError"
        @click="openForm(false, true)"
        >新建学校</el-button
      >
    </el-space>
    <el-form label-position="top" class="selectors">
      <el-form-item label="授权学校">
        <el-select v-model="schoolId" data-testid="education-school" :disabled="busy || saving" placeholder="请选择学校" @change="chooseSchool">
          <el-option v-for="item in schools" :key="item.id" :label="`${item.name}（${item.id}）`" :value="item.id" />
        </el-select>
      </el-form-item>
      <el-button v-if="schoolCursor" :disabled="busy || saving" @click="loadSchools(true)">加载更多学校</el-button>
    </el-form>
    <p v-if="!busy && !schools.length">当前没有可访问的学校。学校列表由服务器按当前授权返回。</p>
    <template v-if="selectedSchool">
      <el-alert
        v-if="selectedSchool.readiness !== 'READY'"
        :title="
          selectedSchool.readiness === 'EXECUTION_GRANT_MISSING'
            ? '学校已创建，等待部署许可后可管理基础资料。'
            : '学校可用性未知，基础资料操作保持关闭。'
        "
        type="warning"
        :closable="false"
      />
      <el-radio-group v-model="kind" :disabled="saving || busy" @change="loadRows(false)">
        <el-radio-button value="organizations">组织</el-radio-button><el-radio-button value="classes">班级</el-radio-button>
      </el-radio-group>
      <div v-if="allowed('termList')" class="terms">
        <el-form-item label="当前学校学期">
          <el-select
            v-model="termId"
            data-testid="education-term"
            :disabled="busy || saving"
            placeholder="请选择学期"
            @change="kind === 'classes' && loadRows(false)"
          >
            <el-option
              v-for="item in terms"
              :key="item.id"
              :label="`${item.termCode} · ${item.status}（${item.startsOn} 至 ${item.endsOn}）`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-button v-if="termCursor" :disabled="busy || saving" @click="moreTerms">加载更多学期</el-button>
        <p v-if="selectedTerm">
          学期状态：{{ selectedTerm.status }}。业务日期：{{ selectedTerm.startsOn }} 至 {{ selectedTerm.endsOn }}，含结束当天；状态只读。
        </p>
      </div>
      <p v-else>当前未开放该学校的学期查询。</p>
      <el-space wrap class="toolbar">
        <el-button
          v-if="allowed(kind === 'organizations' ? 'orgList' : 'classList')"
          :disabled="busy || saving || (kind === 'classes' && !selectedTerm)"
          @click="loadRows(false)"
          >刷新列表</el-button
        >
        <el-button
          v-if="allowed(kind === 'organizations' ? 'orgCreate' : 'classCreate')"
          :disabled="!authorityValid || busy || saving || !!pending || persistenceError || (kind === 'classes' && !selectedTerm)"
          @click="openForm()"
          >{{ kind === 'organizations' ? '新建组织' : '新建班级' }}</el-button
        >
      </el-space>
      <p v-if="!allowed(kind === 'organizations' ? 'orgList' : 'classList')">当前学校未开放此项查询。</p>
      <p v-else-if="kind === 'classes' && !selectedTerm">请选择学校内学期后查询班级。</p>
      <el-table v-else :data="rows" row-key="id" v-loading="busy" data-testid="education-records">
        <el-table-column prop="id" label="编号" min-width="190" />
        <el-table-column prop="name" label="名称" min-width="150" />
        <el-table-column :prop="kind === 'organizations' ? 'orgCode' : 'classCode'" label="稳定编码" min-width="130" />
        <el-table-column prop="rowVersion" label="版本" min-width="90" />
        <el-table-column v-if="allowed(kind === 'organizations' ? 'orgDetail' : 'classDetail')" label="详情" width="100">
          <template #default="scope"><el-button link :disabled="busy || saving" @click="showDetail(scope.row)">查看</el-button></template>
        </el-table-column>
      </el-table>
      <el-button v-if="cursor" :disabled="busy || saving" @click="loadRows(true)">加载更多记录</el-button>
    </template>
    <el-card v-if="detail" shadow="never" class="detail" data-testid="education-detail">
      <template #header>记录详情</template>
      <dl>
        <template v-for="(value, field) in detail" :key="field"
          ><dt>{{ detailLabels[field] || field }}</dt>
          <dd>{{ value ?? '未设置' }}</dd></template
        >
      </dl>
      <el-button v-if="canRename" :disabled="!authorityValid || busy || saving || !!pending || persistenceError" @click="openForm(true)"
        >仅修改名称</el-button
      >
    </el-card>
    <el-card v-if="formOpen" shadow="never" class="detail" data-testid="education-form">
      <template #header>{{ editing ? '修改名称' : rootSchool ? '新建学校' : kind === 'organizations' ? '新建组织' : '新建班级' }}</template>
      <el-form label-position="top" @submit.prevent="submit">
        <p v-if="editing">只允许修改名称。保存时会校验打开详情时的版本；冲突后需刷新。</p>
        <el-form-item label="名称"
          ><el-input v-model="form.name" :disabled="saving" :maxlength="kind === 'organizations' || rootSchool ? 190 : 128"
        /></el-form-item>
        <template v-if="!editing">
          <el-form-item label="稳定编码（创建后不可修改）"><el-input v-model="form.code" :disabled="saving" :maxlength="64" /></el-form-item>
          <template v-if="kind === 'organizations' || rootSchool">
            <template v-if="!rootSchool">
              <el-form-item label="父组织"
                ><el-select v-model="form.parentId" :disabled="saving"
                  ><el-option :value="schoolId" :label="selectedSchool?.name || schoolId" /><el-option
                    v-for="item in organizations.filter((item) => item.id !== schoolId)"
                    :key="item.id"
                    :value="item.id"
                    :label="`${item.name}（${item.id}）`" /></el-select
              ></el-form-item>
              <el-form-item label="组织类型"
                ><el-select v-model="form.orgKind" :disabled="saving"
                  ><el-option label="校区" value="CAMPUS" /><el-option label="部门" value="DEPARTMENT" /></el-select
              ></el-form-item>
              <p>父组织选择来自当前授权列表。需要其他父组织时，请取消并加载更多记录。</p>
            </template>
            <el-form-item label="业务生效时刻（UTC，非创建时间）"
              ><el-input v-model="form.validFrom" :disabled="saving" placeholder="2026-11-08T00:00:00Z"
            /></el-form-item>
          </template>
          <el-form-item v-else label="年级编码（按学校既定口径）"
            ><el-input v-model="form.gradeCode" :disabled="saving" :maxlength="40"
          /></el-form-item>
        </template>
        <el-space wrap
          ><el-button
            native-type="submit"
            type="primary"
            :loading="saving"
            :disabled="!authorityValid || busy || saving || !!pending || persistenceError"
            >保存</el-button
          ><el-button :disabled="saving" @click="closeForm">取消</el-button></el-space
        >
      </el-form>
    </el-card>
    <p>删除、组织撤并、学期状态变更、人员资料及身份绑定不在此阶段开放。服务器会在读取和写入时重新核验当前学校范围与权限。</p>
  </section>
</template>
<style scoped>
.toolbar,
.terms,
.detail {
  margin: 18px 0;
}
.selectors {
  max-width: 680px;
}
.el-select {
  width: 100%;
  min-width: 220px;
}
dl {
  display: grid;
  grid-template-columns: minmax(110px, 1fr) 3fr;
  gap: 10px;
}
dd {
  margin: 0;
  overflow-wrap: anywhere;
}
p {
  line-height: 1.7;
}
</style>
