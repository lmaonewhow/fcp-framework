# 教育 SQL 生成融合验收记录

## 2026-11-08 首轮：生成及构建通过，原生启动失败

PR16 head `42713878c19b76c6b02f9d740efe776e5502e1dc`，实际 CI 检出 `ec81e224e40cfc40ed5f050d3949a819c3e6f753`。两者源码树均为 `ef22cffa37a241dbddfde185fd35c3891aa5adc8`，已通过 Git 对比确认。

[全融合运行 42478400930](https://github.com/lmaonewhow/fcp-framework/actions/runs/42478400930) 整体 FAIL。新 clone 中真正从锁定 P042 生成并通过安全引擎 CREATE 10 个目标、还原 tracked SHA 和 clean tree 的门禁实际 PASS，operationId `a78205ce-e686-4509-8a28-b2f0e80a83db`。797 组件、实际双前端类型与生产构建、Maven prod 标签选择的 22 项测试、隔离数据库安装及已有数据库适配器门禁通过。基础资料/生成入口 67 项和名册 48 项 Vue 组件也通过，均不替代真实浏览器。

本次 buildId `ddab57276def490fb806db7bb92d536e`，JAR SHA256 `1368d1f256e3746e204ef857efaf63ce22edb9b2c7303b316e56fe56a3f2e144`。启动真实 JAR 时名册产物门禁报 `ROSTER_EXECUTION_ARTIFACT_MISMATCH`，宿主未就绪，教育 HTTP、浏览器、生成应用界面和最终品牌截图尚未运行，不能标为通过。原名册组合运行 42478400874 也复现相同启动拒绝。

原因是两个独立模块启动约束不兼容：生成器验收固定使用 `-Duser.timezone=UTC`，名册的严格 JAR 启动解析只接受内存参数后接 `-jar`；因此正确 JAR 也被拒绝。错误摘要负例此前没有带 UTC 参数，未覆盖真正启动前缀。修复必须只允许这一固定 UTC 选项并把正负例前缀对齐，不能接受任意 `-D`、agent、classpath，也不能关闭实际 JAR 来源与摘要校验。

失败产物 13617045257 SHA256 `453606b7d283f1601ada0b9ee7aa4bed842c06731737d8bdb729c241bb5825d7`；名册失败产物 13616369094 SHA256 `912c6a9fe92b9ec6f9b5585683ca0ed71ec89a56cb7b0d5706ebd77bcd81860e`。原始失败证据完整保留。修复后原生结果须来自新精确提交的完整重跑，不复用本次局部 PASS。

未合并任何 PR，未部署生产，未操作用户电脑或既有数据库。
