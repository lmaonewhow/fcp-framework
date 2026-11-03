# FCP 0.4.2 真实宿主接入：验收报告

日期：2026 年 11 月 1 日。结论：**本地组件与保留回归通过；真实若依全链路发布门禁 BLOCKED。** 当前交付为真实宿主接入源码工程与构建、验收工具，不包含已通过验收的原生可运行 JAR。

## 实际完成的代码工作

工程延续 FCP 0.4.1 内核与业务能力，增加面向原生 RuoYi-Vue-Plus 5.4.1 的 Spring 配置、控制器、统一会话与菜单适配。真实管理端基于 plus-ui，真实教师端基于 ruoyi-plus-soybean；分别保留 Element Plus 和 Naive UI 的原生项目结构、登录、请求处理和布局。固定仓库、标签、提交与关键文件 Git blob 校验写入构建器。

构建器在新目录和新分支中取得上游，向实际 `ruoyi-admin` 模块装配 FCP 源码，向实际 Vue 项目加入业务页面和会话桥接。它将两个生产构建复制进原生 JAR，明确指定 `org.dromara.RuoYiApplication` 为启动类。原有根路径控制器复用为管理端跳转，避免重复请求映射。FCP profile 按最后优先顺序加载，并在启动时检查配置顺序。

共享会话覆盖了管理端原生 TokenKey 和 Soybean 统一 storage API，使原生请求拦截器继续发送正确的 Authorization 与 clientid。教师导航接口返回 Soybean 原生解析器期待的若依格式路由。后端权限由实际若依会话解析，班级授权由数据库读取，前端切换不会提升角色或数据权限。

原生适配器只接受宿主真实 DataSource，启动时检查 MySQL 版本及表列，配置失败时不回退文件存储。SQL 包包含独立 FCP 业务表、幂等记录、审计、班级授权及原生菜单角色。初始化器只处理空的隔离验收库，记录逐阶段执行状态，不对既有生产库进行自动覆盖。

Python 资源打包到 JAR，通过校验索引释放到私有临时目录，Java 负责按需创建、空闲回收和退出清理。当前实际组件测试已经调用真实 Python 子进程；真实 Spring、MySQL 与浏览器链路仍未运行。

## 执行环境与实际结果

执行环境为 Linux x86_64、OpenJDK 21.0.12.1、Python 3.13.5、Node 22.16.0、TypeScript 5.8.3。纯 Java 代码使用 `javac --release 17` 编译，未在独立 JDK 17、Windows 或 macOS 上复测。

`tools/verify_components.py` 通过 82 项检查：Java 组件与真实 Python 调用 33 项，共享会话 TypeScript 单元检查 21 项，构建器和发布门禁单元检查 24 项，Java 密钥生成执行检查 4 项。Java 业务测试使用显式的 FileRecordStore 测试夹具，TypeScript 的 fetch 使用显式测试替身；它们均不算真实宿主端到端验收。密钥生成检查验证创建、配置字段、文件权限与拒绝覆盖，未验证浏览器与原生后端的完整加密握手。

`tools/verify_legacy.py` 保留并重跑原始框架回归，通过 105 项检查：内核、双流程、请求边界、审计、运行兼容、优化、进程资源限制均完成。部分 JDBC 检查使用测试代理，不能当作 MySQL 执行结果。首次进程配额测试使用了错误的测试启动参数 `fcp.process.max`，更正为内核真实读取的 `fcp.maxProcesses` 后全部通过；保留首次失败日志，并未修改内核来绕过断言。

真实构建实际尝试过，日志中的 Git 返回码为 128，错误为 `Could not resolve host: github.com`。环境检查同时发现 Maven 和 pnpm 不存在，GitHub、Maven Central、npm registry 的域名解析失败。没有通过临时 Spring 桩、参考 HTTP 服务器或伪造前端代替这些步骤。

原生 API 与性能验收脚本在缺少真实宿主会话时返回 BLOCKED；浏览器脚本在没有真实登录状态时返回 BLOCKED，未启动浏览器，也未生成本轮录屏。发布门禁确认缺少真实 JAR、完整构建证据、API/浏览器/性能结果及录屏，最终保持 BLOCKED。不能把这些阻塞路径的正确退出视为真实业务验收通过。

## 性能范围

代码层面设置了较小的 Undertow、Hikari 与 Redis 池，并关闭演示闭环不需要的 SnailJob 客户端和监控注册。两套前端预构建，运行阶段不常驻 Node/Vite；Python 延迟启动并回收。上述机制不等于原生整体内存已经下降，线程池缩小还可能降低高并发能力，需要以真实负载复测。

本轮没有可报告的原生若依启动时间、RSS、吞吐或 p95。此前轻量参考宿主测得的数据与本工程不具备相同测试对象，未复制进本报告。`tools/measure_native.py` 已提供真实列表请求测量，并在可读取 `/proc` 的 Linux 上采样指定原生 Java PID；它仍需在真实运行环境执行。

## 证据与交付范围

`reports/components.json` 和各组日志保存本轮组件结果，`reports/legacy-regressions.json` 与 `reports/legacy` 保存原始回归结果。`reports/doctor-last.json` 保存环境检查，`reports/native-build-attempt.log` 与 `reports/build-last.json` 保存真实构建失败。`reports/native-api.json`、`reports/native-performance.json`、`reports/native-browser/browser-report.json` 明确记录未运行原因。`reports/RELEASE-GATE.json` 是最终门禁状态。

`upstreams.lock.json` 固定三个上游项目；`tools/native.py` 固定适配文件的 Git blob SHA。仅有三个完整、小型上游文件被作为单元测试夹具验证其逐字节哈希，尚未在本机完成三个完整仓库的组装构建。压缩包提供 FCP 接入代码与组装工具，上游完整仓库由构建工具在可联网环境获取。

首次 plus-ui 依赖解析需要显式同意 `--resolve-admin-lock`，因为选定标签未提供锁文件。后续可用 `--admin-lock` 恢复上次保存的锁文件。尚未生成依赖锁、SBOM 或完成生产漏洞审计。构建成功也只会写入 BUILT_NOT_RUNTIME_VERIFIED，无法单凭编译绕过真实验收。

## 剩余工作与安全限制

必须在具备真实源码与依赖下载能力、Maven、pnpm、MySQL 和 Redis 的环境中完成原生 Maven 打包、两端 Vue 构建与类型检查、真实配置启动、数据库 SQL 执行、正常登录、角色与租户权限验证、Python 调用和浏览器录屏。仍需要修复实际完整构建和运行可能暴露的兼容性问题，本报告不承诺尚未执行的步骤一定一次通过。

当前角色种子只覆盖默认租户，第二租户需独立配置。示例班级未接学校主数据。插件启停为单节点内存状态，重启默认恢复启用。管理员权限即时撤销、集群状态同步、数据库增量升级及恢复、全部原生后台页面、强隔离第三方插件、生产安全和跨操作系统部署均未验收。

交付包中的任何 PASS 都只适用于报告写明的测试范围。真实宿主接入尚未达到用户要求的完整最终验收状态。
