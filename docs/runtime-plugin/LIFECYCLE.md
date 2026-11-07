# 本地进程生命周期与开发宿主

本模块复用 Engine 与 ProcessProvider，实现已校验包的独立进程运行、版本协商、就绪/健康检查、租户目录发布、依赖回调、精确授权、排空和持久调用回执。插件采用 fcp.runtime/1 按行 JSON-RPC 2.0；stdout 只用于协议。进程协议与夹具见 tests/runtime-lifecycle。

控制状态通过 StateJournal 的 CAS 持久化；开发实现为带文件锁与 fsync 的 FileStateJournal，真实宿主须接数据库实现。运行实例由当前 ProcessHandle 的 PID 和启动时间共同判断；不凭历史 PID 杀进程。重启预算和副作用 PENDING/COMPLETE/UNKNOWN 回执持久保存。无法确认副作用结果时拒绝盲重放；取消不声称回滚外部已提交数据。

DevHost 为明确 opt-in 的回环开发 HTTP 宿主，无需 ruoyi-admin.jar。必须提供 --development、私有身份配置、状态目录和端口；FCP_ENV=production 时拒绝启动。私有配置是开发鉴权，不是若依登录替代，也不能作为真实若依验收证据。配置包含 development:true、trustedPublishers、executables（解释器绝对路径），以及 identities 的随机长token、userId、tenantId、permissions、scopes、精确grants。例子由 tests/runtime-http/test_dev_host.py 在专用临时目录生成，不提交固定口令。配置中的token必须至少32字符，并应保持0600文件权限。请求使用Authorization头。

共同应用服务提供包上传、启停计划、明确确认、操作状态、目录及受控能力调用。安装不自动启用、不自动授权；UI目录来自当前已就绪租户快照并按调用权限过滤。业务端点标识由插件和能力身份生成，调用者不能自报可信租户或主体。exchange.commit保留给后续预览确认链路，通用invoke拒绝绕过。

内部恢复使用受限fcp-control-recovery任务身份，只允许恢复生命周期，不等于管理员会话，也不允许调用业务或存储。DevHost会重新读取专用身份配置，撤权影响旧入口；数据可见性仍由每次调用的授权决定。

验证命令为 python3 tools/verify_runtime_lifecycle.py。它实际启动 Java、Python、Node、ZIP安装及回环HTTP，包含依赖回调、双租户共享进程、排空、过期版本拒绝、持久未知结果、恢复预算和HTTP安装/幂等/启停/撤权/宿主重启。既有ProcessProvider边界、终止与进程树回归同时运行。测试不是全套A01–A41：真实RuoYi鉴权、数据库持久化、迁移、双浏览器和生产资源隔离仍需后续集成验收。

首期仅可信代码与单实例POSIX；同一OS用户可读取同用户文件，不宣称恶意插件沙箱。目录只能通过受信控制器发布；Registry公共可变映射不是面向任意调用者的并发安全接口。

开发 HTTP 宿主源码独立位于 development-host，不参与原生若依 JAR 组装。
