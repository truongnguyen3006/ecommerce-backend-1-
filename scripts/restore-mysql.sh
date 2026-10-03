#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
service=${1:-mysql};input=${2:?Usage: restore-mysql.sh mysql|keycloak-db /secure/path/backup.sql}
case "$service" in mysql|keycloak-db) ;; *) exit 2;; esac
[ "${RESTORE_ISOLATED_DATABASE_ACK:-}" = YES ] || { echo 'Restore requires an isolated empty database, stopped writers, and RESTORE_ISOLATED_DATABASE_ACK=YES' >&2; exit 2; }
[ -s "$input" ] || exit 2
[ -f "$input.sha256" ] || { echo 'Backup checksum is required' >&2; exit 2; }
(cd -- "$(dirname -- "$input")" && sha256sum --check "$(basename -- "$input").sha256")
"$root/scripts/compose-prod.sh" exec -T "$service" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root' < "$input"
printf 'Import finished; verify histories, identities, migration checksums and stock before enabling traffic.\n'
