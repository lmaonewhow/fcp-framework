# Runtime package contract regressions

These tests exercise actual local ZIP files, package extraction, immutable publication,
reopening the store, concurrent requests and adversarial inputs. They do not execute
installed code, activate Registry routes, launch RuoYi or demonstrate an OS sandbox.

## API

`PluginManifest.parse(String)` accepts manifestVersion 1 and exact contractVersion
`1.0`. Required fields are pluginId, version, publisher and runtime. Runtime is
`{type:"local",entry:"worker.py",argv:[]}`; entry is a package-relative file, argv
is a bounded array, and shell/environment/remote configuration is rejected. The
runtime launcher must choose its trusted interpreter separately if one is needed.
The installer never calls any launcher.

Optional arrays are dependencies, provides, requires and secretRefs. Dependencies
use `{pluginId,versionRange}` with exact semantic versions or `^1.2.3` (nonzero
major only). `matchesVersion` implements this deliberately limited grammar; this
is not a general npm version-range parser. Dependency cycles across packages and
host/version/grant availability must still be checked by the control plane.
Capabilities declare id, version, permission, sideEffects, idempotent,
timeoutMillis, inputSchema and outputSchema; schemas pass the existing
`fcp.Schema.check` subset. Secret references contain names only, never a map of
secret values. This contract cannot detect secrets hidden in arbitrary source
files or strings: publishers must not package them.

Storage declares schemaVersion, compatibleVersions, tables and migrations.
Supported field types are string/integer/decimal/boolean/timestamp/json; indexes
refer only to declared fields. Migration operations currently permit only
`{type:"addField",table,field}` referencing a declared nullable field. They are
validated declarations, not executed migrations; database privilege, migration
history and grant checks belong to the storage/control plane.

UI uses the shared `1.0` declarative contract: page declarations contain local id,
title, permissions, components, capabilities and content; slot declarations
contain local id, slotId, order, permissions, components, capabilities and
content. Supported components are text@1, field@1, button@1, table@1 and stack@1.
Nodes contain type and optional literal text, field, capabilityId and children;
only buttons bind actions, only stacks contain children. Each stack accepts at
most 128 children; a content tree has at most 1,000 nodes and depth 13. Text and
titles must be nonblank, at most 2,048 UTF-16 code units, and cannot contain `<`,
`>`, `${` or `{{`. This conservative first-version restriction also rejects some
otherwise harmless plain text; it is not an HTML sanitization guarantee. UI
identifiers use the manifest identifier grammar and a 128-character limit. The
shared `ui-boundaries.json` exercises identical content cases in Java and the
frontend validator. The control plane adds
pluginId, namespaced IDs and routes, checks host slot/component availability and
re-evaluates permissions. Manifest declaration alone never grants access.

`new PackageInstaller(root, trustedPublishers[, Limits])` creates an
owner-controlled store. `install(zipPath, expectedSha256, idempotencyKey)` returns
`InstalledPackage(manifest, sha256, directory, replayed)`. Expected SHA-256 must
come from the caller's authenticated distribution/approval channel. Publisher
allowlisting is an identity check against that trusted digest, NOT a signature
verification mechanism. An attacker can self-label an untrusted manifest.

Archive bytes, entry count (including implicit directories), per-entry bytes,
total expanded bytes and compression ratio are bounded. Only UTF-8 names,
stored/deflated single-disk non-ZIP64 archives are supported. Traversal, duplicate
and case-alias names, conflicting files/directories, mismatched local headers,
Unix symlinks/special files, encryption, bad CRCs and undeclared runtime entries
are rejected. Unsupported archives fail closed.

Package directories use the archive SHA-256 as their immutable address. Files
are flushed, atomically published on the same POSIX filesystem and sealed
read-only; installation records are flushed and atomically replaced. The entry
file receives owner-execute permission without executing it. SHA-based request
records persist idempotency across restarts. A key cannot refer to a different
archive and a plugin/version cannot be replaced with different bytes. Replays
revalidate the supplied archive and compare published content before returning.
A single-node file lock serializes cooperating installer instances.

An interruption between publication and the records may leave an unreferenced
package; retrying reconciles and seals the same verified content. This is not a
full activation/recovery journal. Host crashes, power-loss recovery and full
control-plane lifecycle are separate acceptance work. Owners can chmod their
own files; read-only publication is not protection against hostile same-uid
processes. The store and its ancestors must not be writable by untrusted users.

## Running

With a full JDK 17 or later, compile core sources and
`tests/runtime-packages/RuntimePackagesTest.java`, then run
`java -cp <classes> RuntimePackagesTest`. The unified
`tools/verify_runtime_contracts.py` also invokes this test.

Initial local execution used Java 21's `jdk.compiler` module because the image
has no `javac` launcher or `ct.sym`; that execution does not certify `--release
17`. The initial readonly-directory publication failure was preserved in
`/tmp/fcp-packages-first-failure.log`, then fixed and regression-tested. Generated
local ZIP fixtures and stores are intentionally retained in the printed test
evidence directory. No prior acceptance result is reused.
