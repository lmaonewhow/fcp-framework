<script setup lang="ts">
import {useIngestion} from './useIngestion';
defineProps<{shell:'admin'|'teacher'}>();
const buildId=String(import.meta.env.VITE_FCP_BUILD_ID||'');
const {state,entity,classId,busy,error,notice,rows,summary,version,encoding,file,pending,plugins,columns,labels,active,toggle,ingest,retry,abandon,selectFile,reload}=useIngestion();
const kindNames={business:'业务模块',connector:'API 连接器',adapter:'CSV 适配器'};
</script>
<template>
  <main class="ingestion" data-testid="fcp-ingestion" :data-shell="shell" :data-build-id="buildId" :aria-busy="busy">
    <header class="heading"><div><small>FCP · 数据接入</small><h1>学生名册与考勤</h1><p>统一校验、按班级隔离、整批提交。业务模块与输入插件分别启停。</p></div><button :disabled="busy" @click="reload">刷新状态</button></header>
    <p v-if="error" class="message error" role="alert" data-testid="ingestion-error">{{error}}</p>
    <p v-if="notice" class="message success" role="status" data-testid="ingestion-receipt">{{notice}}</p>
    <section v-if="pending" class="message warning"><p>本页保留了一次尚未确认的导入请求。重试会使用原始内容、版本和幂等键，避免重复提交。</p><button :disabled="busy" @click="retry">重试同一请求</button><button :disabled="busy" @click="abandon">放弃重试信息</button></section>
    <template v-if="state">
      <section class="controls panel" aria-label="业务与班级">
        <label>业务模块<select v-model="entity" data-testid="ingestion-entity" :disabled="busy||Boolean(pending)" @change="reload"><option value="roster">学生名册</option><option value="attendance">考勤汇总</option></select></label>
        <label>授权班级<input v-if="state.allClasses" v-model="classId" data-testid="ingestion-class" :disabled="busy||Boolean(pending)" maxlength="64" @change="reload"/><select v-else v-model="classId" data-testid="ingestion-class" :disabled="busy||Boolean(pending)" @change="reload"><option v-for="value in state.classes" :key="value" :value="value">{{value}}</option></select></label>
        <span class="version">数据版本 {{version}} · {{rows.length}} 条记录</span>
      </section>
      <section class="plugins" aria-label="插件状态">
        <article v-for="plugin in plugins" :key="plugin.id" class="panel plugin" :data-testid="`plugin-${plugin.id}`">
          <small>{{kindNames[plugin.kind]}}</small><h2>{{plugin.title}}</h2><p><span :class="['dot',{running:plugin.effective}]"/>{{plugin.effective?'已启用':plugin.enabled?'等待业务模块启用':'已停用'}}</p>
          <code>{{plugin.id}}</code><button v-if="state.canManage" :disabled="busy||Boolean(pending)" :data-testid="`toggle-${plugin.id}`" @click="toggle(plugin)">{{plugin.enabled?'停用':'启用'}}</button>
        </article>
      </section>
      <section v-if="state.canImport" class="panel import-panel" aria-label="输入数据">
        <div><h2>从平台同步</h2><p>使用服务器为当前租户配置的接口。连接器自动解析分页数据。</p><button class="primary" data-testid="sync-api" :disabled="busy||Boolean(pending)||!active('connector')||!classId" @click="ingest('connector')">通过 API 同步</button></div>
        <div><h2>导入平台导出文件</h2><p>支持中文或 Campus-v1 英文表头的 CSV，每批最多 1,000 行、128 KiB。</p><label>文件编码<select v-model="encoding" :disabled="busy||Boolean(pending)"><option value="utf-8-sig">UTF-8 / UTF-8 BOM</option><option value="gb18030">GB18030</option></select></label><input type="file" accept=".csv,text/csv" aria-label="CSV 文件" data-testid="csv-file" :disabled="busy||Boolean(pending)" @change="selectFile"/><button class="primary" data-testid="import-csv" :disabled="busy||Boolean(pending)||!active('adapter')||!file||!classId" @click="ingest('adapter')">校验并导入 CSV</button></div>
      </section>
      <section class="panel" aria-label="业务数据">
        <div class="data-heading"><h2>{{entity==='roster'?'学生名册':'考勤汇总'}}</h2><span v-if="!active('business')">启用业务模块后可查看数据</span></div>
        <div class="metrics" data-testid="ingestion-summary"><div v-for="(value,key) in summary" :key="key"><small>{{labels[key]||key}}</small><strong>{{value}}</strong></div></div>
        <div class="table-scroll"><table data-testid="ingestion-data"><thead><tr><th v-for="key in columns" :key="key" scope="col">{{labels[key]||key}}</th></tr></thead><tbody><tr v-for="row in rows" :key="`${row.studentId}-${row.day||''}`"><td v-for="key in columns" :key="key">{{key==='status'?(labels[row[key]]||row[key]):row[key]}}</td></tr><tr v-if="!rows.length"><td :colspan="columns.length">当前没有可展示的记录。</td></tr></tbody></table></div>
      </section>
    </template>
  </main>
</template>
<style scoped>
.ingestion{width:100%;min-width:0;box-sizing:border-box;max-width:1280px;margin:0 auto;padding:24px;color:var(--el-text-color-primary,#263345);font-size:14px;line-height:1.6}.heading,.data-heading{display:flex;align-items:center;justify-content:space-between;gap:20px}.heading{margin-bottom:24px}.heading>div{min-width:0}.heading>button{flex-shrink:0;white-space:nowrap}.heading small{letter-spacing:.12em;color:#5369bd}.heading h1{font-size:28px;margin:4px 0}.ingestion h2{font-size:17px;margin:0 0 10px}.ingestion p{margin:8px 0 14px}.panel{min-width:0;background:var(--el-bg-color,#fff);border:1px solid var(--el-border-color-light,#e1e6ee);border-radius:12px;padding:20px;margin-bottom:18px}.controls{display:flex;align-items:end;flex-wrap:wrap;gap:20px}label{display:flex;flex-direction:column;gap:5px;font-weight:500}input,select,button{font:inherit;border:1px solid #c8d1df;border-radius:7px;padding:8px 12px;background:var(--el-bg-color,#fff);color:inherit}button{cursor:pointer}button:hover:not(:disabled){border-color:#526bc8}button:focus-visible,input:focus-visible,select:focus-visible{outline:3px solid #a7b7ef;outline-offset:2px}button:disabled{opacity:.5;cursor:not-allowed}.primary{background:#415ab0;color:#fff;border-color:#415ab0}.version{margin-left:auto;color:#69758a}.plugins{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px}.plugin small,.plugin code{color:#69758a}.plugin button{display:block;margin-top:16px}.dot{display:inline-block;width:8px;height:8px;border-radius:50%;background:#9aa6b5;margin-right:8px}.dot.running{background:#238267}.import-panel{display:grid;grid-template-columns:1fr 1fr;gap:28px}.import-panel>div{min-width:0}.import-panel input,.import-panel button{display:block;margin-top:12px;max-width:100%}.import-panel select{width:190px}.metrics{display:flex;flex-wrap:wrap;gap:28px;padding:12px 0 20px}.metrics small,.metrics strong{display:block}.metrics strong{font-size:24px}.table-scroll{overflow:auto}table{border-collapse:collapse;width:100%;text-align:left}th,td{padding:12px 14px;border-bottom:1px solid #e6eaf1;white-space:nowrap}th{font-weight:600;background:var(--el-fill-color-light,#f4f6fa)}.message{padding:14px 18px;border-radius:8px;border:1px solid}.error{background:#fff2f2;color:#a62b36;border-color:#edbdc1}.success{background:#eef8f4;color:#236c53;border-color:#b0d9cb}.warning{background:#fff9e9;color:#805b18;border-color:#ead6a7}.warning button{margin-right:12px}@media(max-width:760px){.ingestion{padding:14px}.plugins,.import-panel{grid-template-columns:1fr}.heading{align-items:start}.version{margin-left:0}.heading h1{font-size:23px}}
</style>
