# Production verification checklist

Source/build checks do not certify a live deployment. Record the evidence and owner for each item.

## Required before first public deployment

- [ ] Pin matching backend/frontend commits and immutable image digests; run both CIs and all 12 image builds on an accessible Docker host. Inspect SBOMs and scan images/Java dependencies; triage the remaining frontend development-tool advisories.
- [ ] Resolve the runtime support decision: required Temurin 24.0.2 is EOSL. Review the 29 backend coordinate/version metadata matches with complete SCA and reachability evidence; preserve the private health/prometheus-only Gateway management exposure. The current source validation is not dependency security clearance.
- [ ] Rehearse the complete Compose startup/readiness/smoke flow on a disposable project without deleting existing volumes. Verify every service resolves container DNS and has the correct profile, private management port and issuer.
- [ ] Generate real unique secrets; keep protected `.env.production` outside Git/images/logs. Validate variables and GHCR package/pull permissions.
- [ ] Configure application/auth DNS and valid HTTPS certificates on the trusted same-host TLS edge. Match 50 MiB ingress body limit, Upgrade/forwarding headers and callback paths. Confirm all infrastructure/admin/metrics ports remain private.
- [ ] Create/review Keycloak production realm/client, least-required service-account grants, brute-force protection and exact origins. Verify a USER cannot become ADMIN, exact operator identity/profile, login, refresh, logout, disable and issuer/JWK behavior. Rehearse any upgrade of the existing Keycloak 18 DB separately.
- [ ] Back up and restore-test SQL/Keycloak, Redis, Kafka/Registry/Streams and Grafana/config as one documented boundary. Rehearse fresh MySQL migrations and backed-up legacy adoption at explicit baseline 0; validate schema/checksums and legitimate rows.
- [ ] Verify Redis AOF/RDB survival/TTL and Kafka/Streams restart recovery with unchanged IDs. Test duplicate INIT/CHECK/compensation and conserved stock on a real broker/Redis/MySQL stack.
- [ ] Enable and test Cloudinary single/gallery upload through the real ingress, including 10/48/50 MiB and 20-file boundaries, 413 translation and ADMIN denial for USER.
- [ ] Configure VNPay sandbox merchant/secret and public HTTPS return/IPN, verify GMT+7 dates, HMAC/merchant/amount/ownership, both cancellation/payment race directions, signed duplicates/tampering, provider acknowledgements and late success reconciliation. Record external test evidence; never test real money through generic smoke scripts.
- [ ] Verify SQL rollback persists no outbox intent, outage leaves committed rows pending, ack/restart replay preserves IDs and exactly one business effect; verify receipt → Order decision → Payment final result and frontend polling.
- [ ] Confirm readiness fails appropriately on dependency outage while liveness stays healthy. Verify all Prometheus targets, existing inventory/order metrics, outbox alerts and private Grafana access. Configure an actual alert delivery channel; alert rules alone do not deliver notifications.
- [ ] Agree on operational handling for deferred H04/H05 and M01/M02 before accepting real orders/money. Reconcile aged/DLT workflows and prohibit retired SKU reuse in operating procedure until source fixes arrive. The production stack does not erase these audited risks.
- [ ] Run `scripts/smoke-production.sh` and public DNS/TLS smoke; perform controlled authenticated flow tests and rehearsal of a compatible image rollback without state resets.

## Optional / future scale-out

- [ ] Implement owning-task inventory query routing before multiple Inventory replicas.
- [ ] Use a shared notification broker/fan-out before multiple Notification replicas.
- [ ] Replace one-node Kafka/MySQL/Redis with tested HA topologies if availability requirements demand it.
- [ ] Establish persistent trace storage, capacity/retention policies and validated resource limits from measured load.
- [ ] Address the explicitly deferred feature/concurrency findings listed in the final report; do not represent this branch as full commerce/fulfillment production maturity.

## Current verification boundary

Java 24 backend and frontend source suites were run during this task; consult `PROJECT1_PRODUCTION_READY_FINAL_REPORT.md` for exact current commands/results. Compose model, script syntax and configuration guards were checked. Docker daemon access, live MySQL/Redis/Kafka/Keycloak, external Cloudinary/VNPay, real TLS, backup/restore and public deployment were not available here. Local historical benchmarks do not substitute for these checks.
