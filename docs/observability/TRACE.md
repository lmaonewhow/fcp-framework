# 请求追踪与性能边界

本模块补充可排障的 W3C Trace Context 关联，不等于已经接入 OpenTelemetry Collector、Jaeger、Tempo 或 SkyWalking。接口返回 Trace ID，错误页面可复制；运维人员可以在原生请求日志、原生日志、插件调用摘要和现有审计中查询同一编号。查询仍需正常的日志与审计权限，Trace ID 不是登录凭证或租户授权。

## 已实现的入口与传播

原生最高优先级 Servlet filter 处理所有 URL，包括登录、验证码、若依系统接口、FCP、鉴权拒绝和不存在的路径。每个请求独立生成非零 32 位十六进制 traceId、16 位 spanId 和独立 requestId；成功及失败响应都有 X-Trace-Id/X-Request-Id。FCP 原有错误体不再生成另一枚 UUID。若依原生日志增加 MDC；数据库中的原生登录/操作审计保持原 schema，以成功插入的审计行主键和该线程 Trace 关联。FCP runtime 审计原有 trace_id 现在来自同一个请求。旧式 record store 事务审计的 JSON 增加 traceId/requestId，不改变幂等指纹。

公共 traceparent 默认不可信。只有部署者明确列出的直接网络对端可以续接严格 W3C v00；重复头、超长、非法/全零 ID 会重新起根。Forwarded/X-Forwarded-For 不参与信任判断。可信代理必须剥离公众 traceparent，且不能用转发头改写 remoteAddr 后仍启用对端信任。无 baggage、tracestate、客户端身份或权限传播。flags 固定 00，不让来客强制遥测预算，也不假称链路已被采集。

Spring 异步执行器、原生 scheduledExecutorService、FCP pipe I/O 显式传播并恢复上下文。周期任务每次建立新根，一次性提交保留父请求。FCP Exchange 和恢复任务使用独立 TASK 身份，不保存管理员会话。标准线程本地上下文不会自动覆盖手工新建的 Thread、第三方私有线程池或插件私有后台任务。

ProcessProvider 为每次实际 RPC 建子 span 并输出有界、无载荷的调用摘要。托管运行时的 params.context.traceparent 由宿主补入；可信身份仍来自原来的服务端授权路径。旧 stdio 插件只在宿主清单 runtime.traceContext="w3c" 显式选择后增加顶层 traceparent，未选择的旧协议仍保持兼容。六个 ingestion 插件和 statistics 示例已接入。插件 callback 的可选 traceparent 只能续接当前 trace，不能覆盖身份或更换 trace 根。

Python ContextVar 和 Node AsyncLocalStorage 的标准库示例在 runtime/observability，逐消息清理；Python capture 支持同步线程及 async 函数的实际执行期，Node withTrace 跨 Promise 保留。roster/mock-exam 包包含 Python helper。其他语言仍需实现相同协议并验收，任意第三方插件内部日志、SQL及后台工作不因此自动可观测。

外部平台连接器默认不发追踪头。部署者可在已受控的私有连接配置中设置 propagateTrace=true，向该固定来源发送 traceparent；原有地址校验、TLS、大小/超时约束及禁止重定向保持。LoopbackConnector 是显式回环测试适配器。不会自动向任意第三方发送遥测或业务数据。

## 查问题与数据落点

用户在原生管理端、教师端、FCP 页面或开发宿主发生错误时，可以复制唯一面板中的编号。没有后端响应时不会编造编号。错误对象保留编号；原有通知去重不改变，不新增重复 toast，不保存或上传请求体。

原生请求/审计摘要最早进入有界内存队列，再写 logs/fcp-request.log，滚动文件 fcp-request.YYYY-MM-DD.N.log.gz。字段是路由模板、有限 HTTP method、HTTP status、耗时、失败标记、Trace/Span/Request ID，以及原生日志审计类型和审计主键。未匹配路由固定 unmatched，不记录 raw URI、query、body、cookie、token、IP、姓名或用户标识。若依旧有数据库审计的敏感字段与原有保留/权限策略没有自动清洗；第三方日志、SQL debug、代理访问日志和崩溃转储仍需分别治理。

用严格校验的编号在 fcp-request.log 与原生 info/error 日志作精确搜索；原生审计摘要的 kind/auditId 指向已有 sys_logininfor/sys_oper_log 行，FCP runtime 查现有 trace_id 字段，旧 record JSON 审计查 traceId。查询界面和索引后端本次未新增，不开放跨租户诊断 API。插件调用摘要由 Java 写 stderr；部署者需要收集/轮转宿主 stderr，插件任意原始 stderr 仍被有界丢弃以免泄漏载荷。

新的原生诊断队列容量 2048，neverBlock，满时丢弃并累计 TraceAsyncAppender.droppedCount；后续完成日志带 droppedTotal。文件上限 32MB/段、7天、历史总额512MB（当前打开文件另计）。这些是配置，不是容量保证。core TraceEvents 同样为 2048 有界队列，ProcessProvider.metrics().traceTelemetry 给出 accepted/dropped/sinkFailures/queued。内存队列和进程退出可丢诊断数据，不作为正式审计账本。

## 性能与测试

每请求只保留小型上下文和一条完成摘要，不复制/序列化请求响应体，不同步联系 Collector。源头字段白名单在进入新队列前执行。原生数据库审计任务饱和时采用 CallerRuns 保留任务，会增加请求延迟；历史原生日志与数据库 I/O 行为没有全面改成异步，因此不能承诺“零性能影响”。

运行 python3 tools/verify_observability.py，产生带实际源码哈希、Git 提交、命令、退出码和原始日志的 reports/observability。包含 W3C 信任边界、复用线程清理、真实 Python/Node RPC、Python HTTP opt-in、阻塞日志 sink 的有界丢弃测试与微基准。TraceBenchmark 是上下文创建/绑定/序列化/清理的微基准，不是 HTTP、数据库或生产 P95；报告只能用于对应机器和源码。

双前端的严格补丁及 DOM/HTTP doubles 回归在 trace-frontend 工作流；native-ingestion 工作流构建精确锁定 RuoYi 与双 UI，并运行 prod-tagged Trace 边界、周期任务、真实 loopback HTTP/Spring 测试。DOM doubles、组件编译、Spring 测试及真实浏览器验收是不同层次。结果必须以对应 head 的实际 CI 产物为准，存在测试代码不算已经 PASS。本地 Maven 下载被 DNS 阻断时明确 BLOCKED，不以 mock 代替原生。

## 未完成的覆盖

没有部署 Collector/查询后端，没有 OTel spans 导出、数据库客户端自动埋点、完整调用树/RUM、跨节点队列任务重启链接或自动 Source Map。若启用 OTel Java Agent，需要独立验证其与本模块的 Trace 根/采样策略是否一致，不能直接把两套不相同 ID 当一条链。推荐后续建立单一 OTel 上下文适配、Collector 白名单/限额/TLS/权限/保留策略，并实测导出不可用时的业务延迟。

长任务的业务 operationId 与重试幂等键仍是结果对账依据；每次 HTTP 重试有自己的 requestId/trace，不能用 Trace ID 代替操作回执。旧式插件启停审计表没有新增 trace 列，关联只到请求/诊断日志，未宣称所有历史表都可直接查 Trace。发布 PR #9/#10 未合并时，本分支不提前覆盖其 release/affinity/fence 逻辑；合并后还需专门核对新增发布入口和多版本执行链。Servlet async/error/forward 分派、实际登录/操作审计完整链、磁盘满/进程崩溃损失、饱和负载吞吐与 P95、24小时稳定性、生产多节点仍需独立实测。
