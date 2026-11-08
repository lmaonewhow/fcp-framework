# 教育关系名册：第二阶段只读接口

本模块是独立的学校范围关系元数据读取，不是通用表 CRUD，也不是人员身份查询。依据 v4.2.1 的 model.json、reference_contracts、docs/02 和 docs/03，同租户自然人跨校共享；学生通过 enrollment 归属学校，任教通过 assignment → class → school 归属学校。学校管理员只管理明确授权的本校，租户管理员在本租户学校内工作。不能从账号角色名称、超级管理员、基础资料权限或 URL 自报 schoolId 推断授权。

用户尚未决定姓名/学号用途是否包含 LEFT 历史、RESTRICTED 的展示方式、全租户自然人改名与学期创建/修改规则。本阶段这些操作全部关闭；不把 ACTIVE 推断为可以展示姓名。合成测试中的状态只是验证输入和边界，不代表用户批准的展示规则。

## 配置与独立用途授权

默认关闭。必须设置 `fcp.education.roster.enabled=true`，并由部署过程提供 `FCP_EDUCATION_ROSTER_MANIFEST_DIGEST`，值为实际执行产物 SHA-256。该值绝不能来自浏览器。原生配置在创建 store 前检查真实 Java 进程参数的主入口为 `-jar`，只支持前置 `-Xms` / `-Xmx`，拒绝 main-class、module、argfile、agent 和伪装成应用参数的 -jar。验证配置类与校验器自身的 CodeSource 都来自该外层执行 JAR，并对实际归一文件流式计算 SHA-256、比较配置值和读取前后文件属性。摘要或来源不符即以 `ROSTER_EXECUTION_ARTIFACT_MISMATCH` 拒绝启动，类目录模式不隐式降级。该门不是签名验证、动态类加载沙箱或恶意宿主证明；部署者仍须保持执行文件不可变、控制 Java agents 与类加载器。设置配置本身不授予任何学校访问权。

独立扩展 `sql/education/003__roster_authority.sql` 定义 `ed_host_roster_role`，不改 P042 的 54 表设计。每行包含 tenant_id、native_role_id、admin_kind、school_id、operation_key、purpose_key、enabled、source_approval_id、created_at、row_version。enabled 默认 0，无任何默认授权。purpose_key 固定为 `school-roster-metadata-v1`，只授权本校关系元数据，不包括姓名、学号、person_id、任教学科或变更原因。TENANT_ADMIN 必须 school_id=0，SCHOOL_ADMIN 必须 school_id>0。

精确原生菜单权限 `education:roster:student:read` 对应 enrollment/membership，`education:roster:teacher:read` 对应 assignment；两者不能互相替代。页面目录 `986120` 和发现按钮 `986100` 使用 `education:roster:view`，学生 `986101`，任教 `986102`。view 只用于模块未启用时查询建设状态。`004__roster_menu.sql` 只插入菜单，不给用户、角色或套餐授权。所有表和菜单仅允许本次新建的隔离验收库应用，现有库/生产迁移另行审查。

每次请求都使用真实宿主会话 tenant/actor 的原始字符串。JDBC 在同一连接中设置并检查 UTC、严格 SQL 模式、FK 检查和 READ_COMMITTED，然后按租户 policy fence、cap:education.roster fence、主体 epoch、实际 manifest 的 tenant grant、原生租户/用户/角色/菜单/套餐及独立用途映射、school grant、学校/领域对象锁进行读取。缺权威行直接拒绝；基础资料的 execution grant 和角色表不会自动授予本模块权限。学校目录把授权学校条件放在 SQL 分页前，先查候选 ID，再锁定全部相关学校 grant 并重新锁读学校行，不使用未锁定的候选元数据对外响应。

## 路由与输入

前缀 `/fcp/education/roster`。所有接口 GET，未提供 POST/PUT/PATCH/DELETE 或 person 路由。

| 路径 | 查询参数 | 结果 |
| --- | --- | --- |
| /capabilities | 无 | 模块能力与未决策略 |
| /schools | afterId、limit | 当前授权学校目录 |
| /schools/{schoolId}/{kind} | afterId、limit | 本校关系列表 |
| /schools/{schoolId}/{kind}/{id} | 无 | 本校关系详情 |

kind 仅 `enrollments`、`memberships`、`assignments`。limit 默认 50，范围 1..100；afterId 默认字符串 `0`。实体 ID 必须为 1..18446744073709551615 的规范十进制字符串，游标允许 0；不得用 JS number/Java long 转换。数值列的等值、范围和学校 IN 条件统一用 `CAST(? AS UNSIGNED)` 显式转换已校验的字符串参数，不依赖 Connector/J prepared 模式或 MySQL 字符串比较的隐式 DOUBLE 转换。任何额外参数以及重复参数均拒绝，包括 teacher/person/personId/tenant/actor/scope/purpose/epoch/manifest/status。学校路径是目标选择，不能声明授权。分页按 ID 升序，返回 `data:{items:[...],nextAfterId:null|string}`；详情 `data=record`。外层沿用 `{code:200,msg:"",data:...}`。控制器成功与错误响应均显式设置 `Cache-Control: no-store`。

## 能力与学校目录 DTO

capabilities：schemaVersion=`education-roster-v1`，status 为 READY 或 BLOCKED，operations、readiness、blockers 和 securityEventFailures。readiness 包含 schoolAuthorization 与 transactionProtocol，启用为 IMPLEMENTED，关闭为 NOT_ENABLED；nativeAcceptance 固定 NOT_RUN，personDisplayPolicy 固定 PENDING。该 NOT_RUN 是代码内建设标记，不是 CI 报告；真实验收结论必须按实际提交证据判断。

operations 的完整键为 schoolList、enrollmentList、enrollmentDetail、membershipList、membershipDetail、assignmentList、assignmentDetail、personDisplay、enrollmentWrite、membershipWrite、assignmentWrite、personRename、termWrite。后六项恒 false。前七项依据当前明确 student/teacher 用途映射计算。关闭时全部 false。blockers 至少包含 PERSON_DISPLAY_POLICY_PENDING、ROSTER_WRITE_POLICY_PENDING，关闭时另有 ROSTER_NOT_ENABLED。

学校项只包含 id、name、rowVersion、readiness、operations。readiness 为 READY 或 EXECUTION_GRANT_MISSING。目录发现例外仍需要有效 tenant 执行 grant、当前原生身份和至少一个覆盖该校的 student/teacher 用途映射。缺本模块学校 grant 的目录项只显示学校元数据，所有操作 false；不会读取该学校关系。学校是否当前有效按 org_unit 的 SCHOOL 类型、空 parent_id 和左闭右开的有效区间判断，没有臆造 school.status 列。

## 关系 DTO 白名单

所有关系共同包含 id、schoolId、validFrom、validTo（可 null）、createdAt、rowVersion、personDisplay。时间均 UTC ISO 字符串；createdAt 是入库时间，不拿它代替业务生效时间。personDisplay 恒为 `{status:"PENDING",reason:"PERSON_DISPLAY_POLICY_PENDING"}`，不包含身份属性。

- enrollments 另含 admissionKey、status。状态仅认可 ACTIVE、TRANSFERRED、GRADUATED、SUSPENDED，不提供转换或修改。
- memberships 另含 enrollmentId、classId、className、termId、termCode、termStatus。
- assignments 另含 classId、className、termId、termCode、termStatus、roleCode。roleCode 仅认可 TEACHER、SUBSTITUTE、HEAD。

termStatus 从同租户同校学期行解析，只认可 DRAFT、OPEN、FROZEN、ARCHIVED。冻结和归档是读取的历史上下文，不会推断可写操作。membership 同校 enrollment 与 class 均在当前事务重新解析，membership 区间必须包含于 enrollment 区间。未来/当前/已结束关系都只是用途受控的元数据，不能被当成当下任教或人员可见权限。

person resolver 在同一个已授权连接上只读取 id、status，验证同租户存在性、已知状态和所属学校关系的左闭右开时间上下文。person 没有 school_id，也没有 valid_from/valid_to，故不得添加虚构字段或凭 created_at 拒绝合法回填的历史关系。ACTIVE、LEFT、RESTRICTED 均不会打开 PII 门；未知状态拒绝整个请求。不会通过另开连接校验后继续使用未锁定的人员引用。

不会 SELECT/返回 display_name、private_ref、student_code、subject_code、change_reason、账户当前/历史、外部身份映射、监护或任何他校经历。person_id 仅内部绑定到 typed resolver，绝不进入公开 DTO。返回的关系 ID 本身仍可关联个体，因此关系元数据也需要上面的独立明确用途授权，不是公开数据。

## 错误、审计与验收范围

所有 roster 路径另有最高优先级范围限定 filter，在原生认证前设置 no-store，覆盖控制器之前的未登录拒绝；不更改旧模块缓存策略。

当前授权不足为 EDUCATION_FORBIDDEN/403，目标在授权学校内不存在为 EDUCATION_NOT_FOUND/404，输入或来源关系不合法为 EDUCATION_INVALID_INPUT 或 EDUCATION_INVALID_RELATION/400，权威不可用和锁失败为 503，授权规模超过上限为 EDUCATION_RESOURCE_LIMIT/429。内部 SQL、凭据和人员值不进入错误消息。只读事务失败不产生 COMMITTED 业务审计或 outbox；拒绝/失败以最小上下文进入独立安全事件连接，失败计数有界保留。中央报警和生产可靠性仍未验收。

本地 `tools/verify_education_roster.py` 会编译新增 core 类并执行 tests/education_roster 下的 RosterStoreBoundaryCheck 与 Python 源/锁守卫；这些是纯边界和编译检查，不是 MySQL/原生认证验收。`RosterStoreNativeProbe` 仅在 GITHUB_ACTIONS 且本次 fcp_ci_ 隔离 MySQL 8.4 库运行，HTTP 原生登录、菜单、套餐和前端由独立 ci_roster 组合验收覆盖。教育专属进程重启与生产恢复仍未验证。禁止拿旧基础资料 PASS 代替本模块真实测试。
