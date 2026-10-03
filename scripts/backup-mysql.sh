#!/usr/bin/env bash
set -euo pipefail
umask 077
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
service=${1:-mysql};output=${2:?Usage: backup-mysql.sh mysql|keycloak-db /secure/path/backup.sql}
case "$service" in mysql|keycloak-db) ;; *) exit 2;; esac
[ ! -e "$output" ] || { echo 'Refusing to overwrite a backup' >&2; exit 2; }
# Stop application writers for a coordinated SQL/Redis/Kafka backup; read the runbook first.
trap 'rm -f -- "$output.partial"' EXIT
"$root/scripts/compose-prod.sh" exec -T "$service" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --user=root --single-transaction --routines --triggers --events --hex-blob --no-tablespaces --set-gtid-purged=OFF --all-databases' > "$output.partial"
[ -s "$output.partial" ] || exit 1
mv -- "$output.partial" "$output"
(cd -- "$(dirname -- "$output")" && sha256sum -- "$(basename -- "$output")") > "$output.sha256"
printf 'Backup created; verify in an isolated restore environment before relying on it.\n'
