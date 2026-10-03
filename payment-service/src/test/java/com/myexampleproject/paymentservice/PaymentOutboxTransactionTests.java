package com.myexampleproject.paymentservice;
import com.myexampleproject.paymentservice.service.PaymentService;
import com.myexampleproject.paymentservice.repository.PaymentTransactionRepository;
import com.myexampleproject.paymentservice.model.*;
import com.myexampleproject.paymentservice.dto.*;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;
import java.time.Clock;
import java.math.BigDecimal;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import com.myexampleproject.paymentservice.config.VNPayConfig;
import com.myexampleproject.paymentservice.util.VNPayUtil;
@SpringBootTest(classes=PaymentOutboxTransactionTests.Config.class, webEnvironment=SpringBootTest.WebEnvironment.NONE, properties={
    "spring.datasource.url=jdbc:h2:mem:payment_outbox;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate", "eureka.client.enabled=false",
    "spring.cloud.discovery.enabled=false", "app.seed-products.enabled=false", "app.seed-admin.enabled=false"
})
class PaymentOutboxTransactionTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcOutbox outbox;
    @Autowired PaymentService service;
    @Autowired PaymentTransactionRepository repository;
    @BeforeEach void setup() {
        repository.deleteAll();jdbc.update("DELETE FROM outbox_event");
        repository.saveAndFlush(PaymentTransaction.builder().orderNumber("TX-ORDER").txnRef("REF").provider("VNPAY").amount(BigDecimal.TEN).status("PENDING").build());
    }
    Map<String,String> success() {
        Map<String,String> p=new HashMap<>(Map.of("vnp_TxnRef","REF","vnp_TmnCode","TEST","vnp_Amount","1000","vnp_ResponseCode","00","vnp_TransactionStatus","00"));
        p.put("vnp_SecureHash",VNPayUtil.hmacSHA512("test-only-key",VNPayUtil.buildHashData(p)));return p;
    }
    @Test void providerReceiptRollbackCannotEscapeToKafka() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {service.handleVnpayIpn(success());throw new IllegalStateException("commit failed");})).isInstanceOf(IllegalStateException.class);
        assertThat(repository.findByTxnRef("REF").orElseThrow().getStatus()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event",Integer.class)).isZero();
    }
    @Test void moneyReceiptAndAcceptedSuccessBothHaveCommittedDeliveryIntents() {
        service.handleVnpayIpn(success());
        assertThat(repository.findByTxnRef("REF").orElseThrow().isProviderSuccessReceived()).isTrue();
        assertThat(jdbc.queryForList("SELECT topic FROM outbox_event",String.class)).containsExactly("online-payment-received-topic");
        service.handleOnlinePaymentDecisions(List.of(new org.apache.kafka.clients.consumer.ConsumerRecord<>("online-payment-decision-topic",0,1,"TX-ORDER",new OnlinePaymentDecisionEvent("TX-ORDER","REF",true,"ACCEPTED"))));
        assertThat(repository.findByTxnRef("REF").orElseThrow().getStatus()).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForList("SELECT topic FROM outbox_event ORDER BY id",String.class)).containsExactly("online-payment-received-topic","payment-processed-topic");
    }
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration(exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration.class)
    @EntityScan(basePackages={"com.myexampleproject.paymentservice.model","com.myexampleproject.common.outbox"})
    @EnableJpaRepositories(basePackageClasses=PaymentTransactionRepository.class)
    static class Config {
        @Bean ObjectMapper mapper() {return new ObjectMapper().findAndRegisterModules();}
        @Bean JdbcOutbox outbox(JdbcTemplate jdbc,ObjectMapper mapper) {return new JdbcOutbox(jdbc,mapper,Clock.systemUTC());}
        @Bean MeterRegistry metrics() {return new SimpleMeterRegistry();}
        @Bean RestTemplate rest() {return mock(RestTemplate.class);}
        @Bean VNPayConfig config() {VNPayConfig c=mock(VNPayConfig.class);when(c.getTmnCode()).thenReturn("TEST");when(c.getSecretKey()).thenReturn("test-only-key");return c;}
        @Bean PaymentService service(JdbcOutbox outbox,TransactionTemplate tx,ObjectMapper mapper,PaymentTransactionRepository repository,VNPayConfig config,RestTemplate rest) {
            return new PaymentService(outbox,tx,mapper,repository,config,rest);
        }
    }
}
