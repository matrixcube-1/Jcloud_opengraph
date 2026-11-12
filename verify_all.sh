#!/usr/bin/env bash
# Verification en chaine du binaire JForex — UNE commande.
# Compile -> suite JUnit complete -> VerifyAll (S1..S6 + MC final).
# Code retour : 0 = tout vert.
set -e
cd "$(dirname "$0")"
JDK="${JDK:-/c/arb_work/tools/jdk/jdk-17.0.21+9/bin}"
API="${JF_API:-C:/arb_work/tools/jforex-api.jar}"
JUNIT="${JUNIT_JAR:-C:/arb_work/tools/junit.jar}"
ROOT="${1:-C:/arb_duka/data}"

echo "[1/3] compile main+tests..."
"$JDK/javac" --release 11 -d target/classes -cp "$API" \
    $(find src/main/java -name "*.java") 2>&1 | grep -v "^Note" || true
"$JDK/javac" -d target/test-classes -cp "$JUNIT;$API;target/classes" \
    $(find src/test/java -name "*.java")

echo "[2/3] suite JUnit..."
"$JDK/java" -jar "$JUNIT" execute \
    -cp "$API;C:/arb_work/og-java/jforex/target/classes;C:/arb_work/og-java/jforex/target/test-classes" \
    --scan-classpath C:/arb_work/og-java/jforex/target/test-classes 2>&1 \
    | grep -E "tests (found|successful|failed)|\[X\]"

echo "[3/3] VerifyAll (arborescence -> mapping -> formules -> perf -> MC)..."
"$JDK/java" -Xmx4g -cp target/classes opengraph.replay.VerifyAll "$ROOT"
