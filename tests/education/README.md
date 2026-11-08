# 智慧云校基础资料管理端验证

真实API位于 `/fcp/education/foundation`。能力READY后读取授权学校、学校组织/学期/班级及详情，创建学校/组织/班级和仅名称CAS修改。BLOCKED时显示具体阻断原因。学校必须readiness READY且返回对应明确操作许可；新校待部署许可时只显示元数据，不开放管理。没有通用删除或学期状态写入。当前为管理端基础资料阶段，不是54表或教师端完成。

入口 `@/views/fcp/education/index.vue`，由共享装配方注册 `/fcp/education` 及菜单组件 `fcp/education/index`。菜单权限仅决定入口可见，业务授权由后端每次复核。既有native.py自动复制overlay，无需额外复制逻辑。

使用锁定原生管理端Vue/ElementPlus，HTTP和身份为组件测试替身，不替代原生登录/数据库验收：

```sh
FCP_ADMIN_PROJECT=/absolute/assembled/admin FCP_COMPONENT_TOOLS=/absolute/locked/component/tools node /absolute/assembled/admin/node_modules/vitest/vitest.mjs run --config "$PWD/tests/education/vitest.config.mjs" education
(cd /absolute/assembled/admin && npm exec -- vue-tsc --noEmit && npm run build:prod)
```

`native_foundation_browser.py` 提供 `verify_foundation_page`（BLOCKED只读页）和 `verify_organization_roundtrip`（新建组织/详情/CAS改名）。必须由原生验收fixture先建立专用loopback宿主、真实登录、明确学校授权/部署许可及构建标识。写探针只对调用方拥有的隔离测试学校运行，保留新增行作证据。禁止拦截HTTP响应、伪造token或对现有用户库运行。两个探针当前均NOT_RUN，仅Python语法检查通过。

未决请求的原path/body/intent保存在按原生身份隔离的sessionStorage中（无凭证）。页面先GET `/schools/{schoolId}/operations/{kind}/{action}/{intentId}` 或新校 `/operations/schools/create/{intentId}` 核对；UNKNOWN/无权/未找到都不自动新提交。原请求体可能包含基础资料名称，测试不要保存浏览器session/storage-state到Git或证据附件。当前不提供核对后重发按钮。

## 整合CI真实浏览器模块

`native_browser.py` 暴露 `verify_browser(education_fixture, base, build_id)`。fixture应有 `.host`（真实ingestion登录fixture）、`.sql`（仅供模块执行SELECT）、`.plan.database`、`.plan.applied` 和 `.output`。输出在 `<fixture.output>/browser/result.json` 与截图目录，记录精确Git提交、模块SHA256、buildId及仅合成教育业务POST的白名单字段；不记录headers、token、登录页或storage state。

推荐先运行现有HTTP验证，然后由CI恢复测试角色987102的class:rename许可并递增相关版本，再调用浏览器模块（模块自身执行全新真实登录）。浏览器依次验证两校管理员切换、单校管理范围、组织新建/详情/名称CAS及真实并发陈旧版本冲突、班级创建/详情/名称修改/刷新持久化、取消、连续双点击及SQL单效果、只读用户无写控件。最后才创建等待部署许可的新学校，避免改变前置HTTP对两个学校的预期。额外JDBC/HTTP记录不会被当作失败；列表按nextAfterId加载，SQL仅按本次随机合成编码计数。模块不做UNKNOWN故障注入，不得用组件模拟结果填充原生证据。

模块当前只通过Python语法检查，真实CI执行由父级整合后产生结果；源码存在不等于原生PASS。

### 固定视口截图边界

第四轮真实CI曾出现fullPage捕获前布局正常、捕获后侧栏与正文进入不同步响应式过渡的证据；前后几何PASS也不能证明中间捕获的长图可读。保留旧图与旧FAIL，不通过关闭动画或改CSS掩盖。当前截图改为固定1440×1080真实视口：关闭下拉，等待连续4帧几何相等且有限CSS过渡结束，滚动到顶部/底部分别保存独立PNG，不拼接、不裁剪、不调整viewport。每帧记录捕获前、即时捕获后及稳定后几何，继续检查横滚和侧栏遮挡；下一轮CI仍需逐张人工检查图片后交付。

本地合成布局复现实验因Chromium ProcessSingleton所需Unix socket被执行环境拒绝（EPERM），在请求提升执行权限后仍无法启动浏览器，属于BLOCKED，不是截图验证PASS。Python语法检查通过；真实固定视口截图效果等待CI证据。
