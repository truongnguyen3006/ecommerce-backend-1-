# Production deployment runbook

This branch prepares source and configuration for an initial single-host deployment. No public server, TLS certificate, provider credentials or live integration was provisioned by this task. Container builds and live recovery must be verified on the operator's Docker host. Read [PRODUCTION_CHECKLIST.md](PRODUCTION_CHECKLIST.md), [BACKUP_RESTORE.md](BACKUP_RESTORE.md) and [payment/outbox recovery](docs/production-recovery.md) before enabling traffic.

## Architecture and limits

The production stack contains API Gateway, Eureka, Product, Inventory, Cart, Order, Payment, User, Notification, Next.js frontend, business MySQL, Keycloak MySQL, Keycloak, Redis, KRaft Kafka, Schema Registry, Prometheus, Grafana, Zipkin and Nginx. `common-events` and `common-dto` remain shared libraries. There is no new business service.

Use **one Inventory and one Notification replica**. Inventory queries only its own Streams state; notifications use an in-process STOMP broker and a shared Kafka consumer group. All application services start at one replica. Kafka has one combined broker/controller, replication factor 1 and transaction min ISR 1; this is a portfolio/small-host topology with no node-failure availability guarantee. Do not scale inventory/notification with `--scale` until routing and shared broker behavior are implemented and verified.

Only Nginx binds a host port: **127.0.0.1:8080**. Applications use the `application` network; data infrastructure uses an internal `data` network; management UIs use an internal `monitoring` network. Application egress remains available for Cloudinary/VNPay. MySQL, Redis, Kafka, Registry, Eureka and observability UIs have no public ports. Private Kafka/Registry traffic is PLAINTEXT in this profile. Adding managed Kafka/SASL/TLS requires a reviewed override covering custom producers, consumers, Streams, readiness probes and Schema Registry; environment variables alone do not enable it.

## Requirements and checkout

Use a Linux amd64 Docker Engine with BuildKit and Compose v2.24+ (validated model with Compose v5.5.0), Python 3, Bash and sufficient memory/disk for 20 containers. Start capacity planning at 16 GiB RAM and 4 vCPU, then measure actual heap, broker and database use; this is planning guidance, not a benchmark claim. Pin the release SHAs for both repositories. Put the frontend beside the backend or set `FRONTEND_SOURCE` to its checkout path. Both use `production-ready-final`; base/main are preserved.

Local development still uses `docker-compose.yml`, `application.properties` and `npm run dev`. Do not combine the local and production Compose files or reuse local volumes blindly.

## Secrets and public addresses

1. Copy `.env.production.example` to an untracked `.env.production`, set mode `0600`, and replace every `REPLACE` value. Never commit it, upload it as a workflow artifact, print `docker compose config` without `--quiet`, or enable shell tracing.
2. Generate each self-hosted password/client secret as 64 random hex characters with `openssl rand -hex 32`. Hex credentials make SQL bootstrap and Basic-auth URL composition safe. These are operator secrets, not demo defaults. Use distinct runtime and migration DB passwords.
3. Choose distinct public application and auth DNS hosts. Set `PUBLIC_FRONTEND_URL`, `KEYCLOAK_HOSTNAME`, issuer, SockJS and VNPay callbacks to the exact HTTPS URLs shown by the example's paths. No trailing slash on base URLs.
4. Set a lowercase `IMAGE_NAMESPACE` and an immutable `IMAGE_TAG`; publish corresponding backend and frontend releases with that tag. Do not reuse/overwrite release tags. Registry private-pull credentials belong on the deployment host (`docker login --password-stdin`), not in `.env` or image layers.
5. Generate `KAFKA_CLUSTER_ID` once for a fresh cluster, using `kafka-storage random-uuid` from the pinned Kafka image. Preserve the original ID when adopting data. Do not change it to make a failed broker start.
6. Fill all three Cloudinary variables together and both VNPay merchant variables together when enabling these features. Empty credentials fail safely; they do not demonstrate a working integration. Real external verification is required before advertising online payment/upload.
7. Run `python3 scripts/validate-production-env.py .env.production`. It validates names, URL consistency, secret shape and complete provider configuration without printing secret values. `.env` uses literal unquoted values; it is parsed as data, never sourced as shell code.

Spring `prod` profiles require environment values and override all local MySQL/Eureka/client-secret defaults. Flyway is enabled, `baseline-on-migrate=false`, `clean-disabled=true`; Hibernate uses `validate`. Product/admin seeders are OFF. Local seeders remain available. Do not enable production demo seeding or run stock initialization to conceal a restore failure.

## TLS and ingress

Provide DNS and valid certificates on an operator-managed TLS edge on the **same host**, forwarding only to `127.0.0.1:8080`. The edge must preserve `Host`, overwrite forwarding headers, support HTTP/1.1 WebSocket Upgrade, allow the 50 MiB request envelope and use reasonable timeouts. Block direct public access to the loopback listener. A remote TLS edge needs a private authenticated/encrypted tunnel and a reviewed binding override.

The Nginx template expects HTTPS at the edge and supplies fixed `X-Forwarded-Proto=https`/port 443 to upstreams. It does not terminate TLS itself and must not be exposed as a public HTTP endpoint. It replaces forwarded IP values with its immediate peer address; original client IP propagation/rate limits require an explicit trusted-edge configuration review. It adds HSTS, frame-denial, content-type and referrer headers; it strips upstream debug/server information. Access logs record `$uri` without query strings, headers or request bodies.

Application host routes `/api/` and `/auth/` to Gateway, frontend pages to Next, and `/ws` plus SockJS transport paths directly to Notification with Upgrade and buffering disabled. `/actuator`, `/eureka` and internal Order endpoints are blocked. `/health/frontend` and `/health/gateway` expose status only. Auth host serves Keycloak `/realms/` and `/resources/`; `/admin`, `/health` and `/metrics` are blocked publicly. Keycloak management port 9000 remains private. Access admin and monitoring UIs with an operator tunnel, not public port publication.

Supported upload envelope: **20 image files maximum, 10 MiB each, total file bytes at most 48 MiB, total multipart HTTP body at most 50 MiB**. Frontend and Product validate file/count totals; Nginx `50m`, Gateway `RequestSize=50MB` and Spring multipart `50MB` cover the body with overhead margin. Spring file size is `10MB`. The TLS edge must match the 50 MiB body ceiling. Public uploads bypass Next.js rewrites, so Next proxy buffering is not a production upload size control. 413 has an accurate frontend message; Product validates the whole gallery before its first external upload. Public upload APIs retain ADMIN checks.

## Keycloak initialization and adoption

`deploy/keycloak/Dockerfile` pins Keycloak 26.8.0, enables health/metrics at build time and runs **start --optimized**, never `start-dev`. Database/hostname/proxy/bootstrap credentials are runtime only. `scripts/prepare-keycloak.py` copies a JSON template containing environment placeholders into the ignored runtime directory with the required realm filename. It does not materialize real secrets or overwrite a changed file. Startup import skips a realm that already exists; changing `.env` cannot rotate an existing realm's client secret automatically.

The template creates USER/ADMIN roles, a confidential `spring-cloud-client`, exact frontend origin/redirect, 300-second access tokens, session limits and brute-force protection. Existing password-grant login is retained to avoid a feature/auth redesign. Service-account grants are `manage-users`, `view-users`, `query-users`, `view-realm`, with explicit scope mapping and no `realm-admin`, `manage-realm` or `manage-clients`. `manage-users` is still powerful: protect the client secret and verify the effective service-account token roles and the application's USER role assignment in a disposable realm before release. ADMIN is granted deliberately by the operator to a verified identity with an existing SQL profile, not by production seeding.

Create an application admin by registering an intended operator through the application (creating Keycloak and SQL identities), verifying the exact username/ID and then assigning ADMIN through private Keycloak administration. Preserve ordinary registration and role boundaries. No real admin username/password is committed.

The local realm/image remains unchanged. **Do not attach the old Keycloak 18 database volume directly to 26.8 as a routine startup.** Back up DB/realm, rehearse the documented Keycloak upgrade path in an isolated copy, review changed defaults/client grants and verify login, refresh, logout, disable, issuer and role behavior. Import/export is not a full DB/session backup. Read the official [production](https://www.keycloak.org/server/configuration-production), [container](https://www.keycloak.org/server/containers), [proxy](https://www.keycloak.org/server/reverseproxy), [import/export](https://www.keycloak.org/server/importExport) and [upgrade](https://www.keycloak.org/docs/latest/upgrading/index.html) documentation.

## Fresh MySQL and legacy schema adoption

Fresh `business-db` initialization creates five logical schemas and distinct `<service>_app`/`<service>_migrate` users. Runtime users have DML only; migration users have schema-scoped DML/CREATE/ALTER/INDEX/REFERENCES. There is no global root application account, auto-created unknown database, stock seed or demo row. Separate Keycloak DB uses a scoped database user. Root is retained for controlled backup/bootstrap only.

Flyway runs on service startup using the scoped migration account before JPA validation/readiness. Any migration/checksum/schema failure must block startup; do not switch back to `update`, disable Flyway, edit applied migrations or run `repair` as a workaround. V1/V2 history is preserved and new V3/V4 migrations are additive. MySQL DDL can partially commit on failure; an old image cannot reverse new migrations automatically.

For a nonempty legacy schema without Flyway history: take and verify backups, restore an isolated copy, inventory schema/data and rehearse adoption. Existing V1/V2 adoption logic can fail on invalid/duplicate rows; resolve only reviewed data issues on the copy. Use an **explicit operator-reviewed baseline at version 0** (e.g. Flyway CLI with a private configuration file), then execute every V1/V2/V3/V4 migration and JPA validate on that copy. Baseline 1/2 would skip required adoption work. No production profile auto-baselines. Reconcile record counts, owner IDs, SKU uniqueness, totals, payment references, constraints and migration checksums before repeating the approved adoption on the backed-up original. Historical [Batch 1 adoption notes](docs/batch1-finalization.md) describe the local `migrations` profile; the production procedure supersedes its automatic-baseline suggestion.

## Build, start and verify

After environment/Keycloak preparation and backup decisions:

```bash
python3 scripts/validate-production-env.py .env.production
python3 scripts/prepare-keycloak.py .env.production
scripts/compose-prod.sh config --quiet
scripts/deploy-production.sh build   # build 9 app images, Keycloak, Nginx and frontend
# or, after publishing all matching immutable release images:
scripts/deploy-production.sh pull
```

Compose sequences databases/broker → Registry/Keycloak → discovery → business services → Gateway/frontend → Nginx. SQL migrations run during application startup. `up --wait` checks readiness and fails on unhealthy services. Initial empty catalog is valid: create real products through an authenticated admin after migrations and identity setup.

`smoke-production.sh` checks frontend, all service readiness endpoints, authenticated discovery registry access, public product search, anonymous protected rejection and private MySQL/Redis/Kafka/Registry/issuer reachability. It creates no orders, payments or stock adjustments. Set `PUBLIC_SMOKE_URL=https://your-app-host` to also verify real DNS/TLS/ingress. Readiness verifies configured issuer metadata, Kafka describe-cluster, Registry HTTP status, DB/Redis contributors and Inventory RUNNING state where applicable. It cannot prove every topic ACL, consumer assignment or business flow. Liveness checks process state only and does not depend on a healthy remote broker/database. Docker health marks a container unhealthy; it does not by itself restart a running unhealthy process.

Manually test a disposable authenticated USER/ADMIN session, bounded stock reads, a sandbox multi-SKU checkout, duplicate events, process restart and outbox recovery. Validate Cloudinary single/gallery and signed VNPay callbacks at the real HTTPS ingress. VNPay remains server-side HMAC/merchant/amount/owner validated; Payment container pins `Asia/Ho_Chi_Minh` for the provider's GMT+7 timestamp convention. `NEXT_PUBLIC_WS_URL` is an HTTPS SockJS base URL; the WebSocket transport upgrades to WSS under it. Browser query-string success is never payment proof. M01 expiry and M02 callback acknowledgement/protocol remain deferred.

## Security baseline requiring release review

The required Java 24 build/runtime is retained and was verified with 24.0.2. Adoptium lists this stream as end of service/support life since September 2025, with no further Temurin builds planned ([support roadmap](https://adoptium.net/support)). Passing tests does not supply ongoing JVM security updates. A maintained runtime choice requires a separately reviewed compatibility decision before public deployment; do not silently change the required Java version.

A limited OSV metadata check of 169 public Maven coordinates embedded in the packaged application jars matched 29 coordinate/version groups. This is not a complete dependency or container scan and does not establish exploit reachability. Spring Boot 3.5.7 / Spring Cloud 2025.0.0 remain the existing framework baseline. Review the final report's exact coordinates/advisory IDs and run complete SCA/image scans before release. Frontend production `npm audit --omit=dev` has zero findings after the targeted Axios update, while the full audit retains 20 development-tool groups (17 high, 2 moderate, 1 low); this is not an all-dependencies security clearance.

The Gateway 4.3.0 metadata includes [CVE-2025-41253](https://spring.io/security/cve-2025-41253/) and [CVE-2025-41243](https://spring.io/security/cve-2025-41243/). Their vendor-described attack conditions require the Gateway actuator route endpoint to be exposed and reachable without adequate security. The production profile exposes only health/prometheus on a private management port, denies other management endpoints and blocks public actuator routes at Nginx. These configuration controls mitigate those conditions; the dependency itself has not been version-patched. Preserve these restrictions and verify the effective live exposure.

## CI, releases and rollback

Both repositories have CI on relevant branch pushes and pull requests, with pinned action SHAs. Backend uses Temurin 24.0.2 / Maven `clean verify`; frontend uses Node 24.19.0 / locked `npm ci`, lint, build, unit, desktop/mobile E2E and responsive suites. Tests use self-contained mocks/H2, never local credentials. The frontend release workflow repeats these checks. Backend image publication repeats verify before the 11-image matrix.

`release.yml` is manual `workflow_dispatch`, guarded to `production-ready-final`; it uses `GITHUB_TOKEN` with `packages:write`, immutable tags, provenance and SBOM. Frontend input supplies a public HTTPS SockJS URL. Use the same release tag across repositories. No workflow deploys to a server. Required GHCR permissions/package visibility and private-pull credentials are operator-owned; no extra hardcoded registry secrets are required. Never dispatch publication with secret-valued build args.

Keep a release manifest pairing backend/frontend Git SHAs, image digests, env version, migration versions and backup IDs. `API_URL=http://api-gateway:8080` and `NEXT_PUBLIC_WS_URL` are frontend **build-time** values. Next rewrite destinations/public JavaScript cannot be changed after building by setting runtime variables. Rebuild for changed upstream/public host. No external secrets enter the frontend build. Backend values are runtime, and the shared image defaults to the prod profile so missing required variables fail startup. Java runtime uses non-root UID 10001 and configurable `JAVA_TOOL_OPTIONS`; frontend uses `node`; Keycloak and Nginx run non-root.

To roll back an application release, stop ingress/writers, verify the previous image is compatible with the current additive schema, restore the previous immutable image tag/manifest and start with `up -d --wait`. Preserve Kafka cluster ID, Streams application ID and all volumes. Re-run smoke checks. If schema/data compatibility is uncertain, restore the **coordinated verified backup into an isolated replacement stack**, reconcile provider receipts/stock/outbox and cut over deliberately. Never run `down -v`, global prune, Streams reset, Redis flush, offset reset or delete migrations as normal rollback. Never reopen a paid/cancelled order or clear a payment fence merely to make a test pass.
