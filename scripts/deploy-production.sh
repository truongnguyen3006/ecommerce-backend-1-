#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
mode=${1:-pull}
case "$mode" in build|pull) ;; *) echo 'Usage: deploy-production.sh build|pull' >&2; exit 2;; esac
env_file=${PRODUCTION_ENV_FILE:-$root/.env.production}
python3 "$root/scripts/validate-production-env.py" "$env_file"
python3 "$root/scripts/prepare-keycloak.py" "$env_file"
compose="$root/scripts/compose-prod.sh"
"$compose" config --quiet
if [ "$mode" = build ]; then "$compose" build; else "$compose" pull; fi
# Back up existing volumes first (BACKUP_RESTORE.md). Never pass --volumes to down.
# Flyway runs before JPA validation in each SQL service; migration failure blocks readiness.
"$compose" up -d --wait --wait-timeout 600
"$root/scripts/smoke-production.sh"
