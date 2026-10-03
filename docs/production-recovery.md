# Outbox, DLT and payment reconciliation

## Outbox publication

Each Order/Payment/Product database has `outbox_event`. A business transaction appends the event intent to that same database transaction; rollback removes both. The publisher uses a new transaction, row locks with `FOR UPDATE SKIP LOCKED`, and preserves per-topic/key sequence. Up to 20 rows are attempted per cycle; a bounded 10-second Kafka acknowledgement is required before PUBLISHED is committed. Retry uses exponential backoff from 2 seconds, capped at 60 seconds, without a drop limit. A crash after Kafka ack but before SQL commit republishes the same event ID, so the contract is at-least-once.

Investigate oldest pending age and `outbox_publish_failures_total` with broker/Registry connectivity, allowed schema evolution, SQL availability, event class metadata and disk space. Errors store class names, not secret-valued payloads. Per-key poison events block later pending rows for that key. Repair the actual configuration/schema/cause and let the same row retry. Do not delete rows or assign new event IDs to bypass ordering. Outbox records retain payload class names; releases must preserve old event class compatibility or drain/migrate pending rows before incompatible code changes.

Consumer identities remain order number, SKU, inventory CHECK/compensation operation and payment transaction reference. INIT does not add stock twice, CHECK results are deduplicated by order/SKU, compensation is deduplicated and Product cache put/tombstone replays are idempotent. Order/Payment SQL status/ref guards suppress repeated terminal effects. This is not an end-to-end exactly-once guarantee or autonomous saga recovery.

## H04 / DLT and aged orders

H04 remains deferred. Inspect PENDING age, DLT messages and every SKU outcome before deciding whether to replay. Preserve original IDs, prove which deductions committed, preserve the correct consumer offset/state and replay only after the underlying defect is corrected. Never compensate all lines without proving successful deductions; never replay stock adjustments with a new identity. Redis loss/TTL expiry can make saga evidence incomplete; suspend affected orders, reconstruct from durable order/broker/store/provider evidence and escalate for an explicit reconciliation decision. Deployment scripts do not flush/reset state or rerun inventory seeders.

## Online payment fence and receipt

Order's SQL row lock is authoritative. A VNPAY VALIDATED order acquires its stable payment-attempt fence before a URL can be returned. Once the fence exists, cancellation returns 409 ONLINE_PAYMENT_IN_FLIGHT. If the payment service fails after the remote fence commits, it conservatively retains the fence and retries the same reference; an uncertain state cannot release reserved stock.

A signed/merchant/amount-checked success records a durable provider receipt as SUCCESS_PENDING_ORDER. Order accepts it only while a matching reserved order is eligible; it completes the order and appends a durable decision. Payment becomes SUCCESS only after acceptance. A terminal/incompatible order remains terminal and is flagged for reconciliation; Payment becomes RECONCILIATION_REQUIRED. Stock is not automatically rededucted and the order is not revived. Browser return parameters and frontend success labels are never proof.

For an old CANCELLED/FAILED/PAYMENT_FAILED order with a real success receipt, compare provider settlement, order amount/ref, payment receipt, outbox/decision delivery and recorded stock compensation. A verified refund or reviewed manual accounting resolution may be needed. **No automated refund/reconciliation admin feature was added.** Use a reviewed operator procedure and recorded evidence; do not manipulate SQL status/fence blindly. Repair transient broker failure first and wait for the durable decision. Duplicated receipts/decisions must retain the original transaction reference and event/business IDs.

M01 expiry/retry policy and M02 return/IPN protocol remain deferred. An abandoned fenced VALIDATED order may remain blocked; never clear the fence merely because a URL looks expired. First establish the provider's terminal outcome and a safe business decision. Test signed duplicate/tampered callbacks and the provider's required acknowledgement rules in a real sandbox before enabling production VNPay.
