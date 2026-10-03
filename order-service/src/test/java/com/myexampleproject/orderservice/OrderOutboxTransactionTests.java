package com.myexampleproject.orderservice;
import com.myexampleproject.orderservice.service.OrderService;
import com.myexampleproject.orderservice.repository.OrderRepository;
import com.myexampleproject.orderservice.model.*;
import com.myexampleproject.orderservice.model.Order;
import com.myexampleproject.orderservice.dto.*;
import com.myexampleproject.common.outbox.*;
import com.myexampleproject.common.event.*;
import com.myexampleproject.common.client.ProductCatalogClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;
import java.time.Clock;
import java.math.BigDecimal;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(classes=OrderOutboxTransactionTests.Config.class, webEnvironment=SpringBootTest.WebEnvironment.NONE, properties={
    "spring.datasource.url=jdbc:h2:mem:order_outbox;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate", "eureka.client.enabled=false",
    "spring.cloud.discovery.enabled=false", "app.seed-products.enabled=false", "app.seed-admin.enabled=false"
})
class OrderOutboxTransactionTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcOutbox outbox;
    @Autowired OrderService service;
    @Autowired OrderRepository repository;
    @BeforeEach void setup() {
        repository.deleteAll();jdbc.update("DELETE FROM outbox_event");
        Order order=new Order();order.setOrderNumber("TX-ORDER");order.setUserId("OWNER");order.setStatus("VALIDATED");order.setPaymentMethod("COD");order.setTotalPrice(BigDecimal.TEN);
        OrderLineItems item=new OrderLineItems();item.setOrder(order);item.setSkuCode("SKU");item.setQuantity(1);item.setPrice(BigDecimal.TEN);
        order.setOrderLineItemsList(new ArrayList<>(List.of(item)));repository.saveAndFlush(order);
    }
    @Test void cancellationRollbackCannotReleaseInventory() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {service.cancelOrder("TX-ORDER","OWNER",false,null);throw new IllegalStateException("later SQL failure");})).isInstanceOf(IllegalStateException.class);
        assertThat(repository.findByOrderNumber("TX-ORDER").orElseThrow().getStatus()).isEqualTo("VALIDATED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event",Integer.class)).isZero();
    }
    @Test void committedCancellationHasDurableRestorationAndStatusIntents() {
        service.cancelOrder("TX-ORDER","OWNER",false,null);
        assertThat(repository.findByOrderNumber("TX-ORDER").orElseThrow().getStatus()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForList("SELECT topic FROM outbox_event",String.class)).containsExactly("inventory-adjustment-topic","order-status-topic");
    }
    @Test void paymentFenceAndCancellationSerializeOnTheSameRealOrderRow() throws Exception {
        tx.executeWithoutResult(s -> {Order o=repository.findByOrderNumberForUpdate("TX-ORDER").orElseThrow();o.setPaymentMethod("VNPAY");repository.save(o);});
        var fenceHeld=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        try (var threads=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=threads.submit(() -> tx.executeWithoutResult(s -> {
                service.beginOnlinePayment("TX-ORDER","OWNER","REF");fenceHeld.countDown();
                try {if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");} catch (InterruptedException e) {throw new RuntimeException(e);}
            }));
            assertThat(fenceHeld.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var cancel=threads.submit(() -> {try {service.cancelOrder("TX-ORDER","OWNER",false,null);return "cancelled";} catch(org.springframework.web.server.ResponseStatusException e) {return "blocked";}});
            release.countDown();first.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(cancel.get(5,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("blocked");
        } finally {release.countDown();}
        assertThat(repository.findByOrderNumber("TX-ORDER").orElseThrow().getPaymentAttemptId()).isEqualTo("REF");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event",Integer.class)).isZero();
    }
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration(exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration.class)
    @EntityScan(basePackages={"com.myexampleproject.orderservice.model","com.myexampleproject.common.outbox"})
    @EnableJpaRepositories(basePackageClasses=OrderRepository.class)
    static class Config {
        @Bean ObjectMapper mapper() {return new ObjectMapper().findAndRegisterModules();}
        @Bean JdbcOutbox outbox(JdbcTemplate jdbc,ObjectMapper mapper) {return new JdbcOutbox(jdbc,mapper,Clock.systemUTC());}
        @Bean MeterRegistry metrics() {return new SimpleMeterRegistry();}
        @Bean RedisTemplate<String,Object> redis() {return mock(RedisTemplate.class);}
        @Bean ProductCatalogClient catalog() {return mock(ProductCatalogClient.class);}
        @Bean OrderService service(MeterRegistry metrics,OrderRepository repository,JdbcOutbox outbox,ObjectMapper mapper,ProductCatalogClient catalog,TransactionTemplate tx,RedisTemplate<String,Object> redis) {
            return new OrderService(metrics,repository,outbox,mapper,catalog,tx,redis);
        }
    }
}
