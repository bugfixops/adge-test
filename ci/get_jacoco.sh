#!/usr/bin/env bash
# Download the JaCoCo command line tool (same 0.8.8 that made the baseline reports) and check its SHA-256.
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION=0.8.8
SHA256=c449591174982bbc003d1290003fcbc7b939215436922d2f0f25239d110d531a
mkdir -p .cache
curl -fsSL --retry 3 -o .cache/jacococli.jar \
  "https://repo1.maven.org/maven2/org/jacoco/org.jacoco.cli/$VERSION/org.jacoco.cli-$VERSION-nodeps.jar"
echo "$SHA256  .cache/jacococli.jar" | sha256sum -c -
