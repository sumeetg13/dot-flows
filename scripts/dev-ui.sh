#!/usr/bin/env bash
# Starts the UI with hot reload for frontend work. API calls are proxied to an application on
# localhost:8080, so run scripts/run.sh in another terminal first.
set -euo pipefail
cd "$(dirname "$0")/../dotflows-ui"

[ -d node_modules ] || npm ci --no-fund --no-audit
exec npm run dev
