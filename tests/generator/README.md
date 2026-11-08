# 代码生成应用测试

`test_native_generator_frontend.py` 是独立组件测试，使用锁定 plus-ui 的原始页面 fixture，Git HEAD 由测试模拟。覆盖页面接入、漂移与重复应用拒绝、覆盖冲突、符号链接拒绝、错误提交以及重复锚点。无需工作目录中的上游检出或联网。

从仓库根目录运行：

```sh
python -m unittest discover -s tests/python -p test_native_generator_frontend.py -v
```

`ui-state.test.mjs` 运行 Vue SFC 编译及隔离的状态逻辑回归，模拟响应、确认框与生命周期，不启动浏览器或宿主。需要可信环境中已安装的 `@vue/compiler-sfc` 和 `typescript`，可用两个参数分别传入包目录；有本地模块可解析时可省略参数。例如，在已安装依赖的管理端检出上使用：

```sh
node tests/generator/ui-state.test.mjs \
  "$PWD/work/prepared/upstream/admin/node_modules/@vue/compiler-sfc" \
  "$PWD/work/prepared/upstream/admin/node_modules/typescript"
```

覆盖首次不发请求、默认关闭、取消后慢请求隔离、确认期间防重复与关闭、网络结果未知后查历史并废弃旧计划、冲突阻止应用、取消确认、卸载后不执行确认操作，以及恢复记录阻止旧预览。另对 API/types 文件执行 TypeScript 转译，并验证原生 R<T> 响应解包、HTTP 200 中的业务失败与操作编号 URL 编码。

这些测试不代表完整 plus-ui 类型检查、生产构建或真实浏览器通过。真实宿主场景由 `native_acceptance.py` 执行，并独立记录构建与运行证据。

原始页面 fixture 来源：plus-ui 提交 `d0d451967676707021b9857df529c395b27e90a7` 的 `src/views/tool/gen/index.vue`，保留 MIT 许可范围；校验摘要由补丁器的 `PREIMAGE` 固定。

## 标准生成模板兼容回归

构建器在打包后端资源之前，只对锁定 `vm/vue/index.vue.vm` 做 SHA-256 与唯一锚点校验后的修复：datetime/BETWEEN 使用字符串日期元组；标准 String checkbox 的局部 EditForm 保持数组，接口 Form 仍为逗号分隔字符串，提交时构造新 payload，不修改控件状态。生成下载与安全应用使用同一模板资源。不修改用户已有生成文件，不扩展到树模板、自定义模板或 Soybean。

```sh
python -m unittest discover -s tests/python -p test_native_generator_templates.py -v
node tests/generator/template-contract.test.mjs /absolute/path/to/node_modules/typescript
```

Python 测试采用上游 `8136a0191a2258c0e1b36a8146a1c5ebc070c139` 的原始 `index.vue.vm` fixture（MIT），检查摘要、漂移、重复应用、符号链接及装配调用。Node 测试从修复器提取选定分支的 TypeScript 片段，验证原始 checkbox 数组/字符串错误、修复后严格类型检查，以及空值、编辑、重复提交和重试时控件保持数组。它不是完整 Velocity 渲染或完整生成页面的 vue-tsc；这两项必须通过真实原生验收另行验证。
