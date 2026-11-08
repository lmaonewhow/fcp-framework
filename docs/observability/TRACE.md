# 请求追踪与性能边界

本模块补充可排障的 W3C Trace Context 关联，不等于已经接入 OpenTelemetry Collector、Jaeger、Tempo 或 SkyWalking。接口返回 Trace ID，错误页面可复制；运维人员可以在原生请求日志、原生日志、插件调用摘要和现有审计中查询同一编号。查询仍需正常的日志与审计权限，Trace ID 不是登录凭证或租户授权。

## 已实现的入口与传播

原生最高优先级 Servlet filter 处理所有 URL，包括登录、验证码、若依系统接口、FCP、鉴权拒绝和不存在的路径。每个请求独立生成非零 32 位十六进制 traceId、16 位 spanId 和独立 requestId；成功及失败响应都有 X-Trace-Id/X-Request-Id。FCP 原有错误体不再生成另一枚 UUID。若依原生日志增加 MDC；数据库中的原生登录/操作审计保持原 schema，以成功插入的审计行主键和该线程 Trace 关联。FCP runtime 审计原有 trace_id 现在来自同一个请求。旧式 record store 事务审计的 JSON 增加 traceId/requestId，不改变幂等指纹。

公共 traceparent 默认不可信。只有部署者明确列出的直接网络对端可以续接严格 W3C v00；重复头、超长、非法/全零 ID 会重新起根。Forwarded/X-Forwarded-For 不参与信任判断。可信代理必须剥离公众 traceparent，且不能用转发头改写 remoteAddr 后仍启用对端信任。无 baggage、tracestate、客户端身份或权限传播。flags 固定 00，不让来客强制遥测预算，也不假称链路已被采集。

Spring 原生执行器只增加上下文装饰，不替换其名称、容量、虚拟线程或拒绝策略；框架 @Async、原生 scheduledExecutorService、FCP pipe I/O 显式传播并恢复上下文。周期任务每次建立新根，一次性提交保留父请求。FCP Exchange 和恢复任务使用独立 TASK 身份，不保存管理员会话。标准线程本地上下文不会自动覆盖手工新建的 Thread、原生 Servlet AsyncContext.start 的裸 Runnable、第三方私有线程池或插件私有后台任务；这些调用方必须显式包装。

ProcessProvider 为每次实际 RPC 建子 span 并输出有界、无载荷的调用摘要。固定字段 pluginId、pluginVersion 来自宿主已装载清单，在 provider 初始化时校验并缓存；managed-stdio 明确使用已验证包的 manifest 身份和版本，不使用内部派生模块 ID。capability 是宿主解析出的导出能力名，operation 是引擎操作或 lifecycle 控制名。插件 ID/能力最多 128 字符，版本最多 100 字符，operation 最多 96 字符，只接受 ASCII 字母、数字、下划线、点、冒号和短横线，版本字段另外允许 + 以保留 build 标记（不放宽 managed 清单自身的版本校验）；缺失、非法或过长整体替换为 other，不截断或读取请求参数来补值。

RPC 的 outcome=RETURNED 只表示收到合法 JSON-RPC result，errorCode=NONE；它不证明任意业务结果成功、schema 通过或副作用已提交。错误记录 ERROR，errorCode 仅允许 BUSY、DEADLINE、CANCELLED、TRANSPORT、FRAME_LIMIT、PROTOCOL、CLOSED、RUNNER_EXITED、BUSINESS_RULE、INVALID_INPUT、PLUGIN_REJECTED、INTERNAL_ERROR，其他错误归 OTHER。不会输出任意异常文本、原始插件错误码、payload、query、token、principal 或原始 stderr。RPC 返回值、异常、授权和对账语义保持原样；解析后的业务失败与 OUTCOME_UNKNOWN 仍按原业务回执和宿主审计判断。托管运行时的 params.context.traceparent 由宿主补入；可信身份仍来自原来的服务端授权路径。旧 stdio 插件只在宿主清单 runtime.traceContext="w3c" 显式选择后增加顶层 traceparent，未选择的旧协议仍保持兼容。六个 ingestion 插件和 statistics 示例已接入。插件 callback 的可选 traceparent 只能续接当前 trace，不能覆盖身份或更换 trace 根。

发布调用另有 release.invocation 固定摘要，记录实际选中的 selectedReleaseId、routeRevision、technicalFailure、businessOutcome、unknownResult；不读取结果正文或 affinity。UNKNOWN 优先，不以 RPC RETURNED 代替业务成功；诊断失败不能替换原调用、分类器或 ticket 的结果/异常。发布入口页面、bootstrap 与入口 HTML 失败保留响应编号；身份改变后不展示旧身份的编号，原有 EMBEDDED/200、摘要校验和禁止静默回切规则不变。

部署注册的 ReleaseTaskApplication 在 pin/run 入口绑定并恢复 Trace scope。由 HTTP 同步触发时续接当前请求，没有入口时新建合法根；仅传关联 ID，TASK principal、tenant、scope、deadline 与精确包授权保持原值，不继承管理员身份。应用审计与运行时审计按同一编号关联；status/pin 的控制元数据白名单和 run 返回业务结果前的再次授权保留。任务重启恢复、无人值守调度和所有第三方后台线程仍需另验。

Python ContextVar 和 Node AsyncLocalStorage 的标准库示例在 runtime/observability，逐消息清理；Python capture 支持同步线程及 async 函数的实际执行期，Node withTrace 跨 Promise 保留。roster/mock-exam 包包含 Python helper。其他语言仍需实现相同协议并验收，任意第三方插件内部日志、SQL及后台工作不因此自动可观测。

外部平台连接器默认不发追踪头。部署者可在已受控的私有连接配置中设置 propagateTrace=true，向该固定来源发送 traceparent；原有地址校验、TLS、大小/超时约束及禁止重定向保持。LoopbackConnector 是显式回环测试适配器。不会自动向任意第三方发送遥测或业务数据。

## 查问题与数据落点

用户在原生管理端、教师端、FCP 页面或开发宿主发生错误时，可以复制唯一面板中的编号。没有后端响应时不会编造编号。错误对象保留编号；原有通知去重不改变，不新增重复 toast，不保存或上传请求体。

原生请求/审计摘要最早进入有界内存队列，再写 logs/fcp-request.log，滚动文件 fcp-request.YYYY-MM-DD.N.log.gz。字段是路由模板、有限 HTTP method、HTTP status、耗时、失败标记、Trace/Span/Request ID，以及原生日志审计类型和审计主键。原生 R 响应另外只读取整数 code，记录 businessCode 和 businessFailed，避免 HTTP 200 掩盖 R.code=401/500；不读取 msg/data，不猜测任意 DTO/Map 语义，缺省记 none。HTTP failed 与业务失败分别表达。未匹配路由固定 unmatched，不记录 raw URI、query、body、cookie、token、IP、姓名或用户标识。若依旧有数据库审计的敏感字段与原有保留/权限策略没有自动清洗；第三方日志、SQL debug、代理访问日志和崩溃转储仍需分别治理。

用严格校验的编号在 fcp-request.log 与原生 info/error 日志作精确搜索；原生审计摘要的 kind/auditId 指向已有 sys_logininfor/sys_oper_log 行，FCP runtime 查现有 trace_id 字段，旧 record JSON 审计查 traceId。查询界面和索引后端本次未新增，不开放跨租户诊断 API。插件调用摘要由 Java 写 stderr；部署者需要收集/轮转宿主 stderr，插件任意原始 stderr 仍被有界丢弃以免泄漏载荷。

新的原生诊断队列容量 2048，neverBlock，满时丢弃并累计 TraceAsyncAppender.droppedCount；后续完成日志带 droppedTotal。文件上限 32MB/段、7天、历史总额512MB（当前打开文件另计）。这些是配置，不是容量保证。core TraceEvents 同样为 2048 有界队列，ProcessProvider.metrics().traceTelemetry 给出 accepted/dropped/sinkFailures/queued。内存队列和进程退出可丢诊断数据，不作为正式审计账本。

## 性能与测试

每请求只保留小型上下文和一条完成摘要，不复制/序列化请求响应体，不同步联系 Collector。源头字段白名单在进入新队列前执行。原生业务/数据库审计仍使用上游执行器的原有容量和拒绝策略，不能把有界的新增诊断队列当作所有业务队列都有界。历史原生日志与数据库 I/O 行为没有全面改成异步，因此不能承诺“零性能影响”。

运行 python3 tools/verify_observability.py，产生带实际源码哈希、Git 提交、命令、退出码和原始日志的 reports/observability。包含 W3C 信任边界、复用线程清理、真实 Python/Node RPC、已验证 managed package 的身份/版本/崩溃关联、元数据白名单与长度边界、Python HTTP opt-in、阻塞日志 sink 的有界丢弃测试与微基准。RPC 的固定小摘要只在 RPC 路径分配；HTTP/task 路径保持原字段和序列化，无新增载荷复制或序列化。TraceBenchmark 是上下文创建/绑定/序列化/清理的微基准，不是 HTTP、数据库或生产 P95；报告只能用于对应机器和源码。

可运行 python3 tools/measure_trace_overhead.py --baseline-ref <已验证基线提交>，在同机独立临时目录交替启动基线/候选开发宿主，用合成身份对回环 /fcp/session 做真实 HTTP A/B。默认每轮4并发、1000预热、8000请求，以唯一run-id目录保留每轮P50/P95/P99、吞吐、错误、诊断事件计数及配套原始日志；不接数据库、不冒充若依或生产性能，也不平均分位数。pull_request 组件CI也以该PR基线运行此工具，并归档对应run-id日志。报告PASS仅表示测量完成且本次诊断计数一致，performanceAcceptance始终为NOT_EVALUATED，不能当性能门槛已通过。

可运行 python3 tools/measure_rpc_trace_overhead.py --baseline-ref <已验证基线提交>，以真实 Python/Node 子进程各执行四轮 baseline/candidate/candidate/baseline，对比每轮 500 次预热、2000 次空载 RPC 的 P50/P95/P99 与吞吐；记录真实 stderr 诊断事件数及丢弃计数。报告保留命令、源码/基准哈希、环境和原始日志，PASS 只表示测量及计数核对完成。小样本包含 JIT、调度与文件 I/O 噪声，不能用它承诺生产性能或零开销。

双前端的严格补丁及 DOM/HTTP doubles 回归在 trace-frontend 工作流；native-ingestion 工作流构建精确锁定 RuoYi 与双 UI，并运行 prod-tagged Trace 边界、周期任务、真实 loopback HTTP/Spring 测试。DOM doubles、组件编译、Spring 测试及真实浏览器验收是不同层次。结果必须以对应 head 的实际 CI 产物为准，存在测试代码不算已经 PASS。本地 Maven 下载被 DNS 阻断时明确 BLOCKED，不以 mock 代替原生。

## 未完成的覆盖

没有部署 Collector/查询后端，没有 WebSocket 消息级或任意非 HTTP 协议自动追踪，没有 OTel spans 导出、数据库客户端自动埋点、完整调用树/RUM、跨节点队列任务重启链接或自动 Source Map。若启用 OTel Java Agent，需要独立验证其与本模块的 Trace 根/采样策略是否一致，不能直接把两套不相同 ID 当一条链。推荐后续建立单一 OTel 上下文适配、Collector 白名单/限额/TLS/权限/保留策略，并实测导出不可用时的业务延迟。

长任务的业务 operationId 与重试幂等键仍是结果对账依据；每次 HTTP 重试有自己的 requestId/trace，不能用 Trace ID 代替操作回执。旧式插件启停审计表没有新增 trace 列，关联只到请求/诊断日志，未宣称所有历史表都可直接查 Trace。本分支已接入新增发布入口和 TASK 关联，但发布与 Trace 的正式组合 native 验收仍需在最终提交上执行，不能用旧分支绿色结果替代。

已有 ed61ad1 的完整原生 CI 证据：22 项 prod-tagged 测试、双壳 ingestion 89 项和 runtime 72 项检查通过，覆盖 Servlet async/error/forward、真实登录/操作审计、双壳 FCP fetch 错误编号与真实剪贴板，以及 admin 原生 Axios 的 HTTP200/业务500错误分支。teacher 原生 Axios 仍 NOT_RUN。admin 原生异常页有一个精确匹配的上游未处理 Promise rejection，验收只允许该错误，不能宣称零异常。租户二登录的原生限流按原60秒窗口处理，两壳退出、旧 token 拒绝和身份隔离通过，未修改限流/Redis/IP/授权。证据：https://github.com/lmaonewhow/fcp-framework/actions/runs/42427204894 。

新增 tools/ci_trace_correlation.py 复用既有第一笔真实 UI 导入，不增加业务写；仅 SELECT 已提交审计的白名单投影，并在3秒、1MiB、16KiB每行范围内读取该次私有日志，核对 HTTP、一次connector、两次business RPC和原审计的Trace/Request ID。重放只验证原审计未被改写；这一新增检查须以最终 native CI 实际结果为准，单元测试不代表它已通过真实 MySQL/浏览器验收。

磁盘满/进程崩溃的诊断损失、原生饱和负载吞吐与 P95、24小时稳定性、生产多节点仍需独立实测。
