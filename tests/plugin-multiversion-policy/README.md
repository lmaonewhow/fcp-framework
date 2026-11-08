# 版本包授权

`VersionPluginPolicy` 是宿主端口，不读取旧插件的全局安装指针。`JdbcVersionPluginPolicy` 使用 V008 表，审批键绑定租户、插件、版本、包摘要、能力、操作、数据范围和主体。哈希主键只为控制 MySQL 索引大小，读出后仍逐字段核对完整身份。

`ManifestResolver` 必须从已验证包仓库按 version/digest 重新核验，不能从 HTTP 请求构造 manifest。用户权限名只能从该 manifest 的 provides/requires 推导，审批请求不能自填权限。管理调用只接受经过宿主认证的 USER，管理授权回调需要验证平台角色和目标租户权限。此层不自行把任意 TrustedContext 当作认证来源。

`LiveIdentity` 每次独立读取当前租户、用户/服务主体、权限和数据范围。USER 使用租户级 `*` 审批且仍要求本人业务权限。TASK 必须有同摘要 USER 基准审批，以及精确任务主体和未来到期时间的 TASK 审批；撤销基准后任务立即失去能力。严禁从 HTTP body 建立 TASK 身份。

审批仅接受声明能力的 invoke。实现将宿主存储层的内部 storage/read 等二次检查明确映射到已声明并获相同摘要授权的 host.storage.get/list/put/delete/restore，未声明和未批准的存储能力仍拒绝，不放开泛 storage。

修改使用锁定读取、预期 revision、CAS 和同事务审计。撤销写 del=1，不硬删除。提交结果不明确返回 VERSION_GRANT_OUTCOME_UNKNOWN，调用方应重新读取对账，不自动改用新 revision 再写。

## 测试边界

`bash tests/plugin-multiversion-policy/run.sh` 是 JDBC SQL 绑定代理 fixture，验证授权分支、参数、版本隔离、失效、冲突和 fail-closed，不模拟真实 MySQL 引擎、锁争用或事务回滚，不能当作 MySQL 验收。

`JdbcVersionPolicySmoke.java` 是单独真实 MySQL 测试，只接受 `GITHUB_ACTIONS=true`、匹配 `FCP_ACCEPTANCE_DATABASE=fcp_ci_<12hex>` 的 loopback 33306 专用库、owner-only JDBC 配置。执行前由部署身份应用 V008，运行账户仅 DML。编译时加入框架 core/host 源码，运行时加入真实 MySQL JDBC driver：`java -cp <compiled-classes>:<mysql-driver> JdbcVersionPolicySmoke <private-jdbc-properties>`。此测试不创建或删除数据库、不硬删授权记录。尚未运行时必须标 NOT_RUN；即使运行通过也不代表原生 RuoYi 身份或 UI 验收。
