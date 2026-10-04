# Batch 6 validation procedure and source checkpoint

This work continues the already pushed Batch 4 and Batch 5 checkpoints. All changes remain on `production-ready-final`; no deployment or real provider credentials are used.

## Source gate

Java 24.0.2+12, Maven 3.9.9: `mvn -B -ntp -Dlogging.level.root=ERROR -Dspring.main.banner-mode=off clean verify` passed all 12 reactor modules and **150 tests, zero failures/errors/skips**. The exact current source was copied into an isolated temporary directory because workspace synchronization had introduced a generated `.rsync-tmp` class directory in an earlier build. No assertion or test discovery exclusion was weakened.

Frontend `npm ci`, lint and build passed; **79 tests passed** (20 unit, 54 desktop/mobile flow, 5 responsive), using Chrome Headless Shell 154.0.8037.92 with one worker.

## Native disposable identity fixture

Use Java 24 and Maven on PATH, download the official Keycloak 26.8.0 release archive, then:

```bash
python3 .github/scripts/run-keycloak-lifecycle.py /path/to/keycloak-26.8.0.tar.gz
```

The launcher verifies the official archive SHA256, owns a new loopback process/realm and generates all credentials. It never connects to an existing realm. Five explicit `KeycloakLifecycleIT` tests passed against the actual provider, including SQL failure after Keycloak creation, retry with the same managed origin, disabled account classification and partial profile updates. These opt-in `*IT` tests are recorded separately from the default 150-test suite.

Runtime testing found two narrow defects: Keycloak 26.8.0 rejects `unmanagedAttributePolicy: DISABLED` (disabled is represented by omission); its Admin PUT can erase omitted managed core profile fields. The template now omits that invalid enum and profile updates retain existing core fields and the admin-only provisioning marker. A serialization test also found the default-address response needed an explicit getter property name to emit only `isDefault`.

The first Batch 6 Docker startup also exposed MySQL incompatibility in the new, unreleased SKU V4 migration: DATETIME(6) requires CURRENT_TIMESTAMP(6), and MySQL prohibits reading the INSERT target in a subquery. V4 now uses matching precision and a top-level anti-join. This migration was introduced by this task, has not been deployed to an owner environment, and was applied only to disposable fixtures. All 15 migration files from the starting checkpoint remain byte-identical; no existing database history/checksum is repaired. A disposable MySQL 8.4.10 SQL/backfill probe checks the new migration before image builds, followed by the actual Flyway/Hibernate production startup gate.

The completed 20-container smoke then exposed a missing owner claim: both Gateway and direct Cart returned 401 BUSINESS_ERROR for a valid USER token. The native provider regression confirmed an empty access-token `sub` for USER/ADMIN because the template omitted modern Keycloak’s `basic` default scope. The template now retains that scope; native tests assert the exact registered owner after login/refresh/ADMIN grant. The signed HTTP Cart fixture independently checks valid owner 200, foreign owner 403, anonymous/missing-sub/wrong-issuer 401 with the production management chain active. Authentication and ownership guards were not relaxed.

The next runtime attempt passed registration, authenticated ownership, default-address replacement and SKU/revision checks, then Cart mutation returned 503. Cart's production profile inherited loopback Product/Inventory URLs; Order also inherited a loopback catalog URL, and Cart's Order default used the Product port. The production overrides now use the existing private Compose DNS names and actual application ports (Product 8083, Inventory 8082, Order 8086). Environment overrides, timeouts and fail-closed error handling remain unchanged. The full backend gate and subsequent actual business rehearsal validate these configuration changes; no endpoint or authorization contract was changed.

## Disposable Docker rehearsal

Local Docker/Podman is unavailable. Backend GitHub CI builds all production images without publishing them and runs the existing production Compose stack on a disposable runner. The completed Batch 5 frontend is pinned. CI fixture configuration generates secrets; VNPay's URL is an unroutable fixture domain and is never contacted.

`.github/scripts/validate-business.py` requires `GITHUB_ACTIONS=true`, the exact CI SHA/project name, and fixture merchant values. It creates only fixture users/products/orders and checks real USER/ADMIN/owner boundaries, SQL/Kafka order completion, local signed Return/IPN callbacks, cart revision cleanup, stable stock operation IDs, Kafka outage/outbox recovery, process restarts, duplicate stable events and a coordinated restore.

The restore stops writers and consumers, uses the documented business/Keycloak SQL backup scripts, snapshots stopped Redis/Kafka/Streams volumes, verifies checksums, creates a second project with distinct labelled volumes, restores into empty targets and compares identities, totals, references, event/operation IDs, stock and cart revisions. Private dumps, generated credentials and archives stay in runner temporary storage. No owner data, live settlement, off-host disaster recovery, capacity or RPO/RTO claim is implied.

The routing-correction run passed the first nine business checks, including COD, signed mock payment, compensation, admin retry and Kafka outage/Order restart. Its next callback hit a temporary Gateway 503 immediately after Payment container readiness; the Gateway explicitly reported no registered Payment server before discovery converged. The probe now polls the authenticated, read-only payment view for the retained PENDING reference after restart (and SUCCESS references after restore) before sending callbacks. The bounded wait does not retry an ambiguous first financial callback or weaken the required 00/02 acknowledgements.

The next attempt passed the Payment restart callback and Inventory SIGKILL/quantity/operation-ID assertions, then Redis restart entered an exit-1 loop before AOF/cart comparisons. The Redis launcher previously reopened a fixed file in sticky `/tmp` after transferring ownership to Redis. It now creates a fresh private 0600 configuration with `mktemp` for each start, retaining the same authentication, AOF and non-evicting policy. Failure diagnostics now include Redis logs. The native workspace cannot reproduce the Docker ownership/kernel setup; the actual restart/conservation gate supplies the final proof.

The disposable MySQL probe also executes the exact DLT SQL read from `WorkflowDeadLetters.java` against the real migration's table, checks immutable duplicate evidence and verifies constraint errors propagate. These actual MySQL checks passed without changing the DLT implementation. The result is narrower than a real poisoned-Kafka-record/operator rehearsal.

Redis then restarted successfully and loaded its AOF, but Cart's first read hit a client `QueryTimeoutException` during reconnect and returned 500. Only the Cart snapshot-read boundary now classifies Redis connection/timeouts as `503 CART_STORE_UNAVAILABLE`, without leaking connection details or replaying mutations. Two regressions cover those transient cases and retain unexpected Redis errors as genuine errors. The probe snapshots the whole owner/cart/quantity/revision response before Redis restart, then bounds benign read retries and requires exact equality after reconnect; it does not reduce the conservation assertion to an item count.

The script records a check only after its assertions pass. Its presence alone is not runtime evidence. Consult the final report for the actual CI run, completed checks, failures and any unverified scenarios.

## Security

See [dependency-review-batch6.md](dependency-review-batch6.md). Full runtime Maven metadata scan: 276 coordinates queried, 49 coordinates matching 137 OSV record IDs, no query errors. These are metadata matches and can share aliases. Frontend production audit is zero; development/tooling has 20 package entries. No broad framework upgrade was performed. Container/SBOM scanner and advisory reachability review remain open.
