# Response Trace ID diagnostics

These patches are derived from the exact commits in `upstreams.lock.json`, not inferred anchors. `native_trace_frontend.py` checks the commit, locked target, patch checksum, exact preimage and postimage, then rejects drift or repeat application. Native assembly copies `frontend-shared/trace.ts` and applies both request patches. Authentication, encryption, retry and authorization paths remain the native implementation.

Only nonzero 32-hex IDs from backend `X-Trace-Id` or top-level `traceId` are used. No client-generated IDs, outgoing request IDs, nested business values or free-form error-text parsing are trusted. Errors retain the ID in their message and, before wrappers recreate an error, in `error.traceId`. A single bounded, selectable notice shows the latest request error ID with explicit copy and close controls. Clipboard refusal leaves manual selection available. No extra toast, poller, persistence, telemetry or body upload is introduced. A WeakMap associates binary download responses with their response ID without retaining their contents after garbage collection. The trace does not prove causation or replace authorization for diagnostic access.

The shared module is compiled to the development shell's `dev-ui/trace.js`; the test requires byte-identical compiler output. Both error-only response paths and malformed JSON keep the response header. No response means no fabricated ID. Existing native notifications remain unchanged; raw server bodies are not added to diagnostics.

Run `node tests/observability/trace-frontend.test.cjs <directory containing node_modules/typescript and node_modules/jsdom>`. Set `FCP_TRACE_ADMIN_SOURCE` and `FCP_TRACE_TEACHER_SOURCE` to local Git repositories containing the exact pinned commits to execute both real native interceptor functions against explicit HTTP doubles; without them those two groups are SKIP. `TRACE_BASELINE=1` runs the untouched upstream implementations and reproduces the missing-ID failures. Run `python tests/observability/test_frontend_trace_patch.py -v` with the same source environment variables for strict patch regressions. Neither suite is a real authenticated native browser/backend test or a performance measurement.

Native requests capture a diagnostic scope in a module-local WeakMap at the actual
request interceptor. The scope includes the current token/user/tenant snapshot and
checks AbortSignal at each diagnostic side effect; it is never added to headers,
URLs, request bodies or serializable Axios config. Missing scope is untrusted.
Synchronous identity watchers clear an already displayed notice. A stale or aborted
completion does not show or clear a newer notice and does not add a Trace ID to the
existing native toast message. Existing native error propagation and notification
policies otherwise remain unchanged. Admin downloads check again after Blob text
parsing, since identity may change while parsing is pending.

Frontend regressions execute both exact pinned request modules with their pinned
Axios versions and the teacher's actual pinned @sa/axios implementation. Transport
adapters and auth state are explicit test doubles; these are not native HTTP/browser
acceptance. The tests verify real interceptor config identity, deferred response
ordering, identity-only changes, abort, no-stamp failures, and the release adapter's
compiled setup. All existing exact source/patch/postimage checks remain enforced.
