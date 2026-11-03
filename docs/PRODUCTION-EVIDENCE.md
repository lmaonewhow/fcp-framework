# 生产证据与发布门禁

`tools/production_gate.py` 在发布目录读取真实 `ruoyi-admin.jar` 和 `BUILD-EVIDENCE.json`，在证据目录读取 api、browser、migration、load、soak、security、restore、baseline 八类 JSON。所有结果需绑定同一 buildId、jarSha256 与 production-targets.json 的 SHA256。原生 Maven 报告必须有实际测试且无失败、错误和跳过，两个前端必须有真实构建、类型检查与可重复使用的依赖锁证据。

每类结果要求 schemaVersion 为 1、execution 为 REAL、status 为 PASS、mocked 为 false、skipped 为 0，附带 `checks` 断言和实际原始日志/轨迹文件的相对路径及 SHA256。必需断言 ID 在工具的 GROUPS 常量中明确规定。浏览器还需管理员、教师、只读三个角色的 WebM 视频，门禁用 ffprobe 检查真实可解码媒体和长度，只有空文件或仿造文件头无法通过。环境必须提供 ffprobe。

性能阈值与负载条件定义于 `config/production-targets.json`，包括 50 并发用户、10 万条记录、至少两个租户、30 分钟混合业务和 24 小时稳定性。这些数字是规划约束，当前没有对应的真实性能结果。基准必须比较同硬件、同数据及同业务下的原生若依与 FCP 版本；现有单接口测量脚本不足以自动填满所有生产指标。仍需扩充负载、备份恢复和安全执行器，保留原始证据。

门禁检查真实 JAR 摘要、证据路径范围、非空文件、文件摘要、录像格式、数值有限性和边界、必要测试断言，不接受缺失或跳过。它是证据一致性检查器，无法证明人为编造的 JSON 和日志是真的。完整上线决策还要求受信 CI、源代码审查和独立复核，不能通过手写 PASS 文件替代实际工作。

本次 `reports/production/PRODUCTION-GATE.json` 应为 BLOCKED。组件测试可以使用标注清楚的替身证明代码逻辑，生产证据必须来自真实 MySQL、真实宿主、真实页面和真实业务操作。当前手动 CI 只覆盖构建、数据库适配器及静态入口冒烟，不能被直接转换成生产 PASS。
