# Local repair stage 1

The user authorized product repairs after the initial local review. Work remains
on `test/local-native-review1`; no upstream versions, authentication settings,
database history, TLS settings or production request budgets were changed.

The native assembler now adds `fcp` and `fcp_school` to both Soybean locale route
maps, checks the pinned locale blobs before adaptation, and rejects duplicate
locale adaptation. The real installed teacher project passes its unchanged
`pnpm run typecheck` after the same adaptation. Its earlier two TS2739 diagnostics
remain in the initial evidence.

Local-source fetch creates a unique disposable branch instead of reusing a
fixed branch already copied from an assembled checkout. A real Git regression
clones a repository with that existing branch and an uncommitted source change,
then verifies the committed pin was cloned and the source worktree was preserved.

`.gitattributes` pins text checkouts to LF so immutable SQL and upstream fixture
checksums survive Windows `core.autocrlf=true`. Existing source files were
restored to their exact committed bytes only after confirming a clean worktree.
No database checksums were edited. The earlier CRLF-initialized database remains
preserved for investigation; it must not be silently adopted as a matching LF
installation.

All 66 tool tests completed: 65 passed and the POSIX-only permission test was
skipped on Windows. The source-only reference-server check now scans all shipped
Java roots and excludes generated worktrees and dependency caches. This preserves
the original check while reducing the same complete tool run from 156.424 seconds
to 2.184 seconds in this workspace. Logs are in `../evidence/repair1/`.

## Stage 1 platform decision (historical)

The file state implementation's directory force remains unchanged and fails on
this Windows JDK. Swallowing that exception would weaken its stated persistence
requirements. The original supported deployment scope is Linux single-node;
WSL2/Linux acceptance preserves that scope and supports testing actual POSIX
termination behavior. A fully native Windows file-state implementation needs a
separate platform-specific persistence design and recovery verification.

Microsoft documents write access requirements and write-through options for
[FlushFileBuffers](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-flushfilebuffers)
and specific flushing behavior for
[MoveFileExW](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-movefileexw).
Those APIs are not treated here as proof that simply omitting directory force
preserves the Java implementation's current guarantees.

The management frontend's 69 pre-existing type errors, original DualFlow cold
process deadline failure, pagination/statistics boundaries and complete same-JAR
browser/API/load/stability acceptance still need repair or verification. No
complete acceptance result is claimed by this stage.

## Local repair stage 2

The pinned plus-ui compatibility bundle fixes the original 69 diagnostics and
three diagnostics exposed while refining those types. It pins the exact upstream
commit, all 25 source blobs and the patch checksum. The assembler rejects drift
before applying it. A fresh pinned clone reproduced all 25 files byte for byte.
The real management project now passes full `vue-tsc --noEmit` (61.203 seconds)
and `npm run build:prod` (274.390 seconds), without a version or lockfile change.

Two existing interaction defects were also corrected: the editor calls the
current Quill instance, and a single upload descriptor follows the same item
normalization as a descriptor array. Seven real-component/router unit checks
pass, including real Quill toolbar dispatch, descriptor/ID paths and preserved
router serialization. API and DOM selection doubles remain explicit; these are
not native browser acceptance. All 67 tool tests complete: 66 pass, one POSIX-only
test skips on Windows. Raw results are in `../evidence/repair2/`.

The user selected pure Windows and then explicitly approved the proposed pinned
SQLite JDBC runtime and bundled Windows DLL for file state, with explicit legacy
conversion and recovery verification. The design is recorded separately in
`WINDOWS_PERSISTENCE_PROPOSAL.md`. At this stage it is approved but not yet
implemented or downloaded. Complete same-JAR acceptance remains outstanding.

## Local repair stage 3

The approved driver was downloaded from official Maven Central. Its SHA256
matches Central and is pinned in the partial dependency inventory and runtime
validation. Its detached signature is retained but no cryptographic signature
verification is claimed. Actual Windows x64 DLL execution passes the 13-check
framework example. File-state recovery, migration, capacity and failure checks
use the actual driver and files; the detailed commands and limits are in
`WINDOWS_FILE_STATE.md`. JSON backups and closed-SQLite backups restore into
separate directories with exact value/revision checks. Interrupted import needs
explicit checked retry. Authentication and the native MySQL adapter are unchanged.

The first forced-kill test attempted immediate reopen before Windows released its
kernel file lock. The test now measures a bounded wait for the actual OS lock,
without relaxing production locking. Initial measurements ranged 11–41 ms.
Another lifecycle run hit the original request deadline; the unchanged direct
provider probe measured 103 ms first call and 1/0 ms hot calls, and the unchanged
28 lifecycle assertions then passed. No production timeout was increased. The
failure evidence is retained; occasional host scheduling/startup delays remain a
concern for subsequent native load/stability validation.

A later actual Windows trace isolated a separate idle-cleanup defect: process-tree
enumeration took 4324 ms while root termination/waits took about 4 ms. Tree scans
now share the existing bounded pipe executor, wait at most 100 ms in the caller,
and terminate late captured descendants asynchronously. Root termination and
quota release conditions remain unchanged. A six-assertion regression uses real
children with an explicit 1500 ms enumeration double; late descendants are still
terminated. The unchanged real idle-reclamation assertion now passes. The full
component run passes 225 checks with one explicitly skipped POSIX permission
test, including 29 Windows SQLite checks. These counts include component/unit
groups with different scopes; they are not 225 native application E2E cases.

The same investigation found that Windows exposes `Path` and `windir` keys which
the case-sensitive environment-map allowlist discarded. The allowlist now
compares names case-insensitively on Windows while preserving exact POSIX names.
Two actual-child environment assertions pass. The final component run passes
227 checks with one skip in 61.437 seconds. The final preserved regression run
passes all 105 checks, including all 25 DualFlow cases under the unchanged
two-second timeout. Earlier 24/1 failures and 3.0–3.9-second first-start traces
remain preserved; one successful full run does not replace long-term validation.

From the user's later instruction onward, no WSL invocation, wakeup, restart or
indirect Docker/WSL operation is allowed. Native application acceptance is pending
a Windows-native database baseline. The old isolated WSL databases and credentials
remain preserved; their install failure/state is not silently reset.
