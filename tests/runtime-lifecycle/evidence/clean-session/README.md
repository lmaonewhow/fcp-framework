# Clean lifecycle sessions versus failed restart budget

The native dde2bcd evidence records successful initial enable, two browser-driven
clean disable/restore cycles, version selection, and then a 503 at re-enable.
The startup log repeatedly reports RESTART_BUDGET. Native runtime permits three
unresolved starts per rolling 60 seconds. The original code charged every start,
including completed clean lifecycle sessions, so this valid workflow reached its
fourth start inside the window. A subsequent storage-plugin enable reconciles the
whole desired catalog and encounters the earlier failed Python enable; this remains
fail-closed and is not bypassed by this fix. The HTTP artifact omitted fcpCode, so
it does not independently identify the exception of each individual 503 response.

The original runtime is reproduced locally with a real verified Python package:
five requested clean start/stop cycles fail at the fourth start (before.log).
No budget value is increased. Each runner retains its precise durable start time.
Only an explicit stop with zero tenant references and in-flight calls, matching
live OS identity, acknowledged shutdown and completed transport termination can
mark that exact start as clean. It never clears previous failures. Host shutdown,
already-dead runners, missing runners, failed shutdown and UNKNOWN outcomes do not
receive this mark. All timestamps, including clean starts, still enforce 250ms
minimum spacing. Crash/unknown charges and clean-session marks persist in the same
File/JDBC state journal across host restarts. Cleanup persistence failure remains
fail-closed. No business request is replayed.

Actual Python regression: five clean cycles pass; two force-killed sessions remain
charged through one later clean stop and journal reopen; the next killed session
exhausts the unchanged budget. Calling stop without a runner does not reset it.
A separate controlled-clock test uses the actual file journal to prove 249ms is
still rejected after clean completion, and unknown charges survive reopen.
Existing host-callback persistent-budget, process quota/termination, lifecycle and
version/schema/crash suites also pass. Logs and source hashes accompany this report.

Native rerun after the fix is NOT_RUN locally; the recorded native failure is not
relabeled PASS. This changes failed-restart accounting, not admission grants or the
failure behavior of other desired plugins in the same tenant catalog.
