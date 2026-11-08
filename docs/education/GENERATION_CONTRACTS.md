# 教育生成契约基础（未接入原生宿主）

> 当前融合链路另见 [SMART_SCHOOL_FUSION.md](../SMART_SCHOOL_FUSION.md) 与 [EDUCATION_GENERATED_FRONTEND.md](../EDUCATION_GENERATED_FRONTEND.md)。下文只记录最早元数据模块的边界；后续页面与路由由固定 Java profile 生成，可信身份与事务仍由业务内核负责。

> 阶段记录：本文保留各子模块首次交付时的本地验证与 NOT_RUN 边界。后续集成的真实运行记录以 [ACCEPTANCE_STATUS.md](ACCEPTANCE_STATUS.md) 和 PR14 的精确提交产物为准；子模块初始状态不是当前集成结论，也不把源码存在当作验收通过。

本模块从 dev 提交 f362456999f2f8586650edf96721cb8e0faf222f 的教育设计 4.2.1 生成元数据，不修改该设计，不是 P042 执行器，也不是页面或业务 API。它与 PR12 通用生成器验收独立。现有 fcp_dual_record 的 JSON/UUID 存储与教育关系模型不同，ScopeNames 的前缀租户作用域不能当作原始 tenant_id 使用。

## 文件与生成边界

`contracts/education/sources.lock.json` 固定模型、P042 SQL、数据契约、02/03/11 文档及现有 SQL 生成校验器的 SHA-256。刷新锁必须人工审查来源变更，验证入口不会自动重锁。`runtime/education_contracts/catalog.py` 在加载或生成前验证所有摘要，再以完整对象比较已提交目录。生成目录包含真实 54 表、548 列、40 复合外键及模型中的主键、唯一键、CHECK 和 domain_invariants。测试另外运行设计原有生成器，验证模型与 SQL 完全一致；不执行 SQL。

`runtime/education_contracts/scalars.py` 与 `core/src/main/java/fcp/education/EducationScalars.java` 提供可独立测试的无依赖 API 标量边界。Python 负责构建时目录生成和校验。Java 只是可供未来宿主调用的基础类，目前没有控制器调用、可信身份绑定、Java 目录加载器、领域命令或数据库事务。加入 core 源码不等于已原生接入。

目录所有操作白名单和状态转换都为空，所有字段 client_writable=false，显示策略默认拒绝直到用途与授权明确，包括普通字段。操作门禁也拒绝 READ，因为描述数据库表不代表获得数据读取权限。没有通用 DELETE、逻辑删除字段推断、任意 SQL 或开发数据库绕行。状态描述保留原文，但枚举不是初始态或转换规则。主键字段标记不可变，tenant/id/version/入库时间标记服务端管理。所有非“普通”及缺失分类字段保守视为敏感或未分类。

模型存在六个缺少 classification 的字段，及 ed_identity_account_current 缺少 immutable 属性，目录明确列出待定项，不能默认推断为可编辑。ed_ops_release_binding 虽然表级 immutable=false，其 RELEASE_CONTENT_IMMUTABLE 不变量仍完整保留；表级 CAS 预条件不构成内容编辑许可。

## 无损数值与时态

租户必须是非空、最多 100 个 Unicode 标量字符的字符串，保留原样空格、大小写、组合字符和前缀；不 trim、normalize 或调用 ScopeNames。该校验不证明租户来源可信，真实宿主必须从可信会话取得并逐对验证与数据库 NO PAD 语义一致。未配对 UTF-16 代理字符拒绝；100 个补充平面字符在 Java 和 Python 都计作100字符。

unsigned BIGINT 接口只接受 0 至 18446744073709551615 的规范 ASCII 十进制字符串，拒绝数字对象、浮点、指数、符号和前导零。学校ID可使用显式 positive=true。DECIMAL(12,4)/(16,4) 使用精确字符串，拒绝溢出、指数和超小数位，不舍入；账户 unit_scale 明确限制允许小数位。负数是否合法仍由积分类型及账户领域规则决定。返回值保留原串，这不是业务意图指纹规范化器。

UTC 时间必须带 T 和 Z，最多六位小数，采用文档持久化范围 1000-01-01 至 9999-12-31T23:59:59.499999Z。DATE 使用独立业务日期校验，不能当 UTC 瞬间转换；学校 IANA 时区解析与数据库连接 UTC 初始化尚未实现。字符串格式通过不是 JDBC 绑定或 MySQL 验收。

## 复合键、CAS 与待完成规则

Python 按锁定目录要求完整主键，包括非 id 主键，不接受缺字段、额外字段或跨租户键。Java compositeKey 只检查服务端传入的已审查键集合与租户一致性，具体每个字段类型仍须调用标量校验，不能让浏览器提供 reviewedColumns。两者都不是授权入口。

Python cas_precondition 只生成结构预条件，拒绝不可变或未明确可变的表及版本耗尽；Java nextVersion 和 requireSingleAffectedRow 提供相同数值/行数边界。实际提交必须在领域事务内按完整主键和预期版本条件写入，原子增版本且影响一行，否则回滚。尚未执行任何并发更新，因此不能宣称 CAS 数据库安全已验收。

开放具体操作前仍需要逐领域明确初始状态、转换、可写字段、受限字段脱敏/显示、学校/班级/监护权限、逻辑引用解析、时间区间不重叠、当前指针、不可变内容、冲正及余额、冻结报告和投影代次规则。身份切换与授权 epoch、写入栅栏、执行许可、幂等、成功审计和 outbox 必须由真实宿主在同一事务边界实施。缺规则或权限行时拒绝，不能把版本预条件当作授权。

## 本地验证

在仓库根目录执行 `python tools/verify_education_contracts.py --java-home /path/to/jdk`，或在 PATH 已有完整 JDK 时省略 java-home。Java 编译目标17，测试运行使用实际配置 JDK。每次输出独立 run_id、命令、退出码、日志摘要、源码逐文件摘要、Git HEAD/工作区状态及完成标记，历史运行不得替代本次结果。缺 JDK 则 NOT_RUN 并整体不通过；无第三方依赖，无数据库访问。使用 `--write-catalog` 可重新生成目录，但不会刷新锁。单独运行 `python -m unittest discover -s tests/education_contracts -v` 仅覆盖 Python 和设计真实文件。

设计完整回归另外执行 `python design/education-platform/v4.2.1/tools/run_checks.py --output reports/education-design`。其 SQLite/Node 策略测试不等同原生宿主、MySQL 或浏览器验证。本阶段上述原生层、迁移、生产发布全部 NOT_RUN。
