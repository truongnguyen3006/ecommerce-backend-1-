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
import org.springframework.kafka.core.KafkaTemplate;
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
    KafkaTemplate<String,Object> kafka=mock(KafkaTemplate.class);
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
        when(kafka.send(anyString(),anyString(),any())).thenReturn(CompletableFuture.completedFuture(null));
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
        verify(kafka,times(1)).send(eq("inventory-adjustment-topic"),eq("SKU1"),argThat(e -> ((InventoryAdjustmentEvent)e).getAdjustmentQuantity()==2));
        verify(kafka,never()).send(eq("inventory-adjustment-topic"),eq("SKU2"),any());
    }
    @Test void duplicateInventoryResultCannotCompleteOrderEarly() {Order o=order("PENDING");result("SKU1",2,true);result("SKU1",2,true);assertThat(o.getStatus()).isEqualTo("PENDING");result("SKU2",1,true);assertThat(o.getStatus()).isEqualTo("VALIDATED");}
    @Test void cannotReadAnotherUsersOrderOrInternalPaymentContext() {
        order("VALIDATED");assertThatThrownBy(() -> service.getOrderDetails("O","B",false)).isInstanceOfSatisfying(ResponseStatusException.class,e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
        assertThatThrownBy(() -> service.getPaymentContext("O","B",false)).isInstanceOf(ResponseStatusException.class);
        assertThat(service.getOrderDetails("O","A",false).getUserId()).isEqualTo("A");
    }
    @Test void completedOrderCannotBeCancelled() {order("COMPLETED");assertThatThrownBy(() -> service.cancelOrder("O","A",false,null)).isInstanceOf(ResponseStatusException.class);verify(kafka,never()).send(eq("inventory-adjustment-topic"),anyString(),any());}
    @Test void cancellingPaymentFailedOrderDoesNotRestockAgain() {Order o=order("PAYMENT_FAILED");service.cancelOrder("O","A",false,"Cancel");assertThat(o.getStatus()).isEqualTo("CANCELLED");verify(kafka,never()).send(eq("inventory-adjustment-topic"),anyString(),any());}
    @Test void paidEventCannotReviveCancelledOrder() {Order o=order("CANCELLED");service.handleOrderEvents(List.of(event("payment-processed-topic",new PaymentProcessedEvent("O","P"))));assertThat(o.getStatus()).isEqualTo("CANCELLED");}
}
