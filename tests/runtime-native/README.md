# Genuine native runtime-plugin acceptance

This suite is wired into `tools/ci_native.py --ingestion` after the existing native ingestion acceptance completes. It uses that runner's fresh `fcp_ci_*` MySQL database, actual RuoYi JVM, dedicated Redis namespace and genuine encrypted browser login/captcha flow. It never constructs a token, disables captcha, substitutes DevHost, or treats compilation as native acceptance. The backend PID baseline starts after ingestion's own restart checks.

The fixture creates CI-only permission menus and two CI-only roles in that newly provisioned database. It assigns them to the already-created synthetic acceptance users. These grants are not production migrations and are never run outside the guarded CI fixture. Plugin grants themselves are then planned and explicitly confirmed through the real native HTTP endpoints, not inserted by the test seed.

The acceptance sequence installs deterministic verified Python and Node ZIPs while the JVM stays running, verifies upload idempotency, rejects a required capability before platform approval, confirms package-bound grants and the dependency enable closure, invokes both real interpreter processes, checks their `/proc` parent is the native JVM, exercises Node-to-Python callbacks, and checks native catalog publication. Both built upstream Vue shells are opened in Playwright with their genuine native users. The script invokes a real capability through each rendered page, checks the server-derived user identity, records screenshots, verifies the viewer lacks management controls, and tests revoked deep links. It revokes a platform grant, rejects an old entry, explicitly disables the dependency closure, stages compatible Python 1.1.0 and undeployed-schema 1.2.0 without changing the active package, and races two real HTTP confirmations of the same upgrade revision. Exactly one must apply and the other must reject stale CAS. It verifies digest-bound operation receipts and unchanged grant rows, explicitly grants and starts the upgraded subprocess, rejects the undeployed schema target, then explicitly rolls back and invokes the original 1.0.0 subprocess. Each switch keeps tenants disabled until a separate enable, preserves grant rows, and retains the JVM PID. Finally it uninstalls the disabled packages. Existing plugin-record row counts are preserved; the echo packages themselves own no storage, so this is not full business-data recovery acceptance.

Artifacts are written to `reports/runtime-native/acceptance.json` and PNG screenshots. The native workflow uploads that directory even on failure. Errors are scrubbed using the existing login fixture's secret list. Tokens and browser storage states remain in memory only. No browser response mocking or route interception is used.

The CI runner separately provisions a **second fresh database** for `MysqlGrantBindingTest`, applies only V005 through the CI deployment identity, and uses a new DML-only account for the test. This verifies package-digest binding, upgrade revocation, task-grant intersections/expiry and tenant/scope checks without touching the native application's `plugins` journal.

## Local fixture checks, not native acceptance

```sh
python3 -m unittest discover -s tests/runtime-native -p 'test_*.py' -v
python3 tests/runtime-native/native_runtime_acceptance.py --package-output /tmp/fcp-native-packages
javac --release 17 -d /tmp/fcp-native-package-test $(find core/src/main/java -name '*.java') tests/runtime-native/NativeFixturePackageTest.java
java -cp /tmp/fcp-native-package-test NativeFixturePackageTest /tmp/fcp-native-packages
```

Running the Python module without the CI fixture returns `NOT_RUN` and exit code 2. Local validation evidence is in `evidence/local-fixture-validation.log`; `evidence/native-status.json` explicitly records that native HTTP/browser acceptance has not been executed in this workspace. A37–A41, native production readiness, migration/upgrade/rollback, long-duration stability and complete crash matrices are not marked fully accepted by these local checks.

The version fixtures are generated with the same package-output command as the baseline inputs: `ci.python-1.1.0.zip` and `ci.python-1.2.0.zip`. Local version-fixture validation is recorded separately in `evidence/version-fixture-validation.log` and does not establish that the added native HTTP scenarios passed. Full native version acceptance remains NOT_RUN until the isolated native workflow actually executes it.
