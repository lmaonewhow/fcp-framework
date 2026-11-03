<script setup lang="ts">
const buildId = String(import.meta.env.VITE_FCP_BUILD_ID || '');
import {computed,h} from 'vue';
import {NAlert,NButton,NCard,NDataTable,NForm,NFormItem,NInput,NInputNumber,NSelect,NSpace} from 'naive-ui';
import {useSchool} from './useSchool';
import type {RecordRow} from './bridge';
const {state,rows,error,summary,busy,form,nextCursor,savedRecord,create,analyze,reload,next}=useSchool('teacher');
const classes=computed(()=>state.value?.classes.map(value=>({label:value,value}))||[]);
const indicators=['课堂参与','合作交流','实践活动'].map(value=>({label:value,value}));
const columns=[{title:'学生代号',key:'student',render:(r:RecordRow)=>h('span',r.body.student)},
 {title:'班级',key:'class',render:(r:RecordRow)=>r.body.classId},{title:'指标',key:'indicator',render:(r:RecordRow)=>r.body.indicator},
 {title:'分数',key:'score',render:(r:RecordRow)=>r.body.score},{title:'观察',key:'comment',render:(r:RecordRow)=>h('span',r.body.comment)}];
</script>
<template>
  <NSpace vertical size="large" data-testid="fcp-native-teacher" :data-build-id="buildId">
    <NAlert v-if="error" type="error">{{error}}</NAlert>
    <NAlert v-if="savedRecord" type="success" data-testid="save-confirmation">已保存评价：{{savedRecord.body.student}}。记录编号：{{savedRecord.id}}。列表按记录编号分页，可在后续页面查看。</NAlert>
    <template v-if="state">
      <NCard title="今天，也记录一点成长。">
        <NSpace><NButton :loading="busy" @click="reload">刷新记录</NButton><NButton v-if="state.operations.includes('analyze')" :disabled="busy" data-testid="analyze" @click="analyze">Python 统计摘要</NButton></NSpace>
        <p data-testid="analysis-result">{{summary}}</p><a v-if="state.allowedShells.includes('admin')" href="/admin/fcp/school">返回管理端</a><NDataTable :columns="columns" :data="rows" :scroll-x="700" :row-key="(r:RecordRow)=>r.id"/>
        <NButton v-if="nextCursor" :disabled="busy" @click="next">下一页</NButton>
      </NCard>
      <NCard v-if="state.operations.includes('create')" title="新增评价">
        <NForm @submit.prevent="create"><NFormItem label="学生代号"><NInput data-testid="student" v-model:value="form.student" :maxlength="40"/></NFormItem>
          <NFormItem label="授权班级"><NSelect v-model:value="form.classId" :options="classes"/></NFormItem>
          <NFormItem label="评价指标"><NSelect v-model:value="form.indicator" :options="indicators"/></NFormItem>
          <NFormItem label="分数"><NInputNumber v-model:value="form.score" :min="0" :max="100" :precision="0"/></NFormItem>
          <NFormItem label="观察"><NInput v-model:value="form.comment" type="textarea" :maxlength="200"/></NFormItem>
          <NButton attr-type="submit" type="primary" :loading="busy">保存评价</NButton>
        </NForm>
      </NCard>
    </template>
  </NSpace>
</template>
