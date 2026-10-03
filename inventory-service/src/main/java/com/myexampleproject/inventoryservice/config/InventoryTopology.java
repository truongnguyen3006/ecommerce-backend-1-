package com.myexampleproject.inventoryservice.config;

import com.myexampleproject.common.event.*;
import io.micrometer.core.instrument.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.state.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Same keyed, transactional stock store; commands are validated and repeated checks are idempotent. */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryTopology {
    public static final String INVENTORY_STORE = "inventory-store";
    public static final String CHECK_STORE = "inventory-processed-checks";
    public static final String ADJUST_STORE = "inventory-processed-adjustments";
    public static final String OPERATION_REQUEST_STORE="inventory-operation-requests";
    public static final String OPERATION_RESULT_STORE="inventory-operation-results";
    private final SerdeConfig serdeConfig;
    private final MeterRegistry meterRegistry;
    private final Map<String, AtomicInteger> gauges = new ConcurrentHashMap<>();

    @Autowired
    public void buildTopology(StreamsBuilder builder) {
        var strings = Serdes.String();
        var resultSerde = serdeConfig.jsonSchemaSerde(InventoryCheckResult.class);
        builder.addStateStore(Stores.timestampedKeyValueStoreBuilder(Stores.persistentTimestampedKeyValueStore(INVENTORY_STORE), strings, Serdes.Integer()));
        builder.addStateStore(Stores.keyValueStoreBuilder(Stores.persistentKeyValueStore(CHECK_STORE), strings, resultSerde));
        builder.addStateStore(Stores.keyValueStoreBuilder(Stores.persistentKeyValueStore(ADJUST_STORE), strings, strings));

        builder.addStateStore(Stores.keyValueStoreBuilder(Stores.persistentKeyValueStore(OPERATION_REQUEST_STORE), strings, strings));
        builder.addStateStore(Stores.keyValueStoreBuilder(Stores.persistentKeyValueStore(OPERATION_RESULT_STORE), strings, strings));
        var adjustmentSerde=serdeConfig.jsonSchemaSerde(InventoryAdjustmentEvent.class);
        var input=builder.stream("inventory-adjustment-topic",Consumed.with(strings,adjustmentSerde))
            .filter((key,event) -> event!=null && event.getSkuCode()!=null && !event.getSkuCode().isBlank());
        // Globally claim the operation ID before repartitioning by SKU. EOS atomically commits claim + forward.
        var admin=input.filter((key,event) -> event.getOperationId()!=null && event.getOperationId().matches("[A-Za-z0-9-]{16,64}"))
            .selectKey((key,event) -> event.getOperationId())
            .repartition(Repartitioned.with(strings,adjustmentSerde).withName("admin-operations-by-id-v1").withNumberOfPartitions(10))
            .transform(() -> new Transformer<String,InventoryAdjustmentEvent,KeyValue<String,InventoryAdjustmentEvent>>() {
                private KeyValueStore<String,String> requests;
                public void init(ProcessorContext context) {requests=context.getStateStore(OPERATION_REQUEST_STORE);}
                public KeyValue<String,InventoryAdjustmentEvent> transform(String id,InventoryAdjustmentEvent event) {
                    if(requests.get(id)!=null) return null; // First immutable payload wins, including after a restart.
                    requests.put(id,com.myexampleproject.inventoryservice.service.StockOperation.of(event,"PENDING",null,null).json());
                    return KeyValue.pair(event.getSkuCode(),event);
                }
                public void close() {}
            },OPERATION_REQUEST_STORE);
        KStream<String, String> creations = builder.stream("product-created-topic", Consumed.with(strings, serdeConfig.jsonSchemaSerde(ProductCreatedEvent.class)))
                .filter((key, event) -> event != null && event.getSkuCode() != null && !event.getSkuCode().isBlank()
                        && event.getInitialQuantity() != null && event.getInitialQuantity() >= 0)
                .map((key, event) -> KeyValue.pair(event.getSkuCode(), "INIT:" + event.getInitialQuantity()));
        KStream<String,String> adjustments=input.filter((key,event) -> event.getOperationId()==null)
            .map((key,event) -> KeyValue.pair(event.getSkuCode(),"ADJUST:"+event.getAdjustmentQuantity()+":"+(event.getReason()==null?"":event.getReason())))
            .merge(admin.map((key,event) -> KeyValue.pair(event.getSkuCode(),"ADMIN:"+event.getAdjustmentQuantity()+":"+event.getOperationId()+":"+
                (event.getReason()==null?"~":java.util.Base64.getEncoder().encodeToString(event.getReason().getBytes(java.nio.charset.StandardCharsets.UTF_8))))));
        creations.merge(adjustments)
                .repartition(Repartitioned.with(strings, strings).withName("stock-changes-by-sku-v12").withNumberOfPartitions(10))
                .transform(() -> new Transformer<String, String, KeyValue<String, Integer>>() {
                    private KeyValueStore<String, ValueAndTimestamp<Integer>> stock;
                    private KeyValueStore<String, String> processed;
                    private ProcessorContext context;
                    private KeyValueStore<String,String> outcomes;
                    public void init(ProcessorContext context) {
                        this.context = context;stock = context.getStateStore(INVENTORY_STORE);processed = context.getStateStore(ADJUST_STORE);outcomes=context.getStateStore(OPERATION_RESULT_STORE);
                    }
                    public KeyValue<String, Integer> transform(String sku, String command) {
                        String[] parts = command.split(":", 3);
                        int quantity = Integer.parseInt(parts[1]);
                        ValueAndTimestamp<Integer> current = stock.get(sku);
                        if (parts[0].equals("INIT")) {
                            if (current == null) { stock.put(sku, ValueAndTimestamp.make(quantity, context.timestamp()));metric(sku, quantity); }
                            return null;
                        }
                        if(parts[0].equals("ADMIN")) {
                            String[] operation=parts[2].split(":",2);
                            String id=operation[0];
                            if(outcomes.get(id)!=null) return null;
                            String reason=operation[1].equals("~") ? null : new String(java.util.Base64.getDecoder().decode(operation[1]),java.nio.charset.StandardCharsets.UTF_8);
                            var event=new InventoryAdjustmentEvent(sku,quantity,id,reason);
                            long next=current==null?0:(long)current.value()+quantity;
                            String code=current==null?"SKU_NOT_FOUND":quantity==0 || next<0 || next>Integer.MAX_VALUE?"INVALID_STOCK_ADJUSTMENT":null;
                            if(code==null) {stock.put(sku,ValueAndTimestamp.make((int)next,context.timestamp()));metric(sku,(int)next);}
                            outcomes.put(id,com.myexampleproject.inventoryservice.service.StockOperation.of(event,code==null?"APPLIED":"REJECTED",code==null?(int)next:current==null?null:current.value(),code).json());
                            return null;
                        }
                        String reason = parts.length > 2 ? parts[2] : "";
                        String commandId = sku + ":" + reason;
                        boolean compensation = reason.startsWith("CANCELLED:") || reason.startsWith("COMPENSATION:") || reason.startsWith("INVENTORY_FAILED:");
                        if (compensation && processed.get(commandId) != null) return null;
                        if (current == null) { log.warn("Ignoring adjustment for unknown SKU {}", sku);return null; }
                        long next = (long)current.value() + quantity;
                        if (next < 0 || next > Integer.MAX_VALUE || quantity == 0) { log.warn("Rejected out-of-range adjustment for {}", sku);return null; }
                        stock.put(sku, ValueAndTimestamp.make((int)next, context.timestamp()));
                        if (compensation) processed.put(commandId, "done");
                        metric(sku, (int)next);
                        return null;
                    }
                    public void close() {}
                }, INVENTORY_STORE, ADJUST_STORE, OPERATION_RESULT_STORE);

        builder.stream("inventory-check-request-topic", Consumed.with(strings, serdeConfig.jsonSchemaSerde(InventoryCheckRequest.class)))
                .filter((key, request) -> request != null && request.getOrderNumber() != null && !request.getOrderNumber().isBlank()
                        && request.getItem() != null && request.getItem().getSkuCode() != null && !request.getItem().getSkuCode().isBlank())
                .selectKey((key, request) -> request.getItem().getSkuCode())
                .repartition(Repartitioned.with(strings, serdeConfig.jsonSchemaSerde(InventoryCheckRequest.class)).withName("checks-by-sku-v12").withNumberOfPartitions(10))
                .transform(() -> new Transformer<String, InventoryCheckRequest, KeyValue<String, InventoryCheckResult>>() {
                    private KeyValueStore<String, ValueAndTimestamp<Integer>> stock;
                    private KeyValueStore<String, InventoryCheckResult> processed;
                    private ProcessorContext context;
                    public void init(ProcessorContext context) {
                        this.context = context;stock = context.getStateStore(INVENTORY_STORE);processed = context.getStateStore(CHECK_STORE);
                    }
                    public KeyValue<String, InventoryCheckResult> transform(String sku, InventoryCheckRequest request) {
                        String checkId = request.getOrderNumber() + ":" + sku;
                        InventoryCheckResult previous = processed.get(checkId);
                        if (previous != null) return KeyValue.pair(request.getOrderNumber(), previous);
                        Integer quantity = request.getItem().getQuantity();
                        ValueAndTimestamp<Integer> value = stock.get(sku);
                        int current = value == null ? 0 : value.value();
                        boolean valid = quantity != null && quantity > 0 && value != null && quantity <= current;
                        String reason = valid ? null : "Invalid quantity or insufficient stock for " + sku;
                        if (valid) { stock.put(sku, ValueAndTimestamp.make(current - quantity, context.timestamp()));metric(sku, current - quantity); }
                        InventoryCheckResult result = new InventoryCheckResult(request.getOrderNumber(), request.getItem(), valid, reason);
                        processed.put(checkId, result);
                        return KeyValue.pair(request.getOrderNumber(), result);
                    }
                    public void close() {}
                }, INVENTORY_STORE, CHECK_STORE)
                .to("inventory-check-result-topic", Produced.with(strings, resultSerde));
    }

    private void metric(String sku, int quantity) {
        AtomicInteger gauge = gauges.computeIfAbsent(sku, key -> meterRegistry.gauge("inventory_stock_level", Tags.of("sku", key), new AtomicInteger(quantity)));
        if (gauge != null) gauge.set(quantity);
    }
}
