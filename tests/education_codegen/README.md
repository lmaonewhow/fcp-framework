# 教育 SQL 到可运行页面的生成验收

本目录不是 PASS 证据。`tools/ci_education_codegen.py` 负责构建前闭环，`native_acceptance.py` 负责真实宿主中的教育生成器入口。两者使用同一个 Java renderer 和 `GeneratedSourceWorkspace`，不自行拼接目标源码，也不执行 SQL。

## 构建前隔离闭环

仅在专用 GitHub Actions job 执行：

```sh
python3 tools/ci_education_codegen.py --clone-dir "$RUNNER_TEMP/generated-school-$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT"
```

脚本要求原 checkout 干净，创建无硬链接的新 clone，checkout 当前精确 HEAD。它编译并调用 `EducationGeneratorCli inspect`，核对固定 11 个仓库输入、规则与六个模板摘要、10 个固定目标及目标 Git 跟踪状态。全部校验成功后，才从新 clone 移除这 10 个已核对生成目标，不删除手工 hooks、领域 Store、transport 或其他文件。随后调用同一 CLI 的 `apply`，断言每个目标均经过真实 `create` 计划与事务回执还原，实际字节必须与原 tracked snapshot 一致，工作树必须干净。

`reports/education-codegen/prebuild.json` 记录 SQL/model/规则/模板摘要、生成结果 SHA、真实 operation ID 和命令退出码。它只证明真实源码生成与安全落盘，native、MySQL、浏览器仍是 `NOT_RUN`。原 checkout 只保留该报告的镜像，不参与后续构建。

如存在 `GITHUB_OUTPUT`，输出名为 `generated_root`。后续所有组件编译、npm 安装、原生构建、vitest 配置及证据归档路径都必须以此 clone 为根，不可继续使用原 `$GITHUB_WORKSPACE` 的源码或旧构建目录。`npm ci --prefix tests/frontend` 必须在 clone 内执行；不能从原 checkout 复制组件 classes 或 node_modules 来冒充 clone 内完成的校验。

在 clone 内完成现有组件检查后执行：

```sh
python3 tools/ci_education_codegen.py --native-root "$PWD"
```

它再次只读验证 HEAD、clean tree、输入、输出及实际 CREATE receipt，然后调用同 clone 的 `tools/ci_native.py --ingestion --education --roster --generator --education-generator`。此组合不启用 `--generator-business`，保留既有不兼容组合拒绝门禁。DB 与 Redis 仍由现有 native CI 新建并隔离；教育 DDL 保留原计划、空库、备份和部署凭证审批流程。

`validate_prebuild_report(root)` 是供 `ci_native.py` 在任何 DB 写入之前使用的纯读取门禁。native 构建后，调用方还必须将真实 buildId 和 JAR SHA 绑定到该次报告。

## 真实 Web 闭环与覆盖边界

入口为 `verify_education_codegen(generator_fixture, base, build_id)`。调用方须在普通 `generatorApply` 成功之后、最终品牌检查之前执行。不得提前复制教育文件到旧生成器的最小 fixture，否则会破坏其“预览不产生 integration 文件”的既有断言。

教育模块此时才核 SHA 并复制固定输入与 10 个生成目标到原生生成器的专用 CI 开发工作区。这个目录与构建前 clone **不是同一个目录**。真实 Web 输出与 prebuild 输出逐项 SHA 相等，证明正在运行的 renderer 一致；点击“应用”不会热加载或重建当前运行的 JAR。

场景实际通过原生加密登录和 `/admin/tool/gen` 操作，覆盖固定 SQL/model 摘要、54 表 DDL/model 字节一致声明、两个受审 module、预览/关闭/取消确认、fresh-clone exact-adopt、重复稳定、首次不同文件冲突、手改保留、陈旧目标/SQL 输入拒绝、原生操作历史与撤销、撤销不覆盖后改、未授权教师和匿名拒绝。撤销 adoption 后，仅删除本次复制且再次核对的 fixture 目标，再从 Web 真正 CREATE，最终 10 个输出 SHA 必须与 prebuild 完全相等。真实 schema 和业务/菜单行数摘要前后核对，不自动执行 SQL。

规则与模板来自当前 JAR 的固定 classpath，所以不能在同一原生进程中制造合法新版模板。教育 profile 的“合法新模板 + 手改双边冲突”明确为 `NOT_RUN_FIXED_CLASSPATH_TEMPLATES`，由已存在通用生成器原生场景与纯 Java profile 回归分别覆盖，不能计作教育 profile 原生完成。

每个独立登录批次保留完整 60 秒等待，不调整 20/IP/60s 门禁，不清 Redis。截图固定为 1440×1080，`full_page=False`，只截原生生成器业务页；自动核验横向几何、截图前后布局稳定、凭据和绝对私有路径未出现在可见文本。截图只在真实浏览器执行时产生，不以组件快照或摆拍替代。

## 组件门禁和未运行项

```sh
python3 -m unittest discover -s tests/python -p 'test_ci_education_codegen*.py' -v
```

这些测试真实使用 Git 和临时文件系统验证 clone 所有权、完整批次先核验再删除、精确路径/摘要、symlink/hardlink 拒绝、源漂移与 receipt 要求，以及测试顺序和完整登录窗口。测试中的合成 manifest 只检验 harness，不能算 Java renderer、MySQL、Spring 或浏览器 PASS。

当前执行环境不具备可用的本地原生 socket/browser 路径，未尝试绕过限制或重试。完整 MySQL、Redis、真实原生 JAR、浏览器与截图结论须来自最终融合提交的专用 GitHub CI；本地只运行对应允许的组件/文件系统校验。
