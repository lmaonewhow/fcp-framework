# Plugin release management UI

The release console embeds an independent manager using its existing authenticated ReleaseHost transport. It does not load the plugin catalog or require an initial routing decision. New mode must be configured server-side; absence is shown as a failed request, never silently redirected to legacy mutation APIs.

Register exact immutable compositions, activate, begin drain, disable and stop. Read actual status including process health, active calls and pinned task counts. Approvals show exact version/digest/tenant/scope/principal/capability/operation/current revision/current expiry. An administrator must manually enter the entire digest and confirm the displayed scope. Changing fields resets confirmation. Server authorization remains authoritative.

Unknown writes disable mutations. Refresh and explicit operator reconciliation are required before new actions; no automatic replay. Identity changes abort/discard late responses. Do not claim automatic recovery of unknown process-start outcomes. The UI explains that the legacy Exchange execution adapter cannot be enabled with multiversion mode.

Run with Node 22+, TypeScript and installed real Vue tooling:

`FCP_VUE_TOOLS=/path/to/node_modules bash tests/plugin-release-ui/run.sh`

Six transport/controller tests plus TypeScript and actual Vue SFC type checking. This does not replace native browser/MySQL integration tests. No deployed/browser acceptance is claimed.

Permission isolation regressions exercise the actual `ReleasePermissionHost` with the plugin manager, for both shell envelopes: lost write responses, same-grant refresh, revoked/failed permissions, regrant and late in-flight responses. The production wrapper normally resolves from `integration/frontend-release/permission-host.ts`; an isolated dependency worktree can be selected with `FCP_PERMISSION_HOST=file:///absolute/path/permission-host.ts`. This is an actual implementation import, not a substitute wrapper. Permission refresh must not release an unknown-write lock; only a genuinely different native identity resets it, or explicit reconciliation after a fresh authorized state read.
