# 系统版本提供者接口与验收

本模块管理系统发布组成，不管理 Git 分支、合并或源码提交。源码提交仅为可选追溯元数据。`SystemArtifact` 记录宿主后端、管理端 UI、教师端 UI、插件、服务、配置和数据库 schema 产物；`SystemRelease` 将多个独立版本的产物组合为一次系统版本。每份产物的标识、组件、标签、摘要、大小与元数据不可变；同标识重复登记相同内容幂等，换包冲突。发布标签同样不可被静默覆盖。

`VersionProvider` 是宿主注入的框架接口，支持读取与不可变登记两类显式能力。`VersionProviders` 注册提供者，拒绝重复标识。`JournalVersionProvider` 接受现有 `StateJournal`，生产宿主使用专用 journal ID 的 `JdbcStateJournal`，开发可用 `FileStateJournal`。提供者不关闭宿主持有的 journal。读操作重新载入，写操作 CAS；冲突或未知提交不会自动重试，调用方先重读核对。`FileVersionProvider` 是独立单机文件目录实现，采用进程内同步、文件锁、临时文件 fsync、原子移动和目录 fsync；它不是多节点共享文件系统协议。

登记元数据不下载或执行任何产物，也不代表来源可信、部署成功、兼容检查或发布验收完成。`FileVersionProvider.verifyBytes` 能对已经取得的实际文件做流式大小及 SHA-256 核对；发布者可信校验须由原有包信任或部署门禁执行。兼容字段是明确的字符串声明，后续发布编排负责解释和验证，不能将声明当作实测兼容证据。摘要用于检测意外损坏，不是防止有文件写权限的攻击者重新计算摘要的数字签名。

## 外部对接协议

`HttpVersionProvider` 是真实 HTTP 客户端，面向 **FCP Version Provider HTTP v1**，不是 GitHub、GitLab 或任意商业部署平台的开箱即用适配。外部平台需要实现下述协议，或编写实现相同 Java 接口的适配器。基地址只能由宿主部署配置注入，界面和插件不能传 URL。默认 HTTPS；仅显式允许时可连接字面量 `127.0.0.1`/`::1` HTTP 测试服务。禁止 URI 用户凭证、查询参数、片段及重定向。本模块不保存部署密钥；外部认证如需加入，须由宿主安全配置注入单独实现，当前适配器不能宣称支持需要特定认证的供应商。

请求带 `X-FCP-Version-Protocol: 1` 和 `Accept: application/json`，响应必须为 JSON。基地址示例 `https://version-service.example/v1/`，端点为：

- `GET artifacts`：返回完整产物数组。
- `GET artifacts/{id}`：返回产物，缺失返回 404。
- `PUT artifacts/{id}`：不可变登记，首次可返回 201，幂等返回 200，不同内容返回 409。
- `GET releases`、`GET releases/{id}`、`PUT releases/{id}`：相同语义，对应发布组合。

产物对象字段严格为 `id, kind, component, version, sha256, sizeBytes, location, compatibility, metadata`。`kind` 是 `HOST_BACKEND, ADMIN_UI, TEACHER_UI, PLUGIN, SERVICE, CONFIG, SCHEMA` 之一；末两项为字符串映射。发布对象字段严格为 `id, version, components, compatibility, metadata`，`components` 映射角色到产物 ID。ID 为 1–128 字符的字母数字、点、下划线、短横线，并以字母数字开头。SHA-256 为64位小写十六进制。未知字段、重复 JSON 键、非字符串属性、错误类型均拒绝。

响应上限1 MiB，列表上限10000条；目前不支持分页，超过上限明确失败，不能静默截断。超时覆盖完整异步响应完成；断线、超时或响应过大返回 `VERSION_REMOTE_UNCERTAIN`，尤其 PUT 必须先查询目标 ID 对账，不盲目重放。只读能力阻止 PUT。外部服务必须自行履行不可变性、引用完整性和持久性约束，客户端响应核对不能替代服务端合同。宿主应用层负责用户/租户授权，这个内部接口不接收来自浏览器的身份声明。

## 实测命令与边界

```sh
source /workspace/shared/fcp-toolchain/env.sh # 如当前环境需要
bash tests/system-version/run.sh
```

测试运行相同合同于单机文件、持久 journal、真实 loopback HTTP 提供者；覆盖不可变冲突、标签换包、重复登记、重启、并发、内容篡改、符号链接、未提交文件、实际字节摘要、CAS 提交结果未知后的重读对账、HTTP 重定向/大小/超时/身份/结构拒绝。HTTP 服务是明确的合同测试夹具，不能写成真实外部供应商已接入。

运行输出包括时间、基线提交、环境和源码摘要。真实 RuoYi、MySQL、Redis、浏览器、外部供应商和生产容灾仍需集成验收，当前脚本将前三类本模块未执行的环境明确记为 NOT_RUN。目录中的测试代码本身不代表测试已经通过，以实际运行日志为准。
