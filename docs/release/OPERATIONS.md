# 系统版本与发布控制面操作说明

状态：开发中，组件合同已有测试，原生与生产验收以精确提交 CI 证据为准。不要在生产直接启用本页参数来替代部署审查。

## 替换版本实现

框架接口为 `fcp.release.version.VersionProvider`。内置 `JournalVersionProvider` 对接现有 `StateJournal`，原生默认使用专用 JDBC journal `system_versions`。`FileVersionProvider` 是单实例开发实现。`HttpVersionProvider` 适配明确的 FCP Version Provider v1 协议，协议见 tests/system-version/README.md；不是宣称现成任意第三方产品都已适配。

在 Spring 中声明自定义 VersionProvider bean 可替换默认实现。非 Spring 宿主直接构造 SystemVersionApplication/ReleaseController。未知实现类型明确失败，不静默回退为内置实现。版本记录登记不等于真实产物已经上传、可信或完成部署。

## 原生接入

先按原有数据库部署规范审查 V007 菜单权限脚本；新增 fcp:release:view 和 fcp:release:manage 不自动授予任何角色。原生控制面限定平台租户 000000 的显式授权用户，TASK、其他租户、通配权限均不能代替该授权。实际身份和权限每次由宿主核验。

功能默认关闭。部署者可设置 `fcp.release.enabled=true`，须已有运行时接入与 JDBC 表，并指定：

- FCP_RELEASE_ENVIRONMENT：此控制器唯一管理的环境。
- FCP_RELEASE_VERSION_PROVIDER：jdbc（默认）或 http。
- http 模式另设 FCP_RELEASE_VERSION_ENDPOINT 与 FCP_RELEASE_VERSION_WRITABLE；生产默认只接受 HTTPS，拒绝重定向。
- FCP_RELEASE_EXECUTOR_ENDPOINT：部署者配置的 FCP Deployment Executor v1 HTTPS 服务。
- FCP_RELEASE_EXECUTOR_ACTIONS：执行器真实支持的动作列表，不要把未实现的动作填入。

运行账号必须获得迁移历史表的只读权限。实际 schemaVersion 取迁移历史的最大版本；任一非 APPLIED 记录即阻止发布。缺少历史同样失败，不接受浏览器自报版本替代。适用的系统兼容范围存于不可变系统版本 `compatibility.schemaMin/schemaMax`，发布计划必须与其一致。

HTTP 执行器协议不是现成部署服务器。执行器必须独立验证产物摘要和可信来源，真实部署、探测版本健康、切换及核对路由、保留幂等回执和在途状态。TLS 客户端认证等部署凭证仅通过部署侧自定义 DeploymentProvider 配置，不放进版本记录、插件包或前端。没有执行器时不得声称原生产品已经具备自动部署。

## API 与操作

GET /fcp/releases/versions 查询提供者与版本；POST /artifacts 登记产物；POST /versions 登记系统版本组合。登记不可变，同 ID 同内容可对账，不同内容冲突。前端当前展示版本并创建计划，登记入口使用 API。

GET /fcp/releases/state 返回实际状态、期望状态、操作与审计。POST /plans 需要 expectedRevision、operationId、plan。POST /operations 需要 expectedRevision、operationId、action、basisPoints。POST /reconcile 需要空 JSON 对象。完整参数见 ReleasePlan 与控制台契约。

计划先预检，后 DEPLOY 候选，再 CANARY/EXPAND/PROMOTE；PAUSE 保留当前比例，ABORT 封禁此发布管辖的新入口，不能默默改投旧版；ROLLBACK 经兼容预检后回切稳定版，RETIRE 必须排空。写调用网络结果未知时，不生成新操作重试；刷新并对账已持久化操作。未知结果持续未知会阻止后续变更，不能清空记录绕过。

## 尚不能据此宣称的能力

发布控制台不是两套静态站点的完整多构建入口。可复用灰度路由模块还需要接入实际宿主请求与授权。既有插件仍是全局单版本，不能因新增系统版本记录就称插件双版本已完成。真实插件依赖/任务版本绑定、浏览器缓存/旧标签页兼容、真实数据库回退演练、长期稳定性与多节点一致性仍需后续交付和验收。

安全恢复默认关闭。只有经过契约验证的执行器才可声明 FCP_RELEASE_EXECUTOR_SAFE_RECOVERY=true 或 FCP_RELEASE_EXECUTOR_FENCED_CONTAINMENT=true。前者必须区分权威未接受与未知，并以同ID同摘要原子占有；后者必须拒绝所有延迟旧代次指令。普通404不是未接受证据。
