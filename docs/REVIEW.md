# FCP 0.5.0-dev.1 review1 审查与本地交接报告

审查日期：2026 年 11 月 1 日。基线提交：55eb7f7e47323ac2e96c2166eb0abc34796cd47f。修订分支：fix/local-codex-review-0.5.0。最终修订提交和文件摘要记录在 DELIVERY.json。本报告记录当前环境真实执行的工作，完整原生集成与生产发布仍为 BLOCKED。

## 结论

原交付存在已复现的进程超时清理缺陷，也有可以通过上游源码确认的原生构建顺序问题，不能直接标为“检查无问题”。review1 修复了这些已确认问题，补充本地验收的防误操作检查并重新构建纯框架 JAR，可以作为本地 Codex 实测起点。原生 Maven、MySQL、Redis、双前端与浏览器尚未实际运行通过，代码修正不能代替这些验收。

仍需关注两个业务边界：默认列表按随机 UUID 游标分页，新增后刷新第一页可能看不到该条记录；统计目前限制授权范围内最多 1000 条，超过时返回 RESULT_LIMIT，UI 没有选择统计窗口的入口。前者需要真实多页数据下确认并改善反馈，后者属于当前演示的规模限制。CODEX_LOCAL_TASK.md 要求本地验证并在需要时修复，不能声称它已支持任意规模统计。

## 文件、历史和审查范围

原源码 ZIP 的 SHA256 为 6ab1f44754b2281c1c1ee7098f2ef1a388dfe6d82ce803a06eabfa49ed530e43；原纯框架 ZIP 的 SHA256 为 02413397e02c4e74eeef47650d69242f8a82fcf53b3be7844bec0f8a056092b0。两个 ZIP 的 CRC、解压路径、198 项与 22 项内部摘要核对一致。Git bundle 成功恢复上述基线；修改均在独立修订分支进行，未推送远程或运行远程 CI。

审查覆盖 ProcessProvider、状态与持久化、业务授权与记录、原生宿主配置、双前端接入层、构建器、迁移与验收工具。没有进行完整依赖供应链安全审计、真实 SQL 执行、操作系统沙箱审计或形式化证明。本文中的源码观察、单元验证与真实进程测试有各自范围。

## 已复现并修复：超时清理阻塞

旧 ProcessProvider.stop() 调用 Process.destroy()。真实 Python 夹具设置忽略 SIGTERM 且不读 stdin，Java 发送 512 KiB 数据后写管道阻塞；超时清理中的 destroy 又尝试关闭/flush 管道，等待同一写锁，导致控制线程卡住。外部 9 秒 watchdog 触发，线程栈显示 ProcessImpl.destroy、FilterOutputStream.close 与 BufferedOutputStream.flush 的等待链。故障日志和线程栈保留在 reports/review1/stalled-stdin-before.log。

修订使用 ProcessHandle 发出终止信号，按有界等待逐步强制终止，避免在清理线程同步关闭/flush 正被使用的管道；进程名额在确认退出后释放，尚未退出时通过退出回调释放。新增 tests/java/ProcessTerminationRegression.java，以真实不读输入且忽略终止信号的 Python 子进程检验超时返回和配额回收。修复后同一夹具约 3.06 秒返回 DEADLINE 并释放名额，证据见 stalled-stdin-after.log。本数字只描述该复现夹具，不代表原生业务性能或所有操作系统的上界。

当前对子孙进程的清理仍是可信插件场景的 best-effort，不是恶意插件操作系统沙箱。Linux 实测没有替代 Windows、macOS 或所有 JDK 实现验证。

## 已修正：原生构建初始化顺序

锁定若依提交 64100cf1ffaeec78e36344d4617f6cb828ef093f 的 root pom 通过 profiles.active 选择 Surefire 标签；TagUnitTest 的 prod 方法所属类使用 @SpringBootTest，构建时会启动 Spring 并触发 FCP 数据库结构检查。旧 ci_native.py 在 build 之后才初始化数据库与运行配置，顺序不满足该前提。

修订流程为 assemble、创建专用本地 MySQL/Redis、初始化数据库、准备受限运行账号和配置，再执行 Maven/前端 build。native.py build 强制接收 --test-runtime-config，核对私有文件权限、必要配置、loopback 地址、专用测试库名及 Redis 前缀；构建子进程清理继承的 FCP、Spring 和常见 JVM 注入项，通过 SPRING_CONFIG_ADDITIONAL_LOCATION 与 prod,fcp 显式加载本次配置。GenerateKeys 生成随机 Redis 前缀，CI 编排及 README 已同步。

新增单元测试验证配置拒绝路径、环境隔离与 CI 源码顺序。它们没有连接真实数据库。实际 Maven 与 MySQL CI 运行仍需本地完成。上游测试结果注明 prod 标签选择，不能将该子集称为上游全部测试。

上游原始证据为 root pom 的 Git blob f8dab42db452ca2d03bff628c71110930e35857f，以及 TagUnitTest.java 的 Git blob b50afa6cc18c390d538b82fa732e5aa54eef161f。构建器已加入二者校验。固定源码可在 https://github.com/dromara/RuoYi-Vue-Plus/blob/64100cf1ffaeec78e36344d4617f6cb828ef093f/pom.xml 与相同提交的 ruoyi-admin/src/test/java/org/dromara/test/TagUnitTest.java 复核。Spring 配置加载依据 https://docs.spring.io/spring-boot/3.4/reference/features/external-config.html 。

## 已修正：锁文件、测试夹具及验收工具

第一次管理端依赖解析完成后，构建器立即保存 release/admin-package-lock.json。后续教师端类型检查或后端构建失败时，该锁仍可检查和复用，不会错误生成 BUILD-EVIDENCE PASS。新增失败路径单元用例验证保留行为。

原进程边界夹具只有 1 秒预算，本环境观察到最简单 Python 冷启动超过 1 秒，单独复测出现误报。修订仅将测试夹具预算调整为 4 秒、故意超时行为延长为 6 秒，并使用有上限的线程回收轮询，保留全部 13 项断言。生产运行时配置没有因此调大。原误报保留在 process-probe.log，后续全套保留回归通过。

NativeClient 仅接受 loopback 纯 HTTP(S) origin，拒绝重定向并禁用系统 HTTP 代理；端点路径限定为 FCP，本地数据库身份必须与显式 FCP_ACCEPTANCE_DATABASE 完全一致。浏览器入口复用 loopback 检查。新增 15 项 Python 工具单元检查涵盖这些约束、构建锁保留、运行配置和 CI 顺序。此为防误操作措施，不能取代用户对 MySQL 客户端配置、容器、网络和数据库权限的检查。

## 本轮实际验证

最终 tools/verify_components.py 为 190 PASS、0 FAIL：Java 组件与真实 Python 33 项、真实清理回归 2 项、持久化/生命周期 30 项、JDBC 协议替身 22 项、迁移包/解析 14 项、TypeScript 会话单元 21 项、Python 构建/门禁/工具单元 64 项、Java 密钥生成检查 4 项。JDBC 替身与迁移解析都没有在 MySQL 执行。

tools/verify_legacy.py 为 105 PASS、0 FAIL。独立框架重新编译后的真实文件状态与 Python 示例为 13 PASS。组件与保留回归覆盖有交叉，这些数字不可相加当作独立业务功能数。真正执行的命令、退出码和耗时在 reports/review1/final-run.json；最终明细为 reports/components.json、reports/legacy-regressions.json 和 reports/review1/framework-final.log。

初次组合复测发生超时，之后隔离定位到清理缺陷；中间一次框架示例命令遗漏 core/examples 参数导致找不到 worker.py，修正调用路径后通过，未把这一命令错误当作框架通过。失败记录一并保留。最终打包后的独立解压复核记录在外部 FCP_review1_干净解压复核.json，不以计划代替结果。

实测环境为 Linux x86_64、OpenJDK/Javac 21.0.12.1、Python 3.13.5、Node 22.16.0、TypeScript 5.8.3，Java 编译目标为 17。独立 JDK 17、Windows、macOS 未实测。

## 未验证及生产阻断

当前环境缺少 Maven、pnpm、MySQL、Redis 和 Docker，GitHub、Maven Central 与 npm 的容器 DNS 解析失败。doctor-final.log 记录构建工具与 DNS 检测；本轮未再次尝试已明确缺少前置条件的完整原生构建，未报告真实 Spring/MySQL/Redis 通过、原生页面截图或性能。使用连接器读取固定源码不等于容器能下载依赖和运行构建。

production_gate.py 在缺少原生构建与证据时实际返回退出码 2、BLOCKED。final-run.json 中该步骤的 passed=true 表示“按预期拒绝发布”，绝不表示生产通过。真实账号权限、增量迁移、双 UI、XSS/会话安全、依赖审计/SBOM、混合负载、24 小时稳定性、备份恢复以及真实学校主数据接入仍未完成。

## 给本地 Codex 的交接

解压修订包后，在该目录打开本地 Codex，要求读取 AGENTS.md、CODEX_LOCAL_TASK.md 和本报告后执行。任务文件包含独立 worktree、数据库先于 Spring 测试初始化、锁定构建、Java 唯一应用入口、真实四类账号、MySQL 适配器、API/浏览器/录屏、性能与失败修复要求。没有远程操纵用户的本地 Codex，也没有把用户拒绝的 Work 模式接管视为成功。

完整包中的 JAR 为 FCP 库与迁移工具，原生应用 JAR 需要本地构建。review1 沿用 0.5.0-dev.1 的协议与库版本标识以保持既有接口检查一致，源码提交、review 标识和 SHA256 区分本次二进制；不要与原 dev.1 JAR 混用。纯框架包也已重新构建，应替换旧版本对应文件。
