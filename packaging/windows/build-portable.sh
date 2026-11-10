#!/usr/bin/env bash
# Builds the portable Windows package: target/MQToolNG-<version>-windows-x64.zip
#
#   MQToolNG/
#     MQToolNG.exe      launcher (launch4j), runs MQToolNG.jar with the bundled jre
#     MQToolNG.jar      application, IBM MQ client included
#     jre/              Eclipse Temurin 21 JRE for Windows x64
#     LICENSE.txt, THIRD-PARTY-NOTICES.md, README.txt
#
# Requirements: JDK 21, Maven, curl, sha256sum, zip.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

# Temurin JRE for Windows x64; keep the major version in line with maven.compiler.release
JRE_VERSION="21.0.13+9"
JRE_FILE="OpenJDK21U-jre_x64_windows_hotspot_21.0.13_9.zip"
JRE_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/${JRE_FILE}"
JRE_SHA256="2332c0b4cff6bed722da3567c91d5e47568bcba863fa4945d0a8d6fc76158628"

VERSION="$(mvn -q -B help:evaluate -Dexpression=project.version -DforceStdout)"
TARGET="$ROOT/target"
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/mqtoolng"
STAGE="$TARGET/portable/MQToolNG"
ZIP="$TARGET/MQToolNG-${VERSION}-windows-x64.zip"

echo "Building MQToolNG ${VERSION}"
mvn -q -B clean package -P windows-portable

mkdir -p "$CACHE"
if [ ! -f "$CACHE/$JRE_FILE" ]; then
    echo "Downloading Temurin JRE ${JRE_VERSION}"
    curl -fsSL --retry 3 -o "$CACHE/$JRE_FILE.part" "$JRE_URL"
    mv "$CACHE/$JRE_FILE.part" "$CACHE/$JRE_FILE"
fi
echo "${JRE_SHA256}  $CACHE/$JRE_FILE" | sha256sum -c -

rm -rf "$TARGET/portable"
mkdir -p "$STAGE"
unzip -q "$CACHE/$JRE_FILE" -d "$TARGET/portable/jre-unpacked"
mv "$TARGET/portable/jre-unpacked/"*/ "$STAGE/jre"
rmdir "$TARGET/portable/jre-unpacked"

cp "$TARGET/MQToolNG.exe" "$TARGET/MQToolNG.jar" "$STAGE/"
cp "$ROOT/LICENSE" "$STAGE/LICENSE.txt"
cp "$ROOT/THIRD-PARTY-NOTICES.md" "$STAGE/"
sed "s/@VERSION@/${VERSION}/g; s/@JRE_VERSION@/${JRE_VERSION}/g" \
    "$ROOT/packaging/windows/README.txt" | sed 's/$/\r/' > "$STAGE/README.txt"

rm -f "$ZIP"
(cd "$TARGET/portable" && zip -q -r -9 "$ZIP" MQToolNG)
sha256sum "$ZIP" | sed "s|$TARGET/||" > "$ZIP.sha256"
echo "Created $ZIP"
cat "$ZIP.sha256"
