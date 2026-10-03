#!/bin/sh
set -eu
for hostname in "$PUBLIC_APP_HOST" "$PUBLIC_AUTH_HOST"; do
    case "$hostname" in ''|*[!a-zA-Z0-9.-]*) echo 'Invalid public hostname' >&2; exit 2;; esac
done
[ "$PUBLIC_APP_HOST" != "$PUBLIC_AUTH_HOST" ] || { echo 'Application and auth hostnames must differ' >&2; exit 2; }
envsubst '${PUBLIC_APP_HOST} ${PUBLIC_AUTH_HOST}' < /etc/nginx/nginx.conf.template > /tmp/nginx.conf
exec nginx -c /tmp/nginx.conf -g 'daemon off;'
