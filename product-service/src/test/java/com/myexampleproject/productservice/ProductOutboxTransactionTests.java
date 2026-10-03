package com.myexampleproject.productservice;
import com.myexampleproject.productservice.service.ProductService;
import com.myexampleproject.productservice.repository.ProductRepository;
import com.myexampleproject.productservice.model.*;
import com.myexampleproject.productservice.dto.*;
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
@SpringBootTest(classes=ProductOutboxTransactionTests.Config.class, webEnvironment=SpringBootTest.WebEnvironment.NONE, properties={
    "spring.datasource.url=jdbc:h2:mem:product_outbox;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate", "eureka.client.enabled=false",
    "spring.cloud.discovery.enabled=false", "app.seed-products.enabled=false", "app.seed-admin.enabled=false"
})
class ProductOutboxTransactionTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcOutbox outbox;
    @Autowired ProductService service;
    @Autowired ProductRepository repository;
    @BeforeEach void setup() {repository.deleteAll();jdbc.update("DELETE FROM outbox_event");}
    ProductRequest request() {return ProductRequest.builder().name("Test product").basePrice(BigDecimal.TEN).variants(List.of(ProductVariantRequest.builder().skuCode("SKU").initialQuantity(3).build())).build();}
    @Test void creationCommitsInventoryInitializationAndCacheWithProduct() {
        service.createProduct(request());assertThat(repository.count()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT topic FROM outbox_event ORDER BY id",String.class)).containsExactly("product-created-topic","product-cache-update-topic");
        assertThat(jdbc.queryForObject("SELECT payload FROM outbox_event WHERE topic='product-created-topic'",String.class)).contains("initialQuantity", "3");
    }
    @Test void creationRollbackLeavesNeitherProductNorInventoryIntent() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {service.createProduct(request());throw new IllegalStateException("abort");})).isInstanceOf(IllegalStateException.class);
        assertThat(repository.count()).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event",Integer.class)).isZero();
    }
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration(exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration.class)
    @EntityScan(basePackages={"com.myexampleproject.productservice.model","com.myexampleproject.common.outbox"})
    @EnableJpaRepositories(basePackageClasses=ProductRepository.class)
    static class Config {
        @Bean ObjectMapper mapper() {return new ObjectMapper().findAndRegisterModules();}
        @Bean JdbcOutbox outbox(JdbcTemplate jdbc,ObjectMapper mapper) {return new JdbcOutbox(jdbc,mapper,Clock.systemUTC());}
        @Bean MeterRegistry metrics() {return new SimpleMeterRegistry();}
        @Bean ProductService service(ProductRepository repository,JdbcOutbox outbox,com.myexampleproject.productservice.repository.ProductVariantRepository variants) {return new ProductService(repository,outbox,variants);}
    }
}
