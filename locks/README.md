# 原生数据接入构建的依赖锁

`admin-package-lock.json` 保存原生验收提交 `680c19fb5f0fb0d16da6065be65b1553a77ac0cb` 实际使用的完整 npm 依赖解析，SHA-256 为 `ae581b28b46e756ef06d73f58807e997e76f877a993da174c1e2a2873c2dc8c5`。来源是 GitHub Actions 原生验收运行 `41597238830` 的 `native-ingestion-evidence`，已原样持久化至 Git。构建不再依赖早期运行产物的保留期限。对应管理端上游提交为 `d0d451967676707021b9857df529c395b27e90a7`。

执行 `python tools/check_ingestion_lock.py` 校验锁字节摘要和上游绑定。常规原生构建传入 `--admin-lock locks/admin-package-lock.json`，CI 使用同一文件。变更依赖或管理端上游时，应明确重新解析、审查、更新摘要并重新运行原生验收，禁止静默退回浮动解析。教师端沿用已锁定上游中的 pnpm 锁文件。

该锁使已测依赖解析可复现，供应链漏洞审计和包下载可用性仍需要独立验证。一次成功的演示验收不构成生产安全认证。仓库只保存依赖元数据，不保存登录凭证、数据库配置、私钥或浏览器会话。
