# Real subprocess crash and recovery matrix

Run from the repository root with a full JDK 17+ (including `jdk.jdi`), Python 3 and Node on PATH:

```sh
bash tests/runtime-crash/run.sh
# Optional: choose a new, empty evidence destination.
bash tests/runtime-crash/run.sh reports/runtime-crash/my-run
```

This is a **real process-crash test**, not exception-based fault simulation. It runs the production `PackageInstaller`, `VerifiedPackageRepository`, `LifecycleCoordinator`, `FileStateJournal` or `JdbcStateJournal`, `EngineRuntimePort`, `ProcessProvider` and Engine. Each test starts an independent JVM and terminates it abruptly. Five boundaries use `Runtime.halt(86)` in test-only decorators; the private installer staging boundary uses JDK JDI's abrupt `VirtualMachine.exit(86)`. All 12 crash JVMs must actually exit with code 86 and skip their registered shutdown hook. The normal recovery JVMs must execute that hook. No production Java source is changed or rewritten for injection, no HTTP fault endpoint is added, and no production JVM is attached to a debugger.

## Six boundaries

| Point | Exact interruption | Durable state asserted before recovery |
| --- | --- | --- |
| STAGED | JDI breakpoint in the real installer after copying its staging archive, immediately before digest verification | Full staged archive bytes match the source; no published package directory and no coordinator registration |
| VERIFIED | After the real repository installation returns, before coordinator registration | Verified immutable package and inventory exist; no enabled intent |
| INTENT | Immediately after the coordinator's real journal CAS returns with `phase=INTENT` | One operation, enabled desired state, no applied receipt |
| READY | After the real runner handshake/ready/health succeeds, before the coordinator persists READY | Actual Python/Node PID and OS start time; phase PREPARING; no published catalog |
| PUBLISHED | After the real Engine atomically publishes its tenant catalog, before returning to the coordinator | Catalog observed with this plugin and generation; phase PREPARING; no applied receipt |
| RECEIPT | Immediately after a real journal CAS commits COMPLETE/APPLIED | Published catalog and durable operation receipt both observed |

The default file mode exercises `INTENT` through **FileStateJournal, not JDBC/MySQL**, and cannot prove the DB commit point. The explicitly configured MySQL mode uses the production `JdbcStateJournal` for both control intent/receipt and runner restart/invocation state. Its INTENT/RECEIPT crash occurs after the actual JDBC commit has returned. The installer breakpoint resolves a unique production source line and requires `javac -g` line metadata. If that code changes, the fixture fails rather than silently injecting at a different location.

## Recovery assertions

Each of six points runs once with an actual Python plugin and once with an actual Node plugin. Each of the 12 cases then starts three separate recovery JVMs:

1. Revoked recovery uses an independent byte-for-byte copy of the post-crash files. In MySQL mode, it also copies only the original test-owned journal rows into fresh random journal IDs with the JSON and storage revision unchanged. The live host authorization fixture denies enable/invoke. No runner starts and no route becomes callable. This does not mutate the files used for ordinary recovery.
2. Authorized recovery starts directly from the original, untouched post-crash state. Historical READY is never admitted at startup. Registered intent converges to COMPLETE/APPLIED only after a newly handshaken, healthy runner is published. An unregistered staged/verified package remains absent and uncallable until an explicit authorized registration/enable request is issued. The same install key and package digest are replayed safely.
3. A second fresh JVM reconstructs the completed operation. It again starts fail-closed and performs real reconciliation before calls succeed. Repeated reconciliation in that JVM keeps its catalog generation stable, and the original confirmation key returns the same operation receipt without adding an operation.

Checks also cover actual Engine invocation, server-created tenant/principal context despite a forged tenant in business input, no route for another tenant, fresh PID/start-time identity after a prior runner existed, runtime process-counter cleanup, and preservation of an independent synthetic data sentinel. The sentinel is **not a database or proof of business-transaction recovery**. The Python/Node protocol fixtures are shared with `tests/runtime-lifecycle/plugins/`; they exit on stdio EOF after their parent dies. The suite does not claim arbitrary uncooperative orphan-process reclamation or PID-reuse coverage.

Abandoned installer staging is retained, not guessed safe for deletion. The test explicitly verifies its continued presence after recovery. An operational retention/garbage-collection policy is separate work; no business data is deleted by the test.

## Evidence and boundaries

Each run creates a new directory under `reports/runtime-crash/`, recording the exact source baseline and dirty worktree, JDK/Python/Node/OS versions, source and compiled-class SHA-256 manifests, the real command/exit code, original crash state and markers, per-process logs and JSON results, and `acceptance.json`. A post-run checksum verifies that tested sources did not change during the run. Compiled files use a disposable build directory; the temporary fixture package repositories and journals remain in the run directory for inspection. The default timestamp/PID output avoids overwriting prior failures. Reusing an existing case directory is an error.

`evidence/` contains selected unedited logs, state snapshots, results and hashes from the documented local run, plus the initial harness failures. It does not copy plugin installations, lock files or private configuration into Git. The full original run directories remain under `reports/runtime-crash/`. The initial failures were test-harness mistakes: comparing JSON numeric values by Java wrapper class, then omitting the actual safe `SCOPE_MISSING` denial for an unknown tenant. They were not production crash-recovery defects; no production fix was made to turn these failures into passes.

This matrix is Linux single-instance evidence for the A14 recovery mechanics; the journal backend is explicit in each result. Native RuoYi authentication and HTTP, unknown JDBC commit outcomes, MySQL service crashes, OS/power-loss durability, migration/DDL interruption, business writes with lost responses, malicious plugin isolation, arbitrary orphan reclamation, simulated PID reuse, distributed fencing and long-duration stability remain **NOT_RUN here**. Do not combine a file-backed result with unrelated MySQL tests to claim that the six-point JDBC matrix passed.

## Optional dedicated MySQL CI mode

The same script selects real MySQL only when all of these are explicitly set by the disposable GitHub Actions fixture:

- `GITHUB_ACTIONS=true`.
- `FCP_CRASH_TEST_URL=jdbc:mysql://127.0.0.1:33306/fcp_ci_crash_<12-to-40-random-hex>?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=UTC`.
- `FCP_CRASH_TEST_USER` and `FCP_CRASH_TEST_PASSWORD`: a dedicated test identity, never production credentials.
- `FCP_CRASH_TEST_DRIVER_CLASSPATH`: an explicit path to the MySQL Connector/J JAR (or colon-separated existing JARs). The script records each JAR hash without printing credentials.

`tools/ci_native.py` must create a **new dedicated crash database**, apply V005 with its deployment identity, grant the test user only SELECT, INSERT and UPDATE on that database's `fcp_runtime_state` table, then run `bash tests/runtime-crash/run.sh reports/runtime-crash-mysql` after extracting the real driver and before starting the native host. The test itself executes no schema creation, DDL, DELETE, DROP or TRUNCATE. It refuses non-loopback URLs, databases lacking the `fcp_ci_crash_` random suffix, partial settings, runs outside the Actions guard, missing Connector/J, non-InnoDB journal tables and any pre-existing journal rows. Do not set the Actions guard merely to bypass it on another database or computer.

Each case creates random control/call IDs. Its revoked branch receives different IDs and only INSERT ... SELECT copies of those owned rows. Original rows are not overwritten for branching; no cleanup deletes data. Journal identity/revision snapshots are recorded in `crash-journals.json` and `final-journals.json`. The database stays disposable and separate from native lifecycle IDs such as `plugins`.

For CI success require `acceptance.json` to contain `result=PASS`, `journalBackend=MYSQL`, `mysqlJournalCrashRecovery=PASS`, `crashCases=12`, `failedCases=0`, and `databasePreflight.emptyAtStart=true`. The default file report instead has `journalBackend=FILE` and `mysqlJournalCrashRecovery=NOT_RUN`. Schema-DDL crashes and MySQL-server crashes are reported separately as NOT_RUN even after the JDBC lifecycle matrix passes. Pure `JournalConfigurationTest` checks configuration refusal without connecting to a database; its success is not MySQL evidence.

The local optional-mode implementation was compiled and the default file matrix rerun successfully. This cloud workspace is not the disposable Actions job and no explicit crash database credentials were supplied; **the new MySQL mode is NOT_RUN locally and awaits the actual CI result**.
