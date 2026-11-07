# Captured local result

This directory commits only a compact local summary, source hashes and command output. Full per-process logs, crash markers, snapshots and the two development-harness failures remain in the corresponding workspace reports and are generated afresh by the committed CI script; they are not all committed here.

The captured file-journal run at 2026-11-07T13:35:32Z completed at 13:36:00Z with exit 0: 12 actual sudden JVM terminations and 36 successful recovery JVMs. It used baseline b711ed7 plus the recorded source bytes, so source-sha256.txt, rather than that commit alone, identifies the tested code. OpenJDK21 compiled for Java17, Python3.12 and Node22 executed the actual plugins. Original full evidence: reports/runtime-crash/20261107T133532Z-2/.

The first development failures were test-harness mistakes: numeric wrapper comparison, then failing to accept the legitimate SCOPE_MISSING response for an absent tenant catalog. Production code was not changed to make those cases pass. The original logs remain in the workspace's prior runtime-crash reports.

Optional JdbcStateJournal support was added afterward. Its default-file rerun and 14 configuration guards passed, recorded in reports/runtime-crash/20261107T134207Z-2/. Real MySQL execution was NOT_RUN locally: the guarded disposable CI provisions a separate database and must independently produce journalBackend=MYSQL and mysqlJournalCrashRecovery=PASS. File success never substitutes for that result.
