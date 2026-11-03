# Native Windows file state

`FilePluginStateStore` uses SQLite transactions on Windows. Its public state API,
exclusive host lock, capacity and CAS revisions are unchanged. Native RuoYi uses
its existing MySQL adapter and does not load the SQLite dependency. Linux keeps
the existing atomic JSON implementation. Framework compilation still needs only
the JDK; standalone Windows file-state execution additionally needs the pinned
JDBC JAR and bundled native DLL approved by the user.

The exact dependency, source, license, SHA256 and Windows x64 DLL digest are in
`core/dependencies/windows-file-state.lock.json`. The runtime checks the JAR
digest before initializing the driver, rejects library overrides, requires a
private owner/SYSTEM-only native extraction directory, and checks driver version,
effective `journal_mode=DELETE`, `synchronous=EXTRA` and database integrity.
Initialization, unknown formats, corruption and uncertain writes fail closed.

Run from the full package root in PowerShell, in a new private application path:

```powershell
python tools/build_libraries.py
python tools/windows_file_state.py prepare config/private/windows-file-state-runtime
python tools/windows_file_state.py verify config/private/windows-file-state-runtime
$runtime = (Resolve-Path config/private/windows-file-state-runtime).Path
java "-Dorg.sqlite.tmpdir=$runtime/native" -cp "dist/fcp-core-0.5.0-dev.1.jar;$runtime/sqlite-jdbc-3.54.1.0.jar" core/examples/StateAndProcessCheck.java core/examples
python tools/verify_components.py --windows-file-runtime config/private/windows-file-state-runtime
```

Preparation downloads only the locked official Maven Central artifact, or accepts
`--source-jar PATH` for the identical previously downloaded file. It changes ACLs
only on the newly created application runtime directory, retains dependency
notices, and never installs a service or edits machine security configuration.
Preserve the runtime lock and notices in any standalone Windows distribution.

## Explicit legacy conversion

Stop the old host first and back up its whole directory. Keep all state files and
backups owner-controlled. Normal startup rejects an unconverted JSON file. Obtain
a checksum plan, review the target path, then apply that exact checksum:

```powershell
$classpath = "dist/fcp-core-0.5.0-dev.1.jar;$runtime/sqlite-jdbc-3.54.1.0.jar"
java "-Dorg.sqlite.tmpdir=$runtime/native" -cp $classpath fcp.state.FileStateMigration plan PATH_TO_STATE_DIRECTORY 128
java "-Dorg.sqlite.tmpdir=$runtime/native" -cp $classpath fcp.state.FileStateMigration apply PATH_TO_STATE_DIRECTORY 128 SHA256_FROM_PLAN
```

The converter rejects unknown fields/formats, invalid keys/flags/revisions,
duplicate keys, capacity overflow, changed source and mismatched backups. It
holds the same exclusive host lock, retains the original JSON and a digest-named
backup, imports every row/revision transactionally, and verifies parity. A
recorded source digest guards later startup. Repeated conversion cannot reset
subsequent SQLite mutations. An interrupted uncommitted conversion requires
explicit retry with the original reviewed digest; no default state is served.

Once converted, do not open that directory with the old JSON-only version. Its
retained JSON is the conversion checkpoint, not the current SQLite state.

## Backup, restore and recovery boundaries

For a current SQLite backup, stop the host cleanly and confirm it exited. Copy the
closed `plugin-state.sqlite`, retained JSON/backup if present, and a SHA256
inventory to a separate private backup directory. Do not copy a live database or
discard a hot journal after a crash. Reopen the original once for recovery and
integrity verification before making a closed backup. Preserve the dependency
lock. Restore only into a new private directory, verify file digests, then open
with the pinned runtime and verify expected values/revisions and integrity.
Keep the prior directory intact until the restored result is accepted.

The regression runs actual Windows JDBC/DLL transactions, kills children before
and after commit, verifies rollback/reopen and cross-process exclusion, uses
`query_only` for a read-only SQL error and `max_page_count` for actual SQLITE_FULL,
and checks conversion plus JSON/closed-SQLite restoration. These are component
checks. They do not prove a physical power cut, filesystem/device flush behavior,
host disk exhaustion, Windows ACL denial, or complete native application E2E.
After a forced process kill, Windows may briefly retain the kernel file lock;
startup continues to reject access until the lock is actually released.
