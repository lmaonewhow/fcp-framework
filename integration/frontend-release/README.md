# 系统发布控制台适配契约

这是预编译宿主页面，不是可执行插件前端，也不表示宿主静态资源多版本路由已完成。将本目录复制到两套宿主的 `src/fcp/release`；各自 `views/fcp/releases/index.vue` 负责原生 transport 与身份绑定。菜单和服务端权限单独配置，不自动授予角色权限。

ReleaseHost 必须提供原生已认证请求、显式 `fcp:release:view/manage` 权限、身份隔离键及同步身份/权限变化通知。隔离键只用于内存比较，不发送或显示；实现不得将宿主 token 交给插件。两套宿主请求沿用原生拦截器和认证机制。

接口基址 `/fcp/releases`。GET `/state` 返回带整数 revision 的控制器状态，GET `/versions` 返回版本提供者及不可变产物和系统组合。POST `/plans` 接收 expectedRevision、operationId 和十字段 ReleasePlan。POST `/operations` 接收 expectedRevision、operationId、action、basisPoints。POST `/reconcile` 只对账已提交操作。所有成功响应按宿主已有封装返回 data；实际能力和状态转移仍由服务端校验，不能依赖按钮作为权限边界。

同一计划确认只对当前内容和 revision 生效。修改计划、身份或修订会撤销确认。部署、开始灰度、扩大、暂停、终止、全量、回退、退役必须再次确认。未知执行结果不自动重试，不随读取状态解除。已进入服务端的请求在浏览器取消后可能继续执行，换身份后必须回原身份核对。

控制台分别展示 desired 和 observed，登记和提交均不显示为部署成功。暂停扩量不是停流；代码回退不回退数据库数据。版本登记目前通过受授权 API 完成，控制台展示现有清单与执行计划，不提供任意脚本、命令或 URL 输入框。

当状态同时包含 pending、supportsRecovery=true 和有效 revision 时，管理用户可显式确认安全恢复。POST `/recover` 仅提交 expectedRevision，不创建新 operationId，不重发原业务写入。执行器必须核实原操作的接收状态，并仅在权威确认未接收后原子认领原命令；未知仍保持锁定。普通 HTTP 404 不能作为未执行证据。对账与安全恢复分开显示，未知状态下仍不开放常规发布动作或 ABORT。

## 服务器观察面板

ReleaseObservation 是独立可折叠面板，仅手动读取 `/fcp/releases/automation/state`。显式 view 权限只允许查询，manage 权限才能确认 enable、disable、tick。enable 投影为服务端七个策略字段加 expectedRevision，disable 仅 expectedRevision，tick 仅空对象；浏览器不会提交 MetricsGate.Sample 或伪造监控值。后续阶段由百分比准确转为万分位，必须递增且以100%结束。启用策略不启动浏览器轮询或无人值守定时器。

执行前再次读取实际发布状态。系统发布有 pending、父控制台有未知结果时禁止推进；观察本身 pending 时禁止 enable/disable，只在系统发布已对账且无 pending 后允许明确确认“对账当前观察步骤”，由服务器继续同一持久操作，不创建新操作。网络未知结果阻止重复动作，只读刷新不自动清除锁定，需要管理员核对服务端记录。HTTP404显示未启用/接口不可用，不自动重试；HTTP401清空身份相关显示。解除跨页/跨设备的未知状态仍依赖服务端持久操作及权限，不把前端按钮当作一致性边界。
