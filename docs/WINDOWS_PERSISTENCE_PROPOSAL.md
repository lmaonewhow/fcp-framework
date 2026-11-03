# Native Windows persistence — approved design

The user selected Windows including standalone file state, then explicitly
approved this SQLite JDBC/native DLL direction and conversion/recovery testing.
At approval time no SQLite runtime had been downloaded or loaded. The
application's existing MySQL state adapter remains unchanged.

Implementation and operating commands are now in `WINDOWS_FILE_STATE.md`.
Actual Windows DLL checks and recovery evidence are in `../evidence/repair3*/`.
This remains component-level evidence, not complete application acceptance.

## Recommended option: transactional local SQLite state

Proposed artifact: `org.xerial:sqlite-jdbc:3.54.1.0`, from the project's official
release and Maven Central, pinned by version and verified digest before use.
The artifact includes a Windows native DLL. This adds an external runtime JAR
and native-code trust to the standalone file mode; it is not a pure-JDK runtime
any more. The core can still compile using standard JDK JDBC interfaces.
Licenses are Apache-2.0/BSD-2-Clause; the native SQLite licensing and all notices
must be included in the dependency inventory before distribution.

Load/extraction would be restricted to an owner-controlled application-private
directory. No global installation, service, administrator volume flush,
registry, firewall or machine security changes are proposed. The DLL and JAR
must be verified, recorded in the SBOM, and checked before loading. Missing or
invalid dependencies fail closed; there is no in-memory fallback.

Keep the current state API, bounded capacity, exclusive single-writer behavior,
monotonic revisions and CAS. Persist each mutation as a transaction through the
Windows SQLite VFS with verified durable synchronous and rollback-journal
settings. Query effective settings, rather than assuming PRAGMA setters worked.
An uncertain commit poisons the current store until explicit reopen/verification.
Do not suppress the current directory-force exception and declare success.

## Existing files and recovery

The Windows on-disk format becomes SQLite instead of JSON. Existing JSON must
never be silently replaced. An explicit converter will validate its format and
checksum, generate a plan, take an owner-controlled backup, transactionally
preserve every scope/plugin/value/revision, verify parity and retain the
original. Unknown versions, corruption, changed plans and incomplete conversion
stop. This never rewrites MySQL migration history.

Acceptance must run on Windows with the actual pinned driver/DLL: existing
persistence/lifecycle/CAS/cross-JVM exclusion checks; process-kill-and-reopen at
transaction boundaries; recovery of interrupted work; corruption rejection;
read-only/full/capacity error paths; and conversion/backup/restore parity.
SQLite test page limits may simulate full errors without filling the host disk.
No reboot or physical power cut is proposed. Process-kill recovery must not be
reported as proof of a real hardware power-loss test; filesystem/device flush
assumptions remain explicit.

## Alternative: keep a pure-JDK runtime

A Windows-specific append/commit journal would require a new
file protocol and substantially more crash-consistency design and recovery
verification. It avoids the SQLite dependency but makes this project responsible
for transaction and recovery correctness. It cannot be implemented by ignoring
directory synchronization failures. This is not the recommended first option.

Decision recorded: the user approved the pinned SQLite JDBC/native DLL and
explicit file format conversion direction. Approval does not waive dependency
verification, private extraction or migration/recovery acceptance.

Sources: [official driver](https://github.com/xerial/sqlite-jdbc),
[pinned release](https://github.com/xerial/sqlite-jdbc/releases/tag/3.54.1.0),
[SQLite commit/recovery](https://www.sqlite.org/atomiccommit.html),
[synchronous settings](https://www.sqlite.org/pragma.html#pragma_synchronous).
