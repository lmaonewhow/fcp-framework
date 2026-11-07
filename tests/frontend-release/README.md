# 发布控制台测试

`node --experimental-strip-types --test tests/frontend-release/controller.test.mjs` 使用 Node 22.14+ 的 TypeScript 类型擦除，验证独立前端状态机与两种宿主响应封装。它不冒充真实 RuoYi、Vue 浏览器或 MySQL 验收。原生 UI 类型检查、生产构建及浏览器流程仍必须由原生流水线实际运行并记录。

覆盖显式权限、身份切换与迟到响应丢弃、重复点击、计划确认、CAS 修订、未知写入结果禁止重发、对账、待决执行、输入校验及销毁行为。网络失败后不自动生成第二个部署请求；刷新只读状态也不解除未决锁。
