package com.myexampleproject.inventoryservice.controller;

import com.myexampleproject.common.event.InventoryAdjustmentEvent;
import com.myexampleproject.inventoryservice.service.InventoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {
    public record Adjustment(@NotBlank @Size(max=255) String skuCode, @NotNull Integer adjustmentQuantity, @Size(max=255) String reason) {}
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final InventoryService inventoryService;
    @PostMapping("/adjust")
    public ResponseEntity<?> adjustInventory(@Valid @RequestBody Adjustment request) {
        int current = inventoryService.getQuantity(request.skuCode());
        long next = (long)current + request.adjustmentQuantity();
        if (request.adjustmentQuantity() == 0 || next < 0 || next > Integer.MAX_VALUE)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Adjustment would create invalid inventory");
        try {
            kafkaTemplate.send("inventory-adjustment-topic", request.skuCode(), new InventoryAdjustmentEvent(request.skuCode(), request.adjustmentQuantity(), request.reason())).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Inventory update interrupted");
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Inventory update could not be queued");
        }
        // The stream revalidates against current stock when processing this asynchronous command.
        return ResponseEntity.accepted().body(Map.of("status", "queued", "skuCode", request.skuCode()));
    }
    @GetMapping("/{sku}")
    public Map<String, Object> getInventory(@PathVariable String sku) {
        return Map.of("skuCode", sku, "quantity", inventoryService.getQuantity(sku));
    }
}
