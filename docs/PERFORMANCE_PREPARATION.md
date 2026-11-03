# Performance repair: validation in progress, no performance acceptance yet

## 2026-11-02 validation after the original baseline completed

The original baseline reached COMPLETE: periodic 24-hour observation PASS, load latency FAIL. Its immutable JAR and evidence are retained. The candidate has now passed an old/new deterministic comparison: old admission and committed-acknowledgement cases exit 1, candidate cases exit 0; publication, close and preparation cases pass on both. The initial restricted javac attempt exited 3 (`Cannot close compiler resources`); that evidence was kept, and a task-owned temporary directory plus authorized native execution compiled both versions successfully.

The candidate component suite passed 242 checks with one POSIX-only skip. A separate actual MySQL run passed 20 checks against the existing dedicated Windows 23306 task schema: six real transaction commit boundaries, read admission, committed acknowledgement, globally serialized actions, publication/close, stale rejection, immediate native account revocation at the effect boundary, restored synthetic account status, and rollback after a real strict-mode record error. Adapter fixture scopes are explicit; this does not replace native HTTP session, tenant, or browser tests. Reports and commands are under the parent task's evidence/perf-candidate and private perf-supervised directories. Counts overlap and must not be summed into a single acceptance total.

`tools/supervise_acceptance.py` retains actual child PID, Windows creation FILETIME/UTC, start/end time and measured return code, with private logs/command specification. Actual component/JDBC exit codes were zero. Separate tests verified a real child exit 7, launch failure with no invented child exit code, and refusal to overwrite earlier records. It creates no scheduler or persistent service. A new native JAR, full native regression, equal-hardware load and independent final stability window are still pending.

## Original preparation record (before baseline completion)

Subsequent native candidate `bd330aef804159c6a710bbb0898a91e34cf53e36b183b9056d2522c1a791ef35` built both UIs and the genuine host, passed four genuine captcha logins, 19 API checks, 16 revocation checks, eight Python lifecycle checks, UUID save confirmation, 1000/1001 statistics, desktop/mobile recording and controlled restart/restore checks. Its native cross-shell logout regression found the pinned session DAO deleting Redis values without invalidating its five-second Caffeine entries: UI tabs logged out while the old token briefly remained accepted. Three actual Spring/Redis cache-deletion regressions failed on the unmodified DAO (three assertion failures, zero setup errors). The candidate therefore remains unaccepted and has not started a new load/24-hour window.

The follow-up patch pins the DAO preimage and invalidates only the deleted key in both deletion paths, including exceptional completion. JWT mode, server settings, expiration policy and dependencies remain unchanged. Real Spring/Redis regression coverage includes an explicit lost-ack fault after actual deletion; both raw old failure evidence and source are retained. A new JAR and complete native/latency/stability validation are still required after this patch.

Base: 8abc583, incorporating product source b1dcef2. This isolated worktree does not own or modify the running release JAR. The active acceptance remains SHA256 9154317b4b91c4460844d3aedcf170482618dcec2ea72fd214e777045840e498, original Java PID48224 and test PID24340.

The full 1802.156s load completed 83,015 requests with zero failures, read P95 1468ms and write P95 1781ms: latency targets failed. This draft is not evidence that either target is fixed.

## Actionable code issue

Engine.Context.atomic holds the same Engine monitor used by invocation admission, current-snapshot checks, describe(), publication and close. The action includes fresh authorization and real JDBC commit. Database I/O therefore blocks otherwise independent admissions and read completions. The implementation comment calls this a short fence, but actual commits can take hundreds of milliseconds on this host.

This draft introduces a separate global commitFence. Durable atomic actions remain serialized with each other and with the final publication swap and close. Snapshot/refcount metadata remains under the existing Engine monitor; current() still checks the captured snapshot before authorization and action. External authorization and persistence no longer run while holding the admission monitor. Provider preparation remains outside the commit fence. Commit acknowledgement and stale-capability behavior must remain unchanged.

No permission cache, skipped authorization boundary, weaker transaction/flush policy, index/schema change, parallel commit semantics, or timeout/target relaxation is proposed here. The lock order for publication/close is publication → commitFence → metadata; atomic actions use commitFence → metadata. Reentrant lifecycle operations from an arbitrary atomic supplier need review against existing behavior; this draft does not claim to make such callbacks safe.

## Validation deliberately deferred

EngineAdmissionFenceRegression uses an explicit memory/latch RecordStore fixture. It covers a blocked durable action with concurrent read/describe; globally serialized atomic actions; publication waiting for an active commit; close waiting for commit completion; a known committed acknowledgement completing while a later commit is blocked; stale commit rejection when publication wins; and slow provider preparation not blocking commits. It is wired into verify_components.py. These are concurrency/ordering tests, not MySQL or performance evidence.

No compilation, component suite, Maven build, native test, or new load has been run in this worktree, to avoid disturbing the current 24-hour RSS baseline. Only source inspection and git diff --check are performed. The draft must not be merged into acceptance results or deployed on the basis of static inspection.

After the current baseline reaches a recorded terminal state or an explicit decision authorizes stopping it, run the new regression against the old implementation to retain a failure and against this draft, plus existing authorization-at-commit, lifecycle/CAS, process cleanup and persistence regressions. Then build a distinct native JAR, repeat genuine login/role/tenant/class/revocation and composition tests, and run a fresh measured load. A changed JAR requires its own necessary 24-hour interval; do not concatenate this baseline with it.

The draft primarily removes avoidable read queuing. It also removes a source of post-commit write-response queuing: Engine.invoke finally needs the metadata monitor to release the pinned snapshot; another commit could previously acquire that monitor and hold it across I/O before the already committed request can finish. The regression isolates that ordering with latches. It does not guarantee write P95≤500ms: commits remain serialized and real durable I/O still costs time. Compare the same artifact on the existing HDD and an explicitly chosen isolated SSD location with equivalent durability, then assess any remaining SQL/network or transaction queue bottleneck.
