# Independent OS termination proof for clean-session accounting

Baseline b33bad9 cleared the clean-session charge when ProcessProvider.identity()
returned null after close. ProcessProvider clears its Java process reference before
termination attempts, so this was not proof of OS exit.

EngineRuntimePort.stop now captures a real ProcessHandle before shutdown and verifies
its PID/startInstant against the runner's owned identity. Only acknowledged shutdown
followed by !isAlive() on that retained handle permits the clean-session mark.
Missing identity, unavailable metadata, failed OS inspection or a still-live owned
process throws RUNNER_TERMINATION_UNKNOWN and keeps the persistent restart charge.
No signal is sent using stale metadata. No ProcessProvider semantics or budget
values were relaxed.

CleanStopTerminationTest uses a genuine verified Python child that acknowledges
shutdown but remains alive. An explicitly labelled Process wrapper denies destroy
signals and simulates wait timeout; the real OS child is intentionally still alive
when the provider's reference becomes null. The fixed runtime rejects stop as
unknown and retains its budget charge after journal reopen. The test finally kills
only its own fixture child. This does not claim to force an OS unkillable process.

The b33bad9 runtime fails this regression (before.log). The fixed full lifecycle
suite and version suite pass (after.log and versions.log), including existing PID
identity, process termination, clean-session, persistent-budget and JVM-recovery
checks. Native CI after this change has not run locally.
