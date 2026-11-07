# 独立开发宿主与演示壳

这是显式开启的开发工具，不是第二个生产宿主，不替代若依的登录、验证码、数据库或生产验收。只绑定回环地址，`FCP_ENV=production` 拒绝启动。前端资源只来自程序内置白名单，不读取插件包中的脚本；页面与槽位使用与两套若依 UI 相同的 `UiRuntime` 契约校验。

## 启动

准备 JDK 17+、Python 3，Node 仅用于 Node 插件。无需 Maven、若依 JAR 或若依前端。运行：

```sh
python3 tools/build_devhost.py
python3 tools/init_devhost.py config/private/development
java -cp build/development-host dev.fcp.dev.DevHost --development config/private/development/identities.json config/private/development/state 18880
```

打开 `http://127.0.0.1:18880/dev/`。初始化器生成两个示例租户，各有管理员与只读身份；随机令牌仅写入 0600 的身份文件，不打印、不提交。启动与每次热重读都要求身份文件为普通非符号链接文件，权限限 0600 或 0400，父目录限当前用户私有。开发者从自己的私有文件读取所选身份的令牌并输入页面。令牌只留在当前页面内存，退出或刷新后重新输入，不保存到 localStorage、cookie 或 URL。初始化器拒绝覆盖已有配置，初始没有可信发布者或业务授权。

页面支持受信 ZIP 上传、列出插件、预览启停计划后明确确认、操作回执、配置页面与预留 `dashboard.status` 槽位。安装不会自动启用或放权。当前用户没有相应权限时，页面不提供管理操作，服务端仍独立校验。插件能力版本或目录变化时，旧页面输入和未确认动作会清理。普通调用不绕过导入预览确认。

## 私有配置与存储

`trustedPublishers` 明确列出审核过的发布者。每个身份的 `permissions` 是宿主权限，`grants` 逐项列出 `plugin`、`capability`、`operation`、`dataScope` 和对应 `permission`，不支持隐式管理员通配授权。运行时插件仍只能通过宿主能力读写数据，不能拿到 JDBC 凭据。

`GET /fcp/development/configuration` 返回当前租户的开发偏好及 revision；`POST` 接收 `{expectedRevision, values}` 并做 CAS 持久化。它不改变身份、可信发布者、连接器、JDBC 或任何运行配置，也不用于保存秘密。配置文件和状态目录必须保留在私有目录。

没有 `storageJdbc` 时，开发壳可以验证不依赖存储的插件；带存储声明的包不会因为开发模式而跳过结构校验。要运行真实学生数据闭环，显式配置 `storageJdbc` 的 `url`、`user`、`password`，只允许回环 MySQL 地址。使用预先按仓库迁移流程准备的独立开发库、仅 DML 运行账号以及审核过的插件声明表。DevHost 没有自动建库、DDL、权限管理或直接执行 SQL 的接口。回环地址只能限制连接位置，不能证明目标是独立库或账号确实只有 DML 权限；这些由部署者配置并核验，当前没有自动审计数据库账号全部有效权限。CI 使用自己新建的隔离库及仅 DML 运行账号，不能把 CI 的条件推定为任意手工配置都安全。

JDBC 驱动由开发者按实际使用版本准备，并加入类路径：

```sh
java -cp 'build/development-host:/absolute/path/mysql-connector-j.jar' dev.fcp.dev.DevHost --development config/private/development/identities.json config/private/development/state 18880
```

启用 `exchangeFixture: {classId: "class-a"}` 后，开发壳展示合成数据导入导出入口。可额外配置 `campusApi` / `examApi` 为受控回环夹具地址。它复用原生 `ExchangeService`、`StorageCapabilities` 与 `JdbcPluginStorage`；`roster-fixture` / `exam-fixture` 只代表现有合成 CSV 契约，不代表学校正式格式已确定。用户不能从请求中提交任意 URL、租户或数据库连接。除插件能力授权外，必须分别授予 `exchange` 的 import/export、`storage` 的 read/write 以及精确 host.storage 能力权限。开发恢复 TASK 只能恢复生命周期，不能继承管理员业务权限；该工具未启用通用后台任务调度器。

## 复验及边界

```sh
python3 tools/verify_devhost_shell.py
python3 tools/verify_runtime_portability.py
```

前者需要 TypeScript 5.8.3 和已安装 Chromium 的 Playwright，真实启动独立 JVM 与浏览器，检查内置壳、插件上传/启停、页面槽位及身份边界。后者继续运行共享跨语言 HTTP 契约。没有环境时不能把检查记为通过。

完整原生 CI `tools/ci_native.py --ingestion --exchange` 在一次性的实际 MySQL 中，先通过若依真实登录和受控部署接口安装声明表，再在独立开发租户及独立状态目录启动 DevHost。两个宿主调用同一份 `tests/runtime-portability/business_contract.py`，检查学生 CSV/API 预览一致性、无预览写入、确认/重试、冲突、数据范围、撤权及受控导出，比较相同学生记录；开发 JVM 实际退出重启后再检查 MySQL 数据持久化。它不替代原生故障矩阵，不把开发文件日志说成生产 JDBC 日志。

原始结果见该次 CI 的 `reports/development-business/acceptance.json` 和 `reports/development-host/`，每次必须核对源码提交。独立真实第二宿主、生产认证、长期稳定性、多实例及恶意插件操作系统沙箱仍未验收。
