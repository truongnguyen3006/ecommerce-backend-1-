package com.myexampleproject.paymentservice;

import com.myexampleproject.common.exception.GlobalExceptionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
@SpringBootApplication
@Import({ com.myexampleproject.common.health.ProductionDependenciesConfiguration.class, GlobalExceptionHandler.class, com.myexampleproject.common.outbox.OutboxConfiguration.class })
@org.springframework.boot.autoconfigure.domain.EntityScan(basePackages = {"com.myexampleproject.paymentservice.model", "com.myexampleproject.common.outbox"})
@org.springframework.scheduling.annotation.EnableScheduling
public class PaymentServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(PaymentServiceApplication.class, args);
	}

}
