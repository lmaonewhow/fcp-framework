# FoundationStore 实现说明与验收边界

> 阶段记录：本文保留各子模块首次交付时的本地验证与 NOT_RUN 边界。后续集成的真实运行记录以 [ACCEPTANCE_STATUS.md](ACCEPTANCE_STATUS.md) 和 PR14 的精确提交产物为准；子模块初始状态不是当前集成结论，也不把源码存在当作验收通过。

本文件记录真实 JDBC 源码，不把源码存在视为原生验收通过。P042 的54表保持不变，新增 `sql/education/001__host_education_authority.sql` 是明确的宿主教育授权扩展，只允许本次新建、持有所有权的隔离测试库。没有注册到任何既有库自动迁移，没有默认授权。

## 已实现代码与身份边界

`FoundationStore(DataSource,Supplier<String>,String manifestDigest)` 只接收原生适配器捕获的原始 tenant/actor；Supplier 必须接真实宿主统一ID分配器，manifestDigest 必须来自可信服务端配置/实际构建。不能由HTTP请求提供上述参数。构造器拒绝非SHA-256格式，不代表它自己能验证发布签名。

新增 `ed_host_education_role` 把真实 native_role_id 显式映射为 TENANT_ADMIN 或 SCHOOL_ADMIN，按 operation_key 配置。TENANT_ADMIN school_id=0，可管理本租户全部有效学校；SCHOOL_ADMIN school_id>0，仅可管理精确学校。不存在从超级管理员、教师、租户字符串、旧 fcp_eval_assignment 或父组织关系自动推导教育管理权。不同操作必须同时具有新绑定行和真实原生角色菜单中的精确 `education:foundation:<operation_key>`，没有 wildcard。首阶段操作枚举 read、org:create、org:rename、class:create、class:rename、school:create；学校角色即使误配 school:create 也不能执行根学校创建。

每次请求使用 READ_COMMITTED 新事务，设置并读回UTC/严格SQL模式/外键检查，确认隔离级别。原生 sys_tenant/sys_user/sys_role 等跨租户关联显式二进制比较，读取的原生 tenant/actor 再做 Java String.equals 校验。不 trim、改大小写、添加 tenant- 前缀或重算租户。新教育模型保存100字符原样scope，但宿主本身的列宽仍以锁定原生模型为准，不声称已扩容宿主身份体系。

## 固定锁序与撤权保证

事务首先 FOR SHARE 锁 policy:tenant、cap:education.foundation 栅栏，再锁当前主体 access_epoch、tenant execution_grant、tenant_binding；随后锁原生租户、用户、真实用户角色/角色/菜单/套餐及新教育role绑定行；选择学校后锁精确 school execution_grant，再锁有效学校组织及领域对象。rename 目标使用 FOR UPDATE，更新只含 name 和 row_version，比较旧版本且影响行数必须1。范围解析的非锁定首次定位只是提示，后续在同一事务锁定并重新验证实际学校与父链，不把提示当授权。

**当前实现不接受旧授权票据，不生成可绕过重验的缓存权限。** 原生角色/用户/菜单/套餐撤销或删除将与本次 SELECT FOR SHARE 持有的真实行锁冲突，从而在本次短事务后才能提交；撤权已提交的新请求查询不到有效授权而拒绝。教育绑定/epoch/fence/execution grant 同样持锁到提交。普通原生权限变更目前没有自动推进教育epoch；因此不宣称实现跨任务旧票据epoch同步协议，异步任务入口不开放。未来需要授权票据/长任务时，必须为所有原生写路径补同步epoch协调器，不能只复用这里的读取代次。

锁序、真实MySQL外连接FOR SHARE行为、原生删除路径、死锁与撤权线性化仍必须由真实8.4并发验收证明。锁超时/死锁返回 EDUCATION_RETRY_SAME_INTENT，不自动生成新意图；除账本INSERT 1062的单次已提交回执恢复外，当前实现不隐式重试或掩盖失败。学校元数据发现为了输出待激活状态，会先读学校行再读取学校执行许可；其全部为共享锁且不执行领域写入，但与管理操作的组合仍须真实并发验证。数据库超级用户绕过流程不在应用授权保证内。

## 领域操作与业务不变量

schools、list、detail 只输出显式白名单。班级、组织、学期ID与版本始终字符串；创建时间服务器UTC微秒。学期只读，人员、身份、status任意编辑、关系换绑、关闭和DELETE无方法。组织父链限制32层，循环/未知类型/失效父节点均拒绝；学校必须同租户、根SCHOOL、有效期内。子组织仅 CAMPUS/DEPARTMENT，生效时间不得早于父组织，已指定结束时间的父组织不接受无限期子组织。

班级新建引用同学校同学期组合，学期只允许DRAFT/OPEN，创建不改变学期状态。class_code、grade_code、org_code不可后续编辑，稳定编码不去除前导零。名称长度遵循P042，空白名称拒绝，其他合法文本不悄悄规范化。

全局学校创建仅 TENANT_ADMIN 的 school:create，属于tenant级命令；它不隐式创建school execution_grant，也不隐式授予新管理员。新学校投入业务前必须经明确部署流程配置学校执行许可。schools 是独立的学校元数据发现用途：明确 read 授权下可看见待激活学校，缺学校执行许可时 operations 全 false、readiness=EXECUTION_GRANT_MISSING。创建回执含 activation:EXECUTION_GRANT_REQUIRED。具体学校领域读取和写入仍要求学校许可，不回退租户执行许可。

列表在SQL按uint64 id排序和afterId分页，limit=1..100；学校选择器支持相同afterId/limit分页，每页候选扫描最多1000行；组织查询使用仅从当前已授权学校根向下的参数化递归CTE，不扫描其他学校后再过滤，超限返回 RESOURCE_LIMIT而非静默截断。规模扩展需专门索引/递归查询与执行计划，不声称已经完成大规模性能验收。

## 正式事务与幂等

真实表名为 `ed_core_command_request`，不是泛化的 request_dedup。效果命名空间固定 `education.foundation.<kind>.<action>`，intent_id跨重试保持；指纹包括主体、学校、命令与排序后的固定字段请求，不包含代码版本。每次回放先重验当前权限；其他主体、其他学校或不同内容不能读取旧结果。

事务内插入ACCEPTED账本，执行领域变更，插入COMMITTED审计与NEW outbox，并把账本更新COMMITTED/最小receipt，同一连接一次commit。所有ID来自统一供应器。审计只记批准的变更字段名称/版本，不默认复制完整请求；outbox只记对象ID/版本，创建aggregate_seq=1，rename递增，uint64溢出回滚。成功审计、outbox或receipt失败都会回滚业务。

返回 `{operation:{intentId,outcome:"COMMITTED"},record:{...}}`。commit异常不推断回滚，返回 EDUCATION_COMMIT_UNKNOWN。GET operation查询当前权限下的权威账本，找不到返回 UNKNOWN，不能证明未提交。重复键/业务冲突返回 CONFLICT；同意图并发的账本INSERT 1062在确认整个旧事务rollback成功后，最多开启一次完整重授权事务，只读取同namespace/intent/actor/school/fingerprint的COMMITTED回执；没有匹配回执则CONFLICT，绝不再次执行领域写入。其他表唯一冲突、死锁与UNKNOWN不触发该自动恢复，不可当失败就换intent。

DENIED/FAILED/UNKNOWN使用独立连接写 `ed_core_security_event`，不塞到回滚的业务审计；安全事件并发预算4，失败或超预算累计可观测 `securityEventFailures()`，能力响应也携带计数。该计数为进程内监测，不是持久积压；持久安全事件失败队列、告警接入和洪泛限速仍待完善，不能称完整生产安全审计可靠性已验收。拒绝事件只写稳定错误码和空安全上下文，不泄露SQL/异常/原请求。

## 真实验证状态

本次实际执行 javac --release 17 编译 FoundationStore/EducationScalars/FcpException/Json 及 FoundationStoreBoundaryCheck，运行35条纯请求边界断言通过。没有使用JDBC代理、内存数据库、伪造token或Spring桩冒充真实宿主。

真实MySQL DDL/驱动绑定/SQL执行、并发撤权/CAS/意图争用、commit UNKNOWN故障注入、原生角色/套餐/认证、浏览器与持久化重启尚未执行，全部NOT_RUN。父级的独立原生CI需绑定最终源码及构建产物后执行。此文件不表示可生产发布或54表业务完成。

## 原生 JDBC 探针接入

`tests/education/FoundationStoreNativeProbe.java` 必须在 `EducationFixture.seed()` 完成后、HTTP verify修改学校管理员授权前运行。它使用实际原生表/角色942101、账号1/950001与真实学校800001/800002，创建本次probe专用class/账本/事件行，不清库、不执行DDL、不创建假会话。依赖真实MySQL Connector/J。

环境变量仅从CI私有运行配置传递：GITHUB_ACTIONS=true，FCP_EDUCATION_TEST_DATABASE（fcp_ci_加12位hex），FCP_EDUCATION_TEST_URL、FCP_EDUCATION_TEST_USER、FCP_EDUCATION_TEST_PASSWORD、FCP_EDUCATION_TEST_MANIFEST。不得打印这些凭证。先编译 FoundationStore、EducationScalars、FcpException、Json和probe，再 `java -cp <classes>:<真实mysql驱动.jar> FoundationStoreNativeProbe`。

probe包含跨校/跨租户拒绝、精确tenant尾空格拒绝、幂等、成功audit/outbox计数、两并发真实CAS仅一成功、真实outbox唯一冲突导致业务/审计/账本全回滚、真实原生角色禁用与已接纳事务共享行锁竞争、撤权确认后的新命令拒绝。屏障由服务端ID供应器在已完成授权且持锁时触发，不替换JDBC或认证。probe原生执行结果尚未产生，当前仅编译成功；UNKNOWN提交故障注入明确NOT_RUN。

探针运行账号只在专用CI测试库拥有sys_role与ed_*的DML，用于合成授权撤销；这不是生产运行账号权限建议。尾空格探针显式准备全部前置ed_*权威行，再以不存在的原生精确tenant触发拒绝，避免仅被缺栅栏行挡住。它不单独证明所有原生join路径，仍需原生SQL与锁验收。

## 独立复查修订

组织查询的CTE只递归当前已授权学校，历史同校有效CAMPUS/DEPARTMENT可以任意嵌套，不附加未经确认的父子类型组合限制。深度32或循环/未知子类型作为错误哨兵优先返回，明确拒绝而非静默截断。CTE的物化结果不冒充锁定读；分页输出和实际祖先再次FOR SHARE读取并重验同校。其他学校的坏链和行数不会污染当前学校页；原生probe新增他校坏类型、跨层嵌套和limit=1游标用例。

原生probe新增屏障同步的相同create意图两请求：两事务均在无回执后、真正INSERT前到达屏障，然后实际争用MySQL唯一键，要求完全相同回执、且业务/账本/审计各一份。该新增原生探针仍待MySQL执行，不以纯编译宣称PASS。

Controller边界可调用 rejectBoundary/rejectIdentity，以固定码记录最小安全事件；无法取得可信身份时仅调用 boundaryAuditUnavailable 累计可检测失败，不发明tenant/actor、不保存原始body。
