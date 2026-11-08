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
