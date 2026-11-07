# Persisted restart backoff investigation

Baseline: 61bef99c4c1781db82eb422ad65491bacf9fa537 (isolated PR6 CI investigation tree).
The reported CI failure was not reproduced verbatim locally. Unmodified HTTP tests
passed 20/20; diagnostic runs observed actual RESTART_BACKOFF and 5.6–5.9 second
completion rather than normal ~0.8 second completion. This establishes a scheduling
boundary, not proof that the absent CI private log contained the same exception.

The restart journal enforces 250ms between runner attempts, including host restart.
The HTTP test immediately restarts the host. When that reaches the persisted guard,
the old recovery scheduler waits five seconds, competing with the unchanged
100 × 50ms HTTP recovery assertion. The source changes named in the CI request do
not modify this path.

The journal now supplies a structured remaining-delay hint only for RESTART_BACKOFF.
The shared native/development PluginRecovery honors that hint for the next pass;
normal reconciliation remains five seconds. Every pass still checks live authority,
original restart budget and persisted backoff. Neither the 250ms guard nor the HTTP
assertion was relaxed. No business calls are retried. Unexpected discovery failure
also retains scheduling through finally; shutdown cancels further passes.

The deterministic regression uses the actual file journal, closes/reopens it, and
calls the real scheduler with an explicitly labelled reconciliation fixture and
controlled admission clock. Its first attempt must receive exactly 250ms backoff;
the next happens no earlier than 250ms and before the ordinary five-second interval.
It then checks that a 249ms interval remains rejected and the three-start budget is
still enforced. A negative-control build retaining fixed-five-second scheduling
fails this test. This negative control is not described as the original CI failure.

After the fix, 30/30 complete real Java HTTP + Python process tests passed, including
15 observed persisted-backoff events. Failure diagnostics print only server output
with the fixture token/temp path redacted, Java process exit state, and selected
non-secret synthetic lifecycle state. No identity config, call input or tokens are
archived. Test timeout and all original lifecycle assertions remain unchanged.

Run `source /workspace/shared/fcp-toolchain/env.sh` then
`python3 tools/verify_runtime_lifecycle.py`. The suite includes the new deterministic
recovery test. Evidence JSON contains measured times and log hashes; raw sanitized
logs are retained locally under evidence/. Native RuoYi HTTP was NOT_RUN here;
both hosts use the same tested PluginRecovery implementation.
