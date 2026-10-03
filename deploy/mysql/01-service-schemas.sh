#!/bin/bash
set -euo pipefail
# Entrypoint runs this only on an empty MySQL data volume. No demo rows or inventory seed.
for prefix in PRODUCT ORDER PAYMENT USER CART; do
    for suffix in PASSWORD MIGRATION_PASSWORD; do
        key="${prefix}_DB_${suffix}"
        [[ ${!key:-} =~ ^[a-fA-F0-9]{64}$ ]] || { echo "Invalid required credential: $key" >&2; exit 2; }
    done
done
export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
for service in product order payment user cart; do
    prefix=${service^^};app_key="${prefix}_DB_PASSWORD";migration_key="${prefix}_DB_MIGRATION_PASSWORD"
    mysql --protocol=socket --user=root <<SQL
CREATE DATABASE IF NOT EXISTS \`${service}-service\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '${service}_app'@'%' IDENTIFIED BY '${!app_key}';
CREATE USER IF NOT EXISTS '${service}_migrate'@'%' IDENTIFIED BY '${!migration_key}';
GRANT SELECT, INSERT, UPDATE, DELETE ON \`${service}-service\`.* TO '${service}_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON \`${service}-service\`.* TO '${service}_migrate'@'%';
SQL
done
