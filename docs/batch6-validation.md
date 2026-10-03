# Batch 6 validation procedure and source checkpoint

This work continues the already pushed Batch 4 and Batch 5 checkpoints. All changes remain on `production-ready-final`; no deployment or real provider credentials are used.

## Source gate

Java 24.0.2+12, Maven 3.9.9: `mvn -B -ntp -Dlogging.level.root=ERROR -Dspring.main.banner-mode=off clean verify` passed all 12 reactor modules and **147 tests, zero failures/errors/skips**. The exact current source was copied into an isolated temporary directory because workspace synchronization had introduced a generated `.rsync-tmp` class directory in an earlier build. No assertion or test discovery exclusion was weakened.

Frontend `npm ci`, lint and build passed; **79 tests passed** (20 unit, 54 desktop/mobile flow, 5 responsive), using Chrome Headless Shell 154.0.8037.92 with one worker.

## Native disposable identity fixture

Use Java 24 and Maven on PATH, download the official Keycloak 26.8.0 release archive, then:

```bash
python3 .github/scripts/run-keycloak-lifecycle.py /path/to/keycloak-26.8.0.tar.gz
```

The launcher verifies the official archive SHA256, owns a new loopback process/realm and generates all credentials. It never connects to an existing realm. Five explicit `KeycloakLifecycleIT` tests passed against the actual provider, including SQL failure after Keycloak creation, retry with the same managed origin, disabled account classification and partial profile updates. These opt-in `*IT` tests are recorded separately from the default 147-test suite.

Runtime testing found two narrow defects: Keycloak 26.8.0 rejects `unmanagedAttributePolicy: DISABLED` (disabled is represented by omission); its Admin PUT can erase omitted managed core profile fields. The template now omits that invalid enum and profile updates retain existing core fields and the admin-only provisioning marker. A serialization test also found the default-address response needed an explicit getter property name to emit only `isDefault`.

## Disposable Docker rehearsal

Local Docker/Podman is unavailable. Backend GitHub CI builds all production images without publishing them and runs the existing production Compose stack on a disposable runner. The completed Batch 5 frontend is pinned. CI fixture configuration generates secrets; VNPay's URL is an unroutable fixture domain and is never contacted.

`.github/scripts/validate-business.py` requires `GITHUB_ACTIONS=true`, the exact CI SHA/project name, and fixture merchant values. It creates only fixture users/products/orders and checks real USER/ADMIN/owner boundaries, SQL/Kafka order completion, local signed Return/IPN callbacks, cart revision cleanup, stable stock operation IDs, Kafka outage/outbox recovery, process restarts, duplicate stable events and a coordinated restore.

The restore stops writers and consumers, uses the documented business/Keycloak SQL backup scripts, snapshots stopped Redis/Kafka/Streams volumes, verifies checksums, creates a second project with distinct labelled volumes, restores into empty targets and compares identities, totals, references, event/operation IDs, stock and cart revisions. Private dumps, generated credentials and archives stay in runner temporary storage. No owner data, live settlement, off-host disaster recovery, capacity or RPO/RTO claim is implied.

The script records a check only after its assertions pass. Its presence alone is not runtime evidence. Consult the final report for the actual CI run, completed checks, failures and any unverified scenarios.

## Security

See [dependency-review-batch6.md](dependency-review-batch6.md). Full runtime Maven metadata scan: 276 coordinates queried, 49 coordinates matching 137 OSV record IDs, no query errors. These are metadata matches and can share aliases. Frontend production audit is zero; development/tooling has 20 package entries. No broad framework upgrade was performed. Container/SBOM scanner and advisory reachability review remain open.
