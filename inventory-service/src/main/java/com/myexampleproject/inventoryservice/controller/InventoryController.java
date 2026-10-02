package com.myexampleproject.inventoryservice.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myexampleproject.common.event.InventoryAdjustmentEvent;
import com.myexampleproject.inventoryservice.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final InventoryService inventoryService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ============================
    // ADJUST INVENTORY
    // ============================
    @PostMapping("/adjust")
    public ResponseEntity<?> adjustInventory(@RequestBody String rawBody) {
        log.info("RAW REQUEST BODY: {}", rawBody);

        InventoryAdjustmentEvent event;
        int delta;

        // ---------- VALIDATE JSON ----------
        try {
            JsonNode json = objectMapper.readTree(rawBody);

            if (!json.has("skuCode"))
                return ResponseEntity.badRequest().body(Map.of("error", "skuCode required"));

            if (!json.has("adjustmentQuantity"))
                return ResponseEntity.badRequest().body(Map.of("error", "adjustmentQuantity required"));

            String sku = json.get("skuCode").asText();
            delta = Integer.parseInt(json.get("adjustmentQuantity").asText());
            String reason = json.has("reason") ? json.get("reason").asText() : null;

            event = new InventoryAdjustmentEvent(sku, delta, reason);

        } catch (Exception e) {
            log.error("JSON parse error", e);
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid JSON"));
        }

        if (delta == 0)
            return ResponseEntity.badRequest().body(Map.of("error", "Adjustment cannot be zero"));

        // ---------- READ CURRENT STOCK ----------
        Integer current = inventoryService.getQuantity(event.getSkuCode());
        if (current == null)
            return ResponseEntity.status(503).body(Map.of("error", "State store not ready"));

        // ---------- PREVENT NEGATIVE STOCK ----------
        if (current + delta < 0)
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Inventory cannot be negative",
                    "current", current,
                    "attempted", delta
            ));

        // ---------- PRODUCE EVENT ----------
        kafkaTemplate.send("inventory-adjustment-topic", event.getSkuCode(), event);

        // ---------- WAIT FOR ASYNC UPDATE ----------
        Integer updated = inventoryService.waitForUpdatedQuantity(event.getSkuCode());
        if (updated == null)
            return ResponseEntity.accepted().body(Map.of("status", "queued"));

        // ---------- RETURN RESULT ----------
        return ResponseEntity.ok(Map.of(
                "skuCode", event.getSkuCode(),
                "newQuantity", updated
        ));
    }

    // ============================
    // GET INVENTORY
    // ============================
    @GetMapping("/{sku}")
    public ResponseEntity<?> getInventory(@PathVariable String sku) {
        Integer qty = inventoryService.getQuantity(sku);

        if (qty == null)
            return ResponseEntity.status(503).body(Map.of("error", "State store not ready"));

        return ResponseEntity.ok(Map.of("skuCode", sku, "quantity", qty));
    }
}
