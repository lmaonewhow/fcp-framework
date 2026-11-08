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


## 生成业务真实运行门禁

`tools/ci_native.py --ingestion --exchange --generator --generator-business` 仅在独立 GitHub Actions 的新建 `fcp_ci_*` 数据库中运行。新开关同时要求 `--generator` 与 `--ingestion`，不改变生产生成器默认行为：生成或应用源码本身始终不执行 SQL。现有 `--generator` 单独路径仍只验证源码应用、编译、构建和类型检查，业务运行与 SQL 执行保持 `NOT_RUN`。

原生生成测试先验证缺少五个审计列以及非 primary 数据源均无法直接应用，再使用带完整原生审计列的合成表。业务门禁仅接受固定 `generatorprobe/probe` 的六条原生菜单 INSERT，逐列验证内容、父菜单与权限，先检查数据库所有权、空业务表及菜单冲突，输出计划并用 mysqldump 备份相关表，再执行菜单和只读验收角色授权。预览、备份失败或 SQL 漂移会停止，不清库，不自动恢复，也不触碰其他数据库。菜单 SQL 仅由独立验收部署身份执行，实际 JAR 继续使用只具 DML 权限的账号。

首次原生生成、应用和回滚之后，测试在 Vue 源码中加入明确标注的手工提示，重新生成并确认提示被保留，再使用同一个锁定装配检出编译 Java、真实构建 Vue 和执行 vue-tsc。旧 JVM 必须完成退出，才能运行带私有隔离配置的 Maven clean verify 和启动新 JAR，避免两个宿主共享运行目录；最外层 finally 始终回收最新进程。两个原生 JVM 均明确使用 UTC 时区。原始发布 JAR 不覆盖，生成构建保存到 `work/ci-native/generated-release/`。

生成业务页通过原生菜单 `/admin/tool/probe` 加载。真实 Chromium 场景使用原生验证码、加密登录和会话，完成三条合成记录的新增、列表、编辑、名称 LIKE 查询、含日末记录的日期范围、条件重置、删除及刷新持久化。复选框覆盖多选回显、改为单项、清空、重新打开；数据库核对原生租户和审计字段。独立只读账号重新登录，验证按钮隐藏及新增、修改、删除、导出接口明确返回 403；匿名调用必须为 401；另一租户合成记录不能被读出、修改或删除。不会拦截响应、伪造令牌、替换后台或关闭权限。

`reports/generator-native/acceptance.json` 分开记录源码应用、`generatedSqlExecution` 和 `generatedBusinessRuntime`；失败、未运行不能写成通过。`menu-plan.json`、`menu-before.sql`、原始合成业务 HTTP 记录、生成源码副本、编译日志，以及新 JAR 的 sourceCommit、generatedBuildId、baselineBuildId、逐文件摘要和 JAR SHA-256 一并保留。`SOURCE-FILES.json` 可复核装配源摘要，Maven 日志仅脱敏私有配置。截图与录像在登录之后采集，等待请求、加载遮罩和过渡动画结束，涵盖表单、实际列表、日期查询、删除和只读页面。打包结果里的 `runtimeVerification=NOT_RUN` 是打包阶段范围；业务是否通过以绑定同一 JAR 的 acceptance.json 为准。

本地护栏/打包回归使用临时文件及进程替身，不代表真实 MySQL、Maven 或浏览器验收：

```sh
python -m unittest discover -s tests/python -p test_generated_business.py -v
python -m unittest discover -s tests/python -p test_native_generated_build.py -v
```


`test_generated_checkbox_actions.py` 回归 Element Plus 的可见标签交互：表单复选框和表格行选择都点击 `label.el-checkbox`，点击前检查可见、唯一和启用状态，点击后验证隐藏原生 input 的真实 checked 状态。不直接对隐藏 input 调用 check/uncheck，不使用 force 或 DOM 赋值。组件替身覆盖选中、取消、幂等选择、多选到清空、禁用/隐藏/歧义拒绝及点击未生效时失败；真实浏览器结果仍由上述原生业务门禁判定。

普通 CRUD 模板的日期范围控件是 `daterange`。锁定 Element Plus 2.13.5 的日历选择会应用 `default-time`，而手工输入经 `parseUserInput` 不应用该值，因此不能仅靠控件的默认时间实现整日检索。模板现在只在该字段的查询边界规范两端为 `00:00:00` 与 `23:59:59`，不修改控件值、全局 `addDateRange` 或编辑表单的 datetime 精度。空值、清空和不完整范围会清除两个查询参数。

模板片段回归覆盖日历值、手输午夜值、跨日、闰日、夏令时日期、日末记录与清空，完整模板仍受原始摘要与唯一锚点保护。原生验收继续实际键入日期，检查最终请求中的 `params[beginEventTime]` / `params[endEventTime]` 精确边界及重置后的双参数清除，并保留 23:59:58 记录必须被查出的断言。只读页独立收集 pageerror；失败截图跟随正在验收的角色，只截同源业务页面，不截登录页，也不误用先前管理员页。
