package com.myexampleproject.orderservice;

import com.myexampleproject.orderservice.service.OrderService;
import com.myexampleproject.orderservice.repository.OrderRepository;
import com.myexampleproject.orderservice.model.*;
import com.myexampleproject.orderservice.dto.*;
import com.myexampleproject.common.dto.OrderLineItemRequest;
import com.myexampleproject.common.event.*;
import com.myexampleproject.common.client.ProductCatalogClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import com.myexampleproject.common.outbox.JdbcOutbox;
import org.springframework.data.redis.core.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class OrderBusinessTests {
    OrderRepository repository=mock(OrderRepository.class);
    JdbcOutbox kafka=mock(JdbcOutbox.class);
    RedisTemplate<String,Object> redis=mock(RedisTemplate.class);
    HashOperations<String,Object,Object> hashes=mock(HashOperations.class);
    ValueOperations<String,Object> values=mock(ValueOperations.class);
    ProductCatalogClient catalog=mock(ProductCatalogClient.class);
    TransactionTemplate transactions=mock(TransactionTemplate.class);
    Map<String,Order> orders=new HashMap<>();Map<Object,Object> state=new HashMap<>();Map<String,Object> requests=new HashMap<>();
    OrderService service;
    @BeforeEach void setup() {
        service=new OrderService(new SimpleMeterRegistry(),repository,kafka,new ObjectMapper(),catalog,transactions,redis);service.initMetrics();
        when(redis.opsForHash()).thenReturn(hashes);when(redis.opsForValue()).thenReturn(values);
        when(repository.findByOrderNumberForUpdate(anyString())).thenAnswer(a -> Optional.ofNullable(orders.get(a.getArgument(0))));
        when(repository.findByOrderNumberWithItems(anyString())).thenAnswer(a -> Optional.ofNullable(orders.get(a.getArgument(0))));
        when(repository.findByOrderNumber(anyString())).thenAnswer(a -> Optional.ofNullable(orders.get(a.getArgument(0))));
        when(repository.saveAndFlush(any())).thenAnswer(a -> { Order o=a.getArgument(0);orders.put(o.getOrderNumber(),o);return o; });
        when(repository.save(any())).thenAnswer(a -> a.getArgument(0));
        when(hashes.putIfAbsent(anyString(),any(),any())).thenAnswer(a -> {Object key=a.getArgument(1);if(state.containsKey(key))return false;state.put(key,a.getArgument(2));return true;});
        when(hashes.entries(anyString())).thenAnswer(a -> new HashMap<>(state));
        when(values.setIfAbsent(anyString(),any(),any(java.time.Duration.class))).thenAnswer(a -> requests.putIfAbsent(a.getArgument(0),a.getArgument(1))==null);
        when(values.get(anyString())).thenAnswer(a -> requests.get(a.getArgument(0)));
        when(catalog.find(anyString())).thenAnswer(a -> new ProductCatalogClient.CatalogItem(a.getArgument(0),"Product",BigDecimal.TEN,null,"Red","40",true));
        doAnswer(a -> { ((Consumer)a.getArgument(0)).accept(null);return null; }).when(transactions).executeWithoutResult(any());
    }
    Order order(String status) {
        Order o=new Order();o.setOrderNumber("O");o.setUserId("A");o.setStatus(status);o.setPaymentMethod("COD");
        OrderLineItems first=new OrderLineItems();first.setSkuCode("SKU1");first.setQuantity(2);first.setPrice(BigDecimal.TEN);first.setOrder(o);
        OrderLineItems second=new OrderLineItems();second.setSkuCode("SKU2");second.setQuantity(1);second.setPrice(BigDecimal.TEN);second.setOrder(o);
        o.setOrderLineItemsList(List.of(first,second));o.setTotalPrice(BigDecimal.valueOf(30));orders.put("O",o);return o;
    }
    ConsumerRecord<String,Object> event(String topic,Object value) {return new ConsumerRecord<>(topic,0,1,"O",value);}
    void result(String sku,int quantity,boolean success) {service.handleInventoryCheckResult(List.of(event("inventory-check-result-topic",new InventoryCheckResult("O",new OrderLineItemRequest(sku,quantity),success,success?null:"Insufficient stock"))));}
    @Test void successfulPlacementUsesServerPricesAndReplayDoesNotInsertAnotherOrder() {
        OrderPlacedEvent event=new OrderPlacedEvent("O","A",List.of(new OrderLineItemRequest("SKU1",2)),"COD",null,null,null,null);
        service.handleOrderEvents(List.of(event("order-placed-topic",event)));service.handleOrderEvents(List.of(event("order-placed-topic",event)));
        assertThat(orders.get("O").getTotalPrice()).isEqualByComparingTo("20");verify(repository,times(1)).saveAndFlush(any());
    }
    @Test void repeatedCheckoutKeyReturnsSameOrderAndRejectsDifferentBody() {
        OrderRequest request=OrderRequest.builder().items(List.of(new OrderLineItemRequest("SKU1",1))).build();
        String first=service.placeOrder(request,"A","KEY");assertThat(service.placeOrder(request,"A","KEY")).isEqualTo(first);
        assertThatThrownBy(() -> service.placeOrder(OrderRequest.builder().items(List.of(new OrderLineItemRequest("SKU1",2))).build(),"A","KEY")).isInstanceOf(ResponseStatusException.class);
    }
    @Test void invalidOrderQuantityIsRejected() {assertThatThrownBy(() -> service.placeOrder(OrderRequest.builder().items(List.of(new OrderLineItemRequest("SKU1",0))).build(),"A")).isInstanceOf(ResponseStatusException.class);verifyNoInteractions(kafka);}
    @Test void insufficientInventoryWaitsForAllItemsAndRestoresOnlySuccessfulDeductionsOnce() {
        Order o=order("PENDING");result("SKU2",1,false);assertThat(o.getStatus()).isEqualTo("PENDING");result("SKU1",2,true);assertThat(o.getStatus()).isEqualTo("FAILED");result("SKU1",2,true);
        verify(kafka,times(1)).append(eq("inventory-adjustment-topic"),eq("SKU1"),argThat(e -> ((InventoryAdjustmentEvent)e).getAdjustmentQuantity()==2));
        verify(kafka,never()).append(eq("inventory-adjustment-topic"),eq("SKU2"),any());
    }
    @Test void duplicateInventoryResultCannotCompleteOrderEarly() {Order o=order("PENDING");result("SKU1",2,true);result("SKU1",2,true);assertThat(o.getStatus()).isEqualTo("PENDING");result("SKU2",1,true);assertThat(o.getStatus()).isEqualTo("VALIDATED");}
    @Test void cannotReadAnotherUsersOrderOrInternalPaymentContext() {
        order("VALIDATED");assertThatThrownBy(() -> service.getOrderDetails("O","B",false)).isInstanceOfSatisfying(ResponseStatusException.class,e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
        assertThatThrownBy(() -> service.getPaymentContext("O","B",false)).isInstanceOf(ResponseStatusException.class);
        assertThat(service.getOrderDetails("O","A",false).getUserId()).isEqualTo("A");
    }
    @Test void completedOrderCannotBeCancelled() {order("COMPLETED");assertThatThrownBy(() -> service.cancelOrder("O","A",false,null)).isInstanceOf(ResponseStatusException.class);verify(kafka,never()).append(eq("inventory-adjustment-topic"),anyString(),any());}
    @Test void cancellingPaymentFailedOrderDoesNotRestockAgain() {Order o=order("PAYMENT_FAILED");service.cancelOrder("O","A",false,"Cancel");assertThat(o.getStatus()).isEqualTo("CANCELLED");verify(kafka,never()).append(eq("inventory-adjustment-topic"),anyString(),any());}
    @Test void paidEventCannotReviveCancelledOrder() {Order o=order("CANCELLED");service.handleOrderEvents(List.of(event("payment-processed-topic",new PaymentProcessedEvent("O","P"))));assertThat(o.getStatus()).isEqualTo("CANCELLED");}
    void receipt(String ref) { service.handleOrderEvents(List.of(event("online-payment-received-topic", new OnlinePaymentReceivedEvent("O",ref,BigDecimal.valueOf(30))))); }
    @Test void issuedPaymentCannotBeCancelledAndCallbackCompletesWithoutReleasingStock() {
        Order o=order("VALIDATED");o.setPaymentMethod("VNPAY");service.beginOnlinePayment("O","A","TXN");
        assertThatThrownBy(() -> service.cancelOrder("O","A",false,null)).isInstanceOf(ResponseStatusException.class);
        receipt("TXN");receipt("TXN");assertThat(o.getStatus()).isEqualTo("COMPLETED");assertThat(o.isPaymentReconciliationRequired()).isFalse();
        verify(kafka,never()).append(eq("inventory-adjustment-topic"),anyString(),any());
    }
    @Test void committedReceiptWithDelayedOrderConsumptionStillBlocksCancellation() {
        Order o=order("VALIDATED");o.setPaymentMethod("VNPAY");service.beginOnlinePayment("O","A","TXN");
        // Payment has committed its receipt, but the order consumer has not run yet.
        assertThatThrownBy(() -> service.cancelOrder("O","A",false,null)).isInstanceOf(ResponseStatusException.class);
        receipt("TXN");assertThat(o.getStatus()).isEqualTo("COMPLETED");
        verify(kafka,never()).append(eq("inventory-adjustment-topic"),anyString(),any());
    }
    @Test void cancellationWinningBeforeAttemptPreventsUrlAndLateLegacyMoneyIsReconciled() {
        Order o=order("VALIDATED");o.setPaymentMethod("VNPAY");service.cancelOrder("O","A",false,null);
        assertThatThrownBy(() -> service.beginOnlinePayment("O","A","TXN")).isInstanceOf(ResponseStatusException.class);
        receipt("OLD-TXN");assertThat(o.getStatus()).isEqualTo("CANCELLED");assertThat(o.isPaymentReconciliationRequired()).isTrue();
        verify(kafka).append(eq("online-payment-decision-topic"),eq("O"),argThat(e -> !((OnlinePaymentDecisionEvent)e).isAccepted()));
        verify(kafka,times(2)).append(eq("inventory-adjustment-topic"),anyString(),any());
    }
    @Test void otherOwnerCannotStartPaymentOrCancel() {
        Order o=order("VALIDATED");o.setPaymentMethod("VNPAY");
        assertThatThrownBy(() -> service.beginOnlinePayment("O","B","TXN")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.cancelOrder("O","B",false,null)).isInstanceOf(ResponseStatusException.class);
        assertThat(o.getPaymentAttemptId()).isNull();verifyNoInteractions(kafka);
    }
    @Test void terminalOrdersAreNeverReopenedByLateMoney() {
        for (String status : List.of("CANCELLED","FAILED","PAYMENT_FAILED")) {
            Order o=order(status);o.setPaymentMethod("VNPAY");receipt("TXN");
            assertThat(o.getStatus()).isEqualTo(status);assertThat(o.isPaymentReconciliationRequired()).isTrue();
        }
        verify(kafka,never()).append(eq("inventory-adjustment-topic"),anyString(),any());
    }
    @Test void sqlProofSurvivesRedisLossAndServiceRestart() {
        Order o=order("PENDING");result("SKU1",2,true);state.clear();
        service=new OrderService(new SimpleMeterRegistry(),repository,kafka,new ObjectMapper(),catalog,transactions,redis);service.initMetrics();
        result("SKU2",1,true);assertThat(o.getStatus()).isEqualTo("VALIDATED");assertThat(o.getOrderLineItemsList()).allMatch(i -> Boolean.TRUE.equals(i.getInventoryOutcome()));
    }
    @Test void agedRetriesReuseBusinessIdentityAndStopWithoutRestoringUnknownDeductions() {
        Order o=order("PENDING");result("SKU1",2,true);java.time.LocalDateTime time=java.time.LocalDateTime.now();
        for(int i=0;i<9;i++) service.recoverWorkflow("O",time.plusHours(i));
        assertThat(o.getRecoveryAttempts()).isEqualTo(5);assertThat(o.isWorkflowInvestigationRequired()).isTrue();
        verify(kafka,times(5)).append(eq("inventory-check-request-topic"),eq("SKU2"),argThat(e -> ((InventoryCheckRequest)e).getOrderNumber().equals("O")));
        verify(kafka,never()).append(eq("inventory-adjustment-topic"),anyString(),any());
        result("SKU2",1,true);assertThat(o.getStatus()).isEqualTo("VALIDATED");assertThat(o.isWorkflowInvestigationRequired()).isFalse();
    }
    @Test void recoveryLeavesPaidCancelledAndCompensatedOrdersUntouched() {
        for(String status:List.of("COMPLETED","CANCELLED","FAILED","PAYMENT_FAILED")) {order(status);service.recoverWorkflow("O",java.time.LocalDateTime.now());}
        verifyNoInteractions(kafka);
    }
    @Test void unprovenFailureRequiresInvestigationWithoutStrandingUndocumentedCompensation() {
        Order o=order("PENDING");service.handleOrderEvents(List.of(event("order-failed-topic",new OrderFailedEvent("O","unproven"))));
        assertThat(o.getStatus()).isEqualTo("PENDING");assertThat(o.isWorkflowInvestigationRequired()).isTrue();verify(kafka,never()).append(eq("inventory-adjustment-topic"),anyString(),any());
    }
    @Test void staleReceiptAfterNewerFenceRequiresAccountingAndStaleFailureCannotRestoreStock() {
        Order o=order("VALIDATED");o.setPaymentMethod("VNPAY");o.setPaymentAttemptId("NEW");
        service.handleOrderEvents(List.of(event("payment-failed-topic",new PaymentFailedEvent("O","failed","OLD"))));
        receipt("OLD");assertThat(o.getStatus()).isEqualTo("VALIDATED");assertThat(o.isPaymentReconciliationRequired()).isTrue();
        verify(kafka,never()).append(eq("inventory-adjustment-topic"),anyString(),any());
    }

    @Test void deadLetterIsRecordedAndDoesNotPerformFinancialOrInventoryMutation() {
        var deadLetters=mock(com.myexampleproject.orderservice.service.WorkflowDeadLetters.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"deadLetters",deadLetters);
        Order o=order("VALIDATED");o.setPaymentMethod("VNPAY");o.setPaymentAttemptId("TXN");
        service.recordDeadLetters(List.of(event("online-payment-received-topic.DLT",new OnlinePaymentReceivedEvent("O","TXN",BigDecimal.valueOf(30)))));
        assertThat(o.getStatus()).isEqualTo("VALIDATED");assertThat(o.isWorkflowInvestigationRequired()).isTrue();
        verify(deadLetters).record("online-payment-received-topic.DLT",0,1,"O");verifyNoInteractions(kafka);
        assertThatThrownBy(() -> service.cancelOrder("O","A",false,null)).isInstanceOf(ResponseStatusException.class);
    }

    @Test void unprovenLegacySuccessCannotCompleteAnOnlineOrderAcrossANewerFence() {
        Order o=order("VALIDATED");o.setPaymentMethod("VNPAY");o.setPaymentAttemptId("NEW");
        service.handleOrderEvents(List.of(event("payment-processed-topic",new PaymentProcessedEvent("O","OLD"))));
        assertThat(o.getStatus()).isEqualTo("VALIDATED");assertThat(o.isPaymentReconciliationRequired()).isTrue();verify(kafka,never()).append(eq("inventory-adjustment-topic"),anyString(),any());
    }
    @Test void verifiedFailedIpnResolvesExpiredInvestigationAndRestoresOnce() {
        Order o=order("VALIDATED");o.setPaymentMethod("VNPAY");o.setPaymentAttemptId("TXN");o.setWorkflowInvestigationRequired(true);
        var failure=new PaymentFailedEvent("O","verified failure","TXN");
        service.handleOrderEvents(List.of(event("payment-failed-topic",failure)));service.handleOrderEvents(List.of(event("payment-failed-topic",failure)));
        assertThat(o.getStatus()).isEqualTo("PAYMENT_FAILED");assertThat(o.isWorkflowInvestigationRequired()).isFalse();
        service.cancelOrder("O","A",false,null);verify(kafka,times(2)).append(eq("inventory-adjustment-topic"),anyString(),any());
    }

    @Test void newlyIssuedAttemptOnAnOlderOrderDoesNotImmediatelyBecomeAged() {
        Order o=order("VALIDATED");o.setPaymentMethod("VNPAY");o.setOrderDate(java.time.LocalDateTime.now().minusDays(1));
        service.beginOnlinePayment("O","A","TXN");service.recoverWorkflow("O",java.time.LocalDateTime.now());
        assertThat(o.isWorkflowInvestigationRequired()).isFalse();assertThat(o.getRecoveryNextAt()).isAfter(java.time.LocalDateTime.now());
        service.recoverWorkflow("O",java.time.LocalDateTime.now().plusHours(1));assertThat(o.isWorkflowInvestigationRequired()).isTrue();
        verify(kafka,never()).append(eq("inventory-adjustment-topic"),anyString(),any());
    }

}
