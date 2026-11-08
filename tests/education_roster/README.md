# 教育名册关系页面：只读安全骨架

本阶段按 v4.2.1 `database/model.json` 和文档 02/03 实现学籍、入班历史、任教关系三个页签。入口为 `/admin/fcp/roster`，组件位于 `integration/plus-ui/src/fcp/education-roster`，仅调用 `/fcp/education/roster`。菜单、独立 `education.roster` 能力和原生权限由后端集成负责，绝不复用基础资料权限。

服务端按 `education:roster:student:read` 与 `education:roster:teacher:read` 返回学校目录并集及每校独立 operations。前端不根据角色名推断权限，不传租户、用户、用途票据或全租户 person 查询参数。学校选择项以名称和完整十进制 ID 区分。

当前人员展示政策为 `PENDING`。页面明确显示“关联信息尚未开放”，白名单不会保留 `personId`、姓名、学号、学科编码、入班变更原因或私密引用。全局人员改名、学期新建、学籍/入班/任教增删改均未开放；只读关系页面不是完整 CRUD 交付，也不是已通过原生验收的声明。

ID 和版本不转 JS Number，UTC 时间保留微秒，业务 DATE 不转换成本地时间。切校、切页签、取消、离页及身份/登录会话变化均使原请求失效。当前请求失败清除学校、列表和详情，不把 403/非 200 原生封装当成空列表。原生 Axios transform/interceptor 的失败语义有独立适配测试。

## 组件检查

先在本仓库布局中组装锁定 plus-ui 并复制 integration overlay。依赖使用原生锁，不另建替代 UI 项目。测试工具目录需含 `@vue/test-utils` 与 `jsdom`。

```bash
FCP_ADMIN_PROJECT="$PWD/work/ci-native/upstream/admin" \
FCP_COMPONENT_TOOLS="$PWD/tests/frontend" \
work/ci-native/upstream/admin/node_modules/.bin/vitest run \
  --config tests/education_roster/vitest.config.mjs
```

组件测试运行真实 Vue/ElementPlus，身份和 HTTP 明确使用合成 mock；原生传输测试保留真实 plus-ui request.ts、Axios transforms/interceptors，仅替换网络 adapter 和无关依赖。jsdom 补齐动画调度 API，不声称视觉布局得到浏览器验证。禁止将上述结果描述为真实服务器鉴权或原生浏览器通过。

## 真实浏览器 runner

`native_browser.verify_browser(fixture, base, build_id)` 由隔离原生 CI 调用。需要已应用的本轮专用 fixture，保留原生加密登录与验证码，直接观察实际 HTTP。fixture 提供 `plan.database`、`plan.applied`、`host.login`、`host.diagnostic_secrets` 和 `output`。现有 host 的 admin/teacher/viewer 名称只是合成账号句柄，测试权限由 fixture 独立配置：分别为全校双权限、学校 A 学生读、学校 A 任教读。

runner 仅查询，不执行 seed 或数据库变更，不拦截路由、不伪造登录。检查三类响应字段白名单、精确学校隔离、页签权限、详情关闭和切校。固定 1440×1080 viewport，以真实页面滚动获得上下帧，保存捕获前后几何信息，不使用 fullPage 或 CSS 修改规避原生侧栏。API 分页与撤权由原生 HTTP fixture 检查；竞态故障由明确 mock 的组件测试覆盖。

本地 Chromium 已知受 Unix socket 环境限制，本次不重试或绕过。浏览器 runner 的语法检查不代表已运行。真实浏览器、MySQL/Redis、隔离角色和最终 CI 必须由集成人在最终源码上实际执行并单独记录 PASS/FAIL/BLOCKED/NOT_RUN。
