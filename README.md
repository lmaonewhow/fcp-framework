# FCP 0.6.0-dev.1：若依宿主、跨语言插件与数据接入

本分支 `feat/ruoyi-5.6-plugin-ingestion` 在既有 FCP 基线上增加可启停的业务插件、API 连接器与文件适配器，固定使用 RuoYi-Vue-Plus 5.6.2 作为真实宿主。管理端固定 plus-ui 5.6.2-v2.6.2，教师端固定 ruoyi-plus-soybean 1.1.3，精确提交见 [upstreams.lock.json](upstreams.lock.json)。宿主保留原生登录、验证码、租户、权限、MySQL 和 Redis。

**指定小型场景已通过真实原生验收，生产发布仍未验收。** 被测源码为 `9bf4031a38a065139d016903cfdb59179055c9a4`，通过 240 项组件检查、105 项保留回归、76 项输入检查和 61 项原生场景检查；检查组存在交叉。详情、构建摘要、录屏与限制见 [本次验收记录](docs/INGESTION_ACCEPTANCE.md)。组件与原生流水线分别输出结果，原生报告位于对应运行的 `native-ingestion-evidence` 产物。原 0.5 本地验收的历史结果继续保留在 [docs/LOCAL_ACCEPTANCE_STATUS.md](docs/LOCAL_ACCEPTANCE_STATUS.md)，不代表 0.6 或新宿主已经验收。原源码快照不含旧 Git 历史；本功能分支的后续修改使用普通提交记录。

## 当前实现

学生名册与考勤汇总分别由 Python 业务模块实现，每个模块配有自己的 API 连接器和 CSV 文件适配器，总计六个独立插件。它们通过统一清单声明契约、依赖与启动命令，可以单独启停。Java 按需启动子进程，停用时清理相关进程；插件状态和已导入数据保留到重启之后。停用业务模块会阻止依赖它的输入插件继续调用，其他业务仍可使用。

外部数据经过输入插件解析、业务校验、授权检查、版本检查及统一事务提交。系统提供按租户和班级隔离、幂等重试、更新合并、整批拒绝、导入审计以及考勤对名册的引用校验。API 地址与凭证只能由部署者的私有配置提供，页面不能传入任意 URL 或程序启动命令。

插件进程使用 JSON-RPC/stdio，语言不限定为 Python。真实 Node.js 提供者已加入互通回归测试；其他语言需要实现相同通信契约并安装相应运行时。当前支持已安装插件的运行时启停，目录变更仍通过受控发布和重启完成，尚未提供任意代码热安装市场或不可信代码沙箱。

完整结构、协议、文件格式、私有配置、限额和学校演示流程见 [docs/INGESTION.md](docs/INGESTION.md)。API 使用明确标注的 Campus-v1 合成测试平台，文件适配支持约定格式的 CSV，当前没有宣称已经接通任意商业教育平台或任意 Excel 文件。

## 组件复测

组件环境需要 Java、Python、Node 和 TypeScript。Java 编译目标为 17；原生构建使用 JDK 21。下列命令分别输出 `reports/components.json`、`reports/legacy-regressions.json` 与 `reports/ingestion.json`。报告明确区分真实进程/文件测试与 JDBC 测试替身；覆盖范围有交叉，检查数量不能相加为独立业务功能数。

```bash
python tools/verify_components.py
python tools/verify_legacy.py
python tools/verify_ingestion.py
```

输入回归实际启动 Java、Python、Node 与回环 HTTP 服务，验证分页、CSV 编码、业务校验、租户/班级授权、重复提交、插件停用及持久化重启。该命令使用文件存储，不替代真实 Spring、MySQL 或浏览器验收。

## 真实若依构建

构建需要 Git、JDK 21、Maven 3.9.x、Node 22、npm、pnpm 10.5.2、Python 和上游源码/依赖下载能力。MySQL、Redis 与语言运行时需要预先准备。工具不安装或修改用户已有数据库。先执行环境检查并生成私有配置，密钥和凭证不得提交到仓库。

```bash
python tools/native.py doctor
java bootstrap/GenerateKeys.java config/private
python tools/native.py assemble --work work/prepared --build-env config/private/build.env
```

随后为本次测试创建独立的本地空数据库和 Redis 命名空间，使用 `tools/mysql_install.py` 初始化空库，准备部署账号与仅具 DML 权限的运行账号，并填写 `config/private/fcp-runtime.properties`。运行配置必须指向专用测试库和回环服务；上游 Maven 的 prod 标签测试会实际启动 Spring，所以数据库必须先于构建准备好。仓库已保存通过验收的管理端依赖锁；常规构建直接复用并校验，升级依赖时才显式重新解析和验收。

```bash
python tools/check_ingestion_lock.py
python tools/native.py build --work work/locked-build --local-sources work/prepared/upstream --build-env config/private/build.env --test-runtime-config config/private/fcp-runtime.properties --admin-lock locks/admin-package-lock.json
```

每次构建目录必须为空。构建器核对上游提交、补丁前后摘要和原生集成锚点，遇到漂移明确失败。管理端和教师端均执行真实类型检查与生产构建，后端保留 prod 标签原生测试；它不是上游全部测试。完整上游仓库、依赖和原生 JAR 不内置于源码仓库。

## 一个 Java 应用入口

在发布目录部署 `ruoyi-admin.jar`，将私有 `fcp-runtime.properties` 放到工作目录，并通过其中的 `FCP_INGESTION_CONNECTIONS` 指向私有连接器配置。应用使用原生 `org.dromara.DromaraApplication` 入口，双前端随 JAR 提供，静态前端服务无需 Node/Vite；启用 Python 或 Node 运行时插件时，宿主仍需安装对应解释器并显式配置其绝对路径。

```bash
java -jar ruoyi-admin.jar --spring.profiles.active=prod,fcp
```

管理端路径为 `/admin/`，教师端路径为 `/teacher/`，数据接入页分别为 `/admin/fcp/ingestion` 与 `/teacher/fcp/ingestion`。账号需要明确授予 FCP 权限，教师还需要班级授权；原生超级管理员也须具有显式 FCP 授权。登录令牌由真实宿主获取，不能用伪造令牌或替代后台进行验收。

## 运行时插件与开发宿主

运行时插件能力需要显式开启，不会随安装自动授权或执行。两套真实前端工作台为 `/admin/fcp/plugins` 与 `/teacher/fcp/plugins`，管理操作由服务端原生权限决定。共享组件通过各自 UI 适配器接入路由、输入控件和槽位，不把运行时包当作任意前端脚本执行。

- [原生接入与授权配置](docs/runtime-plugin/NATIVE.md)：V005/V006、运行账号与独立部署账号、可信发布者、解释器及管理流程。
- [独立开发前端与学生闭环](docs/runtime-plugin/DEVELOPMENT.md)：独立构建、私有开发身份、页面槽位与显式 JDBC 开发存储；不替代原生或生产验收。
- [开发宿主与生命周期](docs/runtime-plugin/LIFECYCLE.md)：直接编译共享 Java 代码并启动开发宿主，不依赖预先构建的 ruoyi-admin.jar；开发认证文件不会进入生产 JAR。
- [版本暂存、升级与回退](tests/runtime-versions/README.md)：明确确认、停用排空、结构兼容及重新授权，不承诺零停机升级或自动数据库回退。
- [统一导入导出与任务](tests/runtime-exchange/README.md)：文件/API 同一管线、预览确认、独立 TASK、持久回执/游标及 24 小时临时导出内容清理。业务数据和审计不硬删除。
- [双前端适配与验收](tests/frontend-runtime/README.md)、[宿主一致性和依赖边界](tests/runtime-portability/README.md)。

带存储声明的包先暂存验证，再由独立部署身份审查并执行 schema 计划，最后安装、显式授权和启用。既有版本的替换必须走升级/回退计划，普通安装不是绕过版本确认的入口。完整目标与后续阶段见 [设计 1.1](docs/runtime-plugin/DESIGN.md)；代码存在或本地编译成功不等于 A01–A41 全部通过。

## 原生场景验收

工作流 `.github/workflows/native-ingestion.yml` 在独立 GitHub Actions 运行器中启动回环 MySQL 8.4 和 Redis 7.4，创建临时测试库与账号，执行组件回归、锁定构建、真实 JDBC 检查、加密登录、双前端导入与权限验证、停用进程检查和 JVM 重启。`tools/ci_native.py --ingestion` 仅允许该隔离 CI 使用，不能直接对生产库执行。

原生证据包含 `reports/native-source-commit.txt`、`reports/ci-native.json`、构建标识、原始日志以及 `reports/native-ingestion/` 中的验收报告、截图和录像。验收报告必须绑定同一被测源码和构建产物。失败记录也应保留，测试未运行记为 NOT_RUN，环境阻断记为 BLOCKED，观察到缺陷记为 FAIL。仓库中的流程或测试代码本身不构成通过证据。

演示平台位于 `tests/ingestion/platform.py`，CSV 样例位于 `fixtures/ingestion/`。完整场景从三人名册 API 同步开始，经 CSV 更新为四人名册，再导入和校正考勤，检查停用、权限与重启持久化。详细操作和业务口径见 [数据接入说明](docs/INGESTION.md)。

## 数据库迁移和生产边界

本分支包含 V004 输入插件权限、V005 运行时状态/存储及 V006 插件管理权限迁移；它们不自动给已有角色授权。既有安装按 [docs/MIGRATIONS.md](docs/MIGRATIONS.md) 生成并审查迁移计划后执行。空库初始化工具不用于覆盖已有数据库。MySQL DDL 失败不承诺自动回滚，运行账号没有 DDL 权限。不得对用户已有库执行清库、强制恢复或 Redis FLUSH 操作。

当前适用范围为可信插件、Linux 单节点、每班每种业务最多 1,000 条记录。普通进程隔离不能替代恶意代码沙箱。同源双前端、依赖供应链、实际学校平台、长期稳定性、备份恢复、生产 TLS 与多节点一致性仍需独立验证。生产门禁使用 `tools/production_gate.py`，目标数值在 `config/production-targets.json` 中，不能当作实测性能。

此前 review1 审查及修订依据见 [docs/REVIEW.md](docs/REVIEW.md)，原生本地工作规范见 [CODEX_LOCAL_TASK.md](CODEX_LOCAL_TASK.md) 与 [AGENTS.md](AGENTS.md)。这些历史文件中的旧版本和旧结果用于追溯；当前宿主版本以本分支锁文件为准，运行结果以对应源码提交的原始证据为准。
