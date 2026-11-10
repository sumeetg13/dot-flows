#!/usr/bin/env bash
# Builds every module (core, UI, starter, sample app) and installs them into ~/.m2.
#
#   scripts/build.sh               full build with tests (PostgreSQL tests need Docker; they skip without it)
#   scripts/build.sh --skip-tests  faster: compile and package only
#   scripts/build.sh --clean       remove previous build output first
#
# Any other argument is passed straight to Maven, e.g. scripts/build.sh -pl dotflows-core
set -euo pipefail
cd "$(dirname "$0")/.."

goals=(install)
args=()
for arg in "$@"; do
  case "$arg" in
    --skip-tests) args+=(-DskipTests) ;;
    --clean) goals=(clean install) ;;
    *) args+=("$arg") ;;
  esac
done

./mvnw -B "${goals[@]}" ${args[@]+"${args[@]}"}
