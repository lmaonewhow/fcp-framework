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
