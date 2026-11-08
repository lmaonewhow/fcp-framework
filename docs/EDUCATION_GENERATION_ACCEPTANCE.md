# 教育 SQL 生成融合验收记录

## 2026-11-08 首轮：生成及构建通过，原生启动失败

PR16 head `42713878c19b76c6b02f9d740efe776e5502e1dc`，实际 CI 检出 `ec81e224e40cfc40ed5f050d3949a819c3e6f753`。两者源码树均为 `ef22cffa37a241dbddfde185fd35c3891aa5adc8`，已通过 Git 对比确认。

[全融合运行 42478400930](https://github.com/lmaonewhow/fcp-framework/actions/runs/42478400930) 整体 FAIL。新 clone 中真正从锁定 P042 生成并通过安全引擎 CREATE 10 个目标、还原 tracked SHA 和 clean tree 的门禁实际 PASS，operationId `a78205ce-e686-4509-8a28-b2f0e80a83db`。797 组件、实际双前端类型与生产构建、Maven prod 标签选择的 22 项测试、隔离数据库安装及已有数据库适配器门禁通过。基础资料/生成入口 67 项和名册 48 项 Vue 组件也通过，均不替代真实浏览器。

本次 buildId `ddab57276def490fb806db7bb92d536e`，JAR SHA256 `1368d1f256e3746e204ef857efaf63ce22edb9b2c7303b316e56fe56a3f2e144`。启动真实 JAR 时名册产物门禁报 `ROSTER_EXECUTION_ARTIFACT_MISMATCH`，宿主未就绪，教育 HTTP、浏览器、生成应用界面和最终品牌截图尚未运行，不能标为通过。原名册组合运行 42478400874 也复现相同启动拒绝。

原因是两个独立模块启动约束不兼容：生成器验收固定使用 `-Duser.timezone=UTC`，名册的严格 JAR 启动解析只接受内存参数后接 `-jar`；因此正确 JAR 也被拒绝。错误摘要负例此前没有带 UTC 参数，未覆盖真正启动前缀。修复必须只允许这一固定 UTC 选项并把正负例前缀对齐，不能接受任意 `-D`、agent、classpath，也不能关闭实际 JAR 来源与摘要校验。

失败产物 13617045257 SHA256 `453606b7d283f1601ada0b9ee7aa4bed842c06731737d8bdb729c241bb5825d7`；名册失败产物 13616369094 SHA256 `912c6a9fe92b9ec6f9b5585683ca0ed71ec89a56cb7b0d5706ebd77bcd81860e`。原始失败证据完整保留。修复后原生结果须来自新精确提交的完整重跑，不复用本次局部 PASS。

未合并任何 PR，未部署生产，未操作用户电脑或既有数据库。

## 2026-11-08 第二轮：生成与业务通过，人工截图发现动态品牌漏项

PR16 head `911711bc98528bf4a039040a368322fae339f792` 的 7 条 CI 全部通过。全融合运行 [42479909196](https://github.com/lmaonewhow/fcp-framework/actions/runs/42479909196) 实际检出 `c347b43e794e725963a9e0d19e0d60e241e2b5b8`，与 PR head 的源码树均为 `35f301a0ab321cbb6648df000ea8e328c72fd179`。本轮 buildId `26288ca5688f418b969d81dcede1fbb9`，JAR SHA256 `0afbbfa83784ba9fe685d19d1f70a5bcd095e0e3537aa1d69ec445e3f84d9bd5`。固定 UTC 启动兼容修复已在真实宿主中验证，错误 JAR 摘要仍拒绝启动。

新 clone 的真实 CREATE 10 个文件、组件回归、双端类型及生产构建、Maven prod 标签测试、隔离 MySQL/Redis、原生登录和浏览器均实际执行。教育生成器 99 项、基础资料浏览器 215 项、名册浏览器 345 项检查通过。10 个输出的 tracked、prebuild 和 Web 应用 SHA 逐一相等，11 个工作区输入 SHA 一致；生成器检查未观察到数据库结构或教育/菜单行数变化。浏览器无未处理错误。上述计数包含多层和重复边界检查，不是独立业务功能数。

原始产物 13617199812 SHA256 `ec45fb1506bddd6644cb476ad23320f4ff1e3bb192318d71ee9f72ade7f4ac6d`。人工逐张查看 5 张生成器、12 张基础资料、13 张名册和 9 张品牌截图后，发现自动品牌门禁遗漏了后端登录后延迟 SSE 通知：`education-roster-native/business/browser/admin-school-b-top.png`、`education-codegen-native/04-real-operation-history.png` 和 `05-real-created-source-files.png` 中仍显示 `RuoYi-Vue-Plus`。因此本轮只能标为功能验收通过、品牌完整性 FAIL，不能根据 7 条绿色 CI 宣布全部完成；原始图片不裁切、不涂改、不删除。

名册查看按钮会使宽表正常横向滚动，部分截图停在最右侧；这不等于页面溢出，但左侧字段取景不完整。下一轮应通过真实浏览器滚动复位后重新截图，保留完整大整数详情和所有错误诊断。动态品牌修复应改后端单条消息并增加原登录会话收到通知的真实验收，不能只关闭提示或延后截图来掩盖问题。

本轮仍不是全部 54 表的无条件 CRUD：学校、组织、班级仅受限新建与名称版本校验，学期及三类名册关系只读，人员信息策略为 PENDING。删除、状态转换和身份绑定未开放；教育页面仅在管理端。生产、既有库升级、长期多节点，以及教育 profile 在同一进程热替换合法新模板的场景未运行。PR 仍未合并。
