# Shared verified-package staging

`POST /fcp/packages/staged` belongs to the base plugin controller, not the version
module. `PluginApplication.stage` and `PluginVersionApplication.stage` delegate to
`PackageStaging`. It preserves the existing stage namespace (principal kind, ID,
tenant and idempotency key), 64 MiB bounded upload, package digest verification,
live install authorization before upload and after verified plugin identification,
and VERIFIED/replayed response. Native routing retains explicit matching token
header validation and `fcp:plugin:install`. Development routing uses its explicit
development identity, not simulated native authentication.

The helper calls the pre-existing verified repository install primitive, which
stores an archive but does not register it with LifecycleCoordinator. No version
plan API, new repository method, active version switch, grant or schema deployment
is required for staging. Thus storage-bearing packages can be verified first,
resolved by exact plugin/version/digest for a separate reviewed schema deployment,
and only then installed through the existing schema compatibility gate.

Run `bash tests/runtime-staging/run.sh` with JDK 21 and Python. The actual ZIP test
checks a schema-bearing manifest is available by exact digest while installation
remains ABSENT, lifecycle revision remains zero and no interpreter starts; install
without a schema adapter still fails. The declaration is parsed by the same storage
contract consumed by deployment planning. The actual HTTP test stages/replays a
package, rejects anonymous access and unpublished invocation, then runs the existing
install/enable/invoke/restart/disable test unchanged. SQL planning/deployment and
native authenticated HTTP remain NOT_RUN locally, not replaced by a database mock.

Backport requires only PackageStaging, PluginApplication, FcpPluginController and
the narrow DevHost route plus these tests. On branches already containing version
support, also delegate PluginVersionApplication.stage and remove the duplicate
VersionController endpoint. Full version lifecycle/schema/crash tests pass after
this refactor. Both controllers compile against the pinned actual native classes
and resolved dependencies. A 61bef99 source archive plus the minimal patch also
passes the staging suite, without any version module.
