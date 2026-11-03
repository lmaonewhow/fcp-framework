# FCP 0.6 数据接入与跨语言业务插件

本功能位于 `feat/ruoyi-5.6-plugin-ingestion` 分支。后端固定 RuoYi-Vue-Plus 5.6.2，管理端固定 plus-ui 5.6.2-v2.6.2，教师端保留 ruoyi-plus-soybean 1.1.3。精确提交以 `upstreams.lock.json` 为准。原生登录、验证码、租户、角色、菜单权限和 MySQL 数据库继续由若依提供。构建过程先应用经过摘要检查的前端兼容层，再执行原生类型检查和生产构建。

## 交付边界

当前实现面向可信插件、Linux 单节点和小规模班级数据。业务示例是学生名册与考勤汇总。API 示例使用本仓库明确标注的 Campus-v1 合成平台；没有声称已经接入任何商业教育平台。CSV 适配器支持本文约定的中文及英文导出格式。真实平台接入需要依据其公开或授权接口与导出样例编写对应插件，取得 API 凭证并复核字段语义。

组件检查通过不等于原生验收通过。`reports/ingestion.json` 记录 Java、Python、Node、真实 HTTP 和文件持久化的验证，明确将 Spring、MySQL、浏览器记为 NOT_RUN。原生独立流水线 `.github/workflows/native-ingestion.yml` 执行实际 MySQL、Redis、Maven、双前端、Playwright 和 JVM 重启，结果以该次提交的 `reports/ci-native.json` 与 `reports/native-ingestion/acceptance.json` 为准。未得到最终 PASS 前，不将本分支称为完成验收或生产可用。

## 六个独立插件

名册业务插件 `roster.business` 负责学号、姓名和班级校验与标准化。`roster.connector` 通过 API 分页读取学生信息，`roster.adapter` 读取平台导出的 CSV。考勤业务插件 `attendance.business` 负责日期、状态及统计，`attendance.connector` 和 `attendance.adapter` 分别接入考勤 API 与 CSV。每个插件均有独立 ID、契约、启动器、期望状态和版本；六个插件首次默认停用。

插件清单位于 `runtime/ingestion/catalog.json`。Java 宿主管理进程、授权、依赖、版本和事务；Python 文件 `inputs.py` 解析输入，`roster.py` 与 `attendance.py` 执行业务规则，`worker.py` 提供进程通信入口。持久化和最终提交留在宿主，插件不会获得数据库账号。

启用意味着该插件允许被调用，子进程按需启动，并在空闲时回收。停用操作等待当前已接纳的操作结束，然后释放相关子进程。停用业务插件会同时使它的连接器和适配器失效，保留这些输入插件原本的启用意愿；重新启用业务后恢复依赖关系。停用单个输入插件不影响另一输入通道或业务读取。已有数据和插件期望状态在宿主重启后保留。状态修改采用版本检查，旧页面或旧 JVM 的请求会被拒绝，避免覆盖较新的状态。

## 数据如何进入系统

API 连接器或文件适配器先把外部字段转换为统一记录，业务插件随后执行规则校验。宿主检查租户、班级、依赖状态、当前授权及数据版本，合并已有数据，再交由业务插件计算完整数据集的统计结果，最后通过宿主记录存储提交数据、幂等记录与审计信息。来源批次有任一错误时整批拒绝。外部 API 读取不属于数据库事务，也没有跨平台分布式事务承诺。

名册以学号作为班级内键，考勤以学号和日期作为班级内键。名册导入属于增量合并，未出现在批次中的既有学生不会被删除。考勤引用必须存在于同租户、同班级的名册。跨班级记录、重复键、未知字段、无效日期和不支持的状态均被拒绝。学号必须是字符串，保留 `001` 这样的前导零。

每次导入携带数据版本和请求幂等键。相同用户重试同一键和同一内容时返回历史结果；复用同一键提交不同内容会被拒绝。浏览器在失败后保留原请求，提供重试同一请求或明确放弃重试信息的操作。刷新整个浏览器页面会丢失该页内存中的重试信息，因此网络结果不明确时应先重试或核对数据。

当前每个班级、每种业务的数据集最多 1,000 条记录，单份 CSV 与单页 API 响应最多 128 KiB，API 最多读取 10 页。考勤上限包含多个日期的累计记录，不能将其解释成每天 1,000 条。大规模历史考勤应扩展存储、分页和查询窗口之后另行验收。

## 连接器配置

API 地址和凭证只能来自部署者管理的私有文件。通过运行属性 `FCP_INGESTION_CONNECTIONS` 指向该文件。页面请求只能选择已安装的插件与被授权班级，不能指定 URL、文件路径或凭证。生产配置默认要求 HTTPS，拒绝重定向、私网、链路本地地址及云元数据地址；DNS 校验后的地址用于实际连接，TLS 仍检查配置的主机名。内网学校平台需要另行设计明确的部署者白名单策略，当前示例不会默认放开全部私网。

文件结构如下。`tenant-000000` 是示例内部租户作用域；正式部署应使用实际作用域。私有文件应由运行用户持有，文件权限为 0600，直属目录权限为 0700，不得提交真实令牌。

```json
{
  "format": 1,
  "tenants": {
    "tenant-000000": {
      "roster.connector": {
        "baseUrl": "https://platform.example.invalid",
        "token": "REPLACE_IN_PRIVATE_CONFIG"
      },
      "attendance.connector": {
        "baseUrl": "https://platform.example.invalid",
        "token": "REPLACE_IN_PRIVATE_CONFIG"
      }
    }
  }
}
```

演示平台通过 `python tests/ingestion/platform.py --port 18889` 启动，绑定 `127.0.0.1`。仅演示时把配置的 `baseUrl` 改为 `http://127.0.0.1:18889`，`token` 设为 `demo`，并显式添加 `"allowLoopback": true`。该开关仅允许回环地址，不会开放其他私网。演示 API 是独立测试夹具；宿主运行入口仍是一条 Java 命令，Java 管理业务插件进程。

Campus-v1 名册地址为 `/v1/roster?classId=class-a&cursor=...`，记录字段为 `student_code`、`full_name`、`class_code`。考勤地址为 `/v1/attendance?classId=class-a&cursor=...`，记录字段为 `student_code`、`class_code`、`occurred_on`、`check_in`。响应是含有 `items` 和 `nextCursor` 的 JSON 对象，游标为空字符串或 null 时结束。重复游标、超过页数上限及非 JSON 响应都会失败。

CSV 名册的中文表头是 `学号,姓名,班级`，考勤表头是 `学号,班级,日期,状态`。也接受与上述 API 一致的英文表头。编码可选择 UTF-8（含 BOM）或 GB18030。中文考勤状态支持正常、出勤、迟到、缺勤和请假，英文可用 present、late、absent、leave，Campus-v1 可用 P、L、A、E。CSV 适配器不直接读取 XLSX；表格软件需导出符合约定的 CSV。

## 一个可复现的实际业务场景

场景是班主任为 `class-a` 整理名册和当天考勤。部署前需要真实若依数据库及迁移、独立运行凭证、Redis、Python 和预构建双前端。原生应用由 `java -jar ruoyi-admin.jar --spring.profiles.active=prod,fcp` 启动。MySQL、Redis 与外部平台服务不由该 Java 命令安装。构建流程沿用 README 中的私有配置、锁定依赖及数据库初始化步骤；构建工具使用 JDK 21、Node 22、Maven 3.9.x、pnpm 10.5.2 和 Python。

管理员完成若依原生登录，在管理端 `/admin/fcp/ingestion` 打开数据接入页面，启用名册业务、名册连接器和名册适配器。第一次 API 同步读取两页合成平台数据，得到三名学生。导入 `fixtures/ingestion/roster.csv` 后更新一名学生并新增一名学生，合计四名学生；界面展示新增、更新、未变化数量和数据版本。

随后启用考勤业务及对应连接器、适配器。API 同步得到三条考勤，正常、迟到、缺勤各一条，演示口径的出勤率为 66.67%。再导入 `fixtures/ingestion/attendance.csv`，修正缺勤并增加一条请假，得到四条考勤，出勤率为 100%。这里的业务口径将迟到计入已到、请假排除在应到分母之外；实际学校应确认自己的规则后修改业务插件。

管理员停用名册业务后，其 Python 子进程被释放，名册输入插件停止接受新调用，考勤业务继续可用。宿主重启后，名册仍保持停用，MySQL 中的导入数据保留；重新启用后可以读取既有名册。教师账号通过 `/teacher/fcp/ingestion` 只能导入被分配班级的数据。只读账号不显示导入或管理控件，直接调用被禁止的接口仍会被后端拒绝。跨班级导入整批失败，不产生半批数据。

原生自动化使用独立 `fcp_ci_*` 数据库和临时账号执行上述场景，保留验证码和加密登录。测试只从自己刚创建的隔离 Redis 中读取验证码答案，不替换登录端点、不拦截伪造响应、不生成假会话。录像在登录完成后开始，登录令牌、真实私有配置和 Playwright storage state 不写入证据包。

## 接入其他语言

进程边界使用 UTF-8、按行分帧的 JSON-RPC 2.0。请求包含 `jsonrpc`、字符串 `id`、`method` 和 `params`；当前业务示例的方法为 `run`。响应回显 `id` 并返回契约匹配的 `result` 或结构化 `error`。每条消息以换行结束，stdout 仅用于协议，日志使用 stderr。语言运行时需预先安装，启动命令由受信的插件清单声明，禁止调用者通过请求提供任意启动命令。

因此 Java 宿主不依赖插件的 Python 类或 Python 包结构。Go、Node.js、Rust、.NET 等语言可以实现相同协议并提供自己的启动命令。`tests/ingestion/node-worker.mjs` 已通过真实 Node 进程证明名册业务契约能够跨语言替换，不需要改动 Java 业务调度代码。该证据表示协议与已测实现互通，不表示所有语言、框架、操作系统及未来插件都已自动完成兼容性验收。

新增输入插件时应给它独立 ID 与契约、声明业务依赖，并为平台字段、分页、权限、错误、编码和限额添加测试。现有六插件目录在宿主启动时加载；已安装插件支持运行时启停，新增或修改目录和启动器仍需受控发布及重启。当前未提供任意上传代码即热安装的插件市场，也未提供恶意插件沙箱。

## 复测与证据

在准备好 Java、Python 和 Node 的工作区执行 `python tools/verify_ingestion.py` 可以复测输入解析、业务规则、真实跨语言进程、分页 HTTP、权限、幂等、停用和文件持久化重启。执行 `python tools/verify_components.py` 与 `python tools/verify_legacy.py` 复测原有组件。不同组存在交叉覆盖，检查数不可相加作为独立业务功能数。

原生 GitHub Actions 工作流会创建临时 MySQL 8.4 与 Redis 7.4，绑定回环端口，准备独立 DML 运行账号，再构建和启动锁定上游。运行完成后下载 `native-ingestion-evidence` 查看构建清单、原始日志、验收 JSON、截图和录像。失败记录也保留；缺少成功报告、截图或角色检查时不得宣称相应场景已经通过。长期稳定性、生产安全审计、备份恢复、实际学校平台、超过示例规模的数据和多节点一致性属于另外的验收范围。
