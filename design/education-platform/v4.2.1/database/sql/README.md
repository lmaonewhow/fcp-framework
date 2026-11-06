# SQL使用边界

P042__fresh_isolated_schema.sql由database/model.json唯一生成，目标是全新、隔离的MySQL 8.4验收库。当前交付没有在原生MySQL执行它，必须通过tools/mysql_acceptance.py以及实际FCP的升级验收后再采用。运行账号没有建表、改表及任意DDL入口；应用启动只验证模式兼容性。

T042与Q042属于明确标注的协议示例，包含命名参数与必须由应用实现的步骤，不能复制到数据库控制台当作完整程序执行。完整发布所需的原生迁移与应用脚本要针对已经核实的源库生成。本包没有覆盖既有迁移历史，也没有自动改造任何学校数据。

模型变更后执行python tools/generate_schema.py --write，再运行python tools/run_checks.py。生成器会重建SQL和字段目录；手改生成SQL会在一致性检查中失败。SQL文件、模型和测试必须一同进入审查，单改其中一个不能发布。
