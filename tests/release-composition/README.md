# Single-host composite executor fault acceptance

Run with JDK 17+:

    bash tests/release-composition/run.sh

Before integration, point `FCP_COMPOSITION_SOURCE_ROOT` at the actual integration tree. The runner copies the maintained `CompositeReleaseDeployment` and `NativeCompositionComponents` into temporary compilation inputs and prints their hashes. It does not commit duplicate implementation files.

The test uses **actual child JVM processes**, durable file journals, and the production router/executor. Component verification, pending-task counts, and targeted receipt-write faults are explicit test doubles. It verifies:

- Deploy, canary tenant decisions, pause, promotion, emergency stop, rollback and retirement.
- A known FAILED result remains terminal even while component status continues throwing.
- Router effect followed by receipt-write failure, then close/reopen both journals and reconstruct the router/executor, reconciles without reapplying the route.
- STOP_INTENT with a real stopped process and missing receipt reconciles after journal reopening without repeating stop.
- Pending-task count blocks process stop; draining it stops only the inactive process.
- Two executors sharing one router serialize older routing and ABORT. Replay cannot reopen the stopped route.
- Component verification does not hold the router monitor, avoiding runtime-to-router lock inversion.

This is single-owner component acceptance, **not** Spring boot, native plugin protocol/storage/task integration, MySQL, Vue/browser, OS owner lease, or cross-JVM distributed transaction acceptance. The fake task count does not replace genuine durable task-pin tests. Child JVMs are stopped in cleanup. Test journals use fresh temporary directories.
