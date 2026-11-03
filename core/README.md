# FCP Framework 0.5.0-dev.1

这是独立 Java 框架库，包含能力契约、组合与调用、受控 stdio 进程提供者、宿主桥接接口、插件状态 SPI，以及内存和单机持久化文件实现。它不包含 Spring、若依、MySQL 驱动、学校业务或原生前端。`ui/fcp-ui.js` 是保留的受限 Schema/组件/Wasm Worker SDK，本轮没有新增其浏览器验收结果。

`dist/fcp-core-0.5.0-dev.1.jar` 是实际编译的库，不是可直接启动网站的应用。完整若依应用需要在集成源码工程完成原生构建；本版本未交付已验收的 `ruoyi-admin.jar`。

## 仅用 Java 构建和验证

以下命令在包根目录执行。构建只依赖 JDK 17+，不需要 Maven 或联网。示例另外需要 Python 3.10+，由 Java 自动创建子进程，不手工启动 Python 服务。

```bash
java tools/Build.java
java -cp dist/fcp-core-0.5.0-dev.1.jar examples/StateAndProcessCheck.java
```

Windows update after the delivered baseline: compilation still needs only JDK,
but Windows file-state execution now requires the explicitly approved pinned
SQLite JDBC JAR and its DLL. Follow [Windows setup and recovery](../docs/WINDOWS_FILE_STATE.md)
from the full package root. The unqualified classpath example above is the POSIX
path. The earlier shipped Windows-unverified statement below describes the
delivery baseline; current native Windows component evidence is recorded in the
local repair notes. Complete RuoYi/browser/load acceptance remains separate.

非默认 Python 路径可通过 `FCP_PYTHON` 环境变量或位于 Java 命令选项前部的 `-Dfcp.python=/absolute/path/python` 指定。示例真实测试状态落盘、关闭重开后保留停用、旧版本更新拒绝，以及 Java→Python 求和、进程复用、空闲退出和再次拉起。示例不模拟 HTTP 服务，不声称证明 MySQL 或原生前端已运行。

本轮在 Linux 和 OpenJDK 21 上编译 Java 17 目标并运行通过 13 个示例断言。独立 JDK 17、Windows/macOS 运行未验收。文件状态实现要求支持文件锁、原子替换及目录 fsync；不满足时拒绝继续。

## 使用边界

原生宿主的 JDBC 状态和授权适配器位于完整接入源码包。纯框架中 `MemoryPluginStateStore` 明确不持久化；旧 `HostBridge` 是兼容性嵌入示例，不能自动替宿主提供持久化策略与角色校验。安装、授权、数据归属和事务必须由真实宿主实施。可信插件进程管理不等于恶意代码沙箱，租户快照和进程池容量应由宿主按负载配置。

`reports/framework-only.log` 是这个独立包的真实验证日志；原生宿主验收仍为 BLOCKED。
