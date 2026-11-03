# FCP 0.4.2：真实若依双前端接入工程

本包提供基于 FCP 0.4.1 的真实宿主适配源码、两套原生 Vue 业务页面、固定上游版本的组装构建器、独立数据库初始化工具与真实环境验收工具。当前发布状态为 **BLOCKED：尚未完成真实 Spring Boot、MySQL、双 Vue 前端的完整构建及联调**。压缩包不包含已经验收的 `ruoyi-admin.jar`，也不包含三个完整上游仓库及其依赖缓存。构建器在具备网络与工具链的环境中获取固定提交的真实项目，不生成替代宿主或仿制后台。

本轮已执行 82 项本地组件检查和 105 项保留的框架回归。测试范围、测试替身及构建失败记录保存在 `reports`，详情见 `docs/VERIFICATION.md`。这些结果不能证明原生应用已经运行。

## 工程边界

后端固定为 RuoYi-Vue-Plus v5.4.1，管理端固定为 plus-ui v5.4.1-v2.4.1，教师端固定为 ruoyi-plus-soybean v1.1.3。完整提交号记录在 `upstreams.lock.json`。版本沿用此前集成基线，未宣称这些版本是当前最新版，也尚未完成生产依赖漏洞审计。

两套真实前端分别构建，产物进入同一个若依 JAR 的 `/admin/` 与 `/teacher/`。应用入口保留 `org.dromara.RuoYiApplication`。页面切换采用整页导航，当前页面只加载所属前端的运行时；没有引入微前端容器，也没有把两个原生项目强行拼成一个 Vue 应用。登录共享同源会话，具体操作授权在若依服务端执行。

Python 统计模块从 JAR 中经过哈希校验后释放到私有临时目录，由 Java 通过受控 stdio 按需启动，空闲约三秒后回收，宿主退出时清理。它不接收数据库口令。本版本运行可信 Python 插件，操作系统级恶意代码沙箱尚未实现。

构建期使用 Git、Maven、Node、npm、pnpm、Python；运行期由一个 Java JAR 提供宿主、接口和两套静态前端。机器仍需具备 Python 运行环境，MySQL 8 与 Redis 作为预先配置的基础服务独立运行，Java 不负责安装或启动数据库。

## 先复核本地组件

在源码包根目录执行以下命令。它们使用隔离的临时目录与测试存储，不需要数据库，不会把结果标为真实若依验收通过。需要 JDK 17+、Python 3.10+、Node 22 及 TypeScript 5.8.3。当前实际执行环境为 Linux、JDK 21、Python 3.13.5、Node 22.16.0。

```bash
python tools/verify_components.py
python tools/verify_legacy.py
```

## 构建真实项目

构建环境需安装 JDK 17+、Maven 3.9.x、Node 22.16 或更新的兼容版本、npm、pnpm 10.5+、Git 与 Python。`doctor` 检查工具是否存在及上游域名解析，不能代替版本兼容性、仓库可达性或依赖安全检查。

```bash
python tools/native.py doctor
java bootstrap/GenerateKeys.java config/private
python tools/native.py build --build-env config/private/build.env --resolve-admin-lock
```

Java 密钥生成器创建本地私有配置，拒绝覆盖已有文件。请保留同一批生成的前后端密钥，不要把私钥配置放入版本控制。构建环境中存在的 `VITE_` 变量会在前端构建命令中移除，实际配置来自本次生成的项目配置文件。两套页面和后端携带同一个构建 ID，供验收工具核对。

`--resolve-admin-lock` 只用于首次显式解析管理端依赖。固定的 plus-ui 上游标签没有附带 npm 锁文件，第一次解析的间接依赖仍需审查。成功构建会保留 `work/release/admin-package-lock.json`；之后应在新的工作目录通过 `--admin-lock` 恢复同一锁文件，避免再次浮动解析。

```bash
python tools/native.py build --work work-rebuild --build-env config/private/build.env --admin-lock work/release/admin-package-lock.json
```

构建器只在新的工作目录内创建三个真实仓库和新分支，不直接编辑用户已有检出。已有本地真实源码时，可增加 `--local-sources /path/to/parent`，其下须有 `backend`、`admin`、`teacher` 三个 Git 仓库，并包含锁定提交。每次组装必须使用新的工作目录；已存在的目录会触发停止，不会自动删除。

构建器先验证上游提交及关键文件 Git blob，再安装依赖、构建真实页面、运行各自 TypeScript 检查，最后执行原生 Maven 打包。Maven 命令使用 `-DskipTests`，这一点会写入构建证据；完整上游单元测试不能因此计为通过。成功后的 `work/release/ruoyi-admin.jar` 必须包含原生启动类、FCP 控制器、两个前端入口和 Python 资源，随后仍需真实运行验收。

## 准备独立数据库与权限

先在 MySQL 中建立专用的空数据库，例如 `fcp_acceptance`，并为部署和运行分别配置最小权限账号。初始化命令只允许名字为 `fcp_*` 的空库，发现已有表即停止。它不适用于已有生产库的增量迁移，也不会删除数据库。

将 MySQL 客户端连接配置保存在 `config/private/mysql-client.cnf`，POSIX 系统设置为 0600。配置采用标准 `[client]` 段，填写实际的 host、port、user、password、ssl-mode；不要把密码写入命令参数或日志。随后先检查计划，再明确执行初始化。

```bash
python tools/mysql_install.py --backend work/upstream/backend --defaults-file config/private/mysql-client.cnf --database fcp_acceptance
python tools/mysql_install.py --backend work/upstream/backend --defaults-file config/private/mysql-client.cnf --database fcp_acceptance --apply
```

初始化导入锁定版本的若依基础 SQL、原生工作流 SQL、FCP 业务表和原生菜单角色。每阶段在 `fcp_install_history` 写入状态。MySQL DDL 不具备本工具承诺的整体事务回滚；中断或失败后必须检查隔离数据库，工具不会自动修复或重复覆盖。上游基础 SQL 含演示账号，服务对外开放前必须更改这些账号的初始密码。

FCP 菜单使用预留 ID 942000 至 942008，角色使用 942101 至 942103。默认租户 000000 下分别建立 FCP 管理员、教师、只读角色。请通过若依原生用户管理创建真实用户并分配对应角色；教师和只读用户还需要在宿主控制的 `fcp_eval_assignment` 中绑定真实用户 ID 与授权班级。例如真实用户 ID 为 123、租户为 000000 时，部署账号可写入以下授权，实际执行前替换 ID。

```sql
INSERT INTO fcp_eval_assignment(scope_id,subject_id,class_id)
VALUES('tenant-000000','123','class-a');
```

示例业务仍使用 `class-a` 和 `class-b`，尚未接入学校真实学生与班级主数据。跨租户验收需要通过原生租户流程建立第二个测试租户及独立角色、套餐权限和账号，不能复用 000000 租户的角色关联。教师只授予 class-a；只读账号不授予创建和管理权限。权限变更后重新登录，当前版本尚未完成会话缓存下的即时权限撤销验收。

## Java 唯一应用入口

把 `config/private/fcp-runtime.properties` 复制到运行目录，与成功构建的 `ruoyi-admin.jar` 放在一起，填写真实 MySQL、Redis 密码及连接参数。建议数据库连接使用经过验证的 TLS；本地隔离测试的连接参数也需根据实际数据库调整。确保配置文件仅运行用户可读。

```bash
java -jar ruoyi-admin.jar
```

默认构建会启用 `dev,fcp`，FCP 必须为最后一个活动 profile。自定义环境时显式使用 `--spring.profiles.active=prod,fcp`。启动适配器会检查 profile 顺序、MySQL 版本及 FCP 所需表列；配置或表结构缺失即停止，不自动退回文件存储。

默认只绑定本机 127.0.0.1:8080。根路径进入管理端原生登录页，获得教师权限的账号完成若依登录后跳转教师端。管理端地址为 `http://127.0.0.1:8080/admin/`，教师端地址为 `http://127.0.0.1:8080/teacher/`。运行时不需要启动 Node、Vite 或另一个 Python Web 服务。

Python 可执行文件可通过环境变量 `FCP_PYTHON` 或 Java 参数 `-Dfcp.python=/absolute/path/to/python` 指定；该 JVM 参数应放在 `-jar` 前。仅写入 Spring 的 properties 文件不会自动设置这个 JVM 属性。Linux 默认使用 python3，Windows 默认使用 python。当前未完成 Windows、macOS 原生部署验证。

## 真实验收与录屏

`tools/verify_native_api.py` 需要四个通过正常若依登录取得的真实会话。设置 `FCP_ADMIN_TOKEN`、`FCP_TEACHER_TOKEN`、`FCP_VIEWER_TOKEN`、`FCP_OTHER_TOKEN`，以及相同的 `FCP_CLIENT_ID`，按需设置 `FCP_BASE_URL`。令牌只能由实际登录产生，不应写进项目、报告、录屏或 shell 历史。脚本会校验原生构建 ID 和 MySQL 数据库，再验证角色、班级、租户边界、幂等、Python 调用与禁用后的数据保留；它会在专用测试库生成合成评价记录。

```bash
python tools/verify_native_api.py
python tools/measure_native.py --requests 2000 --concurrency 8
```

性能工具运行实际授权列表查询，报告 p50、p95、p99、成功吞吐及错误数；Linux 可额外指定 `--java-pid` 采样原生 Java 的 RSS、PSS 和线程数。它不测全部系统业务，也不代表饱和容量或真实学校数据规模。当前包没有原生若依的性能结果，历史轻量参考 Demo 的数字不能沿用。

浏览器验收使用 `e2e/capture_native.py`。在具备浏览器操作权限的开发环境中安装隔离测试依赖及 Chromium，再使用 headed 模式依次完成管理员、教师、只读账号的正常登录和验证码。脚本只访问真实应用，不替换 API，不填充伪造页面。录屏可能包含登录输入，请使用专用测试账号，发布视频前检查敏感信息并撤销测试会话。

```bash
python -m pip install -r e2e/requirements.txt
python -m playwright install chromium
python e2e/capture_native.py --headed
python tools/release_gate.py --release work/release
```

无界面环境可使用通过真实登录获得的 Playwright storage state，置于私有目录后传入 `--states-dir`，文件名为 admin.json、teacher.json、viewer.json。状态文件包含会话，不可纳入源码包。浏览器脚本检查两套原生组件、桌面与移动布局、真实写入、Python 响应及只读拒绝，并输出实际 WebM 录屏。

只有真实构建、API、浏览器、性能测试都成功且构建 ID 一致，JAR 校验和与三段非空录屏也匹配，发布门禁才允许限定场景的 PASS。当前交付的门禁为 BLOCKED。

## 资源与安全边界

配置缩小了 Undertow、Hikari 和 Redis 连接线程池，关闭不需要的 SnailJob 客户端和 Spring Boot Admin 注册，保留原生工作流。Python 懒加载与空闲回收已在组件层实际执行；原生宿主的内存节省比例及并发影响仍需测量。不能套用此前参考宿主的 128 MiB 堆参数或几十 MiB RSS 数据。

核心业务复用宿主 DataSource，运行时没有执行 DDL 的入口。每次数据访问显式携带服务器解析的租户和用户范围；教师班级授权来自数据库。共享 UI 的令牌保存在同源 localStorage，延续原生前端模式，不提供 HttpOnly Cookie 或独立来源沙箱。两个前端都应视作可信代码。

插件启停状态当前保存在单节点内存，重启后默认恢复启用；多节点一致性与持久化启停配置尚未实现。独立第三方进程的操作系统沙箱、完整迁移回滚、生产安全审计、原生全部页面适配、真实学校数据接入均未通过本轮验收。Dapr、微服务和 Kubernetes 不作为当前版本的强制依赖。

`fixtures/legacy-runtime` 与 `fixtures/legacy-sql` 仅保留旧框架回归素材，当前原生课堂评价安装只使用 `sql/native`。不要对当前数据库执行这些旧 SQL。
