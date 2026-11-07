# 可信宿主 UI 的版本化资产与入口

本模块只处理受信任的 ADMIN_UI/TEACHER_UI 系统发布产物。运行时插件仍然只能声明配置页面、槽点和获批能力，不能借此上传任意 JavaScript 或修改宿主源码。静态包由部署者安装到独立目录，版本登记 API 本身不上传或执行资产。

## 产物契约

`tools/ui_release_bundle.py` 对预编译 dist 建立不可变目录 `<root>/<digest>/<shell>`。摘要是 manifest.json 去掉 digest 字段后递归排序、紧凑 UTF-8 JSON 的 SHA-256，files 各条目绑定文件字节摘要与大小。它不是 ZIP 文件摘要。系统 Artifact 的 sha256 使用该 bundle 摘要、sizeBytes 为文件大小总和，metadata.format 必须为 fcp.ui-bundle/1。旧资产无自动删除。

系统版本 components 的 admin-ui 和 teacher-ui 引用对应 Artifact，类型分别 ADMIN_UI/TEACHER_UI；compatibility.hostContract 必须与实际宿主 fcp.host/1 一致。服务端同时校验 bundle 声明、文件内容及 index。目录不得包含私有账号、租户业务数据或服务端凭据；打包器不能自动识别编译代码中的所有秘密。

Vite 只在生产构建使用相对资产 base，Vue Router 的固定 /admin/、/teacher/ base 不变。原始 index 的哈希先校验，服务器再确定性插入版本化 `<base>` 和 bundle 元数据。个性启动清单返回转换后 index 的摘要，启动器校验后才加载。根 index.html.gz/br 不能表示转换后的内容，因此不发布；默认 JAR 入口也移除原始预压缩索引。其他预压缩静态资产保留。

## 接入与权限

共享路由 bean 为 fcpReleaseRouter（DurableReleaseRouter），JDBC journal 为 system_release_policy。UI 和插件调用必须使用同一个 bean、同一策略及签名上下文。设置 fcp.release.routing.enabled=true，并提供绝对路径 FCP_RELEASE_ROUTING_KEY_FILE：32 个随机字节、文件 0600、父目录 0700、运行用户持有。它是部署秘密，不能上传到仓库、前端或版本登记。

设置 fcp.ui.release.enabled=true、FCP_UI_BUNDLE_ROOT 指向部署者的不可变包根目录。现有 VersionProvider 和 NativePluginHost 必须已配置。自定义宿主可直接组合 UiBundleStore、UiReleaseApplication 与自身实时 ShellAuthorizer。原生适配重新核验登录、租户和 shell 权限；进入灰度不授予业务权限。

GET /fcp/ui/releases/bootstrap?shell=admin 或 teacher 使用真实原生 Authorization/clientid；可带 X-FCP-Release-Affinity，服务器验证绑定而不接受任意 releaseId。响应 private,no-store，包含选定 releaseId、签名 affinity、bundle 和 index 摘要及固定内部 URL。静态 /fcp/ui/assets/** 可公开 immutable 缓存；这不授权任何业务 API。其他 /fcp API 没有放开认证。

控制面必须通过真实 NativeDeploymentProvider 校验所需插件与两套资产就绪并更新共享 router；本模块不提供绕过发布计划的直接策略写接口。平台发布治理入口 /admin/fcp/releases 始终使用可信内置壳层，不依赖业务发布健康，仍由原生平台权限鉴权。没有策略时保留可信内置 UI 供首次部署；存在策略后，停用、资产缺失、完整性失败、未知发布均失败关闭，不自动回旧版。

## 浏览器与一致性

普通入口、深链接刷新都先用当前宿主身份获取启动清单。无登录时仍使用原生登录 UI，不替换认证。每个标签页用当前 token 的 SHA-256 绑定签名上下文，不在 URL、Cookie 或额外持久字段重复保存原始 token。插件目录和调用携带同一个 affinity；在途写入不得读取后来替换的新 affinity 自动重试。

已加载 JavaScript 不会因为发布指针变化自动卸载。导航检查发现新 bundle 或 release 时，未保存输入要求明确确认后刷新。拒绝刷新不会重发业务写入；安全撤权与紧急停流仍由服务端优先拒绝。退出或身份变化清理发布上下文并丢弃迟到响应。旧静态资产保留并不意味着旧业务权限继续有效。

## 验收边界

组件命令：`python tests/ui-release-assets/verify.py`，包括真实回环 HTTP 资产服务、文件完整性和客户端状态测试。打包测试使用明确合成 dist，不冒充 Vue。`native.build_ui_variant` 在完整真实基线构建后重新编译两套锁定 Vue 项目，改变编译 buildId，输出独立候选资产；功能源码有意相同，仅用于证明实际版本选择。

`tests/runtime-native/ui_release_acceptance.py` 提供真正原生登录、双 Vue shell、摘要、缓存、深链接与伪造上下文测试，必须由隔离 CI 实际执行。代码和测试存在不代表原生、数据库、浏览器或生产验收通过。本云环境 Chromium 启动曾因 socket 权限失败，不通过改安全参数绕过。

正常首次部署/禁用使用已认证 HTTP 200 清单：mode=EMBEDDED，shell 必须匹配，reason 为 NOT_CONFIGURED 或 DISABLED。启用发布清单使用 mode=RELEASE。客户端不把 HTTP 401/404 或任何错误解释成内置回退；STOPPED、缺失产物和未知发布仍失败关闭。已加载版本化 UI 不接受直接降级到内置壳层。
