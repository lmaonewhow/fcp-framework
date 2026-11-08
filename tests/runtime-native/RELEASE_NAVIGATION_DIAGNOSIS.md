# 组合发布导航失败诊断（2026-11-08）

保留失败运行 42436271219 的 job 128595670927、artifact 13599609654。输入 ZIP SHA-256 为 `58dc1a648eae756ce7d31587740aad87ba52bc4e89d8f67fe2e8a99eefc60b00`。旧报告在 `admin rollback actual Vue shell no uncaught errors` 失败；它仅将 Playwright 异常包装类型保存在局部变量，未在抛出前归档。因此该报告的 `browserErrors=[]` 不能证明这次导航没有异常，也不能恢复缺失的异常内容。

精确后端 pin 为 `8136a0191a2258c0e1b36a8146a1c5ebc070c139`，不是浮动版本。实际核查的源码如下。

- [SysUserController](https://github.com/dromara/RuoYi-Vue-Plus/blob/8136a0191a2258c0e1b36a8146a1c5ebc070c139/ruoyi-modules/ruoyi-system/src/main/java/org/dromara/system/controller/system/SysUserController.java)：`/system/user/getInfo` 方法没有 RateLimiter 注解，不能据此编造 getInfo 的限流阈值。
- [CaptchaController](https://github.com/dromara/RuoYi-Vue-Plus/blob/8136a0191a2258c0e1b36a8146a1c5ebc070c139/ruoyi-admin/src/main/java/org/dromara/web/controller/CaptchaController.java)：`/auth/code` 在验证码启用时调用 getCodeImpl，明确为 60 秒、10 次、IP。
- [AuthController](https://github.com/dromara/RuoYi-Vue-Plus/blob/8136a0191a2258c0e1b36a8146a1c5ebc070c139/ruoyi-admin/src/main/java/org/dromara/web/controller/AuthController.java)：`/auth/tenant/list` 明确为 60 秒、20 次、IP；login 方法没有此注解。
- [RateLimiterAspect](https://github.com/dromara/RuoYi-Vue-Plus/blob/8136a0191a2258c0e1b36a8146a1c5ebc070c139/ruoyi-common/ruoyi-common-ratelimiter/src/main/java/org/dromara/common/ratelimiter/aspectj/RateLimiterAspect.java)：切入点为注解方法，key 包含请求 URI，IP 模式再包含客户端 IP，不是所有上述请求共用一个桶。

原始脱敏启动日志 02:07:37 的限流 traceId 是 `705c20d4e96adb7becef069bbda608b5`，同秒 getInfo Broken pipe 的 traceId 是 `59125260ee03ddc8b20fb048c1593bb3`。它们不是同一请求。Broken pipe 可能源于导航/关闭连接，之后的 catalog NotLoginException 也不能倒推出该次 pageerror 的起因。限流日志没有 URI，不能确定它来自哪个端点。管理员锁定前端 [Navbar.vue](https://github.com/CrazyLionCat/plus-ui/blob/d0d451967676707021b9857df529c395b27e90a7/src/layout/components/Navbar.vue) 的 initTenantList 确实请求租户列表，但这只提供后续排查线索，不证明该请求导致本次失败。

本补丁只修复证据缺口，不宣称修复真实根因。每次真实 shell 导航及刷新分别归档 `uiNavigations`，包含白名单路径、HTTP 状态、数值业务码、明确限流/网络错误类别、pageerror 名称与计数、网络失败枚举、格式正确的关联 ID。未知异常文本不写入证据；无 query、headers 集合、响应数据、cookie、token、storage、原始 stack。未知落地地址仅记录 `other-redacted`。静态包失败只记固定类别。每导航最多 200 个事件，另保留丢弃计数及不受截断影响的 pageerror 总数。

无异常断言之前显式持久化；goto/expect 抛错也在 context.close 之前持久化，以免因失败直接关闭上下文而丢证据。刷新也检查真实摘要和无未捕获异常。没有豁免 consoleerror/pageerror、盲重试 401/403/500、清 Redis、换 IP、改限流，或无依据等待。现有 native_login 的明确验证码/租户限流规则未改动。

本地验证命令为 `python3 -m unittest discover -s tests/runtime-native -p 'test_*.py' -v` 和 `python3 -m unittest discover -s tests/python -p 'test_native_composition_config.py' -v`，分别 59 和 11 tests PASS，退出码均为 0；`git diff --check` 退出码 0。这是 collector/fixture 单元验证，不是 Spring、MySQL 或真实浏览器通过。完整原生组合重跑为 NOT_RUN，需要精确最终源码的 CI 来取得真实失败端点/异常类别，才能判断是否存在需要正常节流的请求；没有任何生产验证结论。

## 第二轮真实失败与定向补强

b8479b6 对应 run 42437904209、artifact 13600577188，再次在 admin rollback 未捕获异常门禁失败（197 checks）；输入 ZIP SHA-256 为 `9d4264d4455a23c0931cee4275cab4443586aeb3fced9ca4c0941a13e0bde054`。导航证据显示 getInfo/getRouters/session/bootstrap 均返回 200，但 639ms 出现一个未分类 Error。启动日志 02:31:53 的真实限流 Trace 为 `16dbc6be2ce1c3d1b284171133463e30`。这一轮仍没有足够证据把具体端点和 pageerror 绑定。

本轮通过精确后端 pin 的 Git tree 枚举并读取全部 32 个非 demo `*Controller.java`。RateLimiter 注解仅出现在 AuthController 的 tenantList，以及 CaptchaController 的图形验证码、短信验证码、邮箱验证码。通知、配置、菜单、用户、SSE 控制器均没有该注解。锁定管理员前端 [layout/index.vue](https://github.com/CrazyLionCat/plus-ui/blob/d0d451967676707021b9857df529c395b27e90a7/src/layout/index.vue) 在 mounted/nextTick 调用 Navbar.initTenantList，Navbar await `/auth/tenant/list` 而未捕获其失败。通知组件只从 Pinia 读取内容，不发通知列表请求。因此 tenant/list 是最强源码线索，但不是已经证实的失败结论。

发现第一版 collector 的独立缺口：`response.header_value()`、`response.json()` 是可让出执行的 Playwright 同步调用，原代码等它们返回才登记响应。如果主流程先到断言并关闭导航，响应可能丢失。现在在任何此类调用前就登记 endpoint/status/pending，之后仍属于同一导航才更新并持久化。回归模拟了 header 调用让出期间关闭导航，确认起始事件已保存且关闭后不会被改写。不把这一单元复现说成已经证明真实失败的确切时序。

错误信息严格拆除 ` (Trace ID: <32位非零小写hex>)` 后缀后再做固定限流分类，并保留该 Trace；其他文本仍不输出，增加 SHA-256 以供匹配。堆栈只接受同源不可变 UI 包 JS 路径，保存路径 SHA-256 和行列，不保存函数名、原始路径、query 或文本。未知同源 XHR/fetch 只保留 `other-same-origin-api` 类别、状态/数值业务码/Trace 与固定错误类别，网络失败同理。

核实 JVM 使用 `cwd=private_dir`，锁定 logback 的 `log.path=./logs`。CI 在进程退出后、私有目录清理前，读取当前 `logs/fcp-request.log`，只投影失败浏览器 Trace 和启动日志中精确原生限流 Trace 的 request_complete 结构字段。已知端点可写固定路径，其他服务端路由仅写固定类别和 SHA-256；原始日志不复制。当前日志缺失、滚动淘汰或丢弃的关联项保持 missing，不推定成功；投影失败不会覆盖原验收结论。

新增恢复仅用于本 CI 的初次管理员只读 shell 导航。必须同时满足：恰好一个限流 pageerror、合法 Trace、同导航 `/auth/tenant/list` 同 Trace 的 HTTP429 或 HTTP200/500 且业务500/429与精确原生限流类别、没有丢事件/未完成或无法解析响应/其他异常/网络失败/HTTP或业务失败。首次真实错误及元数据先落盘，然后关闭该专用 context，完整等待 60 秒并校验 monotonic 时长，使用同一 storage_state 新开 context，仅执行一次相同只读导航。没有修改或重建 token，没有重新登录，没有改 IP/Redis/服务端限制。重试必须再次通过 heading、bundle、deep-link、无未捕获异常及刷新门禁。第二次失败不恢复，未能严格证明同 Trace 的情况立即失败。首次错误始终保留在 uiNavigations，uiNavigationRecovery 记录首轮失败、实际等待时长及重试结果。这是正常遵守已核实的限流窗口，不豁免未知产品缺陷。

本轮本地命令 `python3 -m unittest discover -s tests/runtime-native -p 'test_*.py' -v` 为 71 tests PASS；composition config 为 11 tests PASS；`python3 -m unittest discover -s tests/python -p 'test_ci_native*.py' -v` 为 15 tests PASS，退出码均为 0；diff check 为 0。包含同 Trace 与跨 Trace、401/403/普通500、未知或多异常、其他限流端点、pending/不可解析响应、截断、第二次失败、提前唤醒、关闭后延迟事件和脱敏反例。完整原生重跑仍为 NOT_RUN，必须以最终精确提交的真实 CI 验证诊断与窄恢复是否实际适用。

## 独立复审反例修复

25520dd 的独立复审指出两条实际缺口：尚未响应的 getInfo 不在响应事件表中，可能被误判为允许 tenant 限流恢复；document header 读取在 finish 后抛异常时，异常分支可能继续改写已保存记录。本轮先加入原样反例，运行得到两个 FAIL，原始输出保留在 `reports/native-rollback-review/before-fix.log`，随后修复。

现在从 request 开始跟踪有限同源 XHR/fetch，并在 requestfinished/requestfailed 更新，恢复必须明确 in-flight 为零且跟踪未溢出。SSE 长连接单独计数，不伪装成已结束有限请求。未知 API 路径仍只记固定类别。导航 gate 前只对已观察流量执行最多 2 秒、40 次 Playwright 回调事件泵，不启动请求、不清除任何错误；未完成请求/响应解析在超时后原样保留并失败。延迟旧阶段请求事件、finish 后 header/body 异常均不能改写已关闭记录。

私有日志 reader 使用 O_NOFOLLOW/O_NONBLOCK、逐级无链接目录打开和 regular-file 验证，只读取打开时初始大小内的尾部至多 32 MiB，单行至多 16 KiB。文件后续增长不扩展窗口，FIFO、文件/目录软链接、超长行均不能阻塞或混入证据。

本轮 `runtime-native` 完整单元集 79 tests PASS，composition config 11 tests PASS，CI-native 15 tests PASS，均退出码 0；新增队列响应在 gate 期间完成、pending 超时不恢复、SSE、重复/旧阶段完成、关闭后异常、软链接/FIFO/文件增长/长行反例。此处仍只是本地 fixture 验证，真实原生 CI 尚未运行，不声明原生通过。
