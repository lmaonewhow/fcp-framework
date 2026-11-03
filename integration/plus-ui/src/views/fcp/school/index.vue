<script setup lang="ts">
const buildId = String(import.meta.env.VITE_FCP_BUILD_ID || '');
import {useSchool} from './useSchool';
const {state,rows,error,summary,busy,form,nextCursor,savedRecord,create,analyze,toggle,reload,next}=useSchool('admin');
</script>
<template>
  <div class="app-container" data-testid="fcp-native-admin" :data-build-id="buildId">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="savedRecord" data-testid="save-confirmation" :title="`已保存评价：${savedRecord.body.student}`" :description="`记录编号：${savedRecord.id}。列表按记录编号分页，可在后续页面查看。`" type="success" :closable="false" />
    <template v-if="state">
      <el-card shadow="never">
        <template #header><strong>课堂评价管理</strong></template>
        <el-space wrap><el-button :loading="busy" @click="reload">刷新</el-button>
          <el-button v-if="state.operations.includes('analyze')" :disabled="busy" data-testid="analyze" @click="analyze">调用 Python 分析</el-button>
          <el-button v-if="state.operations.includes('manage')" :disabled="busy" data-testid="toggle-python" @click="toggle">{{state.pythonEnabled?'停用':'启用'}} Python</el-button>
        </el-space>
        <p data-testid="analysis-result">{{summary}}</p><a href="/teacher/fcp/school">打开教师端</a>
        <el-table :data="rows" row-key="id"><el-table-column prop="body.student" label="学生代号"/><el-table-column prop="body.classId" label="班级"/><el-table-column prop="body.indicator" label="指标"/><el-table-column prop="body.score" label="分数"/><el-table-column prop="body.comment" label="观察"/></el-table>
        <el-button v-if="nextCursor" :disabled="busy" @click="next">下一页</el-button>
      </el-card>
      <el-card v-if="state.operations.includes('create')" shadow="never">
        <template #header>新增评价</template>
        <el-form label-width="100px" @submit.prevent="create">
          <el-form-item label="学生代号"><el-input data-testid="student" v-model="form.student" maxlength="40" /></el-form-item>
          <el-form-item label="授权班级"><el-select v-model="form.classId"><el-option v-for="c in state.classes" :key="c" :label="c" :value="c"/></el-select></el-form-item>
          <el-form-item label="指标"><el-select v-model="form.indicator"><el-option v-for="v in ['课堂参与','合作交流','实践活动']" :key="v" :value="v" :label="v"/></el-select></el-form-item>
          <el-form-item label="分数"><el-input-number v-model="form.score" :min="0" :max="100" :precision="0"/></el-form-item>
          <el-form-item label="观察"><el-input v-model="form.comment" maxlength="200" type="textarea"/></el-form-item>
          <el-button native-type="submit" type="primary" :loading="busy">保存评价</el-button>
        </el-form>
      </el-card>
    </template>
  </div>
</template>
