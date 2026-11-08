# 原生退出后重新登录的响应体诊断

保留组合运行 42442941809、artifact 13602443930 的 FAIL。输入 ZIP SHA-256 为 `3ac775b09add9c9a46e621d4d1105b4adee3c1ed5589482238e51543cc91b30b`，报告摘要见 `evidence/native-relogin-before-fix.json`。158 项既有检查完成后，教师端原生退出、同页面第二租户登录在 `/auth/tenant/list invalid-json` 停止。旧 `_login_envelope` 把 `response.json()` 的所有异常都归到 invalid-json，组合入口又未保存 exception.diagnostic 或临时回调的 diagnostics。因此不能据此断言服务器返回坏 JSON、命中限流，或已经证明 Chromium 丢失资源；本次真实失败原因仍为 UNKNOWN。

## 确定的源码缺口与最小修复

锁定教师端 [auth/index.ts](https://github.com/m-xlsea/ruoyi-plus-soybean/blob/4e625111ce173e5c2f40dbfc47475f2849c58feb/src/store/modules/auth/index.ts) 的 logout 先 `await fetchLogout()`，再调用未 await 的 resetStore；resetStore 先 clearAuthStorage，再 await toLogin。锁定 [router.ts](https://github.com/m-xlsea/ruoyi-plus-soybean/blob/4e625111ce173e5c2f40dbfc47475f2849c58feb/src/hooks/common/router.ts) 将其导向原生 login 路由。管理员 [Navbar.vue](https://github.com/CrazyLionCat/plus-ui/blob/d0d451967676707021b9857df529c395b27e90a7/src/layout/components/Navbar.vue) 也在 userStore.logout 的 promise 完成后 router.replace 到 login。原测试只等待 `/auth/logout` 响应头，不能保证上述正常客户端清理完成，随即进行另一个 goto 存在完成条件缺口。这是源码证实的 helper 契约缺口，不是对旧 CI 异常根因的推定。

native_logout 现在在点击前保存原页面 origin，只接受该 origin 的真实注销响应，再观察同源原生 login 路由及 context.storage_state 中原生 token 已为空。它不读取注销响应体，不写 token、不清 storage、不创建新会话、不额外请求注销，也不替代调用方的真实服务端失效断言。等待超时、仍有 token 或不同 origin 均失败。随后才允许既有调用方打开原生管理员登录表单。

登录准备现在对每个响应只读取一次 body，并单独解析 UTF-8 JSON。body-read-failed 与 invalid-json 明确分开；失败原因只写固定枚举，例如 resource-unavailable、target-closed、timeout、json-syntax、invalid-utf8。诊断只保留固定端点、HTTP 状态、三位数业务码、合法非零 32 位 Trace、固定 contentType 分类、bodyState 和字节长度。没有响应原文、server msg、异常原文/自定义类名、URL/query、租户列表、验证码、token 或 storage state。未加入 body hash。HTTP 429 或既有精确原生限流消息才允许原规则的一次完整 60 秒等待；资源/解析/未知错误仍立即 FAIL，不因其他端点同时限流而重试。

组合 fixture 现在保留回调对象，在 context 关闭之前将 loginPreparation、logoutPreparation 和 loginFailure 持久化，包括成功响应和终止失败。私有日志投影也接入这些失败 Trace，即使 HTTP 200 且拿不到业务 envelope，仍可关联原生 completion 结构字段；成功记录不因此增大投影目标。原始私有日志仍不导出，缺失记录保持 missing。

## 独立浏览器 probe 与验证边界

`probe_native_login_body.py` 仅提供明确合成的 loopback HTTP 页面和数据，使用真实 Chromium 网络及实际 helper，不拦截或伪造正式原生验收响应。负对照固定延迟退出 body，观察旧的仅响应头边界仍保留 token，继而让旧调用方式自行导航；另一负对照在租户响应头后、body 未完成时切换文档，检查旧分类与新的 body-read-failed 区别。正对照默认五次执行实际 native_logout 和 open_native_tenant_login，要求正常导航、token 已由页面自身清除、两个响应各一次且无重试。这个合成 probe 不是若依、MySQL、鉴权或租户隔离验收，不能计入真实原生检查数。

composition workflow 在安装 Chromium 后单独执行 probe，并上传 `reports/native-login-probe/`。probe 失败会阻止正式验收步骤，必须分别看到该 probe 与完整原生组合场景通过，才能给最终提交结论。没有修改原生服务的鉴权、限流、路由或数据库。

本云工作区实际执行 `python -m unittest discover -s tests/runtime-native -p 'test_*.py' -v` 为 91 tests PASS，composition config 为 11 tests PASS，CI-native 为 15 tests PASS，退出码均为 0；py_compile 与 git diff --check 通过。原始输出与源码文件摘要见 `evidence/native-relogin-*.txt`、`evidence/native-relogin-local-status.json`。包含响应体读取/语法/编码区分、成功信息脱敏、恶意 header/异常原文、异源 loopback、注销未完成、报告提前持久化、失败 Trace 投影及原有限流正反例。

实际运行 `python tests/runtime-native/probe_native_login_body.py --browser-executable /usr/bin/chromium --output reports/native-login-probe-local` 返回退出码 2、BLOCKED。容器 Chromium 启动时 socket() 被运行层拒绝，授权 escalation 也没有改变结果；没有进一步绕过限制。因此本地没有真实浏览器竞态复现或修后稳定性结果，CI probe 和真实原生重跑均为 NOT_RUN。不能把这些单元测试、源码论证或合成页面称作本次未知 CI 根因已经被证明，也不作任何生产验收声明。
