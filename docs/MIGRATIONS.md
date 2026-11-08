# 受控增量迁移工具

`dev.fcp.deploy.Migrations` 是独立部署工具，应用和插件不自动调用它。源码依赖标准 Java 与 FCP JSON 工具；执行时另需 MySQL Connector/J。当前验证涵盖 Java 编译、脚本校验和、受限 SQL 解析和失败门禁，真实数据库执行尚未验收。

## 准备和只读计划

从已完成原生构建的依赖取得匹配版本 Connector/J。部署账号应专门授权 DDL，应用运行账号继续仅使用必要 DML。准备仅部署用户可读的 `jdbc.properties`，包含 `jdbc.url`、`jdbc.user` 和 `jdbc.password`。连接 URL 必须指向明确数据库并使用适当的 TLS 验证；不要把密码放到命令行、日志或源码。

```properties
jdbc.url=jdbc:mysql://db.example.internal:3306/fcp_school?sslMode=VERIFY_IDENTITY
jdbc.user=fcp_deployer
jdbc.password=REPLACE_WITH_PRIVATE_VALUE
```

将占位值替换为环境中的真实凭证，POSIX 权限设为 0600。Linux 类路径使用冒号，Windows 使用分号；Windows 未实测。

```bash
java -cp "dist/fcp-migrator-0.5.0-dev.1.jar:/private/mysql-connector-j.jar" dev.fcp.deploy.Migrations sql/native /private/jdbc.properties fcp_school
```

不传 `--apply` 时只查询历史、生成计划，不执行建表。计划摘要绑定数据库名、脚本版本、校验和及待执行动作。数据库必须具有可验证的 V001/V002 安装历史；工具不接受“看起来差不多”的无历史现有库。

## 明确执行

审查计划、锁影响和备份恢复方案后，将本次打印的摘要填入下一条命令。状态变化或脚本变化导致摘要不同，执行会停止；需要重新生成和审查计划。

```bash
java -cp "dist/fcp-migrator-0.5.0-dev.1.jar:/private/mysql-connector-j.jar" dev.fcp.deploy.Migrations sql/native /private/jdbc.properties fcp_school --apply=REPLACE_WITH_64_HEX_PLAN_SHA256
```

执行在同一 JDBC 连接上持有 MySQL 命名锁，并记录 RUNNING、APPLIED 或 FAILED。V001/V002 只验证原始摘要，不由增量工具重新导入。V003 新增插件状态与审计表；运行账号没有修改结构的职责。已经由空库初始化器成功执行且摘要一致的 V003，可登记为 ADOPT_VERIFIED_INSTALL。

MySQL DDL 可能在失败前已经提交。FAILED 或遗留 RUNNING 会阻断后续执行；不会自动删除表、清除历史、重跑脚本或声称事务回滚成功。发生中断时由部署负责人检查库结构、历史和备份，制定修复或恢复方案。恢复演练尚未完成，不能凭此工具宣称 RTO/RPO 已达标。

## V009：只清理确认未修改的上游默认菜单

`V009__remove_upstream_seed_menus.sql` 对应锁定宿主 5.6.2 的原始菜单 4（PLUS 官网）、5（测试菜单）与 1500–1511（测试单表、测试树表及按钮）。新建专用空库的安装清单在 V008 后运行它；不改上游 SQL，不删除测试表、账号、组织、角色、业务记录，也不自动给任何角色授权。生成器验收专用 probe 不在此清单中。

清理要求 ID、名称、父级、顺序、路径、组件、参数、类型、权限、图标、显示/启用状态、创建者/部门、备注与未修改审计字段全部符合原 seed。字符串按二进制精确比较，只有安装时生成的创建时间不参与比较。修改过的菜单、重用 ID、大小写或尾部空格变化都保留。父级身份改变时保留其 seed 子项；任一自定义/不可清理子项存在时保留其全部祖先，避免悬空菜单。因此既有库中带自定义子项的“测试菜单”可能继续保留，需管理员确认业务去向后另行迁移，不自动改名、隐藏或转移业务菜单。

仅删除最终确认可移除菜单的 `sys_role_menu` 引用，并按完整逗号分隔 token 清除 `sys_tenant_package.menu_ids` 中的对应 ID，保留其他 ID、顺序和套餐其他字段。空值、空串、重复 seed ID 均有处理；任一套餐出现非规范数字 CSV（例如空格、前导零、空 token 或非数字）时，本次删除清理整批不改菜单或引用，先由管理员检查。此保守跳过也可能被空库安装记录为已执行，因此验收应同时核对菜单实际状态，不把 APPLIED 等同于全部菜单已移除。

既有数据库只生成审查计划，不通过通用 Java 迁移器执行 V009：缺少 V009 历史时输出 `REVIEW_NATIVE_DATA_ONLY`；即使传入计划摘要，执行入口也在写任何迁移历史或结构前停止。SQL 类别限制仍然拒绝原生菜单 DML。已有空库安装器的匹配 V009 历史可以校验/登记，但不会重新执行清理。

本次未对用户现有数据库运行清理。后续若迁移既有库，必须另外获得授权，先备份菜单、角色菜单及套餐三张表，验证备份可恢复，在副本审查逐行原始身份和自定义子树、套餐 CSV 与预期删除/保留清单，再使用独立部署账号在停机维护窗口单连接执行已审查脚本。脚本事务覆盖引用与菜单变更，临时表仅本连接可见；不能在应用并发修改菜单/套餐时运行。任何失败都停止并检查事务与安装历史，不自动重跑或伪造 APPLIED。保留不可判定的菜单是安全边界，不代表允许额外清理业务数据。

新增回归命令为 `python -m unittest discover -s tests/python -p test_seed_menu_cleanup.py -v`。该回归通过明确的 MySQL 语法转换在 SQLite 上执行交付 SQL，验证删除范围与引用逻辑；它不验证 MySQL ICU 正则、字符集、锁和事务实现。真实 MySQL 8 执行必须另行验证，不能将此结果作为真实 MySQL 验收。

### V009 MySQL 8 语义复核与剩余门禁

已对照 [MySQL 正则说明](https://dev.mysql.com/doc/refman/8.0/en/regexp.html) 和 [ICU 语法](https://unicode-org.github.io/icu/userguide/strings/regexp.html) 复核：删除 CSV token 的 `(?=,)` 是零宽正向前瞻，保留后一逗号以便连续重复 ID 都能匹配；`REGEXP_REPLACE` 默认替换全部匹配项。ICU 的 `$` 可以匹配最后一个换行之前，所以 CSV 校验另加 `[^0-9,]` 排除，不能只依赖首尾锚点。回归适配器使用搜索语义而非强制 fullmatch，并增加尾换行失败后修复用例。

[MySQL 临时表限制](https://dev.mysql.com/doc/refman/8.0/en/temporary-table-problems.html) 禁止同一语句重复引用同一物理临时表。V009 用 candidates 与独立 snapshot 两张表，每条语句对各表只引用一次，不使用临时表自连接或重复子查询，也不封装到存储函数中。[隐式提交规则](https://dev.mysql.com/doc/refman/8.0/en/implicit-commit.html) 明确 CREATE/DROP TEMPORARY TABLE 不触发隐式提交，但临时表自身创建/删除不随事务回滚；断开失败连接可清理它们。脚本没有 ALTER/TRUNCATE/CREATE INDEX 等会引入额外提交的操作。

事务保护要求三张宿主表仍使用 InnoDB（锁定上游定义如此）。已改引擎、额外外键/触发器、复制/GTID 与 statement-based binlog 限制或非预期 SQL mode 的既有库必须另行审查，不能沿用空库结论。执行器必须遇首个 SQL 错误即停止并回滚/关闭连接，禁止使用 `mysql --force` 后继续到 COMMIT。安装器当前没有指定 `--force`；失败会关闭该阶段连接并写失败历史。本次只做源代码及官方文档语义复核，真实 MySQL 8 的字符集、SQL 解析、临时表、ICU 和事务失败测试仍为 NOT_RUN，交由独立 CI 门禁验证。


### V009 原始请假子树的目录显示名

锁定的 `ry_workflow.sql` 会将有效的请假申请入口 11638 挂在原始目录 5 下，并提供 11639–11643 五个权限按钮。此前只测试 `ry_vue_5.X.sql` 的 14 个清理 seed，遗漏了完整 workflow 安装后的这棵有效子树；真实 MySQL CI 因目录仍显示“测试菜单”发现该问题。现在 focused fixture 同时覆盖原始 workflow seed，不能再以“目录 5 必须消失”作为品牌清理判据。

删除清理完成后，只有目录 5 仍完全符合原始 seed 身份、其唯一直接子项为完整原始 11638、11638 的子项恰为完整原始 11639–11643，且这五个权限项下没有任何新增子项时，才将目录 5 的 `menu_name` 改为“日常事务”。其他字段保持原值，包括 ID、父级、路径 `demo`、显示顺序、权限和审计字段；请假入口仍为原来的 `/demo/leave` 路径，已有书签、角色/套餐引用、API 和业务数据不改变。改名决定先存入独立临时 snapshot，再做 JOIN UPDATE，避免 MySQL 更新目标表的子查询限制。

任一静态身份字段被修改、ID 被重用、权限按钮缺失、额外业务分支存在时均不改名；自定义分支仍受原有祖先保护。创建时间是安装时生成值，不属于静态 seed 身份。再次执行时，“日常事务”不再符合原始目录名称，安全保持原状。此精确例外不授权移动或删除请假业务，也不把其他自定义“测试菜单”强行改名。当前真实 MySQL CI 已发现旧版遗漏，新版仍需重新执行 CI 验证；SQLite 适配回归不替代该门禁。
