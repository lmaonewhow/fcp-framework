# Windows database baseline decision

## User-corrected direction

The earlier choice below is withdrawn as a prerequisite. The user instructed us
to connect the Redis already running on this computer. Actual Windows service,
listener and INFO checks identify Redis 7.4.0/Cygwin on 127.0.0.1:6379, not the
separate installed Scoop 7.4.1 binary. No Redis replacement or Memurai installation
is needed. Follow `EXISTING_REDIS_LOCAL_ACCEPTANCE.md`; the remaining text is
historical investigation, not a pending decision or instruction to replace Redis.

The user now forbids starting/waking WSL, including indirect Docker/WSL paths.
No further operations against the earlier WSL-backed fixtures are authorized.
Their last recorded states are historical, not current verification.

Windows-only inspection found these existing tools, without starting a service:

| Component | Existing version/source | Impact |
|---|---|---|
| MySQL | Community Server 8.4.2, existing Scoop `mysql-lts`; vendor MSI `https://dev.mysql.com/get/Downloads/MySQL-8.4/mysql-8.4.2-winx64.msi` | MySQL 8 LTS, differs from earlier isolated 8.0.42; new private datadir and port required |
| Redis | 7.4.1 x64 MSYS2, existing Scoop package from `https://github.com/redis-windows/redis-windows/releases/download/7.4.1/Redis-7.4.1-Windows-x64-msys2.zip` | Community Windows port; differs from earlier Redis 7.2.8 and is explicitly development-only upstream |

For local acceptance, the existing binaries can be run as task-owned processes,
with a new private datadir/config, distinct loopback ports, authentication/TLS
and separate deployment/runtime identities. No Windows service registration,
global install, firewall change, default config, existing data or WSL is needed.
Results would explicitly name MySQL 8.4.2 and this Windows Redis 7.4.1 port;
they cannot prove the earlier Linux/Redis 7.2.8 baseline or production suitability.

The Redis project's [Windows guidance](https://redis.io/docs/latest/operate/oss_and_stack/install/archive/install-redis/install-redis-on-windows/)
names Memurai as its official Windows partner. The existing community port's
[upstream disclaimer](https://github.com/redis-windows/redis-windows) limits its
recommendation to local development. Reusing it for a synthetic loopback test is
a concrete no-install option, but must be accepted as that narrower baseline.

The Scoop manifest labels this Redis package BSD-3-Clause, which is not a valid
inventory for the whole artifact. The build repository's own
[license](https://github.com/redis-windows/redis-windows/blob/main/LICENSE) is
Apache-2.0, while Redis 7.4.1's
[core license](https://raw.githubusercontent.com/redis/redis/7.4.1/LICENSE.txt)
is RSALv2 or SSPLv1. Bundled MSYS2/runtime notices must also be retained.
Redis 7.4.1 predates the 7.4.6 fix for the authenticated Lua RCE
[CVE-2025-49844](https://github.com/redis/redis/security/advisories/GHSA-4789-qfc9-5f9q).
It cannot satisfy the production security gate. Disabling Lua to avoid this would
alter the real Redisson workload, so it is not an acceptance workaround.

The alternative is to evaluate a pinned vendor Memurai package and license first.
[Developer Edition](https://www.memurai.com/get-memurai) is intended for testing
and requires a restart after ten days; its current page claims Redis 8.2
compatibility. It is a different engine/distribution, not an exact Redis 7.2.8
replacement. Its exact installer version, hash, extraction/install requirements
and terms need review before a separate installation approval. No Memurai
artifact has been downloaded or installed.

The free development license is non-production; enterprise licenses are paid.
The current download page also lists ten connected hosts and half of available
system memory as Developer limits. Since 4.2 an actual valid license file is
required, with technical/runtime and telemetry terms encoded in that license.
Official setup documentation requires administrator privileges and defaults to
service registration, PATH modification and a firewall rule. Those defaults are
not approved. Any proposed setup must explicitly exclude those changes where
supported and be reviewed before execution. The exact package version/security
patch level and current EULA, including benchmark disclosure and telemetry terms,
remain to be obtained; an API compatibility claim is not proof of patch coverage.
Sources: [license rules](https://docs.memurai.com/en/config-license),
[terms](https://www.memurai.com/terms),
[setup options](https://docs.memurai.com/en/installation).

Recommendation: do not select installed Redis 7.4.1 as the final acceptance
baseline. The parent should ask whether to approve it only for isolated synthetic
function checks while the security gate remains BLOCKED, or first review a
specific patched Memurai runtime/license for subsequent approval. A patched
community Windows 7.4.11 build also exists, but remains development-only; upstream
Redis 7.4.12 exists while its corresponding Windows tag returned 404 at review
time. No newer community binary has been downloaded or approved implicitly.
Do not substitute a fake Redis, disable Lua or disable authentication.
