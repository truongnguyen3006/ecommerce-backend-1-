package com.myexampleproject.inventoryservice;

import com.myexampleproject.inventoryservice.config.*;
import com.myexampleproject.common.event.*;
import com.myexampleproject.common.dto.OrderLineItemRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.streams.*;
import org.apache.kafka.common.serialization.Serdes;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class InventoryTopologyTests {
    @TempDir Path temp;
    TopologyTestDriver driver;
    TestInputTopic<String,ProductCreatedEvent> products;
    TestInputTopic<String,InventoryAdjustmentEvent> adjustments;
    TestInputTopic<String,InventoryCheckRequest> checks;
    TestOutputTopic<String,InventoryCheckResult> results;
    @BeforeEach void setup() {
        SerdeConfig serde = new SerdeConfig();ReflectionTestUtils.setField(serde,"schemaRegistryUrl","mock://inventory-"+UUID.randomUUID());
        StreamsBuilder builder = new StreamsBuilder();new InventoryTopology(serde,new SimpleMeterRegistry()).buildTopology(builder);
        Properties properties = new Properties();properties.put(StreamsConfig.APPLICATION_ID_CONFIG,"inventory-tests");properties.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG,"dummy:9092");properties.put(StreamsConfig.STATE_DIR_CONFIG,temp.toString());
        driver = new TopologyTestDriver(builder.build(),properties);
        products = driver.createInputTopic("product-created-topic",Serdes.String().serializer(),serde.jsonSchemaSerde(ProductCreatedEvent.class).serializer());
        adjustments = driver.createInputTopic("inventory-adjustment-topic",Serdes.String().serializer(),serde.jsonSchemaSerde(InventoryAdjustmentEvent.class).serializer());
        checks = driver.createInputTopic("inventory-check-request-topic",Serdes.String().serializer(),serde.jsonSchemaSerde(InventoryCheckRequest.class).serializer());
        results = driver.createOutputTopic("inventory-check-result-topic",Serdes.String().deserializer(),serde.jsonSchemaSerde(InventoryCheckResult.class).deserializer());
        products.pipeInput("SKU",new ProductCreatedEvent("SKU",5));
    }
    @AfterEach void close() { if (driver != null) driver.close(); }
    int stock() { return driver.<String,Integer>getTimestampedKeyValueStore(InventoryTopology.INVENTORY_STORE).get("SKU").value(); }
    InventoryCheckResult check(String order, int quantity) {
        checks.pipeInput("arbitrary-client-key",new InventoryCheckRequest(order,new OrderLineItemRequest("SKU",quantity)));return results.readValue();
    }
    @Test void repeatedInitializationDoesNotIncreaseStock() { products.pipeInput("SKU",new ProductCreatedEvent("SKU",10000));assertThat(stock()).isEqualTo(5); }
    @Test void validOrderDeductsExactlyOnce() { assertThat(check("ONE",2).isSuccess()).isTrue();assertThat(stock()).isEqualTo(3);assertThat(check("ONE",2).isSuccess()).isTrue();assertThat(stock()).isEqualTo(3); }
    @Test void insufficientAndInvalidQuantitiesNeverDeduct() { assertThat(check("OVER",6).isSuccess()).isFalse();assertThat(check("ZERO",0).isSuccess()).isFalse();assertThat(check("NEGATIVE",-4).isSuccess()).isFalse();assertThat(stock()).isEqualTo(5); }
    @Test void stockAdjustmentsNeverClampToAnInvalidResult() { adjustments.pipeInput("SKU",new InventoryAdjustmentEvent("SKU",-6,"admin"));assertThat(stock()).isEqualTo(5);adjustments.pipeInput("SKU",new InventoryAdjustmentEvent("SKU",2,"admin"));assertThat(stock()).isEqualTo(7); }
    @Test void duplicateCompensationIsIgnored() { check("ONE",2);InventoryAdjustmentEvent event = new InventoryAdjustmentEvent("SKU",2,"CANCELLED:ONE");adjustments.pipeInput("SKU",event);adjustments.pipeInput("SKU",event);assertThat(stock()).isEqualTo(5); }
    @Test void multipleOrdersCannotConsumeMoreThanAvailableStock() { int successes=0;for(int i=0;i<20;i++) if(check("O"+i,1).isSuccess()) successes++;assertThat(successes).isEqualTo(5);assertThat(stock()).isZero(); }
}
