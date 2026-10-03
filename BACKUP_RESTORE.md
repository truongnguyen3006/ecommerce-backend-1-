# Backup and recovery

Named volumes provide restart persistence; they are not backups. Keep encrypted off-host backups, restrict access and rehearse restores. Suggested initial policy: daily consistent snapshots, pre-migration/release backups, 7 daily + 4 weekly + 6 monthly generations. Choose actual RPO/RTO with the owner and storage capacity; no RPO/RTO was demonstrated in this task.

## Coordinated backup boundary

Stop public writes and application consumers/publishers before a coordinated recovery snapshot. Inventory, Orders, Payments, Redis saga/idempotency state, Kafka offsets/changelogs and outbox records describe the same workflows; restoring one independently can replay completed deductions or lose receipts. Record service Git/image SHAs, schemas/history checksums, Kafka cluster/application IDs, committed offsets, topics/config/Schema Registry subjects, Streams state, counts and provider receipt boundary. Record external callbacks received during maintenance and reconcile them before reopening traffic.

## MySQL and Keycloak DB

Use a secure directory and the deployment host's protected `.env.production`:

```bash
scripts/backup-mysql.sh mysql /secure/backups/business-before-release.sql
scripts/backup-mysql.sh keycloak-db /secure/backups/keycloak-before-release.sql
```

The scripts use a private container exec, `MYSQL_PWD` from container environment, `--single-transaction`, no passwords in source/command args, no overwrite and an atomic completed dump plus SHA256 file. Stop concurrent DDL and coordinate writers; `--single-transaction` does not make Kafka/Redis/SQL a distributed snapshot. Dumps contain confidential data and account metadata. Encrypt/archive them before transfer. Backups must be validated in a disposable database; file existence/checksum alone is insufficient.

For a reviewed **isolated empty replacement database**, with all writers stopped and a separate Compose project/data volume:

```bash
RESTORE_ISOLATED_DATABASE_ACK=YES scripts/restore-mysql.sh mysql /secure/backups/business-before-release.sql
RESTORE_ISOLATED_DATABASE_ACK=YES scripts/restore-mysql.sh keycloak-db /secure/backups/keycloak-before-release.sql
```

The acknowledgement is a guard against accidental overwrite; the script cannot independently prove that the target is isolated. Choose `PRODUCTION_ENV_FILE`/`COMPOSE_PROJECT_NAME` for the replacement stack first. Confirm database server/version and the SHA256 record, then verify schema history, constraints, product/SKU counts, owner identities, order totals/statuses, payment receipts/unique refs and outbox counts. Start the pinned services with Flyway validation. Do not import a dump into an active production DB. Dump/restore execution was not live-tested here because Docker is unavailable.

## Redis

Redis uses AOF `appendfsync everysec`, RDB checkpoints (900s/1, 300s/10, 60s/10000 writes), authentication, `noeviction` and `redis-data:/data`. A host crash can lose the last acknowledged second of AOF writes; disk exhaustion/write failures require an immediate stop/recovery review. No maxmemory cap is asserted without measurements; monitor resident memory/disk and set a tested cap while retaining `noeviction`.

State includes carts, checkout locks/results, owner-scoped idempotency claims, saga progress and product caches. Cart checkout lock TTL is 15 minutes; event results are one day. Order idempotency TTL is one day with an explicit key and 10 seconds without; saga/result keys expire after one day. Ordinary cart hashes are not given a universal TTL by the current code. Redis is not the durable financial/order history source; SQL Orders/Payments and their receipt/reconciliation fields are authoritative. AOF persistence does not fix H04 aged-saga recovery or M08 cleanup races.

With writers stopped, authenticate privately, request `BGSAVE`, verify completion (`LASTSAVE`/persistence INFO), then stop Redis cleanly and copy the entire data volume including the Redis 7 multipart AOF directory, manifest/base/incremental files and RDB. Keep the matched configuration/version and checksum/archive. Copying a live AOF during rewrite is unsafe. Restore to an isolated stopped Redis of a compatible version, preserve file permissions, start and verify persistence/TTL/data counts. Never FLUSHALL/FLUSHDB as recovery. Cache hashes can be rebuilt only through a reviewed cache operation; never blindly rerun stock INIT or application demo seeding.

## Kafka, Schema Registry and Streams

`kafka-data` holds KRaft metadata and broker logs. Preserve the original `CLUSTER_ID`, node ID, topic partitions, compact/retention policies, internal transaction/offset topics, Registry `_schemas`, Streams application ID `inventory-streams-v11` and all changelog/deduplication stores. Inventory's named `inventory-streams` volume holds local RocksDB/task state. Regular event retention defaults to seven days; compacted Streams/Registry topics must keep their compact semantics. Consumer outage/recovery beyond retained history can make safe reconstruction impossible.

For this one-node profile, quiesce consumers/producers, cleanly stop Kafka/Inventory/Registry and snapshot the full broker volume, Streams volume and metadata/config alongside SQL/Redis. Do not archive selected segment files from a running broker or assume a volume is an off-host backup. A one-broker outage stops progress and a host disk loss without a verified backup loses authoritative inventory log history. Broker restore requires consistent metadata/logs and the same cluster ID. Restore in an isolated network, inspect topic health/partitions/transactions, verify schemas/compatibility and offsets, then start a single Inventory and wait for RUNNING/readiness plus state restoration. Compare conserved stock and deduplication outcomes before admitting writes. Do not reset Streams state/application ID or consumer offsets as a deployment shortcut.

A stale snapshot restored after later receipts/orders needs explicit business reconciliation against provider receipts and the newest recoverable logs. SQL outbox replay preserves stable event IDs and existing business identities; at-least-once delivery can duplicate records. Existing inventory INIT/CHECK/compensation handlers must still suppress duplicate effects. Real cross-system crash/replay/restore is an operator verification gate, not established by source/unit tests.

## Observability and configuration

Back up the protected environment through the secret manager (not Git), release manifest, pinned Compose/config, private realm settings and Grafana data/provisioning. Stop Grafana or use a consistent snapshot of its volume. Prometheus has a 15-day/10-GB retention bound; use its snapshot mechanism or a clean stopped-volume snapshot if history matters. Zipkin intentionally uses bounded in-memory storage and loses traces on restart; traces are diagnostics, not financial history or a backup. An external persistent trace backend may be added later with a reviewed override.

## Outbox and payment recovery

Read [production-recovery.md](docs/production-recovery.md). Do not delete pending outbox rows, change event IDs, replay DLTs blindly, clear payment fences or move terminal orders back to payable states. Archive only verified PUBLISHED rows under an agreed retention policy; automatic cleanup is deferred. Record a recovery decision and conserved stock/provider receipt outcome for each affected business identity. A coordinated restore rehearsal must pass before first public deployment and before trusting backup claims.
