#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
compose="$root/scripts/compose-prod.sh"
# No orders, payments, stock mutations or credentials are submitted by this script.
"$compose" exec -T frontend node -e "fetch('http://127.0.0.1:3001/health').then(r=>{if(!r.ok)process.exit(1)}).catch(()=>process.exit(1))"
for service in discovery-server api-gateway product-service inventory-service cart-service order-service payment-service user-service notification-service; do
    "$compose" exec -T "$service" curl -fsS --max-time 12 http://127.0.0.1:9091/actuator/health/readiness >/dev/null
    printf 'readiness passed: %s\n' "$service"
done
"$compose" exec -T discovery-server sh -c 'printf "user=\"%s:%s\"\nurl=\"http://127.0.0.1:8761/eureka/apps\"\n" "$EUREKA_USERNAME" "$EUREKA_PASSWORD" | curl --config - --fail --silent --max-time 10 >/dev/null'
"$compose" exec -T api-gateway curl -fsS --retry 5 --retry-all-errors --retry-delay 2 --retry-max-time 30 --max-time 10 'http://127.0.0.1:8080/api/product/search?page=0&pageSize=1' >/dev/null
"$compose" exec -T api-gateway sh -c 'code=$(curl -sS --max-time 10 -o /dev/null -w "%{http_code}" http://127.0.0.1:8080/api/cart/me); [ "$code" = 401 ]'
"$compose" exec -T api-gateway sh -c 'curl -fsS --max-time 10 "$KEYCLOAK_METADATA_URI" >/dev/null'
"$compose" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot -Nse "SELECT 1" >/dev/null'
"$compose" exec -T redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli ping | grep -q PONG'
"$compose" exec -T kafka kafka-topics --bootstrap-server kafka:29092 --list >/dev/null
"$compose" exec -T schema-registry curl -fsS --max-time 10 http://127.0.0.1:8081/subjects >/dev/null
# Optional public URLs verify actual DNS/TLS/ingress without financial writes.
if [ -n "${PUBLIC_SMOKE_URL:-}" ]; then
    case "$PUBLIC_SMOKE_URL" in https://*) ;; *) echo 'Public smoke URL must use HTTPS' >&2; exit 2;; esac
    curl -fsS --max-time 15 "$PUBLIC_SMOKE_URL/health/frontend" >/dev/null
    curl -fsS --max-time 15 "$PUBLIC_SMOKE_URL/health/gateway" >/dev/null
    curl -fsS --max-time 15 "$PUBLIC_SMOKE_URL/api/product/search?page=0&pageSize=1" >/dev/null
fi
printf 'Read-only production smoke checks passed. Authenticated and external flows still require operator verification.\n'
