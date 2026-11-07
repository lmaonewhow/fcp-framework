#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${FCP_NATIVE_BACKEND:?Set to compiled locked RuoYi 5.6.2 backend}"
: "${FCP_MAVEN_REPOSITORY:?Set to resolved official dependency repository}"
BUILD=$(mktemp -d);trap 'rm -rf "$BUILD"' EXIT
CP=$(find "$FCP_NATIVE_BACKEND" -type d -path '*/target/classes' -printf '%p:';find "$FCP_MAVEN_REPOSITORY" -name '*.jar' -printf '%p:')
CP=${CP//$'\n'/}
printf 'UTC: ';date -u +%FT%TZ
java -version
javac --release 21 -cp "$CP" -d "$BUILD" \
 $(find core/src/main/java -name '*.java') \
 host/src/main/java/dev/fcp/host/PluginPolicy.java \
 integration/ruoyi/src/main/java/org/dromara/fcp/plugin/NativePluginHost.java \
 integration/ruoyi/src/main/java/org/dromara/fcp/plugin/FcpExchange{Configuration,Controller,Advice}.java integration/ruoyi/src/main/java/org/dromara/fcp/plugin/FcpExportRetentionConfiguration.java
find "$BUILD/fcp/exchange" "$BUILD/org/dromara/fcp/plugin" -name '*.class' -print0 | sort -z | xargs -0 sha256sum
printf 'PASS native exchange compilation against actual upstream classes and resolved dependencies; server/HTTP integration NOT_RUN\n'
