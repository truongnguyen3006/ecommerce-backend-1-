#!/bin/sh
set -eu
case "$REDIS_PASSWORD" in ''|*[!a-fA-F0-9]*) echo 'Redis requires a generated hex credential' >&2; exit 2;; esac
[ "${#REDIS_PASSWORD}" -eq 64 ] || exit 2
umask 077
cat > /tmp/redis-production.conf <<CONFIG
bind 0.0.0.0
protected-mode yes
port 6379
requirepass $REDIS_PASSWORD
dir /data
appendonly yes
appendfsync everysec
save 900 1
save 300 10
save 60 10000
maxmemory-policy noeviction
CONFIG
chown redis:redis /tmp/redis-production.conf
exec /usr/local/bin/docker-entrypoint.sh redis-server /tmp/redis-production.conf
