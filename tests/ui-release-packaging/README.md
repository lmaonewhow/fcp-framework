# Immutable frontend bundle packaging

`python tools/ui_release_bundle.py build --dist <trusted-built-dist> --root <private-deployment-root> --shell admin` packages existing compiled bytes. `verify <root>/<digest>/<shell>` verifies the manifest and every file. These commands do not build Vue, download dependencies, authenticate a publisher, scan all secrets, or deploy a service.

The Python API is `build_bundle(dist, root, shell, backend_contract='fcp.host/1') -> {'bundlePath': str, 'manifest': dict}` and `verify_bundle(bundle, expected_digest=None) -> dict`. The manifest has exactly `format`, `shell`, `backendContract`, `files`, and `digest`. The digest hashes UTF-8 JSON of the first four keys, recursively sorted with compact separators and Unicode retained. Each file entry has exactly `sha256` and `sizeBytes`. Original static bytes, including index.html, remain untouched.

Publication uses a deployer-owned directory, cooperating-writer `flock`, file/directory fsync, verified staging, and atomic directory rename. Existing targets are verified and reused, never repaired or overwritten. The directory must not be concurrently writable by untrusted processes. This is not a hostile-filesystem sandbox. Old bundles are retained without automatic deletion.

Only regular static assets are accepted: no symlinks in source or destination ancestors, no hidden paths or dot segments, and no source maps. The file count is limited to 10,000, each file to 32 MiB and the complete content to 256 MiB. `manifest.json` is reserved. Public assets must contain no account/tenant-specific configuration or credentials; the packager cannot infer secret content from arbitrary compiled JavaScript.

`python -m unittest discover -s tests/ui-release-packaging -v` uses explicitly synthetic temporary dist files and actual file/subprocess operations. Native two-shell Vue builds, browser behavior, MySQL, and production deployment are NOT_RUN by these tests.

Root `index.html.gz`/`.br` are deliberately omitted: the server injects the immutable base into index HTML, so a precompressed original could bypass that transform. Other declared compressed static assets remain intact. Public `.md` licenses and `.d.ts` declarations are preserved; arbitrary `.ts` source and source maps are rejected.
