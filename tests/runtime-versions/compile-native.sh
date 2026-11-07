#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${FCP_NATIVE_BACKEND:?Set to compiled pinned RuoYi 5.6.2 backend}"
: "${FCP_MAVEN_REPOSITORY:?Set to resolved official Maven repository}"
BUILD=$(mktemp -d);trap 'rm -rf "$BUILD"' EXIT
CP=$(find "$FCP_NATIVE_BACKEND" -type d -path '*/target/classes' -printf '%p:';find "$FCP_MAVEN_REPOSITORY" -name '*.jar' -printf '%p:');CP=${CP//$'\n'/}
printf 'UTC: ';date -u +%FT%TZ
javac --release 21 -cp "$CP" -d "$BUILD" $(find core/src/main/java business/src/main/java host/src/main/java -name '*.java') \
 integration/ruoyi/src/main/java/org/dromara/fcp/plugin/NativePluginHost.java \
 integration/ruoyi/src/main/java/org/dromara/fcp/plugin/FcpPluginConfiguration.java \
 integration/ruoyi/src/main/java/org/dromara/fcp/plugin/FcpPluginVersion{Controller,Configuration,Advice}.java
find "$BUILD/org/dromara/fcp/plugin" -name '*.class' -print0 | sort -z | xargs -0 sha256sum
printf 'PASS actual native dependency compilation; native HTTP/browser acceptance remains a separate CI check\n'
