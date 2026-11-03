# Existing Redis connection for Windows local acceptance

The user directed use of the Redis already running, without WSL/Docker, service
restart, upgrade, server configuration changes or Memurai installation. Actual
Windows service/listener inspection and PING/INFO identify Redis 7.4.0/Cygwin at
127.0.0.1:6379. Its config is
`D:/Programs_stable/Redis-7.4.0/redis.conf`, with loopback bind and no requirepass
or explicit user directives. This is independent of the installed Scoop 7.4.1.

MySQL uses the existing Oracle 8.4.2 executable with a new private datadir and
loopback 23306, not the shared service on 3306. Initialization used a generated
temporary root password, then a fresh password, separate deployment/runtime
identities and runtime DML-only grants on the dedicated schema. TLS REQUIRED was
verified. No Windows service or firewall changes were made. After execution
transport interruption, inspect exact owned processes/ports/logs before resuming
the existing datadir. Never rerun initialization to recover an interrupted host.

## Explicit connection-only local-test option

Ordinary native builds still require a Redis password. The explicit
`--existing-loopback-redis-no-password` option admits an existing no-password
loopback endpoint for local functional tests. It requires an explicitly empty
password, explicit port and fresh `fcp_accept_<32 hex UUID>` namespace. Remote
destinations, ordinary/default prefixes, omitted passwords and placeholders are
rejected. BUILD-EVIDENCE records `localTestOnlyReasons`; the production gate
rejects that marker. Application login, captcha and authorization stay enabled.
No Redis server authentication or configuration is changed.

An actual Spring Binder/Redisson 3.50.0 test reproduced ERR AUTH: an empty String
was treated as a supplied password. The FCP-profile customizer now normalizes only
an empty default-user password to null/no AUTH. Nonempty passwords and explicit
ACL-user credentials remain unchanged. This does not grant access to a protected
server without valid credentials. The connection regression runs the actual
project libraries and source mapper, not a fake Redis implementation.

## Namespace audit and verification

Database 0 is shared; changing the database number alone is not isolation.
RedisConfig installs KeyPrefixHandler. TenantConfig uses TenantKeyPrefixHandler,
whose global and tenant branches still call the base mapper. Sa-Token's DAO,
captcha and normal caches use the same RedisUtils/Redisson client. Global token
keys still receive the unique application prefix. Internal map-cache indexes and
channels may wrap a mapped key in braces/internal prefixes, so raw checks must
match this UUID namespace anywhere, not assume every key starts with it.

No alternate RedisTemplate/Jedis writer or FLUSHALL/FLUSHDB call was found in the
application sources. Cache monitoring's direct connection reads INFO/dbSize;
that endpoint is not itself a namespace isolation test. Real verification uses
NX creation and TTL on a synthetic bucket plus a Lua-backed map-cache roundtrip
under the fresh namespace. No existing key is deleted. Later raw checks must
filter only this namespace, never read other projects' values, and hash/redact
session key names because they can contain tokens. Do not use MONITOR, global
flush/config/ACL commands, shared-service restarts or broad key deletion.

Deployment risks of the existing Redis version/authentication are recorded
separately. Successful local client/API/browser tests do not mark production
security ready or authorize changing the user's service.
