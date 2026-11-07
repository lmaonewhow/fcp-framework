# 原生 RuoYi Trace 集成

上游为锁定的 RuoYi-Vue-Plus `8136a0191a2258c0e1b36a8146a1c5ebc070c139`，补丁只修改明确列出的三个上游文件。构建验证 HEAD、patch SHA-256、每个文件完整前后 SHA-256 和 Git 补丁目标集合。任何漂移均失败，不能用相似版本替换。

最高优先级 Servlet filter 覆盖登录、验证码、权限拒绝、原生系统、FCP 和未映射路径。响应提供 `X-Trace-Id`、`X-Request-Id`；FCP 错误体使用相同 Trace ID。默认忽略外部 traceparent，每次创建新根。仅部署者显式配置 `fcp.trace.trusted-peers=10.0.0.10,10.0.0.11` 时允许这些精确数字形式的直接网络对端传播 W3C v00。此配置不是自动识别可信网段，代理必须删除公众提供的 traceparent 并自行生成可信头，且 Servlet remoteAddr 必须保留真实直接对端。不要在启用转发头重写 remoteAddr 的部署中配置该白名单。代码不读取 Forwarded 或 X-Forwarded-For。

MDC traceId/spanId/requestId 加入原生日志格式。原生登录及操作审计插入成功后输出 `native_audit kind=... auditId=...`，用审计行主键关联已有数据库记录，不新增字段或复制用户数据；原生审计数据的原有访问权限与保留政策不变。全局 Spring executor 装饰器传播 scope，finally 恢复线程上下文；默认 @Async 保留上游 Boot executor 与 AsyncConfigurer（锁定配置 spring.task.execution.mode=force），仅增加 scope 装饰器，不接管保留 bean 名、线程数、队列、拒绝策略或虚拟线程选择；原生 scheduledExecutorService 的一次性 Runnable/Callable 以及 invokeAll/invokeAny 批量提交也传播 scope。周期性任务每次 tick 创建新根 Trace，不保留注册请求 ID。手工 new Thread、第三方私有 executor、插件自行产生的后台工作不自动传播。

新增诊断日志只写匹配路由模板、有限 HTTP method、status、durationMs、HTTP failed、原生 R 整数 businessCode/businessFailed 和关联 ID，不写原始 URI、query、body、用户 ID 或 token。未知路由固定 unmatched。请求和审计关联日志使用独立 additivity=false 的 2048 条队列、neverBlock=true、按时间和大小滚动的文件，最多 512 MB / 7 天。队列满时丢弃并累计计数，下一条成功请求日志带 droppedTotal，Appender 也提供 getDroppedCount()。没有零丢失保证。原生数据库审计继续使用上游 Boot executor 的队列与拒绝策略，此处不承诺有界审计队列、零丢失或零延迟；原生历史日志与数据库审计仍保留其原有 I/O 行为，不宣称全面无阻塞或已测性能。

这是 W3C 兼容关联工程，不是已经连接 OpenTelemetry Collector 的分布式采集。Maven 的 prod 标签会选择新增 `FcpTraceBoundaryTest` 和真实 Spring `FcpTraceSpringTest`；前者使用 Spring mock servlet 对象验证过滤器行为；FcpTraceScheduledTest 验证真实线程池上的一次性、周期性、批量提交与线程清理。后者使用原生完整 Spring 上下文，在 127.0.0.1 随机端口通过真实 HTTP 请求验证 Servlet 注册链，明确断言验证码成功及原生未登录拒绝，并验证真实配置 executor，要求 CI 提供隔离 MySQL/Redis。本地 Maven 依赖下载受 DNS 限制，Spring 测试 NOT_RUN；只确认锁定源码补丁真实应用和摘要验证通过。长期负载、吞吐/P95、队列饱和丢失以及原生数据库审计关联仍需真实 CI/负载验收。

真实审计链测试位于 FcpTraceSpringTest：发布带随机 synthetic username 的原生 LogininforEvent，经过实际 @Async listener/MyBatis 写入 sys_logininfor，再核对 fcp.audit 捕获的 MDC 与 auditId 和该行主键一致。测试在任何写入前限制 loopback JDBC 与 fcp_ci_* 临时库，只删除本次随机标记行；其他本地库会明确拒绝该测试写入。本地未执行此数据库测试，后续以 native CI 对应提交的 Surefire 结果为证据。

Boot 3.5.15 在 spring.task.execution.mode=force 时即使存在其他 Executor 也注册 applicationTaskExecutor。FCP 不再声明同名 bean/alias，避免 BeanDefinitionOverrideException。FcpTraceExecutorContextTest 使用真实 Boot auto-configuration 与禁用覆盖的 ApplicationContextRunner，验证 force 模式平台/虚拟执行器仍由 Boot 构建、平台线程池指定参数保持不变、真实 @Async 传播并清理 MDC；其运行证据仍以相应源码 CI 为准。
