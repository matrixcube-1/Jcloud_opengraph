#!/usr/bin/env bash
# Verification en chaine du binaire JForex — UNE commande.
# Compile -> suite JUnit complete -> VerifyAll (S1..S6 + MC final).
# Code retour : 0 = tout vert ; 2 = prerequis manquant.
#
# Prerequis (variables d'environnement ou autodetection) :
#   JDK ou JAVA_HOME : repertoire JDK 11+ (sinon javac/java du PATH)
#   JF_API           : jar API JForex (depot public Dukascopy, ex. 2.13.99)
#   JUNIT_JAR        : junit-platform-console-standalone.jar
#   CORPUS_ROOT (ou $1) : repertoire corpus ticks — optionnel (SKIP sinon)
set -euo pipefail
cd "$(dirname "$0")"

# --- toolchain Java -----------------------------------------------------------
if [ -n "${JDK:-}" ]; then
    JAVAC="$JDK/bin/javac"; JAVA="$JDK/bin/java"
elif [ -n "${JAVA_HOME:-}" ]; then
    JAVAC="$JAVA_HOME/bin/javac"; JAVA="$JAVA_HOME/bin/java"
else
    JAVAC="javac"; JAVA="java"
fi
command -v "$JAVAC" >/dev/null 2>&1 || [ -x "$JAVAC" ] || {
    echo "ERREUR: javac introuvable — exporte JDK=/path/to/jdk" >&2; exit 2; }

# --- dependances --------------------------------------------------------------
find_jar() {  # $1 = var env, $2.. = candidats (globs ok)
    local v="${!1:-}" c
    [ -n "$v" ] && [ -f "$v" ] && { echo "$v"; return 0; }
    for c in "${@:2}"; do
        for f in $c; do [ -f "$f" ] && { echo "$f"; return 0; }; done
    done
    return 1
}
API=$(find_jar JF_API "tools/jforex*.jar" "lib/jforex*.jar" "deps/jforex*.jar" \
    "../../tools/jforex*.jar") || {
    echo "ERREUR: jforex-api.jar introuvable — exporte JF_API=/path/to/jforex-api.jar" >&2
    echo "        (telechargeable du depot public Dukascopy, scope provided)" >&2; exit 2; }
JUNIT=$(find_jar JUNIT_JAR "tools/junit*.jar" "lib/junit*.jar" "deps/junit*.jar" \
    "../../tools/junit*.jar") || {
    echo "ERREUR: junit standalone jar introuvable — exporte JUNIT_JAR=/path/to/junit-platform-console-standalone.jar" >&2; exit 2; }

# --- separateur classpath (';' Windows MSYS/MinGW, ':' Unix) -------------------
case "$(uname -s 2>/dev/null || echo Windows)" in
    MINGW*|MSYS*|CYGWIN*)
        SEP=';'
        # MSYS convertit les args '-cp a;b' en liste Unix -> casserait le
        # classpath du javac Windows. On gele la conversion et on donne a
        # javac des chemins natifs (C:/...) pour tout ce qui vient de
        # l'environnement ou de la detection.
        export MSYS_NO_PATHCONV=1
        if command -v cygpath >/dev/null 2>&1; then
            for v in JAVAC JAVA API JUNIT; do
                p="${!v}"
                case "$p" in
                    /*) printf -v "$v" '%s' "$(cygpath -m "$p")" ;;
                esac
            done
        fi
        ;;
    *) SEP=':' ;;
esac
MAIN=target/classes
TEST=target/test-classes
CP="${API}${SEP}${MAIN}${SEP}${TEST}"

echo "[1/3] compile main+tests...  (javac=$JAVAC, api=$API)"
mkdir -p "$MAIN" "$TEST"
# shellcheck disable=SC2046
"$JAVAC" --release 11 -d "$MAIN" -cp "$API" \
    $(find src/main/java -name '*.java')
# shellcheck disable=SC2046
"$JAVAC" --release 11 -d "$TEST" \
    -cp "${JUNIT}${SEP}${API}${SEP}${MAIN}" \
    $(find src/test/java -name '*.java')
# ressources de test (fixture doree golden.tsv...) -> classpath test
if [ -d src/test/resources ]; then cp -r src/test/resources/. "$TEST/"; fi

echo "[2/3] suite JUnit..."
"$JAVA" -jar "$JUNIT" execute -cp "$CP" --scan-classpath "$TEST" 2>&1 \
    | grep -E "tests (found|successful|failed|skipped)|\[X\]"

echo "[3/3] VerifyAll (arborescence -> mapping -> formules -> perf -> MC)..."
CORPUS="${1:-${CORPUS_ROOT:-}}"
if [ -n "$CORPUS" ]; then
    "$JAVA" -Xmx4g -cp "$MAIN" opengraph.replay.VerifyAll "$CORPUS"
else
    "$JAVA" -Xmx4g -cp "$MAIN" opengraph.replay.VerifyAll
fi
