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
    public ResponseEntity<?> adjustInventory(@Valid @RequestBody Adjustment request,@RequestHeader(value="Idempotency-Key",required=false) String operationId) {
        if(operationId==null || !operationId.matches("[A-Za-z0-9-]{16,64}")) throw new com.myexampleproject.common.exception.DomainException(HttpStatus.BAD_REQUEST,"IDEMPOTENCY_KEY_REQUIRED","Stable operation ID is required");
        var event=new InventoryAdjustmentEvent(request.skuCode(),request.adjustmentQuantity(),operationId,request.reason());
        var previous=inventoryService.operation(operationId);
        if(previous!=null) {
            if(!previous.matches(event)) throw new com.myexampleproject.common.exception.DomainException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","Operation ID belongs to a different adjustment");
            return ResponseEntity.status(previous.status().equals("PENDING")?HttpStatus.ACCEPTED:HttpStatus.OK).body(previous);
        }
        if(request.adjustmentQuantity()==0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Adjustment must be nonzero");
        try {
            kafkaTemplate.send("inventory-adjustment-topic", request.skuCode(), event).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Inventory update interrupted");
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Inventory update could not be queued");
        }
        // The stream revalidates against current stock when processing this asynchronous command.
        return ResponseEntity.accepted().body(com.myexampleproject.inventoryservice.service.StockOperation.of(event,"ACCEPTED",null,null));
    }
    @GetMapping("/operations/{operationId}")
    public ResponseEntity<?> operation(@PathVariable String operationId) {
        var result=inventoryService.operation(operationId);
        // Broker-accepted commands can be invisible while awaiting the first stream transaction.
        return result==null ? ResponseEntity.accepted().body(Map.of("operationId",operationId,"status","PENDING")) : ResponseEntity.ok(result);
    }
    @GetMapping("/{sku}")
    public Map<String, Object> getInventory(@PathVariable String sku) {
        return Map.of("skuCode", sku, "quantity", inventoryService.getQuantity(sku));
    }
}
