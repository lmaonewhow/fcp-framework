# Explicit plugin upgrade and rollback

This suite exercises the single-instance control plane, verified package repository,
real Python processes, durable FileStateJournal, and native adapter compilation.
FileStateJournal is a development/test store; the native host uses JdbcStateJournal CAS.
Neither journal claims a distributed transaction with an external schema migration.

## HTTP contract

Native endpoints require the authenticated native token and `fcp:plugin:install`.
The host additionally requires platform tenant `000000` and live lifecycle permission
for every affected plugin. Request bodies cannot select a different trusted identity.

- `POST /fcp/packages/staged`: octet-stream, at most 64 MiB; headers
  `X-Fcp-Digest` and `Idempotency-Key`. Returns
  `{pluginId,version,digest,state:"VERIFIED",replayed}`. Does not select or execute it.
- `GET /fcp/plugins/{id}/versions`: returns
  `{pluginId,revision,current:{state,version,digest},versions:[{version,digest,state:"VERIFIED"}]}`.
- `POST /fcp/plugins/{id}/version-plans`: body
  `{action:"UPGRADE"|"ROLLBACK",targetVersion,targetDigest,expectedRevision}`.
  Returns `{planId,digest,pluginId,action,fromVersion,fromDigest,targetVersion,targetDigest,
  expectedRevision,affectedTenants,dependentPlugins}`. All fields are flat.
- `POST /fcp/version-plans/{planId}/confirm`: body
  `{digest,idempotencyKey,confirmedDowntime:true}`. Returns
  `{operationId,pluginId,action,fromVersion,fromDigest,targetVersion,targetDigest,phase,outcome,revision}`.
- `GET /fcp/version-operations/{operationId}`: reads that same caller-bound receipt.

Only `COMPLETE` / `APPLIED` is success. Other phases are `INTENT`, `SWITCHED`,
`UNKNOWN`; other outcomes are `PENDING`, `OUTCOME_UNKNOWN`. Plans expire after five
minutes, but accepted receipts persist. If the first response is lost, an explicit
retry with the original plan ID, digest and idempotency key finds the durable receipt
before consulting ephemeral plans, including after restart. GET never resumes work.
Do not invent an operation ID or automatically replay a new request.

## Safety and recovery

All affected tenants and reverse dependencies must already be explicitly disabled
and drained. The plan binds selected and target versions/digests, global revision,
impacted tenants/dependencies, live authorization revision and schema fingerprint.
Confirmation rechecks them. It records intent before stopping an unused runner,
then persists the selected version and completion. It never enables, grants,
migrates, rolls back database data, or silently disables another tenant.

Nonempty storage requires a configured compatibility verifier and fingerprint.
Native fingerprints include the verified release, physical schema metadata and DDL
step state. Changed/incompatible schema prevents selection. An accepted operation
whose result is uncertain blocks related enable/version operations. Explicit
same-key recovery verifies actual selected state and schema again; it does not
replay a completed historical upgrade over a later rollback. If schema compatibility
cannot be restored, the plugin stays disabled; there is currently no version-intent
cancellation API. Schema deployment remains a separate reviewed operation.

## Verification

Run `bash tests/runtime-versions/run.sh` with JDK 21, Python and Node available.
The tests cover two compatible versions, real process output, retained verified
versions, two-tenant/dependent quiescence, live permission revocation, concurrent
CAS, rollback, schema races, durable receipts and actual child-JVM termination at
INTENT/SWITCHED/COMPLETE followed by explicit recovery. The schema race adapter is
an explicitly labelled test double; no database is rolled back by these tests.

`compile-native.sh` uses actual pinned backend classes and resolved Maven jars;
set `FCP_NATIVE_BACKEND` and `FCP_MAVEN_REPOSITORY`. Compilation is not HTTP evidence.
`tests/runtime-native/native_runtime_acceptance.py` contains real encrypted native
login, staged upgrade/rollback, incompatible-target rejection, concurrent CAS,
unchanged backend PID, real plugin process output and explicit regrant/enable checks.
It runs only against the disposable native CI environment. Local native HTTP/browser
version acceptance is NOT_RUN; consult the CI report for its actual outcome.
