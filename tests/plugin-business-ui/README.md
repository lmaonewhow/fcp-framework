# Business plugin uncertainty gate

This gate imports actual Vue, Vue Router, `usePlugins` and `UiRuntime`; it does not mock those components. A custom Vue renderer avoids claiming a real browser/DOM test. HTTP and authenticated identity are explicit test doubles. Tests cover both shell response envelopes, lost responses, grant revoke/regrant, permission epoch failure, late success, user isolation, exact-key retry and explicit abandonment.

Use Node 24 and an isolated official-registry npm prefix, without changing production lockfiles:

```
npm install --prefix /tmp/fcp-business-ui-deps --no-audit --no-fund vue@3.5.30 vue-router@5.0.3 vite@7.3.2 typescript@5.9.3 vue-tsc@3.3.14
FCP_VUE_ROOT=/tmp/fcp-business-ui-deps/node_modules bash tests/plugin-business-ui/run.sh
```

The script requires the dependencies; missing tools fail instead of skipping. It performs strict real Vue SFC/type checking, bundles the actual composable with Vite, runs twelve combination tests, the four existing Vue host cases, and the eighteen existing runtime cases. The pinned versions above match the actual assembled management frontend used for local validation.

The uncertainty tombstones are payload-free, scoped to tenant plus principal, and live only for the component lifetime. They contain no tokens, input, output, or cross-user retry payload. This is not a durable browser-reload or backend operation-reconciliation guarantee. Server idempotency and deployment reconciliation remain independent requirements. The checkbox explicitly acknowledges reconciliation/abandonment; it does not cancel a possible committed backend effect.

The component workflow runs this same gate on Node 22.23.4 (setup-node 22) with the isolated pinned test dependencies above. Local Node 24 success is not evidence of Node 22 success; the exact CI result remains required. Production frontend dependency locks are unchanged.
Catalog 401/403 is authorization loss, not proof of an account switch: navigation and unload fences remain active while the sensitive view is cleared. Native shells separate permission events from token/user/tenant identity events. Same-principal credential refresh preserves the fence; a legacy adapter without principalKey stays conservative until an authenticated server context establishes the principal. The suite tests both native-selector and legacy branches.
