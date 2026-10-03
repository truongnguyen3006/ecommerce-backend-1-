package com.myexampleproject.inventoryservice;
import com.myexampleproject.inventoryservice.controller.InventoryController;
import com.myexampleproject.inventoryservice.service.*;
import com.myexampleproject.common.event.InventoryAdjustmentEvent;
import com.myexampleproject.common.exception.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class StockOperationControllerTests {
 InventoryService service=mock(InventoryService.class);KafkaTemplate<String,Object> kafka=mock(KafkaTemplate.class);InventoryController controller=new InventoryController(kafka,service);
 InventoryController.Adjustment request=new InventoryController.Adjustment("SKU",2,"fixture");String id=UUID.randomUUID().toString();
 @Test void acceptedResponseDoesNotClaimApplicationAndCarriesStableOperationId() throws Exception {
  when(kafka.send(anyString(),anyString(),any())).thenReturn(CompletableFuture.completedFuture(null));
  var response=controller.adjustInventory(request,id);assertThat(response.getStatusCode().value()).isEqualTo(202);assertThat(((StockOperation)response.getBody()).status()).isEqualTo("ACCEPTED");
  verify(kafka).send(eq("inventory-adjustment-topic"),eq("SKU"),argThat(e -> id.equals(((InventoryAdjustmentEvent)e).getOperationId())));
 }
 @Test void matchingRetryReturnsStoredFinalResultWithoutRequeueing() {
  when(service.operation(id)).thenReturn(new StockOperation(id,"SKU",2,"fixture","APPLIED",7,null));
  assertThat(controller.adjustInventory(request,id).getStatusCode().value()).isEqualTo(200);verifyNoInteractions(kafka);
 }
 @Test void mismatchingRetryIsExplicitlyRejected() {
  when(service.operation(id)).thenReturn(new StockOperation(id,"OTHER",2,"fixture","APPLIED",7,null));
  assertThatThrownBy(() -> controller.adjustInventory(request,id)).isInstanceOfSatisfying(DomainException.class,e -> assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT"));verifyNoInteractions(kafka);
 }
}
