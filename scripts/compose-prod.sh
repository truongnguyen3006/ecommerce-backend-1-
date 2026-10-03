#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
env_file=${PRODUCTION_ENV_FILE:-$root/.env.production}
python3 "$root/scripts/validate-production-env.py" "$env_file" >/dev/null
exec docker compose --project-directory "$root" --env-file "$env_file" -f "$root/docker-compose.prod.yml" "$@"
