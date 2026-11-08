<script setup lang="ts">
import Workspace from '@/fcp/education-roster/Workspace.vue';
const buildId = String(import.meta.env.VITE_FCP_BUILD_ID || '');
</script>
<template>
  <main class="app-container education-roster" data-testid="education-roster" :data-build-id="buildId">
    <el-card shadow="never">
      <template #header><h1>智慧云校 · 名册关系</h1></template>
      <p>按学校查询学籍、入班历史和任教关系。当前为关系元数据只读阶段，人员最小资料及写入功能尚未开放。</p>
      <Workspace />
    </el-card>
  </main>
</template>
<style scoped>
h1 {
  margin: 0;
  font-size: 20px;
}
p {
  line-height: 1.8;
}
.education-roster {
  min-width: 0;
}
</style>
