# Component regression scope

These are isolated Vue/JSDOM interactions, not native-login/browser/backend E2E.
The actual Quill component/toolbar runs; selection geometry and final format are
spied because JSDOM has no browser text-selection geometry. The file component's
OSS API uses an explicit unit double. The actual pinned Vue Router checks URL
serialization compatibility. None of the test-only dependencies is packaged in
the production application.

Use the assembled pinned admin project's existing Vite/Vitest dependencies.
Install this directory's locked test dependencies with `npm ci --ignore-scripts`.
Set `FCP_ADMIN_PROJECT` to that admin project's absolute path and
`FCP_COMPONENT_TOOLS` to this directory's absolute path, then run the admin
project's `node_modules/vitest/vitest.mjs run --config <this-directory>/vitest.config.mjs`.
The local review can instead point `FCP_COMPONENT_TOOLS` to the isolated
`toolchain/component-tests` installation with the same pinned packages.
