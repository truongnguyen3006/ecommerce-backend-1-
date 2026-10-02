package com.myexampleproject.paymentservice;
import com.myexampleproject.paymentservice.config.PaymentKafkaConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.core.*;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
@SpringJUnitConfig(PaymentKafkaConsumerConfig.class)
@TestPropertySource("classpath:application.properties")
class KafkaConfigurationTests {
    @Autowired ApplicationContext context;
    @MockitoBean KafkaTemplate<String,Object> template;
    @Test void consumerFactoriesResolveRuntimePropertiesAndUseManagedOffsets() {
        var factories=context.getBeansOfType(ConsumerFactory.class);
        assertThat(factories).isNotEmpty();
        for (var factory:factories.values()) {
            var props=factory.getConfigurationProperties();
            assertThat(props.get("bootstrap.servers").toString()).doesNotContain("${");
            assertThat(props.get("schema.registry.url").toString()).doesNotContain("${");
            assertThat(props.get("group.id").toString()).isNotBlank().doesNotContain("${");
            assertThat(props.get("enable.auto.commit")).isEqualTo(false);
            assertThat(props.get("isolation.level")).isEqualTo("read_committed");
        }
        assertThat(context.getBeansOfType(ConcurrentKafkaListenerContainerFactory.class)).isNotEmpty();
    }
}
