# 原生组合发布验收

`python tools/ci_native.py --ingestion --plugin-releases` 仅运行于独立 GitHub Actions 原生夹具，使用新建 MySQL 库、DML 运行账号、Redis 随机命名空间、真实验证码和加密登录、锁定 RuoYi Spring 后端。它和 `--exchange` 互斥，保留原有 `--ingestion --exchange` 的生命周期、存储和 Exchange 验收，不能把旧模式的结果当作多版本通过证据。

工作流 `.github/workflows/native-release-composition.yml` 先完成真实后端和双 Vue 前端构建及类型检查，再通过 `build_ui_variant` 重建两套候选前端。候选使用相同功能源码和不同编译构建标识，证明实际构建产物选择，不宣称两个不同产品功能版本。两组原生 UI 包摘要、构建标识和后端 JAR 摘要均保存在该次证据中。

夹具经真实 HTTP 上传两个 Python 包版本和一个 Node 包，登记不可变系统组成，逐包摘要审批 USER 权限，然后通过 `/fcp/releases/plans` 和 `/fcp/releases/operations` 驱动组合执行器。夹具不直接写路由策略、不改数据库发布状态、不手工激活候选来冒充部署。DEPLOY 必须实际准备两个独立进程组，UI bootstrap 和插件目录必须遵循同一可信路由和 affinity。

检查范围包括 DEPLOY、CANARY、PAUSE、EXPAND、PROMOTE、ABORT、ROLLBACK、RETIRE 及同操作重放。真实 Python 1.0.0 和 1.1.0、Node 回调、父 JVM 和不同子进程 PID 都会断言。候选摘要不得继承旧版本授权，管理员和教师两个实际 Vue 页面都必须调用所选版本；老页面在推广后保留原版本，刷新选取新的不可变包。原生退出登录后在同一个浏览器上下文切换第二租户，检查旧页面和结果清空、未授权目录不可见、第二租户单独授权及撤销、跨租户 affinity 被拒绝。停止路由后旧 affinity 不能调用或回退至旧静态页面，受 RBAC 保护的管理员治理入口仍能打开，回退后恢复原版，退役后候选子进程必须实际退出而稳定版继续工作。

原生 TASK 调度器和 release-aware Exchange 端口没有在这个模式中启用，报告明确写 `NOT_RUN`，不能用 USER 登录测试冒充 TASK、存储写入或未知结果恢复验收。多 JVM、外部后端部署、生产上线和长期稳定性同样不由此夹具认证。

该工作流上传 `reports/native-source-commit.txt`、`reports/ci-native.json`、`reports/native-release-composition/acceptance.json`、失败或成功截图、控制台和脱敏启动日志、`BUILD-EVIDENCE.json` 与 `UI-VARIANT-candidate.json`。会话令牌、affinity、私钥和登录状态仅留在本次私有目录或进程内存，不能进入上传证据。一次失败不会被后续模块成功覆盖。

`python -m unittest discover -s tests/python -p 'test_native_composition_config.py' -v` 只验证模式互斥、真实构建前置条件、确定性夹具摘要和门禁，不运行浏览器、Spring 或 MySQL，不是原生验收 PASS。当前 cloud 执行环境的 Chromium socket 限制不会由修改该夹具绕过；真实验收结论只能取自完成的隔离 CI 运行及其精确提交。
