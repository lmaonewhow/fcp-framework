# FCP review1：本地真实集成实测任务

## 目标与接手状态

请在本机完成真实运行与问题修复。系统必须使用固定的 RuoYi-Vue-Plus 后端、plus-ui 管理端、ruoyi-plus-soybean 教师端、MySQL、Redis 和 Java 自动管理的真实 Python 子进程。管理端与教师端权限不同；两个界面由一个 Java 应用提供，运行时无需分别启动 Vite。纯框架和完整接入源码均已交付，但经过验收的原生 ruoyi-admin.jar 尚未交付。

review1 已修复真实 Python 子进程不读取 stdin 且忽略 SIGTERM 时，Java 的超时清理阻塞问题；已调整首次 npm 锁保存时机、本地验收目标校验和原生构建顺序。实际证据在 docs/REVIEW.md 与 reports/review1/。不要照搬旧包的测试数字，先在本地重新运行。原生构建、MySQL、Redis、两个页面和性能均需要你在本机实际验证。

当前交接环境是 Linux/JDK 21；用户常用 Windows 11 和 WSL2。优先使用用户已有的 WSL2/Linux 工具链保持一致，并明确记录选择。不要擅自改变本机系统、删除已有容器或全局依赖。使用 Windows 原生工具链时，明确验证路径、进程终止、文件权限和类路径分隔符，不把 Linux 结果等同于 Windows 验收。

## 独立分支与工作区

先在交付目录核对 SOURCE-MANIFEST.sha256 和 DELIVERY.json。源码包包含 Git 历史，不需要将包内历史报告提交到业务分支。以下命令仅适用于相邻目标目录尚不存在的情况；已有同名目录时选择新的唯一目录，不删除或重置已有目录。

```bash
git clone history/fcp-source.bundle ../fcp-local-review-repo
git -C ../fcp-local-review-repo switch fix/local-codex-review-0.5.0
git -C ../fcp-local-review-repo worktree add -b test/local-native-review1 ../fcp-local-e2e fix/local-codex-review-0.5.0
cd ../fcp-local-e2e
git status --short
```

恢复后的提交应与交付目录 DELIVERY.json 的 sourceCommit 一致。Git 历史不包含生成的 JAR 和报告；这些文件在交付包内，执行构建工具也能重新生成。新增修复必须提交在本次独立分支，保留最小差异和失败复现，不推送远程。

## 环境与组件复测

检查 JDK 21、Python 3.10+、Git、Maven 3.9.x、Node 22、npm、pnpm 10.5.2、TypeScript 5.8.3、MySQL 8 客户端/服务端、Redis 和浏览器工具。工具版本是本项目选定的复现环境，不代表“当前最新版”。确认上游、Maven 与 npm 下载可达，不用未知镜像绕过校验；保留 doctor 输出。

```bash
python tools/native.py doctor
python tools/verify_components.py
python tools/verify_legacy.py
python tools/build_libraries.py
java -cp dist/fcp-core-0.5.0-dev.1.jar core/examples/StateAndProcessCheck.java core/examples
```

命令中的 python 在部分 Linux 环境对应 python3，按实际可执行程序替换。doctor 的 READY 仅表示检测到构建工具和域名解析，数据库与浏览器不在其 READY 范围内。任何失败都先记录退出码和原始日志，再定位。尤其保留 ProcessTerminationRegression 与 ProcessBoundsRegression 的真实子进程结果，不用删除用例解决挂起。

## 先准备源码和测试基础设施

先生成新密钥和组装上游源码。组装仅准备源码，不执行 Maven 的 Spring 上下文测试，因此此时可以尚未初始化数据库。

```bash
java bootstrap/GenerateKeys.java config/private
python tools/native.py assemble --work work/prepared --build-env config/private/build.env
```

创建本次独立的本地 MySQL 8 与 Redis 实例，优先使用已有工具支持的隔离容器；记录实际镜像版本/摘要、映射端口和资源限制。端口只绑定 127.0.0.1。不要借用生产服务，不要清理旧容器。创建唯一空数据库，例如 fcp_ci_localreview_<随机后缀>。该命名同时满足原生构建测试与 RealMySqlRegression 的专用库要求。MySQL 部署账号负责初始化与迁移，运行账号只授予该库必要 SELECT、INSERT、UPDATE、DELETE，不授予 CREATE、ALTER 或 DROP。

创建 config/private/mysql-client.cnf，采用 MySQL 标准 [client] 配置，显式填写 host=127.0.0.1、此次专用 port、部署 user 和 password，POSIX 权限为 0600。该文件不得显示到公开日志。先核对客户端实际目标，再使用初始化工具。将下面的 fcp_ci_localreview_unique 替换成你实际创建的唯一数据库名，所有后续配置保持一致。

```bash
python tools/mysql_install.py --backend work/prepared/upstream/backend --database fcp_ci_localreview_unique --defaults-file config/private/mysql-client.cnf
python tools/mysql_install.py --backend work/prepared/upstream/backend --database fcp_ci_localreview_unique --defaults-file config/private/mysql-client.cnf --apply
```

第一条只输出脚本计划。核对完整脚本摘要、目标数据库和空库状态后才执行第二条。工具拒绝非空库；初始化中断后不要自动删除或重建，先检查 fcp_install_history。MySQL 默认示例账户需要立即改成专用强密码。Redis 设置独立密码，禁止共享生产实例。

填妥 config/private/fcp-runtime.properties 的 FCP_JDBC_URL、FCP_DB_USER、FCP_DB_PASSWORD、FCP_REDIS_HOST、FCP_REDIS_PORT、FCP_REDIS_PASSWORD 和 FCP_REDIS_PREFIX。生成器已生成随机 Redis 前缀，保留本次唯一值；JDBC 必须指向专用 loopback 实例和同一个数据库。保留生成的 JWT、监控和 RSA 材料，使用 prod,fcp 配置。默认 JDBC 要求 TLS；仅当此次本地隔离服务明确不支持 TLS 时，可为该 loopback 测试实例显式设 sslMode=DISABLED，并在报告记录；不能将该设置作为生产推荐。

## 再执行真实原生构建

上游 prod 标签测试中包含 @SpringBootTest，测试会初始化 FCP 并读取数据库结构。必须在上一步完成数据库、Redis 和运行配置之后再 build。review1 强制要求 --test-runtime-config；构建进程不继承环境里其他项目的 FCP/Spring/JVM 配置。此检查不会代替实际连通性、TLS、数据库权限或 SQL 验证。

```bash
python tools/native.py build --work work/first-build --local-sources work/prepared/upstream --build-env config/private/build.env --test-runtime-config config/private/fcp-runtime.properties --resolve-admin-lock
```

上游管理端标签没有 npm 锁文件，第一次解析需要 --resolve-admin-lock 明确允许。review1 会在安装完成后立即保存 release/admin-package-lock.json，即使后续教师端或后端构建失败仍保留。审查锁文件、来源和漏洞结果后，在新的空工作目录执行锁定构建；不要修改源码包的固定上游来绕过失败。

```bash
python tools/native.py build --work work/locked-build --local-sources work/prepared/upstream --build-env config/private/build.env --test-runtime-config config/private/fcp-runtime.properties --admin-lock work/first-build/release/admin-package-lock.json
```

记录真实 Maven、两套前端生产构建和类型检查日志，检查 release/BUILD-EVIDENCE.json、原生入口和两个静态目录。Maven 显式执行上游 prod 标签选择的测试，并检查实际 Surefire/Failsafe XML；这个选择不等于上游全部测试。如有源码兼容问题，在本次分支修复组装器/集成源码并增加回归，不能加 skipTests 或关闭认证解决。

## Java 单入口与原生账号

从当前工作区根目录启动原生 JAR，并显式指定私有配置的绝对 file URI；路径有空格时保留引号。下面用 Python 只生成路径，不输出任何配置内容。也可在私有运行目录放置同名 fcp-runtime.properties 后运行 JAR，保持秘密文件不会进入 release。

```bash
RUNTIME_URI=$(python -c 'from pathlib import Path; print(Path("config/private/fcp-runtime.properties").resolve().as_uri())')
java -Xms128m -Xmx1024m -jar work/locked-build/release/ruoyi-admin.jar --spring.profiles.active=prod,fcp "--spring.config.additional-location=$RUNTIME_URI"
```

启动前核对并清除会覆盖本次配置的遗留 FCP/Spring/JVM 环境项，但保留本次显式需要的 Python 路径设置。使用隔离 shell，记录实际启动参数和进程树。核对管理端 /admin/、教师端 /teacher/，刷新、直接访问嵌套路由和静态资源都要正常。Java 负责两个预构建前端及 Python 插件；MySQL、Redis 和 Python 解释器需要提前准备，不应为每个界面再启动独立应用进程。

创建并真实登录管理员、教师、只读用户及第二租户用户。默认租户的 FCP 角色是 942101 管理员、942102 教师、942103 只读；必须确认对应有效角色、菜单与租户套餐实际授权，不能只伪造前端 roles。超级管理员仍需显式 FCP 角色。教师限定 class-a，拒绝 class-b。fcp_eval_assignment 使用 scope_id=tenant-<原生tenant_id> 与真实用户 subject_id；按实际 SQL 列定义插入或通过受审查管理方式配置。第二租户需要自己的角色、套餐和班级授权，禁止复用错误租户关系。

原生验证码、加密握手和登录必须实际执行。自动化需要 token 或 storage state 时，只使用此次真实登录生成的内容，保存在 config/private/ 或 e2e/states/ 并限制权限。不得在报告、截图、终端日志或 Git 中输出秘密。

## 实际数据库、API 与浏览器验证

从原生 JAR 的 BOOT-INF/lib 中取得真实 mysql-connector-j 驱动到私有本地目录。准备仅本地可读的 jdbc.properties，内容为 jdbc.url、jdbc.user、jdbc.password，使用同一专用 fcp_ci_* 库和仅 DML 账号。以下类路径示例是 Linux，Windows 分隔符为分号。

```bash
java -cp "build/component-classes:config/private/mysql-connector-j.jar" RealMySqlRegression config/private/jdbc.properties
```

该测试实际验证 MySQL 产品/版本、运行账号不能 CREATE TABLE、并发状态 CAS、持久状态、审计唯一性和事务失败回滚。它只覆盖 JDBC 状态适配器，不能代替业务登录、权限和完整数据恢复。增量迁移另按 docs/MIGRATIONS.md 在从旧版本准备的另一专用库做计划、应用、重复执行、校验和漂移、中断和恢复；不要拿 fresh install 冒充版本升级。

在仅本地私有 shell 中配置 FCP_BASE_URL、FCP_ACCEPTANCE_DATABASE、FCP_CLIENT_ID 和 FCP_ADMIN_TOKEN、FCP_TEACHER_TOKEN、FCP_VIEWER_TOKEN、FCP_OTHER_TOKEN。FCP_ACCEPTANCE_DATABASE 必须与实际库名完全一致。review1 的 HTTP 客户端只接受 loopback，不跟随重定向，不读取系统 HTTP 代理，防止 token 发往错误目标。

```bash
python tools/verify_native_api.py
python e2e/capture_native.py --base http://127.0.0.1:8080 --headed --output reports/local-review-browser
```

实际端口按此次私有配置调整。浏览器工具需要真实 Playwright 和 Chromium；按本机授权安装或使用已配置工具，不在缺依赖时生成假图片。headed 模式需要在实际浏览器完成登录和验证码；也可使用真实登录保存的 e2e/states/admin.json、teacher.json、viewer.json，再通过 --states-dir 传入。状态文件禁止打包。录屏必须能解码，截图必须覆盖两个真实 UI、管理员与只读差异、实际保存和 Python 统计结果。

现有浏览器脚本只覆盖部分流程。补测实际注销和跨端会话变化、空列表、长中文、错误提示、桌面及 390px 视口、网络失败和控制台警告/错误。补测管理员启停后教师旧 generation 被拒绝、直接 API 越权、伪造租户、跨班级读取与写入、重复 requestKey 与冲突载荷、撤销角色/菜单/套餐/班级授权、禁用用户、租户过期、JAR 重启后停用状态与数据仍保留。禁止通过隐藏按钮替代服务端断言。

分页和统计有明确边界需要实测：当前列表按随机 UUID 游标排序，每页默认 25 条，新增后刷新第一页，记录达到多页时新记录可能不在第一页；应验证保存反馈和新增记录可定位性，必要时修复并补浏览器回归。当前统计一次最多允许授权范围内 1000 条，超过时返回 RESULT_LIMIT；UI 尚未提供统计窗口选择。该演示限制不满足任意规模聚合要求，必须在报告列明，不能用 1000 条以内结果冒充十万条统计。修复时保留授权过滤、明确窗口/分页语义，并提供真实 SQL 和浏览器证据。

## 性能、长测与最终报告

先在专用小数据集上跑 API 和浏览器，再用另一隔离数据环境做负载，避免用压测数据使基础录屏用例随机失败。下面的工具只做单身份列表读取和可选 Linux Java 内存采样，覆盖范围有限。

```bash
python tools/measure_native.py --requests 2000 --concurrency 8 --java-pid <实际原生Java进程PID>
python tools/production_gate.py --release work/locked-build/release --evidence reports/production
```

按实际条件补充启动耗时、Java 与 Python RSS/PSS、线程数、空闲回收、进程上限、p50/p95/p99、成功率和吞吐；写明硬件、数据量、租户数、并发、持续时间和冷热状态。CPU/内存优化必须与同硬件同数据的原生基线比较。config/production-targets.json 是目标，当前工具的单身份读取不覆盖其中混合负载、50 用户、十万记录或 24 小时稳定性。

本地实测完成后交付中文 LOCAL_ACCEPTANCE_REPORT.md、机器可读结果、原始日志、关键截图与可播放视频、构建和 JAR 摘要、修复提交以及唯一 Java 启动命令。区分组件、原生构建、数据库、API、浏览器和性能。缺少稳定性、安全审计、SBOM、恢复或其他必需证据时，production gate 必须继续 BLOCKED；已通过的本地集成项目可以单独标明通过。没有实际运行足够时长的稳定性测试标为 NOT_RUN，不延后承诺后台执行。
