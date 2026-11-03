# FCP 0.6 插件与数据接入场景验收

验收日期为 2026 年 11 月 3 日。功能分支为 `feat/ruoyi-5.6-plugin-ingestion`，被测源码提交为 `9bf4031a38a065139d016903cfdb59179055c9a4`。本报告对应真实 RuoYi-Vue-Plus 5.6.2 宿主中的指定小型演示场景，结论为 **PASS**；生产发布结论仍为 **NOT_ACCEPTED**。后续仅更新交付文档时，原生验收仍绑定这里的源码提交，不能把文档提交伪称为另一次原生测试。

## 实现与场景结果

学生名册与考勤汇总分别由 Python 实现，各自提供业务插件、API 连接器和 CSV 适配器，共六个独立插件。Java 宿主负责认证授权、插件依赖、启停、子进程管理、版本检查和 MySQL 事务。接口保留若依原生登录、验证码、加密登录、租户及角色权限；管理端使用 plus-ui 5.6.2-v2.6.2，教师端使用 ruoyi-plus-soybean 1.1.3，精确上游提交保存在 `upstreams.lock.json`。

演示从管理员启用六个插件开始。名册连接器分页读取三名合成学生，CSV 导入更新一名并新增一名，最终保留四人。考勤连接器导入正常、迟到、缺勤各一条，业务插件计算出勤率为 66.67%；CSV 校正缺勤并增加请假后，按照示例中迟到计入已到、请假排除应到分母的规则，出勤率变为 100%。这些是自动化场景断言中的预期与实测通过结果，业务数据来自明确标注的 Campus-v1 合成平台。

浏览器实际点击完成启用、API 导入、文件导入、停用和再次启用。停用名册业务会终止对应 Python 子进程，并阻断依赖它的输入插件。JVM 真正退出并重启后，停用状态和 MySQL 中的名册、考勤保留。教师只在授权班级内导入，管理接口被后端拒绝；只读账号没有导入控件，直接调用导入接口也被拒绝。错误批次整批失败，重复请求的幂等重放不产生重复写入。

跨语言边界使用 UTF-8、按行分帧的 JSON-RPC 2.0/stdio。组件回归实际运行 Python 和 Node.js 提供者，验证语言替换不需要改变 Java 调度代码。其他语言可按同一契约实现，仍需准备运行时并执行相应互通验收。

## 证据与实测

组件流水线运行 [41603722206](https://github.com/lmaonewhow/fcp-framework/actions/runs/41603722206) 通过 240 项组件检查、105 项保留回归和 76 项输入与跨语言检查。原生流水线运行 [41603722251](https://github.com/lmaonewhow/fcp-framework/actions/runs/41603722251) 通过 61 项实际场景检查、真实 MySQL 适配器检查，以及 7 项 Maven prod 标签测试。管理端和教师端的类型检查与生产构建均通过。检查组存在交叉，不能相加作为独立业务功能数；Maven 结果仅覆盖 prod 标签选择。

原生环境为隔离 GitHub Actions Ubuntu 24.04 运行器、JDK 21、MySQL 8.4、Redis 7.4、Python 与真实 Chromium。浏览器操作通过运行器内的 Playwright 执行，服务绑定回环地址。该证据没有声称已经部署到用户电脑或生产服务器。管理员、教师、只读账号的桌面和移动端截图均已生成，浏览器 JavaScript 异常与 console error 检查均通过。录像在原生登录完成后开始，不包含登录密码、令牌或保存的会话文件。

构建标识为 `a05da3fc72404f278cb0b528709035eb`，原生 JAR 的 SHA-256 为 `e0ac5d97a554cf20c3f1e142715963b4c82ea0e651faec72f52670ae7dd45615`。该次启动耗时为 17.341 秒，五次 UI 导入计时为 0.432, 0.351, 0.285, 0.286, 0.374 秒。这些是该次 CI 小样本记录，不是吞吐量、P95 或生产性能保证。最后宿主通过正常退出完成清理，记录为 `GRACEFUL_EXIT`。

`plugin-ingestion-evidence` 保存精确 Git 源码快照、提交号与各组结果。`native-ingestion-evidence` 保存 `reports/native-source-commit.txt`、`reports/ci-native.json`、`reports/native-ingestion/acceptance.json`、截图、录像、构建清单与原始日志。`BUILD-EVIDENCE.json` 的 `runtimeVerification: NOT_RUN` 是构建结束时的阶段快照，后续实际运行结论见 `ci-native.json` 和 `acceptance.json`。证据文件的交付校验另有 SHA-256 清单；文件存在或工作流定义存在本身不构成通过结果。

## 本次续作修复

复核发现原生流水线依赖运行 `41584514997` 中会过期的前端锁文件。已从通过验收的原生运行中取出相同字节的 npm 锁，核对 SHA-256 后提交为 `locks/admin-package-lock.json`，并增加 `tools/check_ingestion_lock.py` 和七项回归测试。缺失锁、锁字节变化或管理端上游变化都会明确失败。原生 CI 已移除历史产物下载依赖，仅保留读取权限；一次性持久化工作流已删除。

同时扩大原生 CI 的变更触发范围，覆盖内核、业务、宿主、运行时、集成层、构建工具、测试、SQL、配置和锁文件，避免关键代码变化后漏跑集成检查。本轮未改变已通过验收的业务运行逻辑。本地重新执行组件、保留回归及输入验证均通过；一次组合命令触发工具执行时限，相关日志已保留，拆分后输入验证通过，未将超时记录算作通过。

## 使用与边界

完整操作步骤、CSV 字段、私有连接器配置、数据限额和协议见 `docs/INGESTION.md`。当前可直接复用提交的依赖锁，运行 `python tools/check_ingestion_lock.py` 后，原生构建使用 `--admin-lock locks/admin-package-lock.json`。准备 MySQL、Redis、Python、独立运行账号、迁移和私有配置后，构建产物通过 `java -jar ruoyi-admin.jar --spring.profiles.active=prod,fcp` 启动。管理端入口为 `/admin/fcp/ingestion`，教师端入口为 `/teacher/fcp/ingestion`。

当前交付覆盖可信的已安装插件、Linux 单节点和指定小型业务场景。目录变更仍需受控发布及重启；外部 API 使用合成平台，真实学校平台尚需凭证和格式对接；文件适配器读取约定 CSV，未提供任意 XLSX 导入。每班每种业务累计最多 1,000 条记录，单份 CSV 或单页 API 最多 128 KiB，API 最多十页。24 小时稳定性、大规模负载、生产备份恢复、多节点一致性和恶意插件沙箱均未验收。源码和证据包不包含可直接部署的原生 JAR、数据库或私有凭证。
