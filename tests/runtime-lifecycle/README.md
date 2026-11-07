# Runtime lifecycle tests

Run from the repository root with JDK 17+ (Java 17 compilation target), Python 3 and Node 22 on PATH:

```sh
bash tests/runtime-lifecycle/run.sh
```

`evidence/real-runtime.log` is actual captured execution, not an acceptance target. The run records toolchain versions, baseline commit and compiled artifact hashes. `evidence/sha256.txt` binds the changed sources. This remains uncommitted implementation evidence, not a production release claim.

## Actual integration coverage

`RealRuntimeTest` creates actual ZIP archives, verifies installation through PackageInstaller, dynamically registers Engine contracts/catalogs without restarting the Java host, and launches real Python and Node processes through the existing ProcessProvider. It verifies handshake/ready/health/invoke, server-derived tenant/principal context, environment whitelist, dependency callbacks and revoked permissions, catalog generation fencing, shared PID across tenants, real in-flight drain, T1 disable while T2 continues, durable invocation-result replay, crash UNKNOWN outcomes that cannot be automatically replayed, restart reconciliation, shutdown and process-quota reclamation.

`HostPortsRuntimeTest` uses an actual Python callback over stdio and Engine routing, with a **simulated host storage handler**. It checks server-bound source-plugin identity, class scopes, live denial, cross-tenant rejection, refusal to hide host writes inside read calls, journaled host writes and restart budgets persisting across runtime reconstruction. It is not MySQL storage evidence.

`ProcessRuntimeExtensionsTest` verifies actual OS process identity (PID plus start instant), managed keepAlive, one queue+I/O timeout budget, refusal to restart without a new handshake, and cleanup. The script also executes unchanged ProcessBoundsRegression (13 checks), ProcessTerminationRegression (2 checks), ProcessTreeRegression (7 checks, slow tree-enumeration is explicitly a test double) and CatalogPublicationTest.

`EnablementPolicyTest` explicitly supplies an approval-policy fixture and checks fail-closed defaults, authorization fingerprints, grant revocation at confirmation, prepare/publication races, recovery rechecks and withdrawal of existing admissions. RealRuntimeTest additionally revokes source-plugin required-capability permission while leaving the target callable, and verifies the next real callback is denied without a restart.

`LifecycleTest` uses an explicitly simulated RuntimePort to exercise real atomic file journals, an exclusive file lock, concurrent Java CAS races, authorization-revision plan binding, explicit dependency/cascade closure, WAIT/CANCEL decisions, close/reopen recovery, failed publication and unsafe journal rejection. Its simulated runner is not presented as native acceptance.

## Integration API and protocol

EngineRuntimePort owns a private existing Engine with a host guard, not a second execution engine. Its constructor takes HostCapabilities, VerifiedPackageRepository, absolute approved python/node executable paths, a StateJournal and restart budget. A Path overload uses FileStateJournal only for development. Production hosts must supply their JDBC CAS implementation for **both** coordinator state and runtime invocation outcomes. These journals require distinct storage identities. Call results, unknown intent and a 60-second restart budget (250ms minimum retry spacing) persist through the journal.

VerifiedPackageRepository retains approved archives and delegates byte-level revalidation to PackageInstaller on resolve/verification. A public InstalledPackage record by itself is never trusted. Package declarations do not grant capabilities. LifecycleCoordinator requires a startup-injected read-only EnablementPolicy for nonempty `requires`; its fingerprint is bound into plans and rechecked at confirm, before each runner preparation, before publication and on recovery. The default denies undelegated requirements. A private invocation source stack also checks the source plugin's live required-capability grant before the target plugin's live invocation permission. Package paths and launch arguments are mapped only to host-approved interpreters; no shell is used. Trusted packages still execute with the host OS identity; this is not a malicious-code sandbox.

The child uses existing JSON-RPC 2.0 newline stdio. `handshake` parameters/result echo protocol=`fcp.runtime/1`, pluginId, version and a fresh nonce. `ready`, `health`, `drain`, `cancel`, `shutdown` return `{ "ok": true }`. `invoke` receives capability, input and server-created context. `fcp.call` callbacks carry only declared dependency, operation=`invoke` and input. Lifecycle methods cannot issue callbacks. Retained Process objects and OS start identities are used; production cleanup never looks up a supplied PID for killing.

The public invocation API accepts TrustedContext, pluginId, capabilityId, input, idempotencyKey and optional expected catalog generation. Root and dependency calls recheck live host authorization. The canonical Grant operation for plugin and host capability invocation is `invoke`; manifest `permission` identifies the required user permission and is not an operation name. The native host can normalize `host.storage.*` to its internal storage grants and recheck in the storage adapter. Context is carried through a private thread-local during the synchronous Engine invocation and cannot be supplied through Engine JSON attributes. Each child receives server-derived identity separately from untrusted input. Root timeout bounds propagate to child callback transport. Side effects require a durable key bound to caller kind/id, tenant, plugin, capability and canonical request fingerprint. UNKNOWN never triggers blind replay.

`enabledVersion(context, plugin)` provides only internal task-execution metadata after deadline, tenant membership, runner identity and health checks; it does not grant business access or require lifecycle/status.

`snapshot(context)` returns one synchronized generation/enabled-manifest projection. Installation alone does not make UI visible. `registerHostCapability(contract, handler)` creates a server-generated per-source-plugin binding. Handler source plugin/context never come from JSON. `host.storage.*` validates and authorizes the requested class or current-tenant data scope before the approved handler; the storage implementation must perform its own semantic validation and authorization too.

Drain is nonblocking at the coordinator. Admission closes first; outstanding calls are counted. After the 30-second window, reconciliation reports DRAIN_TIMEOUT_DECISION_REQUIRED. WAIT extends the window. CANCEL records intent and sends cooperative cancellation when the serialized channel becomes available; it does not claim to interrupt arbitrary running code, kill another tenant's shared process, or roll back committed external effects. Cancellation/shutdown are bounded. A host scheduler must reconcile timeouts and runner crashes. Old Engine snapshot results can be fenced as STALE_COMPOSITION; side-effect uncertainty is journaled instead of reported as rollback.

## Remaining validation and limitations

A real native RuoYi HTTP/UI/MySQL acceptance run, independent process-kill fault matrix across all six publication phases, production recovery authority/scheduling, hostile-process isolation, multi-node fencing, long-duration stability and schema migration/upgrade/rollback acceptance are not covered here. Nonempty storage schema declarations fail with STORAGE_ADAPTER_REQUIRED by default. A host can inject a startup-only read-only StorageCompatibilityVerifier; approved schema deployment/migrations remain a separate deployment port. Plugins using approved JSON record host capabilities can leave storage declarations empty. Uninstall never deletes business data. A04–A17 are not claimed wholly complete.
