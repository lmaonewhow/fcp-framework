# 发布控制台测试

`node --experimental-strip-types --test tests/frontend-release/controller.test.mjs` 使用 Node 22.14+ 的 TypeScript 类型擦除，验证独立前端状态机与两种宿主响应封装。它不冒充真实 RuoYi、Vue 浏览器或 MySQL 验收。原生 UI 类型检查、生产构建及浏览器流程仍必须由原生流水线实际运行并记录。

覆盖显式权限、身份切换与迟到响应丢弃、重复点击、计划确认、CAS 修订、未知写入结果禁止重发、对账、待决执行、输入校验及销毁行为。网络失败后不自动生成第二个部署请求；刷新只读状态也不解除未决锁。

观察面板状态机：`node --experimental-strip-types --test tests/frontend-release/observation.test.mjs`。验证精确enable/disable/tick payload、禁止浏览器样本、view/manage权限、pending与未知结果阻断、重复点击、401/身份切换清理、404未启用及精确百分比换算。共享组件单独使用vue-tsc检查，不等同两套锁定宿主浏览器验收。
权限投影刷新与原生身份变化使用不同通知。相同显式权限（包括不同排列顺序）的刷新不会中止写入或清除确认预览；权限请求失败、撤权和重新授权会清空敏感数据，但保留未知结果及被中断写入的未决锁。只有显式服务器对账可解除该锁。回归覆盖两个真实宿主封装、权限请求竞态、过期成功/失败响应和写入期间撤权。插件及观察控制台须同样消费 `observePermissions`，不能把权限变化当成账户切换。
