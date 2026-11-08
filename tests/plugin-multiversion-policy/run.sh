#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
build=$(mktemp -d)
trap 'rm -rf "$build"' EXIT
javac --release 17 -d "$build" core/src/main/java/fcp/{Json,Schema,FcpException}.java core/src/main/java/fcp/host/{HostCapabilities,TrustedContext}.java core/src/main/java/fcp/plugin/PluginManifest.java host/src/main/java/dev/fcp/host/{VersionPluginPolicy,JdbcVersionPluginPolicy}.java tests/plugin-multiversion-policy/VersionPolicyTest.java
java -cp "$build" VersionPolicyTest
