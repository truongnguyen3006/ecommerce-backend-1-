# Project 1 — Batch 4, Batch 5 and Batch 6 Final Report

Date: 2026-10-04. Authoritative scope: `Pasted text(8).txt`, the original `PROJECT1_FINAL_AUDIT_REPORT.md`, and the preceding `PROJECT1_PRODUCTION_READY_FINAL_REPORT.md`. This continues the completed Batch 2.5 Lite and Batch 3. Only the two existing `production-ready-final` branches were advanced. No deployment was performed.

# 1. Executive Summary

| Batch | Final status | Evidence and boundary |
|---|---|---|
| Batch 4 — core correctness | **PASS** | H04/H05/M01/M02 implemented; Java 24 full reactor: 111 tests, zero failures/errors/skips. Frontend installation/lint/build and 66 tests passed (15 unit, 46 flow, 5 responsive). Both checkpoints were pushed before Batch 5. Conservative investigation remains deliberate for unresolved financial and inventory evidence. |
| Batch 5 — data/API/client consistency | **PASS** | M03–M11 implemented; Java 24 full reactor: 146 tests, zero failures/errors/skips. Frontend installation/lint/build and 79 tests passed (20 unit, 54 flow, 5 responsive). Both checkpoints were pushed before Batch 6. Owner-realm configuration and multi-instance routing remain outside that source gate. |
| Batch 6 — resilience/recovery/security validation | **PARTIAL** | Final Java 24 full reactor **150 tests / 12 modules / 0 failures/errors/skips**, **5 actual Keycloak lifecycle tests**, MySQL 8.4.10 SKU/DLT probes, **79 frontend tests** and **13/13 full-stack business/restart/replay/backup/restore checks PASS**. All 12 production images built, 20 services healthy and smoke passed. Runtime advisory reachability/patching, tooling advisories, image/OS SCA and real DLT/operator/owner-environment validation remain open. |

These statuses distinguish the completed source gates from operational assurance. No claim of live VNPay settlement, owner-data migration, public TLS/ingress, multi-instance availability, measured capacity or complete dependency remediation follows from a green unit suite. The task retained safe payment fences and explicit reconciliation instead of inventing automatic refunds or unproven compensation.

Final tested backend checkpoint: `abf95e57e0f8fee618db7fcf8a7798e377ea1630`. Final frontend SHA: `a68720c7d87dc239e96f3483b9a0c6a4b15158ea`. The report-only commit advances the backend ref after that checkpoint; its resulting SHA is supplied with the final delivery because a commit cannot contain its own SHA.

# 2. Starting Git State

| Repository | Batch 4/5/6 starting SHA | Resumed Batch 6 SHA | Working branch |
|---|---|---|---|
| `truongnguyen3006/ecommerce-backend-1-` | `2f98e838870d6344cd25ff1b0465997bb7c93e93` | `f993bee9659b6b9ba16c9a83fc0483fb1ed4afd1` | `production-ready-final` |
| `truongnguyen3006/ecommerce-frontend-1-` | `5b3be4d6dfb0a588df11d3c5160479b7a1496fdd` | `a68720c7d87dc239e96f3483b9a0c6a4b15158ea` | `production-ready-final` |

The preceding Phase A checkpoints were already persisted: backend `c869fa8184b858f639fa2bf43f9f2e1b1579686f` and frontend `7c3cbb3c0b13627b06a7de1e67215545c25c4d28`. They remain ancestors of all work here. On resumption, the old transient working trees were unavailable, so genuine Git histories were checked out at the current remote heads. Completed Batch 4/5/6 source commits were preserved; no synthetic baseline, history rewrite or restart of those implementations was published.

| Protected ref | Preserved SHA |
|---|---|
| Backend `project1-recovery` | `58e825aa60baaff3bebfe46636e718a1331400d6` |
| Backend `main` | `23bafa4da55ffe5cbb0ed303cd5840c22cbfe2ea` |
| Frontend `batch2-frontend` | `327094404da564b8a4084553fa5cef780e39912a` |
| Frontend `main` | `2c5fff53eb7acd2c48f5176947fb53a3adb5c8bd` |

Fresh remote-ref verification after the final CI completion confirmed all four protected refs still match the table and both working refs match the final source SHAs. Publication used the actual remote parent and Git tree, compared the staged tree with the connector-created tree, and advanced only `production-ready-final` without force. Local updates used fetch and fast-forward only. No merge into main/base, rebase, reset, destructive owner database action, release workflow dispatch or provider deployment occurred.

# 3. Batch 4 Results

## H04 — aged saga and dead-letter recovery

**Original risk:** a missing/dead-lettered result could leave a PENDING order and prior deductions stranded; Redis saga evidence expired and no bounded recovery procedure existed.

**Implementation:** nullable `inventory_outcome` on immutable order/SKU lines supplies durable SQL receipts. Order locking serializes result decisions and compensation outbox intents. Unknown is retained as unknown. A bounded scan of old workflows retries unknown checks with the same order/SKU identities; five attempts with exponential 20–300 second backoff end in explicit investigation. COD replay retains the original order. Online attempts retain their payment fence. SQL dead-letter records identify topic/partition/offset without exception/provider payloads. If that record cannot be saved, the dedicated DLT container stops and retains the broker record; it does not create DLT.DLT. ADMIN retry is limited to safe PENDING inventory work and cannot reopen terminal orders, clear a payment fence or perform a refund.

**Files:** `order-service/.../service/OrderService.java`, `WorkflowDeadLetters.java`, `config/KafkaConsumerConfig.java`, `controller/OrderController.java`, `dto/OrderResponse.java`, `model/Order.java`, `OrderLineItems.java`, `repository/OrderRepository.java`, `V5__durable_saga_recovery.sql`; matching frontend order types/status/actions and `docs/batch4-correctness.md`. Section 15 supplies exact repository-relative paths.

**Invariants:** SQL deduction proof survives Redis loss; compensation and terminal guards remain coupled to authoritative evidence; paid/cancelled/compensated workflows are not blindly reopened or restocked. Safe pending replay does not invent a new deduction identity. Unproven failure cannot be treated as false deduction.

**Tests:** `OrderBusinessTests` cover durable proof after Redis loss/service reconstruction, aged retries and budget exhaustion, completed/paid/cancelled guards, unknown legacy failure, stale receipts, safe failure compensation and dead-letter recording without business mutation. `KafkaConfigurationTests` check DLT configuration; migration and outbox transaction tests validate persistence/rollback. Runtime coverage and its remaining DLT boundary appear in sections 9 and 11.

**Limitations/status:** **MITIGATED**. Explicit financial investigation is an intended terminal policy. A real broker-driven deserialization/DLT failure and operator recovery are not implied by mock/configuration tests. Restored deduplication evidence must be retained; no autonomous financial reconciliation or refund accounting was added.

## H05 — permanent retired SKU identity

**Original risk:** deleting a catalog variant freed its live SQL uniqueness while persistent Inventory still retained the old SKU, allowing another product to inherit historical stock or compensation.

**Implementation:** permanent `sku_identity` reservations survive catalog deletion; current variants and retained INIT outbox keys are backfilled. Deletion/replacement retires reservations in the same transaction. New identities also require a ready Inventory lookup to return 404; existing stock blocks historical pre-outbox reuse and a dependency outage fails closed. The unique SKU primary key arbitrates concurrent claims. ADMIN receives stable, specific identity conflict messages.

**Files:** product `service/SkuIdentityService.java`, `ProductService.java`, new `V4__permanent_sku_identity.sql`, `application-prod.properties`, product migration/business/outbox tests; frontend `ProductEditor.tsx`, `api-error.ts`, unit/flow fixtures/tests and API contract documentation.

**Invariants:** a retired code cannot silently identify another product; reservations never cascade with product rows; duplicate INIT never resets/inflates existing stock; compatible live variants remain usable.

**Tests:** product tests prove permanent reservation, retained-INIT backfill, retirement, legacy Inventory collision, dependency outage and transaction rollback. Frontend tests show the specific conflict. The MySQL 8.4.10 probe verifies live/retired backfill and uniqueness; final startup verifies the actual migration under Flyway/Hibernate.

**Limitations/status:** **FIXED** for the code-reuse defect. A damaged or empty restored Inventory store cannot establish that a historical code never existed. Owner legacy-copy validation and preservation of changelogs remain required.

## M01 — expiry-aware VNPay attempts

**Original risk:** a PENDING row reused an expired/abandoned URL indefinitely.

**Implementation:** one existing SQL row/reference per order remains authoritative; a new URL has a 15-minute expiry. Legacy attempts without proven expiry fail closed. Expired URLs are suppressed and marked `EXPIRED_RECONCILIATION_REQUIRED`. No automatic fence release, stock restoration or reference rotation follows from expiry. Repeated active creates reuse the same attempt; success cannot be replaced by pending. Old references remain recognizable, and signed late receipts use the existing Order acceptance/accounting handshake. Receipt delivery retries are bounded to five before reconciliation. Frontend reflects backend attempt/expiry/reconciliation/retry availability and does not infer money from a query string.

**Files:** payment `service/PaymentService.java`, `repository/PaymentTransactionRepository.java`, `model/PaymentTransaction.java`, `dto/PaymentTransactionResponse.java`, `V5__payment_attempt_expiry.sql`, `PaymentAttemptTests.java`, `PaymentCallbackTests.java`; Order event/reference guards; frontend payment API/types and checkout/waiting/order action/status code/tests.

**Invariants:** at most one recognized attempt per order; expiration is not proof of no settlement; old/stale references cannot bypass the newer fence; no paid + cancelled + restored-stock outcome is introduced.

**Tests:** attempt metadata/reuse/expiry/legacy handling, success/reconciliation refusal, bounded delivery retry, late receipt and stale-ref guards, confirmed failure compensation, cancellation fence and frontend expired-state tests pass in the full gate.

**Limitations/status:** **MITIGATED**. Safe automatic same-order repayment after an expired unresolved attempt is deliberately unavailable. An operator must reconcile settlement/accounting evidence before any separate business action. Live settlement is NOT VERIFIED.

## M02 — read-only Return and authoritative IPN

**Original risk:** browser Return shared a mutating callback handler with IPN and returned inconsistent provider acknowledgement codes.

**Implementation:** Return validates checksum/merchant/reference/amount/shape and navigates to the existing waiting page without SQL/outbox mutation. IPN independently authenticates and records the provider receipt; rollback precedes a processing-failure acknowledgement. Duplicate parameters are rejected. Protocol responses are `00` newly recorded, `02` already recorded/incompatible terminal state, `01` unknown reference, `04` amount mismatch, `97` invalid checksum/merchant and `99` malformed/unavailable processing, following the [official VNPay protocol](https://sandbox.vnpayment.vn/apis/docs/thanh-toan-pay/pay.html).

**Files:** payment `controller/PaymentController.java`, `service/PaymentService.java`, callback/attempt tests; frontend payment response types, waiting page and flow tests; `docs/batch4-correctness.md`.

**Invariants:** browser navigation cannot mark payment successful; provider receipt is distinct from accepted Order SUCCESS; terminal/cancelled/compensated orders cannot be revived by a stale callback; repeat IPN cannot duplicate stock/money effects.

**Tests:** valid/failed/duplicate/invalid-signature/invalid-amount/unknown-reference callbacks, Return before/after IPN, terminal reconciliation, rollback on error and stale-reference/cancellation scenarios. Runtime mock callback evidence appears in section 10.

**Limitations/status:** **FIXED** for Return/IPN separation and tested acknowledgement contract. Deterministic signatures do not prove actual provider acceptance, delivery or settlement.

# 4. Batch 5 Results

## M03 — stable domain codes and accurate client translation

**Original risk:** unrelated 409/integration errors were described as inventory conflicts.

**Implementation:** `DomainException` adds stable action codes; safe error responses suppress provider bodies and production stack traces. Frontend resolves codes before generic status fallbacks. Cloudinary configuration errors, identity conflicts, stale revision, stock and payment fences have distinct messages; generic 409 no longer implies insufficient stock.

**Files:** common `exception/DomainException.java`, `GlobalExceptionHandler.java`, `ErrorContractTests.java`; relevant product/user/cart/inventory service/controller errors; frontend `src/lib/api-error.ts`, `ProductEditor.tsx`, unit tests and `docs/API_CONTRACT.md`.

**Invariants/tests:** safe stable code/status response tests cover representative 400/401/403/404/409/413/5xx and integration/configuration conflicts. Frontend tests prefer codes and preserve a safe unknown-error fallback. No secret or arbitrary upstream response is returned.

**Limitations/status:** **FIXED** for the scoped mapping; unrecognized future codes intentionally use a generic safe fallback.

## M04 — identity-provider/token failure classification

**Original risk:** outage/client configuration/malformed tokens resembled bad credentials or expired refresh.

**Implementation:** distinguish invalid login/refresh (401), determinate disabled user/forbidden access (403), and provider outage/configuration/malformed response (503). Valid token responses require nonblank access/refresh tokens and positive expiry. Transient frontend refresh failure preserves the account; invalid grant/disabled account clears it. Existing refresh coordination and shared read behavior remain; mutations are never automatically replayed.

**Files:** user `AuthProviderErrors.java`, `KeycloakService.java`, `AuthClassificationTests.java`, `AuthController.java`; frontend `axiosClient.ts`, `authApi.ts`, login page, error mappings and unit/flow tests.

**Invariants/tests:** malformed/upstream failure fixtures and representative codes pass; real Axios interceptor tests prove transient session preservation, invalid-session clearing and one refresh coordinator. Actual disposable Keycloak login/refresh/logout/disable tests also pass separately.

**Limitations/status:** **FIXED** within determinate provider information. Existing issued JWTs are validated locally until expiration; account disable is not newly represented as instantaneous distributed token revocation.

## M05 — stock lookup by rendered row SKU

**Original risk:** later paginated variant rows displayed unrelated first-page stock.

**Implementation/files:** each rendered variant resolves its own SKU through the existing Inventory query in `src/components/admin/ProductEditor.tsx`; matching flow fixtures/tests cover products, page changes and row order independently of unrelated query-array indexes.

**Invariants/tests:** displayed and edited stock belongs to the actual row SKU. Pagination regression tests pass in both desktop/mobile projects.

**Limitations/status:** **FIXED**. Inventory remains eventually consistent after accepted asynchronous commands; no instant stock-application promise was added.

## M06 — conservative facet normalization

**Original risk:** stored whitespace/case and query trimming caused inconsistent facets.

**Implementation:** strip only outer ASCII spaces; use lower-case comparison keys (Java `Locale.ROOT`), preserving displayed case, accents and interior spacing. Writes, filtering, labels and client matching share that policy. SQL trim/lower queries support historical values without rewriting owner rows. Case-equivalent labels collapse; semantic interior spacing remains distinct.

**Files:** product `FacetValues.java`, `ProductService.java`, `ProductRepository.java`, `ProductSpecifications.java`, search/business tests; frontend `facets.ts`, `product-filters.ts`, product pages/Header and unit tests.

**Invariants/tests:** normalization/collision/blank/legacy filtering fixtures pass; SKU identity is unaffected. Empty category may still clear a category under the existing API.

**Limitations/status:** **FIXED** for the inconsistent policy. Existing database collation can additionally affect comparisons; no accent-removal or historical rewrite migration was authorized.

## M07 — revision-checked product replacement

**Original risk:** stale full-variant edits silently overwrote newer data or deleted another admin's added SKU.

**Implementation:** additive nonnegative product revision; require the editor's observed revision. Lock the parent row and compare before modifying catalog, children, SKU reservations or outbox. Successful update increments once; stale updates return `PRODUCT_REVISION_CONFLICT` without side effects. Frontend retains the editor's original revision across background refetches and asks for review/refresh on conflict.

**Files:** product request/response/model/repository/service, `V5__checked_catalog_revision.sql`, product business/outbox/migration tests; frontend product types/editor/error mapping and unit/flow tests.

**Invariants/tests:** H2 concurrent writers yield one winner; stale replacement commits no changes or events. Fresh MySQL runtime rejects the stale second update. Frontend conflict tests preserve the earlier observed revision.

**Limitations/status:** **FIXED**. Non-browser callers must include the revision; the contract is documented. Database-level concurrency under owner workloads is not inferred from deterministic fixtures.

## M08 — atomic owner-scoped purchased-cart cleanup

**Original risk:** a separate quantity check and delete could remove a newer cart mutation.

**Implementation:** each mutation changes a per-line revision, including changes back to the same quantity. One Redis Lua snapshot reads quantity/revision; one conditional Lua cleanup checks both and records an idempotent cleanup receipt. It removes only purchased lines of the authenticated owner after verifying an accepted owned order and exact SKU/quantity. Missing legacy snapshot revisions are retained. The legacy asynchronous checkout shares this cleanup and retains cart data on failed/unaccepted checkout.

**Files:** cart `CartService.java`, `CartController.java`, `CartItemEntity.java`, `PurchasedCartRequest.java`, `PurchasedOrderClient.java`, prod URL, business/order-client/`RedisCartAtomicTests`; common `CartLineItem.java`; frontend cart API/checkout store/page/types and tests.

**Invariants/tests:** actual native Redis has five tests for owner scope, idempotency, changed-back revision, mutation/cleanup concurrency, failed acceptance and AOF restart. Purchased Order JWT/owner/quantity checks and client revision tests pass. Runtime business/restore proof is reported separately.

**Limitations/status:** **FIXED** for the atomic cleanup defect. Existing cart TTL remains 24 hours and receipts have a seven-day boundary; this is not indefinite cart archival. SQL/Kafka durability does not replace Redis recovery.

## M09 — at most one default address per owner

**Original risk:** concurrent first/default changes produced multiple defaults.

**Implementation:** lock the owner user row for every address mutation; clear/flush previous flags before setting the new default. Add a nullable generated owner key plus unique index. Legacy migration retains the lowest-ID default, changing only duplicate flags. Default deletion flushes first and then selects the lowest remaining ID. The Batch 6 response getter explicitly emits `isDefault` only.

**Files:** user service/repositories, `V3__one_default_address.java`, response getter, migration/ownership/concurrency/serialization tests; existing frontend address contract remains unchanged.

**Invariants/tests:** concurrent defaults, bypass uniqueness, reviewed legacy duplicate repair, deterministic deletion and owner scope pass. `AddressContractTests` protects the JSON name. Fresh MySQL business checks verify one default after replacement.

**Limitations/status:** **FIXED**. Owner legacy migration has not been applied; rehearsal on a backed-up copy remains required.

## M10 — recoverable Keycloak/SQL provisioning

**Original risk:** remote creation followed by SQL/role failure stranded an unusable identity or unsafe retry adoption.

**Implementation:** public registration retains a stable idempotency key and durable SQL provisioning intent before provider work. The intent reserves normalized username/email and stores a salted PBKDF2 fingerprint, never the password/token. A locked retry requires exact username/email, admin-only `project1ProvisioningId` and any recorded provider ID before USER grant. SQL profile and intent completion commit together. Failed create/role/SQL work leaves safe observable reconciliation metadata. No existing identity is deleted or adopted. Production user-profile policy is checked before identity creation. Batch 6 preserves managed core fields and attributes on partial Admin PUT updates.

**Files:** `ProvisioningService.java`, `KeycloakService.java`, `UserService.java`, controllers/repository, `V4__recoverable_user_provisioning.sql`, production realm/profile configuration, identity/concurrency/ownership tests and `KeycloakLifecycleIT.java`; frontend registration idempotency/API and flow tests.

**Invariants/tests:** failed SQL/role retries, exact-origin mismatch and pre-existing identity protection pass in fixtures. Five real Keycloak 26.8.0 tests include provider-created/SQL-failed retry to the same ID, duplicate/exact lookup, roles, partial profile preservation and login lifecycle. CI business registration repeats retain the identical SQL/Keycloak ID.

**Limitations/status:** **MITIGATED**. Recovery uses the same request key/body and retains durable evidence; an abandoned key may require operator reconciliation. Existing owner realms must append the reviewed managed marker policy; imports do not overwrite them. Legacy Keycloak 18 policy/upgrade and owner grants are NOT VERIFIED. No password-storing background recovery worker was added.

## M11 — stable admin stock operation identity

**Original risk:** retrying an uncertain accepted stock mutation applied it twice.

**Implementation:** require a stable ID; first claim the immutable payload on operation-ID repartition in a persistent store, then forward into existing SKU processing. Kafka `exactly_once_v2` couples claim/forward and stock/result respectively. Store terminal APPLIED/REJECTED; duplicate same payload is safe and a conflicting payload cannot reuse an ID. ADMIN-only status distinguishes accepted/pending from terminal results. Frontend stores unresolved owner/SKU/body/ID in session storage, guards submit, locks uncertain inputs, reopens after reload and polls with bounds before allowing manual check/retry.

**Files:** Inventory topology/service/controller/security, `StockOperation.java`, topology/controller tests; common `InventoryAdjustmentEvent.java`; frontend `stock-operation.ts`, Inventory API/editor/types, unit/flow fixtures/tests.

**Invariants/tests:** duplicate/conflicting/rejected operations, global ID before SKU repartition, no negative stock, restored committed driver state and ADMIN status boundaries pass. Frontend retains the uncertain ID/body and shows terminal success only. Real broker/process/restore evidence is in sections 9/11/13.

**Limitations/status:** **FIXED** for retry identity. Queries assume the existing single Inventory instance; multi-replica owning-task routing is outside this task. Streams driver restoration alone is not live Kafka recovery proof.

# 5. Database Migrations

| Batch | Exact new migration path | Purpose |
|---|---|---|
| 4 | `order-service/src/main/resources/db/migration/V5__durable_saga_recovery.sql` | Nullable line deduction receipts, aged-workflow retry/investigation metadata and unique SQL workflow dead-letter records. |
| 4 | `product-service/src/main/resources/db/migration/V4__permanent_sku_identity.sql` | Permanent SKU registry/backfill from current variants and retained INIT intents. |
| 4 | `payment-service/src/main/resources/db/migration/V5__payment_attempt_expiry.sql` | Attempt creation/expiry and bounded receipt-delivery metadata compatible with existing rows. |
| 5 | `product-service/src/main/resources/db/migration/V5__checked_catalog_revision.sql` | Nonnegative catalog revision for checked updates. |
| 5 | `user-service/src/main/java/db/migration/V3__one_default_address.java` | Preserve address rows/text, retain lowest-ID default among legacy duplicates; generated nullable owner key and unique default-owner index. |
| 5 | `user-service/src/main/resources/db/migration/V4__recoverable_user_provisioning.sql` | Durable provisioning intent, exact identity/key reservations, safe request fingerprint/state/attempt metadata. |

All changes extend the existing schema/history. **All 15 migration files present at backend `2f98e838870d6344cd25ff1b0465997bb7c93e93` remain byte-identical.** These include every original V1/V2 and prior transactional-outbox/payment-decision migration. No owner's history/checksum was repaired, no schema was dropped/reset, and production retains Flyway validation, disabled automatic baseline/clean and Hibernate `validate`.

Fresh CI startup exposed an incompatibility in this task's new, unreleased product V4. The primary MySQL rules require matching fractional precision on DATETIME/default timestamps and rejected the correlated target-table subquery used by the new V4. The migration now uses `DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6)` and a top-level anti-join. Only disposable task fixtures had attempted it; no owner database had applied it. This correction does not alter any migration inherited from the starting checkpoint. A separate owned MySQL 8.4.10 probe applies new V4/V5 to fresh V1/V3 plus live/retired INIT fixtures, checking backfill, uniqueness and the nonnegative revision constraint.

Fresh MySQL 8.4.10 was actually exercised: all production Flyway histories started from an empty fixture, Hibernate validation passed and all 20 services became healthy. Real MySQL address-default replacement, SKU INIT at quantities 12/8, checked catalog update and stale revision 409 passed. The standalone V4/V5 backfill/unique-SKU/nonnegative-revision probe also passed. Final runtime conservation evidence appears below; owner legacy-copy adoption remains outside these fresh fixtures.

H2 migration/concurrency fixtures and fresh MySQL do not prove adoption of the owner's legacy rows. Backup and rehearse a legacy COPY, compare legitimate identities/values/constraints/history, and review any intentional baseline before enabling production. Rollback does not remove additive migrations or reverse money; old images must be compatible with the new status/contract.

# 6. Backend Tests

Required compiler target remained Java 24; it was not downgraded. Local toolchain: Temurin 24.0.2+12, Maven 3.9.9. CI uses Temurin 24.0.2 and Maven on Ubuntu 24.04.

```bash
mvn clean verify
# Equivalent final local invocation, logging only:
mvn -B -ntp -Dlogging.level.root=ERROR -Dspring.main.banner-mode=off clean verify

# Explicit native provider suite, separate from default *Tests discovery:
python3 .github/scripts/run-keycloak-lifecycle.py /path/to/keycloak-26.8.0.tar.gz
# The guarded launcher runs:
mvn -pl user-service -am -Dtest=KeycloakLifecycleIT -Dsurefire.failIfNoSpecifiedTests=false test

# Targeted real HTTP / signed token boundary:
mvn -pl cart-service -am -Dtest=CartSignedTokenBoundaryTests -Dsurefire.failIfNoSpecifiedTests=false test
```

| Gate | Reactor modules | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|---:|
| Preserved Batch 4 | 12 successful | 111 | 0 | 0 | 0 |
| Preserved Batch 5 | 12 successful | 146 | 0 | 0 | 0 |
| Published initial Batch 6 / MySQL correction / diagnostic checkpoint | 12 successful | 147 | 0 | 0 | 0 |
| Subject/HTTP/routing/Redis-launcher checkpoints | 12 successful | 148 | 0 | 0 | 0 |
| Final local exact-source gate after Cart outage fix | 12 successful | 150 | 0 | 0 | 0 |
| Actual owned Keycloak 26.8.0 `KeycloakLifecycleIT` (opt-in, separate) | 4 selected successful | 5 | 0 | 0 | 0 |

| Module | Tests | Failures / errors / skipped |
|---|---:|---|
| common-dto | 3 | 0 / 0 / 0 |
| common-events | 6 | 0 / 0 / 0 |
| product-service | 22 | 0 / 0 / 0 |
| order-service | 31 | 0 / 0 / 0 |
| inventory-service | 13 | 0 / 0 / 0 |
| api-gateway | 6 | 0 / 0 / 0 |
| notification-service | 5 | 0 / 0 / 0 |
| user-service | 21 | 0 / 0 / 0 |
| payment-service | 20 | 0 / 0 / 0 |
| cart-service | 23 | 0 / 0 / 0 |
| discovery-server | 0 | Build SUCCESS, no test class |
| root aggregator | 0 | Reactor SUCCESS |
| **Total** | **150** | **0 / 0 / 0** |

Source gates run from an exact copied source tree in an owned temporary directory to avoid workspace synchronization inserting generated `.rsync-tmp` class directories. No fake class was excluded, no assertion was weakened and no test was intentionally skipped. Five atomic-cart tests launch an actual owned loopback Redis 7.0.15 process and AOF directory; they do not attach to existing Redis.

Initial Batch 6 added the `isDefault` serialization regression (147 tests). `CartSignedTokenBoundaryTests` adds an actual servlet HTTP/RSA-signed JWT/private JWKS fixture with the production management chain active: anonymous 401, valid owner 200, foreign-owner 403 and invalid issuer 401. Only the domain CartService is mocked. That focused fixture does not alone establish the full Keycloak/Gateway/Redis production stack.

CI proof includes [initial Batch 6 run 37150459787](https://github.com/truongnguyen3006/ecommerce-backend-1-/actions/runs/37150459787), [MySQL correction run 37151682296](https://github.com/truongnguyen3006/ecommerce-backend-1-/actions/runs/37151682296), and [diagnostic run 37169327989](https://github.com/truongnguyen3006/ecommerce-backend-1-/actions/runs/37169327989). Their verify jobs passed the 147-test default suite, native 5-test Keycloak fixture and script/Compose checks. The last two also passed the MySQL probe. Subject-claim checkpoint run [37170334546](https://github.com/truongnguyen3006/ecommerce-backend-1-/actions/runs/37170334546), verify job `111341844032`, passed all 12 modules / 148 default tests / 0 failures/errors/skips, the 5 native Keycloak tests including exact USER/refreshed/ADMIN subjects, scripts/Compose and MySQL 8.4.10 probe. Local full clean verify after the subject/HTTP fixes passed 148 in 01:25 min; native provider after-fix Maven suite passed 5 in 22.569s. The final routing correction was followed by another full exact-source local `clean verify`: 148 tests, all 12 modules successful, 0 failures/errors/skips.

The final routing checkpoint [CI run 37171369905](https://github.com/truongnguyen3006/ecommerce-backend-1-/actions/runs/37171369905), verify job `111344813527`, passed **148 default tests / all 12 modules / 0 failures/errors/skips** (Maven 01:24 min), **5 native Keycloak tests / 0 failures/errors/skips** (Maven 20.541s), all shell/Python syntax and Compose checks, and the disposable MySQL 8.4.10 migration/backfill/default-precision/identity/revision probe.

The subsequent read-only convergence probe [CI run 37172321026](https://github.com/truongnguyen3006/ecommerce-backend-1-/actions/runs/37172321026), verify job `111347632563`, again passed **148 default tests / all 12 modules / 0 failures/errors/skips** (Maven 01:23 min), **5 native Keycloak tests / 0 failures/errors/skips** (Maven 20.367s), syntax/Compose checks and MySQL 8.4.10 probe. This is after the latest probe change; application source/configuration is unchanged from the routing fix.

The Redis/DLT checkpoint [CI run 37173526526](https://github.com/truongnguyen3006/ecommerce-backend-1-/actions/runs/37173526526), verify job `111351265493`, passed the same **148 default tests / 12 modules / 0 failures/errors/skips** (Maven 01:27 min), native **5 Keycloak tests / 0 failures/errors/skips** (Maven 23.013s), syntax/Compose and MySQL probe. The added actual-MySQL DLT assertions **passed**: production SQL (read from the unchanged Java class) inserts evidence, retains the original duplicate row, supports a nullable order number and propagates a NOT NULL failure without adding a row. No DLT implementation change was needed; this is SQL-engine evidence, not a poisoned-broker/operator recovery claim.

The four production downstream defaults now match the existing private Compose DNS/ports: Cart→Product 8083, Cart→Inventory 8082, Cart→Order 8086 and Order→Product 8083. Environment overrides and fail-closed handling are retained. No public route, security rule or application contract was added.

After this latest Cart boundary fix, local `clean verify` passed all **150 tests / 12 modules / 0 failures/errors/skips**; the final run took 01:20 min. The two additional Cart regressions cover timeout/connection classification and retention of unexpected errors.

Post-fix [CI run 37174907967](https://github.com/truongnguyen3006/ecommerce-backend-1-/actions/runs/37174907967), verify job `111355419620`, also passed **150 default tests / all 12 modules / 0 failures/errors/skips**, **5 native Keycloak tests / 0 failures/errors/skips**, syntax/Compose and both SKU/DLT MySQL 8.4.10 probes. The full reactor took **01:32 min**; the separate native provider Maven run took **21.539s**.

Earlier non-passes are retained: a generated-class synchronization artifact was resolved by exact-source isolation; initial Keycloak enum/profile and address JSON contract tests identified defects subsequently fixed; initial Docker/MySQL and cart-runtime failures are described below. A prior apt update was unavailable under native process UID restrictions; scoped package downloads supplied a task-owned Redis executable. No failed attempt is counted as a passed gate.

# 7. Frontend Tests

```bash
npm ci
npm run lint
npm run build
npm run test:unit
npm run test:e2e
npm run test:responsive
```

| Checkpoint | Unit | Flow | Responsive | Total | Installation/lint/build |
|---|---:|---:|---:|---:|---|
| Batch 4 `9466155c8daeb69c856a5125598d576b0a7ad5c7` | 15 | 46 | 5 | 66 | PASS |
| Batch 5 `fcfad9b859b57dedc3f644c7cbede7ea4ad29b4b` | 20 | 54 | 5 | 79 | PASS |
| Final frontend `a68720c7d87dc239e96f3483b9a0c6a4b15158ea` | 20 | 54 | 5 | 79 | PASS |

Original exact-head CI: [run 37150401645, attempt 1](https://github.com/truongnguyen3006/ecommerce-frontend-1-/actions/runs/37150401645/attempts/1), verify job `111282945488`: **20 unit passed (2.0s), 54 flow passed (40.2s), 5 responsive passed (48.8s)** with the canonical Playwright Chromium installation. Flow tests are 27 desktop and 27 mobile scenarios. The image job `111283612185` passed standalone image build/health. A second frontend mutation was unnecessary: all resumed fixes are backend/probe changes and the paired frontend code remains the same tested SHA.

After the Redis launcher correction, the full final frontend CI was rerun on the exact unchanged SHA: [run 37150401645, attempt 2](https://github.com/truongnguyen3006/ecommerce-frontend-1-/actions/runs/37150401645/attempts/2). Verify job `111352293299` passed `npm ci`, lint, build, **20 unit (1.7s), 54 flow (36.0s), 5 responsive (43.9s)** — **79 total**. Image job `111352745882` also passed build/non-root/standalone health. The frontend branch was not changed to obtain this result.

After the latest Cart error-classification fix, **final attempt 3** was run on the same frontend SHA: [run 37150401645, attempt 3](https://github.com/truongnguyen3006/ecommerce-frontend-1-/actions/runs/37150401645/attempts/3). Verify job `111355776844` passed `npm ci`, lint, build, **20 unit (2.5s), 54 flow (47.7s), 5 responsive (57.2s)** — **79 total**, with no failed/skipped tests. Image job `111356401329` passed production standalone build and health; the run completed SUCCESS at **2026-10-04 03:53 UTC**. This is the final frontend regression evidence after the latest backend fix.

After the final production-routing fix, the unchanged frontend was rechecked with `npm ci`, `npm run lint`, `npm run build` and `npm run test:unit`: all passed, **20 unit tests (2.4s)**. This repeats the auth/payment/error/idempotency contracts after the latest fix without recreating the completed frontend implementation. The final canonical 54-flow/5-responsive suites also passed in attempt 3 on the same frontend SHA above.

Local full-suite evidence also passed all 79 tests with `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH=/path/to/chrome-headless-shell npm test -- --workers=1`, Chrome Headless Shell 154.0.8037.92. The alternate browser addressed a local Unix-socket limitation; canonical CI browser tests independently passed. Two earlier parallel UI runs each had a transient timeout; the complete serial run passed with the original assertions. Tests use controlled API/provider fixtures, not live provider accounts.

# 8. Docker / Runtime Validation

Docker/Podman was unavailable in the native workspace. The existing GitHub CI runs Docker on disposable runners; fixture secrets are generated, guarded by exact SHA/project names, and never committed. VNPay merchant is `FIXTURE1`, with an unroutable `https://provider.example.test/payment` URL which is never contacted. No application image was published or deployed.

Built production images: **nine Java applications** (`api-gateway`, `discovery-server`, `product-service`, `inventory-service`, `cart-service`, `order-service`, `payment-service`, `user-service`, `notification-service`), optimized **Keycloak 26.8.0**, **Nginx reverse proxy** and the pinned **standalone frontend** — **12 images**. CI checks application users `10001:10001`, Keycloak `1000` and ingress `nginx`.

The production Compose model has 20 services: those applications plus MySQL, dedicated Keycloak MySQL, Keycloak, Redis, Kafka, Schema Registry, frontend, Zipkin, Prometheus, Grafana and ingress. `docker compose --env-file .env.production.example -f docker-compose.prod.yml config --quiet` and shell/Python syntax gates pass. Fixture resource overrides are for empty CI capacity only; they are not a host-sizing guarantee.

| Attempt | Actual result |
|---|---|
| Batch 5 CI `37147551039` | Java verify passed (146). Image builds passed, but Keycloak startup rejected the invalid realm-profile enum. Business/restore not run. |
| Initial Batch 6 CI `37150459787` | Java 147/native Keycloak 5 and images passed; Keycloak now healthy; fresh product Flyway V4 startup failed. Business/restore not run. |
| MySQL correction CI `37151682296` | Java 147/native Keycloak 5/MySQL probe and all 12 images passed; **all 20 containers reached health/readiness and read-only smoke passed**. Registration retry retained identity; cart cross-owner request returned 401 instead of required 403, stopping business/restore. |
| Diagnostic CI `37169327989` | Java 147/native Keycloak 5/MySQL/images/full health/smoke passed. Actual Gateway and direct Cart owner reads both returned 401 BUSINESS_ERROR. The new subject assertion reproduced missing USER/ADMIN `sub` in the real native provider (5 tests, 2 failures, 0 errors/skips); adding default `basic` made all five pass. This failure was not reclassified as an ownership PASS. |
| Subject-claim correction CI `37170334546` | Verify 148/native Keycloak 5/MySQL/images/full health/smoke passed. Registration identity, USER/ADMIN/owner boundaries, default addresses and SKU/revision checks passed. Cart POST then returned 503 because Cart/Order inherited loopback downstream addresses; Cart also used the Product port for Order. No subsequent fault or restore scenario ran in that attempt. |
| Production-routing correction CI `37171369905` | Verify 148/native Keycloak 5/MySQL/images/full health/smoke passed. **Nine business checks passed**, including COD/cart-revision cleanup, read-only Return/signed IPN, cancellation/partial-rejection compensation, stable ADMIN operation retry and Kafka outage/Order restart/outbox recovery. The immediate callback after Payment restart received Gateway 503; Gateway logged `No servers available for service: payment-service`. Inventory/Redis restart, event replay and backup/restore were not reached. |
| Read-only convergence probe CI `37172321026` | Verify 148/native Keycloak 5/MySQL/images/full health/smoke passed. Nine named checks passed again. Subsequent assertions also passed for the restarted Payment callback (00/02, SUCCESS/COMPLETED) and Inventory SIGKILL restoration (stock A12/B6, duplicate ADMIN operation unchanged). The combined tenth check stopped at Redis: exit-1 restart loop before AOF/cart comparisons; event replay/backup/restore were not reached. |
| Redis/DLT correction CI `37173526526` | Verify 148/native Keycloak 5/MySQL/SKU/DLT/images/full health/smoke passed. Redis restarted and reloaded AOF successfully. After Payment/Inventory assertions, immediate Cart read hit `QueryTimeoutException` while its client reconnected and returned 500. Nine named checks passed; the tenth combined check, replay and backup/restore were not completed. |
| Final runtime checkpoint | **PASS** — [CI run 37174907967](https://github.com/truongnguyen3006/ecommerce-backend-1-/actions/runs/37174907967), source `abf95e57e0f8fee618db7fcf8a7798e377ea1630`, verify job `111355419620` and images/runtime job `111355915459` both SUCCESS. All 12 image builds, 20-service startup, smoke and **13/13 named business/fault/replay/backup/restore checks passed**. |

The final images/runtime job completed successfully at 2026-10-04 04:05 UTC. The initial stack and the isolated restored stack both reached production Compose readiness. The paired frontend checkout was `a68720c7d87dc239e96f3483b9a0c6a4b15158ea`. The following are exact assertion-completion markers from job `111355915459` on 2026-10-04 (UTC); each marker is printed only after the corresponding checks pass.

| # | UTC completion | Exact completed check | Result |
|---:|---|---|---|
| 1 | 03:57:14.1120883 | registration retry retains exact fixture SQL/Keycloak identity | **PASS** |
| 2 | 03:57:17.1321887 | real USER/ADMIN boundaries and cart ownership; internal gateway route denied | **PASS** |
| 3 | 03:57:17.7498272 | fresh MySQL address invariant and atomic default replacement | **PASS** |
| 4 | 03:57:22.3139570 | fresh MySQL migrations/Hibernate startup, SKU INIT and stale revision guard | **PASS** |
| 5 | 03:57:30.5391025 | COD completes once; changed cart revision and newly added line survive cleanup | **PASS** |
| 6 | 03:57:39.2278720 | mock Return is read-only; signed IPN/duplicate acknowledgement and paid cancellation fence | **PASS** |
| 7 | 03:57:53.6992154 | multi-SKU cancellation and partial inventory rejection restore only actual deductions once | **PASS** |
| 8 | 03:58:01.1700407 | admin retry adjusts once; excessive deduction rejected without negative stock | **PASS** |
| 9 | 03:59:54.2259260 | Kafka outage delays outbox; active order restart and broker recovery complete without second deduction | **PASS** |
| 10 | 04:01:42.9605049 | active payment restart/callback retry, inventory SIGKILL/RocksDB recovery and Redis AOF restart | **PASS** |
| 11 | 04:01:52.1715860 | duplicate stable INIT/CHECK/compensation delivery conserves stock; all business outbox rows publish | **PASS** |
| 12 | 04:02:14.8702199 | coordinated business/Keycloak SQL dumps plus stopped Redis/Kafka/Streams snapshots and checksums | **PASS** |
| 13 | 04:05:05.9450885 | isolated restore conserves owners/SKUs/totals/payment refs/outbox IDs/cart revisions/operation IDs; retry remains idempotent | **PASS** |

The read-only production smoke passed at **03:57:11 UTC**. The final completion marker was **04:05:05 UTC**. No application image was published. Both disposable projects were cleaned up by their owned-project guards.

Liveness remains independent of remote dependencies; readiness checks the configured issuer metadata and the owning SQL/Redis/Kafka/Registry/Streams dependencies. Management port 9091 and private data networks stay unexposed; ingress remains the only intended edge. Generated CI fixtures bypass no JWT/ownership/production Flyway validation. Smoke uses internal HTTP with fixture host/proxy headers; owner TLS/DNS, real ingress certificate/forwarding behavior and public WebSocket continuity were not exercised.

# 9. Fault Injection / Recovery Tests

The routing-correction attempt proved nine business checks before a discovery-convergence gap after Payment restart. Container readiness does not imply Gateway registry convergence. The updated probe uses a bounded authenticated read of the exact PENDING payment/reference before delivering its first callback, and reads exact SUCCESS references before restored duplicate callbacks. It does not automatically replay a financial mutation or accept a 503 as a PASS. Required 00/02 callback assertions remain unchanged.

The next attempt reached and passed the Payment callback and Inventory SIGKILL/stock/operation assertions, then Redis entered an exit-1 restart loop. The launcher reused a fixed redis-owned file in sticky `/tmp`; it now creates a fresh private 0600 configuration for every start. That ownership/kernel explanation is an inference from the launcher and observed restart failure: Redis logs were absent from the first failure collector, and native UID/kernel restrictions prevented an equivalent reproduction. The updated collector includes Redis; actual new restart/restore outcomes determine the runtime conclusion. Authentication, AOF and no-eviction settings remain the same.

Redis then restarted successfully and loaded AOF. The Cart timeout was a distinct API defect: only the snapshot-read connection/timeout boundary now returns `503 CART_STORE_UNAVAILABLE` with sanitized details. Two new regressions verify transient cases and preserve genuine unexpected Redis failures. The probe captures the entire owner/cart/quantity/revision view before restart and uses bounded benign reads to require exact equality after reconnect, strengthening the previous item-count check. No cart or financial mutation is replayed.

Each runtime PASS is recorded only after its assertion completes. Planned steps in `validate-business.py` are not evidence that they ran. The harness is confined to exact CI fixture projects and generated data; owner data and broker state are never reset.

| Scenario | Expected invariant | Actual result |
|---|---|---|
| User provisioning retry | Same request key/body keeps the exact SQL/Keycloak owner; no adoption of another user | **PASS** in the 13-check fixture. Separately, actual native Keycloak tests passed provider-created/SQL-save-failed retry to the same managed identity. |
| Kafka stop + active Order restart + delayed outbox recovery | PENDING publication survives; same key returns same order; one deduction | **PASS** at 03:59:54 UTC. Broker stopped before COD on SKU B, PENDING SQL outbox confirmed; Order restarted; Kafka/Registry/consumers recovered; order COMPLETED, B=7, retry returned the same order number. |
| Active Payment restart + callback/duplicate retry | Exact PENDING reference survives; success remains authoritative; duplicate is harmless | **PASS** in the tenth check at 04:01:42 UTC. B=6 before restart; benign GET waits for the original PENDING reference; signed IPN returned 00 then 02; payment SUCCESS and order COMPLETED. Earlier discovery 503 is recorded in section 8. |
| Inventory SIGKILL / RocksDB + broker recovery | Retain quantities and terminal operation identity; no double application | **PASS** in the tenth check. After SIGKILL/start, A=12/B=6; retry of the original +3 ADMIN operation retained A=12. |
| Redis restart / AOF + Cart client reconnect | Exact owner/items/quantities/revisions/metadata conserved | **PASS** in the tenth check. Complete pre-restart two-line Cart response equals the post-reconnect response. Earlier Redis restart loop and Cart timeout are recorded in section 8; neither was treated as PASS. |
| Duplicate stable INIT/CHECK/compensation events | INIT cannot reset stock; CHECK cannot deduct again; compensation cannot restore twice | **PASS** at 04:01:52 UTC. Only original fixture outbox IDs were replayed; Product/Order/Payment outboxes all drained to PUBLISHED, with A=12/B=6. |
| ADMIN operation retry + rejected excessive deduction | Stable +3 operation applies once; -100 is rejected; no negative stock | **PASS** at 03:58:01 UTC, after Inventory crash and again after restore. The same operation ID/payload retains APPLIED and A=12; rejected operation retained stock. |
| Coordinated backup and isolated restore | Preserve owners/SKUs/totals/status/payment refs/event IDs/cart revisions/operation IDs; retry remains idempotent | **PASS** at 04:02:14 and 04:05:05 UTC. Business and Keycloak SQL plus stopped Redis/Kafka/Streams were actually restored into distinct empty targets; exact SQL fingerprint and Cart equality passed, A=12/B=6, original order/ADMIN keys and duplicate IPNs remained safe. |
| Aged workflow replay/budget exhaustion | Same order/SKU identity; unknown remains unknown; no blind restock; terminal investigation after bounds | Source/unit/H2 PASS in `OrderBusinessTests`; full broker-age/DLT operator rehearsal is NOT VERIFIED unless separately stated above. |
| DLT SQL-recording/stop-on-failure path | Retain broker record if SQL cannot save evidence; no DLT.DLT; no financial mutation | Source/config/fixture PASS; actual MySQL 8.4.10 SQL PASS (new row, immutable duplicate, nullable order number, propagated constraint failure). Real poisoned broker-record/container-stop/operator recovery remains NOT VERIFIED. |
| Multiple replicas/capacity | Owning-task reads and connected notification clients remain correct under scale | NOT VERIFIED; Inventory/Notification stay at one supported replica. |

# 10. Payment / VNPay Validation

| Behavior | Verification |
|---|---|
| Current reference/one attempt/URL creation and duplicate create | SOURCE/TEST VERIFIED; server-owned catalog amount, signed request construction and stable attempt/fence tests pass. |
| Fifteen-minute expiry/legacy unknown expiry | SOURCE/TEST VERIFIED; expired URL withheld, no automatic new attempt or fence release, protected reconciliation. |
| Read-only Return | SOURCE/TEST VERIFIED; checksum/reference/merchant/amount/shape checked; no receipt/outbox mutation. |
| IPN protocol | SOURCE/TEST VERIFIED; 00/02/01/04/97/99 contract and failure rollback tested. |
| Failed/duplicate/late/tampered callback | SOURCE/TEST VERIFIED; bounded delivery, idempotency, unknown/invalid amounts, terminal protection and stale-ref cases pass. |
| Cancellation race/reconciliation | SOURCE/TEST VERIFIED; accepted money cannot coexist with cancellation + restored allocation; cancelled/compensated orders require reconciliation rather than revival. |
| Process/broker/disposable mock callback | **DISPOSABLE RUNTIME PASS**. Two distinct mocked online orders used original signed references. Return 302 retained PENDING; IPN 00 then 02 led to payment SUCCESS/order COMPLETED and paid cancellation 409. Payment restart retained exact PENDING/ref before callbacks. After coordinated restore both SUCCESS references were identical, duplicate IPNs returned 02 and A=12/B=6 stayed unchanged. Kafka outage/Order restart also recovered. These are generated-secret local protocol checks; no provider endpoint was contacted. |
| Actual successful provider transaction | **LIVE SANDBOX NOT VERIFIED**. No successful live sandbox settlement occurred; the owner's provider-side terminal approval error 71 is not a source-test blocker. |

Payment container runtime is explicitly GMT+7 (`Asia/Ho_Chi_Minh`) for provider request dates. Generated mock HMAC secret never appears in the report, committed files or printed requests. A signed fixture proves code/protocol behavior, not provider settlement/refunds.

# 11. Inventory / Saga Validation

The preserved topology serializes by SKU, retains check results by order/SKU and keeps the no-reset duplicate INIT policy. Admin operation-ID claim/repartition occurs before SKU processing; immutable terminal results and compensation identity survive supported restoration. SQL receipts and outbox decisions are distinct from Kafka transaction guarantees; the project does not claim a distributed exactly-once MySQL/Kafka transaction.

Source/topology/SQL tests cover normal/multi-SKU rejection, duplicate deduction, duplicate compensation, negative/range rejection, stable admin retry, stale-operation payload, permanent retired-SKU identity, SQL proof after Redis loss, bounded aged retry, terminal/paid guards and retained investigation. `InventoryTopologyTests` include restoration of committed driver stores; that is explicitly narrower than a broker/process restore.

**Actual broker/process/coordinated-restore PASS** in final job `111355915459`: SKU A/B began at 12/8; COD A1 left A11, mocked paid A2 left A9; unpaid multi-SKU cancellation and partial rejection restored only the actual deductions to A9/B8. Stable ADMIN +3 produced A12 once and -100 was REJECTED. Delayed COD B1 recovered after Kafka/Order restart to B7; mocked paid B1 left B6. Inventory SIGKILL/restart conserved A12/B6 and the original operation. Original stable INIT/CHECK/compensation events were replayed and all three business outboxes drained without quantity changes. Coordinated SQL/Redis/Kafka/Streams restore retained A12/B6, cart revisions, owners, order totals, payment references, outbox identities and APPLIED operation identity; duplicate order/operation/callback retries changed no stock.

H04 dead-letter/age handling is conservative. Safe retry cannot clear money fences or create refunds. Failure to obtain authoritative deduction/settlement proof leaves explicit reconciliation. Full production deserialization/DLT and operator-action rehearsal, stock task multi-instance routing, and measured crash timing interleavings remain unverified unless a scenario is explicitly passed in section 9.

# 12. Keycloak Validation

Five explicit `KeycloakLifecycleIT` tests passed against an **actual, newly owned Keycloak 26.8.0 process**, with a fresh loopback realm/client and generated credentials. The launcher verifies the official archive SHA256 `9e41da899f838a58cd510fc98ed4f7cadc715aed5683e42aca20a0c9a2a3980a`, compares its issuer and copies exact project sources before Maven. It never attaches to the owner's realm. The default suite does not silently include or skip these opt-in tests; the five tests are reported separately.

Covered: registration and exact identity, USER token claims, login, refresh, logout and rejected invalid refresh, disabled-account classification, controlled ADMIN/duplicate/substring lookup, provider-created/SQL-save-failed retry to the same origin and SQL intent COMPLETE, and partial profile update preserving core identity fields, existing attributes and the provisioning marker. Account disable does not establish immediate invalidation of all previously issued offline-validated access JWTs.

Narrow runtime corrections already pushed: the production profile template omits the invalid `unmanagedAttributePolicy: DISABLED` enum (omission represents disabled); Admin PUT now carries forward existing managed username/email/name/enabled/emailVerified/requiredActions fields and attributes before merging allowed updates. Client input cannot rewrite the admin-only provisioning origin.

The full production Compose run also passed two exact registration retries, real USER/ADMIN grants, owner Cart 200 and cross-owner 403, USER denial on ADMIN routes and public internal-Order denial. Exact JWT subjects equal SQL/Keycloak owner IDs. Both the native provider fixture and production template retain modern default `basic`; authorization rules remain unchanged.

The resumed full-stack failure was traced to owner identity, not relaxed authorization: both Gateway and direct Cart reported 401 BUSINESS_ERROR. New native USER/ADMIN subject assertions reproduced empty `sub` (5 tests, 2 failures, 0 errors/skips) against the unchanged template. Assigning `basic` as a default scope restored exact user IDs and all 5 tests passed; the refreshed-token subject is checked too. [Keycloak’s official upgrade notes](https://github.com/keycloak/keycloak/blob/main/docs/documentation/upgrading/topics/changes/changes-25_0_0.adoc) explain the modern Subject mapper. `DEPLOYMENT.md` documents reviewed scope adoption for existing clients without reimporting owner data. The signed-HTTP test also rejects a valid signed token with no subject.

Owner-specific grants, an existing realm's profile-policy adoption, actual operator bootstrap credentials, Keycloak 18 data upgrade and the public reverse-proxy/TLS path remain NOT VERIFIED. No real credentials or realm export with secrets was committed.

# 13. Backup / Restore Validation

The guarded harness builds a fixture dataset with users, a product/two SKUs, stock, cart revisions, COD and mocked VNPay orders/payment references, outbox rows and an admin operation. The coordinated procedure stops writers/consumers, records SQL identities/totals/status/reference/outbox-ID+payload fingerprints and migration history, runs the documented guarded business/Keycloak SQL dumps, saves/stops Redis and then snapshots stopped Redis/Kafka/Inventory Streams volumes with SHA256 checksums. A second project must own distinct labelled volumes and empty restore targets; source volumes remain stopped and retained. SQL restore requires the documented explicit isolated-target acknowledgement.

After restored startup, completed comparisons include exact owners/SKUs/totals/references/outbox identity/cart revisions/terminal operations, quantity conservation, same order-key retry, same operation retry and duplicate IPN acknowledgement. The actual completed CI assertion, rather than harness presence, supplies the evidence below.

**COORDINATED DISPOSABLE BACKUP/RESTORE PASS**, final CI job `111355915459`. Backup assertion completed **2026-10-04 04:02:14 UTC**, restored conservation/retry assertion completed **04:05:05 UTC**. Actual backups: documented `backup-mysql.sh` business SQL dump (all five business schemas plus their Flyway history) and dedicated Keycloak SQL dump, plus checksummed stopped `redis-data`, `kafka-data` and `inventory-streams` archives. The restore used `restore-mysql.sh` with the explicit isolated-target acknowledgement; SQL/Redis/Kafka/Streams targets were fresh, empty and labelled for the second project. The source project was stopped and its volumes retained throughout the comparison.

The restored stack reached health, USER and ADMIN logged in with exact original subjects, the SQL fingerprint matched exactly and the whole Cart response matched. Fingerprints cover user/owner/default-address/provisioning IDs and state, product/revision/prices and live/retired SKU identity, order owner/status/totals/items/payment attempt/reconciliation flags, payment reference/status/amount/success receipt, business outbox event UUID/aggregate/topic/payload SHA256 and Flyway versions/scripts/checksums/success. Runtime queries additionally checked **A=12/B=6**, all four completed orders' original owner, the original ADMIN operation APPLIED, same COD idempotency key returning the original order, same ADMIN operation retry, and both exact SUCCESS payment references with duplicate IPN **02/02**. Final quantities stayed A12/B6.

Kafka and Streams were genuinely stopped, snapshotted, restored and queried through the recovered application; Redis AOF/cart state was genuinely restored. This is the supported coordinated fixture procedure, not an independently lost or incompatible-state recovery claim.

Private dumps/archives/generated keys/tokens remain temporary to the runner and are not downloadable build artifacts. Prometheus/Grafana archival, owner legacy data, independently lost remote backups, off-host disaster recovery, RPO/RTO, incompatible image/schema rollback and multi-node Kafka/SQL HA were not tested. No disaster-recovery success claim extends beyond the actual coordinated SQL/Redis/Kafka/Streams fixture proof.

# 14. Security / Dependency Review

The completed Batch 6 review enumerated **276/276 external runtime Maven coordinates**, excluding project-owned modules, using:

```bash
mvn -DskipTests package org.apache.maven.plugins:maven-dependency-plugin:3.8.1:list \
  -DincludeScope=runtime -DoutputFile=target/dependency-batch6.txt
npm audit --json
npm audit --omit=dev --json
```

Official OSV queries returned **49 matching coordinates / 137 unique record IDs / zero query or metadata errors**. Alias records can describe the same underlying issue; these are **version metadata matches, not demonstrated exploitability**. No Maven version was changed. [docs/dependency-review-batch6.md](docs/dependency-review-batch6.md) lists every exact installed coordinate, advisory ID and metadata fixed-version candidate.

Candidate families requiring coherent BOM/compatibility review include Spring Boot 3.5 patches, Framework 6.2.19, Security 6.5.11, Gateway 4.3.2, Tomcat 10.1.58, Netty 4.1.133–137, Logback 1.5.34, Jackson patched supported branches, BeanUtils 1.11.0, Commons IO 2.14.0, Avro 1.11.4 and Kafka client 3.9.2. These recorded versions are not a guarantee that independently pinning each is supported or fixes every transitive advisory. Legacy Commons Configuration 1.10 and Commons Lang 2.6 have no fixed version in the matching metadata and need replacement/reachability review. No broad framework/architecture upgrade or competing module pin was performed to manufacture a clean scan.

Frontend production audit: **0 entries**. Full audit: **20 development/tooling package entries — 17 high, 2 moderate, 1 low, 0 critical**. Dependent entries are not 20 independently demonstrated exploits. The suggested `eslint-config-next@14.2.35` major downgrade was not applied to Next 16; no forced audit fix was used. Remaining tooling remediation and runtime advisory reachability are explicit open security work.

Docker/Podman/Trivy/Syft/Grype executables were absent locally. CI supplied Docker for image/stack validation, but no container/OS/SBOM vulnerability scanner was run. Image build/health is not an advisory scan. Container advisories remain NOT VERIFIED.

Source review preserves externalized required secrets, production seeders off, least-privilege application/migration DB accounts, private management/data networks, exact issuer/JWK validation, USER/ADMIN/owner guards, denied public internal Order APIs, production validation and sanitized exceptions. Before each push the staged diff excluded real `.env`, target/build output, Kafka runtime state, dumps/tokens and credentials. Future public release still requires supported dependency patch/triage, owner configuration and edge/provider verification.

# 15. Files Changed

The following exact paths are the union of backend changes since `2f98e838870d6344cd25ff1b0465997bb7c93e93` and frontend changes since `5b3be4d6dfb0a588df11d3c5160479b7a1496fdd`, grouped by source/configuration, migrations, tests and documentation. The final report is included as an added backend document. Temporary tools/logs/dumps are excluded.

## Backend — 100 files

### Source

```text
api-gateway/src/main/java/com/myexampleproject/apigateway/config/SecurityConfig.java
cart-service/src/main/java/com/myexampleproject/cartservice/controller/CartController.java
cart-service/src/main/java/com/myexampleproject/cartservice/model/CartItemEntity.java
cart-service/src/main/java/com/myexampleproject/cartservice/service/CartService.java
cart-service/src/main/java/com/myexampleproject/cartservice/service/PurchasedCartRequest.java
cart-service/src/main/java/com/myexampleproject/cartservice/service/PurchasedOrderClient.java
common-dto/src/main/java/com/myexampleproject/common/exception/DomainException.java
common-dto/src/main/java/com/myexampleproject/common/exception/GlobalExceptionHandler.java
common-events/src/main/java/com/myexampleproject/common/event/CartLineItem.java
common-events/src/main/java/com/myexampleproject/common/event/InventoryAdjustmentEvent.java
common-events/src/main/java/com/myexampleproject/common/event/PaymentFailedEvent.java
common-events/src/main/java/com/myexampleproject/common/event/PaymentInvestigationEvent.java
inventory-service/src/main/java/com/myexampleproject/inventoryservice/config/InventoryTopology.java
inventory-service/src/main/java/com/myexampleproject/inventoryservice/config/SecurityConfig.java
inventory-service/src/main/java/com/myexampleproject/inventoryservice/controller/InventoryController.java
inventory-service/src/main/java/com/myexampleproject/inventoryservice/service/InventoryService.java
inventory-service/src/main/java/com/myexampleproject/inventoryservice/service/StockOperation.java
order-service/src/main/java/com/myexampleproject/orderservice/OrderServiceApplication.java
order-service/src/main/java/com/myexampleproject/orderservice/config/KafkaConsumerConfig.java
order-service/src/main/java/com/myexampleproject/orderservice/controller/OrderController.java
order-service/src/main/java/com/myexampleproject/orderservice/dto/OrderResponse.java
order-service/src/main/java/com/myexampleproject/orderservice/model/Order.java
order-service/src/main/java/com/myexampleproject/orderservice/model/OrderLineItems.java
order-service/src/main/java/com/myexampleproject/orderservice/repository/OrderRepository.java
order-service/src/main/java/com/myexampleproject/orderservice/service/OrderService.java
order-service/src/main/java/com/myexampleproject/orderservice/service/WorkflowDeadLetters.java
payment-service/src/main/java/com/myexampleproject/paymentservice/PaymentServiceApplication.java
payment-service/src/main/java/com/myexampleproject/paymentservice/controller/PaymentController.java
payment-service/src/main/java/com/myexampleproject/paymentservice/dto/PaymentTransactionResponse.java
payment-service/src/main/java/com/myexampleproject/paymentservice/model/PaymentTransaction.java
payment-service/src/main/java/com/myexampleproject/paymentservice/repository/PaymentTransactionRepository.java
payment-service/src/main/java/com/myexampleproject/paymentservice/service/PaymentService.java
product-service/src/main/java/com/myexampleproject/productservice/dto/ProductRequest.java
product-service/src/main/java/com/myexampleproject/productservice/dto/ProductResponse.java
product-service/src/main/java/com/myexampleproject/productservice/model/Product.java
product-service/src/main/java/com/myexampleproject/productservice/repository/ProductRepository.java
product-service/src/main/java/com/myexampleproject/productservice/repository/ProductSpecifications.java
product-service/src/main/java/com/myexampleproject/productservice/service/CloudinaryImageService.java
product-service/src/main/java/com/myexampleproject/productservice/service/FacetValues.java
product-service/src/main/java/com/myexampleproject/productservice/service/ProductService.java
product-service/src/main/java/com/myexampleproject/productservice/service/SkuIdentityService.java
user-service/src/main/java/com/myexampleproject/userservice/controller/AuthController.java
user-service/src/main/java/com/myexampleproject/userservice/controller/UserController.java
user-service/src/main/java/com/myexampleproject/userservice/dto/UserAddressResponse.java
user-service/src/main/java/com/myexampleproject/userservice/repository/UserAddressRepository.java
user-service/src/main/java/com/myexampleproject/userservice/repository/UserRepository.java
user-service/src/main/java/com/myexampleproject/userservice/service/AuthProviderErrors.java
user-service/src/main/java/com/myexampleproject/userservice/service/KeycloakService.java
user-service/src/main/java/com/myexampleproject/userservice/service/ProvisioningService.java
user-service/src/main/java/com/myexampleproject/userservice/service/UserService.java
```

### Configuration / infrastructure

```text
.github/workflows/ci.yml
.gitignore
cart-service/src/main/resources/application-prod.properties
deploy/keycloak/realm-production.template.json
deploy/redis/start.sh
order-service/src/main/resources/application-prod.properties
product-service/src/main/resources/application-prod.properties
user-service/src/main/resources/application-prod.properties
```

### Migrations

```text
order-service/src/main/resources/db/migration/V5__durable_saga_recovery.sql
payment-service/src/main/resources/db/migration/V5__payment_attempt_expiry.sql
product-service/src/main/resources/db/migration/V4__permanent_sku_identity.sql
product-service/src/main/resources/db/migration/V5__checked_catalog_revision.sql
user-service/src/main/java/db/migration/V3__one_default_address.java
user-service/src/main/resources/db/migration/V4__recoverable_user_provisioning.sql
```

### Tests / validation

```text
.github/scripts/check-sku-migration.py
.github/scripts/prepare-smoke.py
.github/scripts/run-keycloak-lifecycle.py
.github/scripts/validate-business.py
api-gateway/src/test/java/com/myexampleproject/apigateway/GatewaySecurityTests.java
cart-service/src/test/java/com/myexampleproject/cartservice/CartBusinessTests.java
cart-service/src/test/java/com/myexampleproject/cartservice/CartSignedTokenBoundaryTests.java
cart-service/src/test/java/com/myexampleproject/cartservice/PurchasedOrderClientTests.java
cart-service/src/test/java/com/myexampleproject/cartservice/RedisCartAtomicTests.java
common-dto/src/test/java/com/myexampleproject/common/ErrorContractTests.java
inventory-service/src/test/java/com/myexampleproject/inventoryservice/InventoryTopologyTests.java
inventory-service/src/test/java/com/myexampleproject/inventoryservice/StockOperationControllerTests.java
order-service/src/test/java/com/myexampleproject/orderservice/KafkaConfigurationTests.java
order-service/src/test/java/com/myexampleproject/orderservice/MigrationTests.java
order-service/src/test/java/com/myexampleproject/orderservice/OrderBusinessTests.java
order-service/src/test/java/com/myexampleproject/orderservice/OrderOutboxTransactionTests.java
payment-service/src/test/java/com/myexampleproject/paymentservice/MigrationTests.java
payment-service/src/test/java/com/myexampleproject/paymentservice/PaymentAttemptTests.java
payment-service/src/test/java/com/myexampleproject/paymentservice/PaymentCallbackTests.java
product-service/src/test/java/com/myexampleproject/productservice/MigrationTests.java
product-service/src/test/java/com/myexampleproject/productservice/ProductBusinessTests.java
product-service/src/test/java/com/myexampleproject/productservice/ProductOutboxTransactionTests.java
product-service/src/test/java/com/myexampleproject/productservice/ProductSearchTests.java
user-service/src/test/java/com/myexampleproject/userservice/AddressContractTests.java
user-service/src/test/java/com/myexampleproject/userservice/AuthClassificationTests.java
user-service/src/test/java/com/myexampleproject/userservice/KeycloakLifecycleIT.java
user-service/src/test/java/com/myexampleproject/userservice/MigrationTests.java
user-service/src/test/java/com/myexampleproject/userservice/ProvisioningIdentityTests.java
user-service/src/test/java/com/myexampleproject/userservice/UserConcurrencyRecoveryTests.java
user-service/src/test/java/com/myexampleproject/userservice/UserOwnershipTests.java
```

### Documentation

```text
DEPLOYMENT.md
PROJECT1_BATCH4_5_6_FINAL_REPORT.md
docs/batch4-correctness.md
docs/batch5-consistency.md
docs/batch6-validation.md
docs/dependency-review-batch6.md
```


## Frontend — 27 files

### Source

```text
src/app/checkout/page.tsx
src/app/checkout/waiting/[orderNumber]/page.tsx
src/app/login/page.tsx
src/app/product/[id]/page.tsx
src/app/products/page.tsx
src/app/register/page.tsx
src/components/Header.tsx
src/components/OrderActions.tsx
src/components/admin/ProductEditor.tsx
src/lib/api-error.ts
src/lib/axiosClient.ts
src/lib/facets.ts
src/lib/order-status.ts
src/lib/product-filters.ts
src/lib/stock-operation.ts
src/services/authApi.ts
src/services/cartApi.ts
src/services/inventoryApi.ts
src/services/orderApi.ts
src/services/paymentApi.ts
src/store/useCheckoutStore.ts
src/types/index.ts
```

### Tests / validation

```text
tests/fixtures.ts
tests/flows.spec.ts
tests/unit.spec.ts
```

### Documentation

```text
docs/API_CONTRACT.md
docs/BATCH6_VALIDATION.md
```


# 16. Git Commits

All rows are actual remote commits. Earlier Phase A/Batch 3 commits remain ancestors and are listed in the preceding report; they were neither squashed nor recreated.

| Repository | Batch | Commit SHA | Message |
|---|---|---|---|
| Backend | 4 | `91e990a2e408b4ef2ce894a34487b40d568b9c04` | Batch 4: harden saga recovery, permanent SKU identity and VNPay lifecycle |
| Frontend | 4 | `9466155c8daeb69c856a5125598d576b0a7ad5c7` | Batch 4: show protected payment expiry and SKU identity conflicts |
| Backend | 5 | `dd3b6c37f63e35fe36c8dfb4fbb72f640e49ff61` | fix: complete Batch 5 API and data consistency hardening |
| Frontend | 5 | `fcfad9b859b57dedc3f644c7cbede7ea4ad29b4b` | fix: complete Batch 5 client consistency and operation status |
| Frontend | 6 | `a68720c7d87dc239e96f3483b9a0c6a4b15158ea` | docs: record Batch 6 frontend validation checkpoint |
| Backend | 6 | `d35fa2a7d69507135ea7e5fdd357d9d9515cd3c4` | test: validate Batch 6 identity, faults and isolated recovery |
| Backend | 6 | `f993bee9659b6b9ba16c9a83fc0483fb1ed4afd1` | fix: validate new SKU migration on MySQL 8.4 |
| Backend | 6 | `d7468283762067cfeebcedd445a469a4a8ff3e46` | test: diagnose remaining authenticated cart validation gate |
| Backend | 6 | `dc3c887ab0d532e0248f297ec225da985cbe83b5` | fix: preserve exact JWT owners in production Keycloak tokens |
| Backend | 6 | `b4fd85f45e27550f61450861a9748081d70d5e78` | fix: route production cart and order clients to private services |
| Backend | 6 | `cdd15ba632618073e76ddf97d7e96b06ca974dee` | test: wait for read-only payment route convergence after recovery |
| Backend | 6 | `85dc6680abc30e3469442a1645005cc9e61c39a9` | fix: preserve Redis startup across restarts and probe DLT SQL |
| Backend | 6 | `abf95e57e0f8fee618db7fcf8a7798e377ea1630` | fix: classify transient cart-store failures and validate reconnect |

The report-only commit is `docs: finalize Project 1 Batch 4 5 6 validation report [skip ci]`; its resulting SHA is supplied with the final delivery. It follows the final tested source checkpoint without changing application behavior. The skip-CI marker applies only to that report-only commit, preventing a duplicate stack rehearsal; the actual source gates and runtime results are linked to their tested checkpoint.

# 17. Remaining Findings

This covers all **32 original IDs**: 1 Critical, 5 High, 14 Medium, 3 Low, 3 Info and 6 unverified risks. FIXED/MITIGATED describe evidence within the stated scope; a successful build does not erase owner/runtime limits.

| ID | Classification | Current result / remaining boundary |
|---|---|---|
| C01 | **FIXED** | Prior Phase A patched the known Next/RSC vulnerable runtime; final frontend production npm audit zero. New/remaining backend/tooling advisories remain separate release work. |
| H01 | **FIXED** | Prior exact verified administrator identity/duplicate protection preserved; no substring-account role adoption. Owner realm/bootstrap credentials still require configuration. |
| H02 | **FIXED** | Coordinated order/payment fence and authoritative receipt handshake preserved; cancellation and terminal guards/source tests pass. Live financial settlement/refund is not certified. |
| H03 | **FIXED** | Transactional SQL outbox and stable consumer identities remain; rollback/retry/restart tests pass. This is durable at-least-once publication with idempotent consumers, not distributed exactly-once. |
| H04 | **MITIGATED** | Durable SQL deduction proof, bounded aged replay, observable investigation and safe DLT retention/ADMIN retry; real DLT/operator and financial ambiguity remain explicit boundaries. |
| H05 | **FIXED** | Permanent SKU reservations/retirement and Inventory collision guard prevent unsafe reuse. Historical evidence must survive restore; owner legacy-copy rehearsal pending. |
| M01 | **MITIGATED** | Expired URLs withheld, no concurrent/new unresolved attempt, bounded receipt reconciliation. No automatic retry until settlement evidence makes it safe. |
| M02 | **FIXED** | Read-only Return; signed authoritative IPN/protocol and rollback contract tested. Live provider delivery/settlement NOT VERIFIED. |
| M03 | **FIXED** | Stable codes and code-first frontend translation; safe generic fallback. |
| M04 | **FIXED** | Invalid grant/disabled/outage/config/malformed distinctions and recoverable transient refresh behavior; no instantaneous JWT revocation added. |
| M05 | **FIXED** | Row-SKU stock lookup across paginated variants; desktop/mobile regressions pass. |
| M06 | **FIXED** | Conservative consistent facet policy and legacy-compatible queries; no arbitrary historical rewrite. |
| M07 | **FIXED** | Checked revision before product/variant/outbox effects; stale/concurrent winner tests pass. |
| M08 | **FIXED** | Owner/accepted-order validation plus atomic quantity/revision cleanup and receipts; real isolated Redis concurrency/AOF tests pass. |
| M09 | **FIXED** | Serialized owner default changes plus database uniqueness/deterministic deletion and JSON contract. Owner legacy adoption pending. |
| M10 | **MITIGATED** | Durable provisioning intent, exact admin-only origin and same-key recovery; real provider SQL-failure retry tested. Owner/legacy realm policy and abandoned-attempt operator reconciliation remain. |
| M11 | **FIXED** | Stable immutable operation ID, retained claim/terminal result and frontend uncertainty/reload behavior; supported single instance. |
| M12 | **MITIGATED** | Required production secrets/profiles/private exposure/Flyway/seeder policy supplied and disposable startup tested; owner's actual secrets/edge/realm remain unverified. |
| M13 | **MITIGATED** | Named broker/Redis/Streams volumes, fixed identities and coordinated runbook/harness. Actual coordinated business/Keycloak SQL + Redis/Kafka/Streams backup/isolated restore passed with exact fingerprints and safe retries. Off-host loss, monitoring archival, HA and measured RPO/RTO remain unverified. |
| M14 | **FIXED** | Prior application/ingress upload envelope aligned, retained source/tests. Owner HTTPS edge and actual external upload boundary NOT VERIFIED; no new credential request. |
| L01 | **DEFERRED** | Admin role-list column/API enhancement outside correctness scope. |
| L02 | **DEFERRED** | No new VALIDATED/CANCELLED realtime Notification consumer; authoritative polling preserved. |
| L03 | **DEFERRED** | Dashboard creation-date/completion wording outside scope. |
| I01 | **DEFERRED** | Category-overflow header/drawer navigation polish outside scope. |
| I02 | **DEFERRED** | COD portfolio simulation and distinct legacy cart checkout retained; no fulfillment/settlement feature expansion. |
| I03 | **DEFERRED** | Business/catalog/metrics/outbox capacity/archival policies need measured workload; no scale/capacity certification. |
| U01 | **NOT VERIFIED** in full | Java 24 and fresh MySQL portions verified. Actual fresh all-history Flyway/Hibernate startup, constraints and coordinated same-version restore passed. Owner legacy-copy adoption/legitimate data comparison remains. |
| U02 | **NOT VERIFIED** in full | Five actual disposable Keycloak tests and specified runtime checks pass; owner effective grants, existing profile-policy adoption and Keycloak 18 upgrade not tested. |
| U03 | **NOT VERIFIED** in full | Actual controlled Kafka/Order/Payment/Inventory/Redis restart, stable-event replay and coordinated restore passed with conserved stock/references/IDs. Full broker DLT/operator recovery and owner production timing remain open. |
| U04 | **NOT VERIFIED** | Cloudinary source/config/tests retained and owner's prior live upload context acknowledged; no new live credentials. VNPay deterministic fixtures only; no successful live settlement. |
| U05 | **NOT VERIFIED** | Single Inventory/Notification replica policy retained. Owning-task multi-instance routing and shared client notification delivery not implemented/rehearsed. |
| U06 | **NOT VERIFIED** | Complete Maven metadata and production/dev npm review available; exploitability/reachability, coherent supported patches and image/OS SCA remain open. |

The final source/runtime checkpoint has **no unresolved failure in the 13 attempted business/restart/replay/backup/restore checks**. Narrow fixes resolved invalid provider template/core-field retention, JSON default-address contract, the task's unreleased V4 MySQL syntax, missing JWT subject, private downstream URLs, Redis restart configuration reuse and Cart's transient store-error classification. Read-only convergence waits preserve exact-state assertions without retrying ambiguous financial mutations. Remaining work is the explicitly scoped security/advisory/SCA triage, real broker DLT/operator rehearsal, owner legacy/realm/edge/provider adoption and scale/capacity boundaries above.

# 18. Final Local Readiness Statement

**LOCAL HARDENING PARTIAL — SPECIFIC ISSUES REMAIN**

Completed source/configuration hardening and both Batch 4/5 validation checkpoints are preserved and pushed; final backend/frontend source evidence and actual disposable CI outcomes are documented above. All feasible final source gates and the complete 13-check disposable business/restart/replay/coordinated-restore rehearsal **PASS** on the final tested backend source SHA; final frontend attempt 3 passes 79 tests, lint/build and standalone image health. Batch 6 stays **PARTIAL** because the review found open dependency/tooling advisories and the remaining DLT/operator/owner-environment/scale checks are not fully verified.

The open backend/tooling advisory triage and container scan, real DLT/operator investigation, owner legacy-data/realm adoption, owner secret/edge/provider verification and multi-instance/capacity limits prevent an unconditional local/production-readiness claim. Live VNPay settlement remains NOT VERIFIED as explicitly requested; owner terminal approval is not used to hide a code-test failure. No public deployment, real provider payment, unrelated UI/feature change, destructive owner state mutation or protected-branch update occurred.
