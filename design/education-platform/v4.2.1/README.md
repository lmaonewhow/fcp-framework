# 教育平台数据库与灰度设计 4.2.1（独立设计参考）

本目录来自2026-11-06交付的4.2.1设计与工具包，隔离引入 FCP 0.6 代码库供审查和后续集成，不直接替换宿主运行逻辑、既有 SQL 迁移、身份授权或 CI 工作流。当前**生产发布 BLOCKED**；现有 `dev` 的真实业务验收不自动延伸到本目录。

原包包括历史压缩包和多轮测试日志。为了保持 Git 仓库轻量，本目录同步可阅读的文档、模型、SQL 草案、参考协议、工具、测试及项目开发约束，未提交历史压缩包和本地运行日志。原始 ZIP 的 SHA-256 和文件摘要见 `GITHUB_IMPORT.json`；这不是已经集成到 FCP 的数据库实现。

需要 Python 3.11+、Node 20+。在本目录运行 `python tools/run_checks.py --output /tmp/edu-v421-check`，仅验证**本地设计参考**；不连接真实宿主、MySQL、浏览器或生产数据。`database/sql/P042__fresh_isolated_schema.sql` 仅供新建隔离测试库，禁止对 FCP 正在使用的数据库直接执行，必须另行设计经批准的增量迁移。

阅读顺序：`开始阅读.md` → `docs/11_最终核查与4.2.1修订.md` → `docs/02_数据库完整设计基线.md` → `docs/04_可观测性与前后端灰度.md` → `docs/05_pstack开发约束与原生验收.md`。`governance/.agents/skills` 是项目技能草案，不会自动安装。请不要用 `governance/AGENTS.fragment.md` 覆盖仓库根部 `AGENTS.md`。

当前宿主依据 `dev` 的 `upstreams.lock.json` 和 `docs/INGESTION_ACCEPTANCE.md`，可插拔模块现有结构需要单独映射本设计对象。要升级现有DB，先确认真实表、schema history、租户/学校标识、历史值和备份，完成测试后提交独立数据库迁移PR。真实MySQL、Windows、FCP、浏览器、观测栈、并发、负载和恢复验收均须另行产生证据。
