# Native runtime and management integration

The production adapter is opt-in on the pinned RuoYi-Vue-Plus 5.6.2 host. The separate `development-host` is never copied into the native JAR. Both adapters call the shared lifecycle/application/runtime code; native identity comes from Sa-Token and fresh database authorization, not the development identity file.

## Startup and identity

Apply the reviewed V005/V006 deployment migrations before enabling `fcp.runtime.enabled=true`. Configure an absolute `FCP_RUNTIME_ROOT`, explicit `FCP_TRUSTED_PUBLISHERS`, and absolute interpreter paths `FCP_RUNTIME_PYTHON` / `FCP_RUNTIME_NODE` for languages actually installed. Packages execute trusted publisher code as the host OS identity: this is not an OS sandbox. Publisher names are an administrator-controlled allowlist, not a cryptographic publisher-signature assertion.

The runtime database identity needs the applicable DML permissions and must not receive DDL permissions. Schema deployment uses a separately configured, explicitly enabled identity; see `tests/runtime-storage/README.md` for its configuration and limitations. Merely uploading a package never executes DDL or gives the plugin business permissions. A storage-bearing package can be staged and verified but cannot become installed/READY until its declared schema is deployed and the actual structure passes compatibility checks. Operators review the schema plan, explicitly apply it, then retry package installation. Destructive migration and raw SQL are not accepted.

V006 creates view/manage/install menu permissions but assigns no roles automatically. A host administrator must explicitly assign permissions through normal native administration. Native superuser UI wildcard is only a client compatibility signal: the server still derives actual runtime permissions from live role/menu/tenant-package state. Grant publication is limited to the platform tenant `000000`; target tenant selection is explicit. Plugin grants bind the immutable package digest and selected data scope. Upgrading package code requires reviewing grants for that new digest. Task grants independently bind the package digest: re-approving platform grants does not revive a task authorized for older code. Task permission must be separately and explicitly re-approved.

## Operations

The two native shells use the same runtime catalog protocol and management component through separate adapters. Managers can upload an archive, inspect installed packages, preview enable/disable dependency impact, explicitly confirm the affected set, inspect operations, choose a drain decision, and uninstall a disabled/drained package while retaining business data. Platform administrators also review and confirm grants. Installation does not automatically grant or enable anything.

Package version replacement requires disabling and draining every affected tenant first. Stage the selected archive, preview an explicit UPGRADE or ROLLBACK plan, and confirm its digest and downtime acknowledgement. Review digest-bound platform and TASK grants before re-enabling. Direct installation cannot replace an installed version. Rollback is rejected if declared/actual storage compatibility fails. See [the version API and recovery contract](../../tests/runtime-versions/README.md). This is an explicit plugin downtime window, not a zero-downtime rolling upgrade. The host process remains running.

All browser mutations require the explicit native Authorization header; cookie-only requests are rejected. Lifecycle confirmation receipts survive HTTP plan expiry and process restart. Retrying an existing confirmation checks actor, tenant, digest, affected plugins and fresh authority before returning the durable operation. Unknown write outcomes are not silently repeated. Uninstall and drain decisions without a replay receipt require inspecting current state before deciding a next action.

## Verification boundaries

`tools/verify_runtime_storage.py` checks Java declarations and contract boundaries only. `tests/runtime-storage/MysqlStorageTest.java` requires a dedicated real database; JDBC doubles or successful compilation do not satisfy it. The native CI provisions disposable databases and separate deployment/runtime users. The grant-binding test must use a separate database because it owns a synthetic lifecycle journal.

`tests/runtime-native/native_runtime_acceptance.py` uses real native encrypted login, a running RuoYi process, two language packages, explicit CI-only grant fixtures and both actual browser shells. Its evidence records the host PID before and after live operations. The CI fixture refuses non-CI databases and is not a production provisioning script. Local production builds/typechecks and component tests are separate evidence, not substitutes for these native checks. Final acceptance status must come from the frozen commit's run, not an earlier workspace build.
