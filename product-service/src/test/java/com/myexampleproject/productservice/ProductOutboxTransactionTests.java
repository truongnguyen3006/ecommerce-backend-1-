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
    @BeforeEach void setup() {repository.deleteAll();jdbc.update("DELETE FROM outbox_event");jdbc.update("DELETE FROM sku_identity");}
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

    @Test void deletedProductSkuCannotBeReusedAndReservationSurvivesDeletion() {
        var product=service.createProduct(request());service.deleteProductById(product.getId());
        assertThat(jdbc.queryForObject("SELECT retired FROM sku_identity WHERE sku_code='SKU'",Boolean.class)).isTrue();
        assertThatThrownBy(() -> service.createProduct(request())).isInstanceOfSatisfying(com.myexampleproject.common.exception.DomainException.class,e -> assertThat(e.getCode()).isEqualTo("SKU_RESERVED"));
        assertThat(repository.count()).isZero();
    }
    @Test void removingVariantPermanentlyRetiresSkuAndLiveSkuCannotBeClaimedTwice() {
        var product=service.createProduct(request());
        assertThatThrownBy(() -> service.createProduct(request())).isInstanceOf(com.myexampleproject.common.exception.DomainException.class);
        service.updateProduct(product.getId(),ProductRequest.builder().revision(0L).variants(List.of()).build());
        assertThat(jdbc.queryForObject("SELECT retired FROM sku_identity WHERE sku_code='SKU'",Boolean.class)).isTrue();
        assertThatThrownBy(() -> service.updateProduct(product.getId(),ProductRequest.builder().revision(1L).variants(request().getVariants()).build())).isInstanceOf(com.myexampleproject.common.exception.DomainException.class);
    }
    @Test void writesAndReadLabelsNormalizeConservativelyAndBlankCategoryCanStillBeCleared() {
        var input=request();input.setCategory("  Giày  ");input.getVariants().getFirst().setColor("  Đen  ");input.getVariants().getFirst().setSize(" XL ");
        var p=service.createProduct(input);assertThat(p.getCategory()).isEqualTo("Giày");assertThat(p.getVariants().getFirst().getColor()).isEqualTo("Đen");
        jdbc.update("UPDATE product_variant SET color='  Đen  ',size=' XL ' WHERE sku_code='SKU'");
        assertThat(service.getProductById(p.getId()).getVariants().getFirst().getColor()).isEqualTo("Đen");
        assertThat(service.getVariant("SKU").size()).isEqualTo("XL");
        service.updateProduct(p.getId(),ProductRequest.builder().revision(0L).category("   ").build());
        assertThat(service.getProductById(p.getId()).getCategory()).isNull();
    }
    @Test void staleFullReplacementCannotRemoveNewVariantOrPublishAnything() {
        var p=service.createProduct(request());
        var variants=new ArrayList<>(request().getVariants());variants.add(ProductVariantRequest.builder().skuCode("NEW").initialQuantity(2).build());
        service.updateProduct(p.getId(),ProductRequest.builder().revision(0L).variants(variants).build());
        int count=jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event",Integer.class);
        assertThatThrownBy(() -> service.updateProduct(p.getId(),ProductRequest.builder().revision(0L).variants(List.of()).build()))
            .isInstanceOfSatisfying(com.myexampleproject.common.exception.DomainException.class,e -> assertThat(e.getCode()).isEqualTo("PRODUCT_REVISION_CONFLICT"));
        assertThat(service.getProductById(p.getId()).getVariants()).extracting(ProductVariantResponse::getSkuCode).containsExactlyInAnyOrder("SKU","NEW");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event",Integer.class)).isEqualTo(count);
    }
    @Test void simultaneousRevisionZeroEditsHaveExactlyOneWinner() throws Exception {
        var p=service.createProduct(request());var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var futures=new ArrayList<java.util.concurrent.Future<Boolean>>();
            for(int i=0;i<2;i++) {final String name="Editor "+i;futures.add(pool.submit(() -> {start.await();try {service.updateProduct(p.getId(),ProductRequest.builder().revision(0L).name(name).build());return true;}catch(com.myexampleproject.common.exception.DomainException conflict){assertThat(conflict.getCode()).isEqualTo("PRODUCT_REVISION_CONFLICT");return false;}}));}
            start.countDown();int winners=0;for(var f:futures) if(f.get(10,java.util.concurrent.TimeUnit.SECONDS)) winners++;
            assertThat(winners).isEqualTo(1);assertThat(service.getProductById(p.getId()).getRevision()).isEqualTo(1);
        }
    }
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration(exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration.class)
    @EntityScan(basePackages={"com.myexampleproject.productservice.model","com.myexampleproject.common.outbox"})
    @EnableJpaRepositories(basePackageClasses=ProductRepository.class)
    static class Config {
        @Bean ObjectMapper mapper() {return new ObjectMapper().findAndRegisterModules();}
        @Bean JdbcOutbox outbox(JdbcTemplate jdbc,ObjectMapper mapper) {return new JdbcOutbox(jdbc,mapper,Clock.systemUTC());}
        @Bean com.myexampleproject.productservice.service.SkuIdentityService sku(JdbcTemplate jdbc) {
            RestTemplate rest=mock(RestTemplate.class);
            when(rest.getForObject(anyString(),eq(Map.class),anyString())).thenThrow(org.springframework.web.client.HttpClientErrorException.create(org.springframework.http.HttpStatus.NOT_FOUND,"absent",org.springframework.http.HttpHeaders.EMPTY,new byte[0],null));
            return new com.myexampleproject.productservice.service.SkuIdentityService(jdbc,rest,"http://inventory.test");
        }
        @Bean MeterRegistry metrics() {return new SimpleMeterRegistry();}
        @Bean ProductService service(ProductRepository repository,JdbcOutbox outbox,com.myexampleproject.productservice.repository.ProductVariantRepository variants,com.myexampleproject.productservice.service.SkuIdentityService sku) {return new ProductService(repository,outbox,variants,sku);}
    }
}
