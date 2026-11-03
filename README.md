# FCP 0.5.0-dev.1 review1：审查修订与本地实测交接

本仓库按用户选择以干净源码快照首次上传，不含原 Git 历史或 GitHub Actions 工作流；文中旧提交号为来源及被测版本标识。

本仓库包含 FCP review1 及本地 Windows 原生集成修复。最新被测源码为 `e0330fad94b2e27b18d2a3cd7d5a1bdaf1c28829`；功能、重启与备份恢复已有真实结果，**最新 HDD 负载写入 P95 未通过，最终版 24 小时稳定性未测，整体未完成验收**。完整范围、产物摘要和剩余门禁见 [本地验收状态](docs/LOCAL_ACCEPTANCE_STATUS.md)。

后文保留 review1 最初交接时的背景、测试环境和未运行范围；其历史状态以该次交接为准，最新结果参见上述状态说明。上传内容为源码，不包含 JAR、依赖、真实私有配置或测试数据。

## 当前交付与实际修改

生产应用入口保持原生 `org.dromara.DromaraApplication`。本轮核对了锁定上游源码并修复 0.4.2 的错误启动类名称。后端固定 RuoYi-Vue-Plus 5.4.1，管理端固定 plus-ui 5.4.1-v2.4.1，教师端固定 ruoyi-plus-soybean 1.1.3，完整提交号见 `upstreams.lock.json`。完整上游仓库和依赖需要在可联网的构建环境取得，本源码包未内置它们。

FCP 新增可持久化的插件期望状态接口。原生宿主使用 JDBC 状态适配器，启停写入携带递增版本，并与审计事件同事务提交；内核另提供独立文件实现和明确标注的内存实现。当前原生统计插件首次默认停用，由具有管理权限的用户启用。宿主重启会读取已有期望状态，旧页面的版本不能覆盖新状态。

业务请求根据原生会话取得用户和租户，再查询数据库中的有效用户、租户、角色、菜单权限和班级授权。写操作在产生业务副作用前再次授权；管理操作在写入启停状态前再次授权。这里收紧的是 FCP 边界，没有替换若依全部原生页面的授权实现。权限新增后若原生会话仍有旧的权限缓存，可能需要重新登录；权限撤销由新增 FCP 查询进一步拒绝。超级管理员也需显式授予 FCP 角色，避免自动继承无限插件权限。

新增 V003 迁移及独立部署迁移工具，支持既有、可验证的 0.4.2 安装历史。工具默认只生成计划，应用变更需要计划摘要；脚本漂移、脏历史、未知新版本会阻断。MySQL DDL 失败不承诺自动回滚，运行时业务账号没有 DDL 执行入口。迁移 SQL 和 JDBC 查询尚未在实际 MySQL 执行。

Maven 构建改用 `prod` 配置并显式关闭测试跳过，读取 Surefire/Failsafe 实际报告。新增生产验收门禁要求构建、权限、数据库升级、浏览器、负载、稳定性、安全和恢复证据绑定同一产物，并检查原始附件及录像。门禁只检查证据一致性，仍依赖受信执行环境和独立审查。

## 本地可复测的组件

本轮实测环境为 Linux x86_64、OpenJDK 21、Python 3.13.5、Node 22.16.0 和 TypeScript 5.8.3。Java 编译目标为 17；这不代表已经在独立 JDK 17 或其他操作系统验收。

```bash
python tools/verify_components.py
python tools/verify_legacy.py
```

组件检查包括实际 Java→Python 子进程、真实文件落盘及重启、跨 JVM 文件锁、并发启停、撤权和故障路径。JDBC 协议检查使用明确的测试替身，SQL 迁移检查只执行锁文件和解析器验证。具体数量和边界以 `reports/components.json` 与 `reports/legacy-regressions.json` 为准。两组覆盖有交叉，不应相加为独立功能数。

单独的 `tests/java/RealMySqlRegression.java` 使用真实驱动和已初始化的专用 `fcp_ci_*` 数据库，未运行时不会冒充组件测试结果。它由准备好的 CI 任务调用。

## 构建与 Java 唯一应用入口

真实构建需要 Git、JDK 21、Maven 3.9.x、Node 22、npm、pnpm 10.5+、Python，以及上游源码和依赖下载能力。先执行检查并生成私有配置；密钥文件只保留在运行/构建环境，不进入源码或发布证据。

```bash
python tools/native.py doctor
java bootstrap/GenerateKeys.java config/private
```

上游管理端标签没有附带 npm 锁文件。首次在隔离环境明确执行 `--resolve-admin-lock`，审查生成的 `admin-package-lock.json`；正式候选构建必须恢复这份已审查锁文件，不使用首次浮动解析结果作为生产基线。

```bash
python tools/native.py assemble --work work/prepared --build-env config/private/build.env
# 此处先创建专用本地 MySQL/Redis、初始化空库、授予 DML 并填妥私有运行配置。
python tools/native.py build --work work/first-build --local-sources work/prepared/upstream --build-env config/private/build.env --test-runtime-config config/private/fcp-runtime.properties --resolve-admin-lock
python tools/native.py build --work work/locked-build --local-sources work/prepared/upstream --build-env config/private/build.env --test-runtime-config config/private/fcp-runtime.properties --admin-lock work/first-build/release/admin-package-lock.json
```

上游 prod 标签含启动 Spring 的测试，因此数据库和 Redis 必须在 Maven 构建前准备好。review1 强制要求显式私有测试运行配置、专用本地数据库及独立 Redis 前缀；缺少配置会在联网组装前退出，不静默跳过测试。Maven 测试结果仅对应 prod 标签选择，不能称为上游全部测试。

每次工作目录必须为空。构建器创建新目录和新分支，校验上游提交及修改锚点，不直接更改用户已有检出。成功编译也只表示已构建，仍需真实运行。三个仓库的原生测试、类型检查及插件代码可能暴露进一步兼容问题，本轮没有执行成功的证据。

在真实 MySQL 8 和 Redis 服务准备完毕、受限账号及配置填妥后，应用只通过以下命令启动。两套前端预构建后由同一 JAR 提供，运行时不需要 Node/Vite；Python 运行环境仍需安装，由 Java 按需启动子进程。Java 不负责安装或启动 MySQL/Redis。

```bash
java -jar ruoyi-admin.jar --spring.profiles.active=prod,fcp
```

管理端路径为 `/admin/`，教师端为 `/teacher/`。数据库迁移使用独立部署凭证，应用只使用业务 DML 凭证。生产须配置 TLS、反向代理或受控入口、真实密码及备份策略。旧版本 Redis 命名空间如需保留应显式配置 `FCP_REDIS_PREFIX`，不能依赖本版本默认前缀 `fcp050` 自动兼容会话。

## 数据库部署和升级

空库初始化仍使用 `tools/mysql_install.py`，限专用 `fcp_*` 空库，保留逐阶段历史；它不用于覆盖生产库。原生数据库中应创建真实租户、账号、角色、套餐权限并绑定班级。示例仍使用 `class-a` 与 `class-b`，学校实际主数据对接尚未完成。

增量迁移说明见 `docs/MIGRATIONS.md`。应用启动检查必要表结构，缺失时停止，不回退到文件数据库。新安装已记录 V003 的库可以由迁移工具校验并接管历史；历史不完整的旧库需要人工调查，工具不会猜测已经执行了什么。

## 联网 CI 与真实验收

本次源码快照不包含 `.github/workflows/native-verification.yml` 或任何 Actions 工作流，也没有自动触发 CI。`tools/ci_native.py` 保留为手动隔离环境的编排工具，首次依赖锁解析及私有配置仍需明确准备。CI 冒烟的范围不包含全部真实账号、浏览器、负载、安全或恢复验收。

本地验收必须设置与实际库名完全一致的 `FCP_ACCEPTANCE_DATABASE`，HTTP 验收目标限定 loopback 并禁止重定向。

原生账号 API 验收继续使用 `tools/verify_native_api.py`，浏览器使用 `e2e/capture_native.py`，负载初测使用 `tools/measure_native.py`。它们需要真实登录、真实数据和真实构建，不启用 mock。完整生产门禁的格式和缺口见 `docs/PRODUCTION-EVIDENCE.md`。

```bash
python tools/production_gate.py --release work/locked-build/release --evidence reports/production
```

缺少证据时返回退出码 2 并写入 BLOCKED。本轮保留该状态。`config/production-targets.json` 的数值是验收目标，**不是已经测得的性能**。

## 安全和适用边界

当前生产化范围为 Linux 单节点、受审查的可信插件。文件状态实现提供单机持久化与排他锁，真实若依使用 JDBC；不宣称跨节点一致性控制面已完成。普通进程隔离与 Java 类加载器不能代替不可信代码沙箱。两套前端为同源可信代码，共享令牌机制仍需要 XSS、会话撤销及正式安全审计。数据库恢复、依赖漏洞扫描、SBOM、实际学校主数据、全部原生页面兼容和长期稳定性仍需完成。

`docs/baseline-0.4.2/` 是历史交付记录，仅用于追溯。原 0.5.0-dev.1 报告保留在 `docs/baseline-0.5.0-dev.1/VERIFICATION.md`。当前说明与结果以 `docs/REVIEW.md`、`reports/review1/`、本 README 和本地实测新证据为准。
