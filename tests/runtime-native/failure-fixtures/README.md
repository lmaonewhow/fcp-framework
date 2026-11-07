# Failure-boundary fixtures and evidence boundaries

`../failure_boundaries.py` is an independent helper. It does not modify the ordinary runtime fixture, create a substitute host, generate login tokens, or write plugin business records with SQL.

## Integration

After `RuntimeFixture(database, sql, private_dir, fixture)` has inserted the CI echo permission, and before the native JVM starts, construct:

```python
from failure_boundaries import FailureBoundaryFixture, run_mysql_failure_boundaries
failure_fixture = FailureBoundaryFixture(database, sql, private_dir, fixture)
```

After the ordinary runtime acceptance finishes (its `ci.python` and `ci.node` packages may already be uninstalled), call:

```python
result = failure_fixture.verify(
    'http://127.0.0.1:18787', report['build']['buildId'],
    lambda: process.pid if process and process.poll() is None else None,
)
report['runtimeFailureBoundaries'] = {
    'status': result['status'], 'totalChecks': result['totalChecks'],
}
```

The helper installs its own `ci.boundary` package. Its only SQL seed creates explicitly synthetic tenant/package/department/user/role/menu authorization records in the guarded disposable database. It uses the native administrator to plan and confirm plugin grants over HTTP. Two additional accounts log in through the real pinned Vue form, native captcha and encrypted password request. One uses the explicitly selected second tenant. Browser states and tokens stay in memory.

The concurrent confirmation check uses two authenticated administrators, two plans made from the same prior state, and overlapping native HTTP requests. It requires exactly one completed confirmation and one `VERSION_CONFLICT`. This is a **single JVM** test, not two independently deployed grant administrators or an HA/REPEATABLE READ snapshot-reproduction test.

The two-tenant checks require the server-reported tenant/user identity and a common real Python child PID. They exercise forged identity envelope fields and headers, and revoke the second tenant while confirming the first still works. This covers shared-process identity and grant isolation. The echo package has no business storage, so this is not evidence for full data/configuration/cache isolation or cross-tenant export handles.

For the separate real MySQL test, reuse the existing storage test's Connector/J path and private environment after V005 is installed in a newly created `fcp_ci_<12 hex>` schema:

```python
mysql_result = run_mysql_failure_boundaries(driver, storage_env)
report['runtimeMysqlFailureBoundaries'] = {
    'status': mysql_result['status'], 'totalChecks': mysql_result['totalChecks'],
}
```

`storage_env` must contain `FCP_STORAGE_TEST_URL`, `FCP_STORAGE_TEST_USER`, `FCP_STORAGE_TEST_PASSWORD`, `FCP_STORAGE_TEST_DEPLOY_USER`, and `FCP_STORAGE_TEST_DEPLOY_PASSWORD`. Runtime/deployment identities must differ. Use DML-only runtime grants and the separate CREATE/ALTER/INDEX deployment grants already used by `MysqlStorageTest`. The Java test creates all business data through `JdbcPluginStorage` and all schema through reviewed production migration APIs. It uses unique plugin IDs, performs only read-only direct SQL assertions, and does not clean up or alter unrelated data.

The runner compiles its entrypoint and transitive production sources with `javac --sourcepath`, then runs against the actual driver. No result sets, update counts or database answers are mocked. The driver-fault decorator **first executes a real successful `Connection.commit()` or physical DDL**, then throws one `SQLException`. The test therefore verifies committed state/receipts and reconciliation after lost driver acknowledgement. It does not disconnect TCP, crash MySQL, interrupt the host JVM or simulate power loss. Authorization in this Java test is explicitly controlled `HostCapabilities`, not native RuoYi or `JdbcPluginPolicy`.

Checks cover logical and physical storage commit uncertainty, exact same-key receipt recovery, changed-content conflicts, one audit per real write, actual concurrent CAS winner/loser transactions, and third-step committed DDL interruption followed by exact-plan recovery. Incomplete storage must fail the production compatibility gate. That gate assertion alone is not native lifecycle READY-state evidence.

The A10 storage-layer matrix runs logical and physical writes as both USER and TASK. A JDBC decorator pauses only after the real transaction has executed the production audit INSERT. Read-only queries on that same real connection must observe the staged business record, idempotency receipt and audit. Independent connections must observe none. The test then revokes the exact tenant/kind/principal in a controlled thread-safe `HostCapabilities` policy and releases the latch. The final production authorization check must return `PERMISSION_DENIED`; the actual connection must roll back without committing, leaving no row, receipt or audit. Retry remains denied until explicit reauthorization, after which the same key must commit once and replay with one identity-specific audit. No business DML is issued by the fixture. `storageInFlightRevocation` changes to PASS only after all four actual database cases complete. This is **not** native RuoYi grant mutation, runtime DRAINING, callback revocation, UI or scheduler evidence; `nativeDrainingRevocation` remains NOT_RUN.

Upload `reports/runtime-failure-boundaries/` on failure as well as success. `http.json` and `mysql.json` are progressive reports, with source hashes and compiler/runtime logs for the MySQL runner. No PASS is emitted until execution completes. Missing native execution is not implied by local package/protocol tests.

## Local validation

```sh
python3 -m unittest discover -s tests/runtime-native -p test_failure_boundaries.py -v
javac --release 17 -encoding UTF-8 -sourcepath core/src/main/java \
  -d build/runtime-failure-boundaries tests/runtime-native/failure-fixtures/*.java
```

Six fixture tests, real Python fixture execution, ZIP verification with production `PackageInstaller`, and Java compilation passed locally on 2026-11-07. The temporary local MySQL 8.4 instance initialized but could not start because creating its Unix socket returned `Operation not permitted`. No local real MySQL fault case and no native HTTP case ran. See `../evidence/failure-boundary-status.json` and `../evidence/failure-boundary-local-validation.txt`; actual acceptance must come from the exact frozen CI commit.
