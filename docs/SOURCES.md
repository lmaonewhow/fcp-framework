# 上游来源与适配依据

本轮通过连接的 GitHub 工具直接读取固定提交的源文件。仓库和提交固定在 `upstreams.lock.json`，构建器执行前检查所修改源文件的 Git blob SHA；这保证目标版本匹配，无法替代完整编译与运行。

RuoYi-Vue-Plus 后端固定 v5.4.1、提交 64100cf1ffaeec78e36344d4617f6cb828ef093f。已核对原生 application.yml、application-dev.yml、ruoyi-admin/pom.xml、IndexController、SecurityConfig、LoginHelper 和基础 SQL 表结构。后端使用原生会话及 clientid 校验，FCP 不自行签发若依令牌。

```text
https://github.com/dromara/RuoYi-Vue-Plus/tree/64100cf1ffaeec78e36344d4617f6cb828ef093f
```

管理端固定 plus-ui v5.4.1-v2.4.1、提交 b000788785dd0d294e03cb650a0cfbf33bc1c758。仓库已迁移到 CrazyLionCat/plus-ui。已核对 package.json、生产环境变量、auth.ts、permission.ts、router/index.ts 及授权文本。选定标签的 package-lock.json 与 pnpm-lock.yaml 均不存在，构建器对此显式处理。

```text
https://github.com/CrazyLionCat/plus-ui/tree/b000788785dd0d294e03cb650a0cfbf33bc1c758
```

教师端固定 ruoyi-plus-soybean v1.1.3、提交 4e625111ce173e5c2f40dbfc47475f2849c58feb。已核对 package.json、env、storage、原生登录存储、request、service、route API、路由 store 和 Elegant 路由转换。原生解析器能够处理若依格式，FCP 返回该格式并限制教师可用菜单；不会要求重写若依的全部菜单表。

```text
https://github.com/m-xlsea/ruoyi-plus-soybean/tree/4e625111ce173e5c2f40dbfc47475f2849c58feb
```

Spring Boot 官方说明 included profiles 先于 active profiles 加入，多个活动 profile 的属性采用最后者优先。本工程据此使用 `prod,fcp` 默认顺序，并避免单独 `include:fcp` 被宿主环境参数覆盖。该修改已进行源代码级检查，真实 Spring 配置加载测试尚未执行。

```text
https://docs.spring.io/spring-boot/reference/features/profiles.html
https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html
```

浏览器验收依赖固定为 Playwright Python 1.57.0，版本存在性已经核对；本轮未安装该固定版本或运行真实浏览器测试。

```text
https://pypi.org/project/playwright/1.57.0/
```

`third-party` 包含两个前端所用源码片段的 MIT 版权声明，许可文本也已按上游 blob 核对。构建后的发布目录会复制三个上游仓库的 LICENSE，完整依赖许可证仍须随正式发布进行审查。FCP 自有代码保留原始 LICENSE。


## 0.5.0 本轮新增核对

固定后端提交的真正启动类为 `org.dromara.DromaraApplication`，路径 `ruoyi-admin/src/main/java/org/dromara/DromaraApplication.java`，Git blob SHA 为 `8ef33fe5f0cf8636f696d2510301a4012d809d8f`。本轮通过 GitHub 连接器取得全文，保存字节一致的测试夹具。0.4.2 所写 `RuoYiApplication` 已纠正；这一发现不等于已经完成原生编译。

本轮还读取 `SysMenuMapper.xml` 和原生 `ry_vue_5.X.sql`，核对用户/角色/菜单/租户/套餐结构后编写即时 FCP 授权查询。数据库没有实际执行这些新增 SQL，其集成结果仍待真实 MySQL 验收。

CI 文件中的 actions/checkout、setup-java、setup-node、upload-artifact 固定为从各自官方仓库读取的具体提交。工作流仅写入本地源码，未推送到远程、未执行。
