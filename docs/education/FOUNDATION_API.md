# 基础资料第一阶段：接口与安全边界

本分支基于 dev f362456，P042 不变，另加显式宿主教育角色映射表。用户已明确：租户管理员管理本租户所有学校，学校管理员只管理自己负责的学校。不是依据原生超级管理员/角色名推断，而是当前数据库中显式教育管理员映射、精确菜单权限和真实成员关系共同证明。代码支持组织/班级受约束新建与名称修改、学校创建、基础读取和操作核对；原生MySQL/宿主/浏览器尚待实际CI，不能当作54表CRUD完成或生产验收。

模块默认关闭，必须显式配置 `fcp.education.enabled=true` 与 `FCP_EDUCATION_MANIFEST_DIGEST`（实际执行JAR摘要），并通过独立批准流程配置租户/主体/执行许可和教育角色权威行。没有运行时建表、默认放权或从客户端读取授权。

## 能力发现与错误响应

GET `/fcp/education/foundation/capabilities` 不接受查询参数。真实原生会话由 LoginHelper 提供 tenant 与 actor，保留原字符串。服务端重新查询当前原生活跃用户、租户、角色、菜单及套餐，要求精确 `education:foundation:view`，不接受 `*:*:*`、超级管理员捷径或 FCP 旧权限。这里的平台套餐规则不是全校授权。该权限仅允许查看模块建设状态，不允许访问任何学校记录；不会自动安装菜单、默认授予角色或创建 P042 表。

模块关闭时 HTTP 200：`{code:200,msg:"",data:{schemaVersion:"education-foundation-v1",status:"BLOCKED",readiness:{schoolAuthorization:"NOT_IMPLEMENTED",transactionProtocol:"NOT_IMPLEMENTED",nativeAcceptance:"NOT_RUN"},operations:{orgList:false,orgDetail:false,orgCreate:false,orgRename:false,classList:false,classDetail:false,classCreate:false,classRename:false,termList:false,personRename:false},blockers:["SCHOOL_AUTHORIZATION_NOT_IMPLEMENTED","REVOCATION_PROTOCOL_NOT_IMPLEMENTED","TRANSACTION_PROTOCOL_NOT_IMPLEMENTED","NATIVE_ACCEPTANCE_NOT_RUN"]}}`。

未登录遵循宿主错误响应（可能 HTTP 200、code 401）。无精确权限 HTTP 403：`{code:403,msg:"Education foundation permission required",error:"EDUCATION_FORBIDDEN"}`。权威查询故障 HTTP 503、error 为 `EDUCATION_AUTHORITY_UNAVAILABLE`；不泄露 SQL/凭据，也不伪装无数据。未知查询参数 HTTP 400、error 为 `EDUCATION_INVALID_INPUT`。能力发现没有业务提交行为，不产生 COMMITTED 审计。拒绝、失败和UNKNOWN通过独立连接记录安全事件；有界并发和失败计数已实现，持久积压与报警接入尚未完成，不能据此声称生产级审计可靠性。客户端未知响应一律关闭功能。

## 第一阶段接口（已编码，原生验收待运行）

启用后能力发现返回 status=READY，readiness 表示代码实现，nativeAcceptance 仍为 NOT_RUN，不能当成验收报告。operations 根据当前明确授权计算，新增 schoolCreate 仅租户管理员可用。学校项也带 operations，缺字段必须关闭；readiness=EXECUTION_GRANT_MISSING 的学校仅展示元数据，全部操作关闭。

| 方法/路径（同上前缀） | 输入/结果 | 权限候选 |
| --- | --- | --- |
| GET /schools | afterId、limit（1..100），仅返回当前主体被明确授权的学校 id/name/rowVersion/operations/readiness | 明确租户管理员 read 或逐学校 read |
| GET /schools/{schoolId}/organizations | afterId、limit（1..100），返回受控组织字段和 nextAfterId | organization:read |
| GET /organizations/{id} | 服务端从组织父链解析所属校后授权 | organization:read |
| GET /schools/{schoolId}/terms | afterId、limit；只读 id/termCode/startsOn/endsOn/status/rowVersion | term:read |
| GET /schools/{schoolId}/classes | termId 必填、afterId、limit | class:read |
| GET /classes/{id} | 实际 school_id 与学期组合解析后授权 | class:read |
| POST /schools | intentId、parentId=null、orgKind=SCHOOL、orgCode、name、validFrom | tenant school:create |
| POST /schools/{schoolId}/organizations | intentId、parentId、orgCode、orgKind、name、validFrom | organization:create |
| POST /organizations/{id}/rename | intentId、expectedRowVersion、name | organization:rename |
| POST /schools/{schoolId}/classes | intentId、termId、classCode、name、gradeCode | class:create |
| POST /classes/{id}/rename | intentId、expectedRowVersion、name | class:rename |
| GET /schools/{schoolId}/operations/{kind}/{action}/{intentId} | kind=organizations/classes；action=create/rename；只读当前授权及权威账本 | 原命令所需权限 |
| GET /operations/schools/create/{intentId} | 租户学校创建的只读操作核对 | school:create |

schoolId/path 是目标选择，不是授权声明。禁止 tenant、actor、scope、epoch、manifest、createdAt、自选ID、status、privateRef、extensions 等额外输入。实体ID/版本均为规范 uint64 字符串，不使用 JS number/long；编码保留前导零。组织 name 190 字符、班级 name 128；不 trim 后替换保存值。ID 来自可信宿主统一分配器，服务器UTC时间由时钟产生并以微秒绑定，不信客户端 createdAt。组织 validFrom 为显式业务时刻，不等于入库时间。

首阶段组织子级新建仅现有已授权学校下 CAMPUS/DEPARTMENT；学校根新建使用独立 /schools 入口且只允许明确租户管理员。学校创建返回 activation:{status:"EXECUTION_GRANT_REQUIRED",schoolId}，不会偷偷创建学校执行许可。缺学校执行许可时仅学校目录可见等待状态，不开放领域读取或写入。组织父链逐行验证同租户、有效区间、类型与循环；新建学期/开关状态不在范围。班级新建采用保守规则：仅同校 DRAFT/OPEN 学期，冻结/归档学期拒绝新建，不改变学期状态。所有 relation、身份绑定、人员、归档、删除操作无路由。

候选成功返回 `operation:{intentId,outcome:"COMMITTED"},record:{id,rowVersion,...}`。CAS/相同 intent 不同规范内容返回409；当前权限不足403；来源不明/缺权威行拒绝；提交连接断开等结果未决返回503/UNKNOWN，客户端必须保留同一 intent，查询权威账本，不可当作未执行重新生成意图。相同意图重放先重验当前授权，且校域/主体/语义指纹完全一致才可读历史结果。


列表返回 data:{items:[...],nextAfterId:null|string}；详情直接 data=record。所有ID与rowVersion为字符串。操作核对返回原已提交回执，查无权威回执则仅返回 {operation:{intentId,outcome:"UNKNOWN"}}，不能认为原操作未执行。前端保持原intent与原body，先核对，绝不自动重新生成意图。CAS冲突与幂等内容冲突为 EDUCATION_CONFLICT/409；提交未知 EDUCATION_COMMIT_UNKNOWN/503；死锁/锁超时 EDUCATION_RETRY_SAME_INTENT/503；不对无法确定结果自动重试。后端所有EDUCATION_*失败仅返回稳定码，不返回SQL或敏感请求。

启用模块实际菜单权限为 `education:foundation:read`、`education:foundation:org:create`、`education:foundation:org:rename`、`education:foundation:class:create`、`education:foundation:class:rename`、`education:foundation:school:create`。view 是模块关闭时发现页权限，不代表任何学校读写权。套餐限制和原生用户/租户/角色状态在每次数据库事务中重新锁定验证。
