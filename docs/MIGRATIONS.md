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
