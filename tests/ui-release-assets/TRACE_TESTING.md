# Release entry response Trace regression

The release guard, launcher and release-console adapter retain validated response
Trace IDs without changing EMBEDDED200, native authorization, identity binding,
release affinity, digest verification, dirty-form confirmation or refresh policy.
Launcher fallback DOM is created before its current failed response ID is shown.
Starting a subsequent fetch discards previous response metadata. Network errors
cannot supply an ID through error properties, and changed identities suppress old
response diagnostics. No request body or telemetry is sent.

`node --test tests/ui-release-assets/release-session.test.mjs tests/ui-release-assets/ui-launcher.test.mjs tests/ui-release-assets/release-trace.test.mjs`

Requires TypeScript 5.8.3 on PATH (or TSC) and the pinned tests/frontend dependencies
(or FCP_FRONTEND_DEPS). The session and DOM tests import strict-compiled ESM files,
including the trace dependency; the legacy launcher VM tests remain compatibility
checks only. DOM HTTP objects are doubles, not evidence of native browser success.

The loader imports fixed same-origin `/{admin,teacher}/release-trace.mjs`.
Native packaging copies canonical dev-host trace.js into both directories and
requires both entries in the output JAR. Existing static allowlists cover both
shell directories. The DOM test compares strict compiler output to that canonical
file. Actual native build, JAR delivery/MIME and browser paths still need native CI.
