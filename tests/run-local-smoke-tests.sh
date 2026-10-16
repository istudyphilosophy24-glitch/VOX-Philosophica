#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
kotlinc \
  app/src/main/java/io/voxphilosophica/Chunker.kt \
  app/src/main/java/io/voxphilosophica/WavWriter.kt \
  tests/ChunkerSmokeTest.kt \
  tests/WavWriterSmokeTest.kt \
  -include-runtime -d "$tmp/tests.jar"
kotlin -classpath "$tmp/tests.jar" ChunkerSmokeTestKt
kotlin -classpath "$tmp/tests.jar" WavWriterSmokeTestKt
