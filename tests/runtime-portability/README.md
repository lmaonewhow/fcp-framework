# A35 / A36: host portability and implementation dependency gate

Run `python3 tools/verify_runtime_portability.py` with JDK 17+, Python 3 and Node on PATH.
It compiles the real core/application/development host, runs actual ZIP-install negative
fixtures, starts a real DevHost JVM, and invokes real Python and Node subprocesses over
HTTP. Logs, exit codes, baseline commit, dirty-tree status, source hashes and class hashes
are written under `reports/runtime-portability/`. This is not native RuoYi acceptance.

## One shared HTTP contract

`http_contract.run_http_contract` is called without changing its assertions by
`tests/runtime-http/test_portability.py` and the real native
`tests/runtime-native/native_runtime_acceptance.py` fixture. The suite creates identical,
deterministic packages and checks install rejection/success/replay, missing dependencies,
required platform approval, stale revisions, exact closure confirmation, enablement,
catalog projection, invocation identity, Node-to-Python capability callbacks, input errors,
live grant revocation, dependent disablement and disabled invocation. Authenticated failures
assert the same `data.fcpCode`, not host-specific error strings.

The live invocation-revocation check removes the Python provider's grant: it has no
`requires`, so its permission can be revoked without removing an enablement prerequisite.
Node requires `echo`; revoking Node's grant instead causes the real recovery scheduler to
fail `CAPABILITY_NOT_GRANTED` and deliberately cut off tenant admissions. That is a different
security boundary, not a permissible substitute for the strict `PERMISSION_DENIED` assertion.
Real HTTP regressions record a log boundary after writing the grant change and observe two
completed recovery events before checking both cases. The single-threaded scheduler starts
the next tick only after the prior tick finishes, excluding a pre-write in-flight tick. Python
revocation still passes the unchanged semantic assertion, while the former Node target is a
negative control that produces `PLUGIN_DISABLED`. No production authorization is changed.
Failure reports retain completed checks and bounded HTTP/code/fcpCode/valid trace ID fields,
never raw messages, bodies, principal IDs or tokens.

The native adapter receives only the client created after genuine RuoYi encrypted browser
login and native host/build/database evidence verification. Its grants use the native
reviewed grant-plan API and MySQL. DevHost uses an explicitly configured private development
identity file. Missing-login denial and transport details are deliberately host-specific:
Sa-Token/clientid behavior is not relabeled as development authentication. A separate actual
JVM process test proves `FCP_ENV=production` refuses development authentication before reading
configuration or binding a listener.

Run native acceptance through `python3 tools/ci_native.py --ingestion` in the dedicated CI
MySQL/Redis environment. The native report contains its own `portability` result and package
hashes. Until that command actually executes, native A35 is **NOT_RUN**. Wiring/deterministic
fixture tests are not native PASS evidence. Python and Node must both execute; neither is
silently skipped.

## Build and installation rule

`fcp.plugin.PluginDependencyPolicy` is the one rule used by both:

* `python3 tools/check_plugin_dependencies.py <unpacked-directory>`, also called by the
  exchange example packaging tool before writing an archive;
* `PackageInstaller`, after bounded extraction and manifest/runtime validation, before any
  package publication, version pointer or installation receipt. This also applies when the
  native HTTP installer, DevHost or repository re-verification invokes the installer.

The first-phase packaging format accepts a static `requirements.txt`, npm `package.json`,
and npm `package-lock.json` v1/v2/v3. Declared direct dependencies, npm aliases, nested lock
entries and literal Python/Node imports must not reference RuoYi or FCP host/business/core
implementation modules. Bundled JVM class/JAR artifacts are rejected for these Python/Node
packages. Public contract/capability SDK names and ordinary portable third-party packages are
allowed; FCP capability declarations and plugin-to-plugin dependencies are not code imports
and are not blocked by name.

Filesystem/git/URL dependency specifiers, pip include/editable/conditional requirements,
linked npm packages, and unsupported/dynamic dependency metadata (such as setup.py,
pyproject.toml, Poetry/uv/Yarn/pnpm files or JVM build files) fail closed. Convert these to
static supported metadata in the reviewed release package. Package managers/build scripts
are never run by the installer. A dependency declaration does not install a registry package;
publishers remain responsible for reviewed available/bundled dependencies.

The negative suite checks both the build rule and actual installer, verifies no package,
version pointer, request receipt or staging payload remains, and uses fixtures that would
throw if installation executed them. It includes allowed contract SDK counterexamples.

**This is a trusted-code dependency/build rule, not an OS sandbox.** Literal-import lint is
not complete Python/JavaScript program analysis. Obfuscated/computed imports, behavior of
third-party packages, arbitrary filesystem/network access and same-OS-user credential access
remain outside this rule and require publisher review plus a separate OS isolation design.
A36 evidence must never be reported as an A23 sandbox PASS.
