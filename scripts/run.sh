#!/usr/bin/env bash
# Runs the sample application: engine, REST API and modeler UI at http://localhost:8080/dotflows
#
#   scripts/run.sh              run, building first if nothing has been built yet
#   scripts/run.sh --build      rebuild (without tests) before running
#   PORT=9090 scripts/run.sh    use another port
#
# Any other argument is passed to the application, e.g.
#   scripts/run.sh --spring.datasource.url=jdbc:postgresql://localhost:5432/dotflows \
#                  --spring.datasource.username=postgres --spring.datasource.password=postgres
#
# Without a datasource argument it uses an in-memory H2 database, so everything is lost on exit.
set -euo pipefail
cd "$(dirname "$0")/.."

rebuild=false
args=()
for arg in "$@"; do
  case "$arg" in
    --build) rebuild=true ;;
    *) args+=("$arg") ;;
  esac
done

find_jar() {
  ls dotflows-sample-app/target/dotflows-sample-app-*.jar 2>/dev/null | grep -v -e '-sources' -e '-javadoc' | head -n 1
}

jar="$(find_jar || true)"
if $rebuild || [ -z "$jar" ]; then
  scripts/build.sh --skip-tests
  jar="$(find_jar)"
fi

port="${PORT:-8080}"
echo "Starting dot-flows sample on http://localhost:${port}/dotflows  (Ctrl+C to stop)"
exec java -jar "$jar" --server.port="$port" ${args[@]+"${args[@]}"}
