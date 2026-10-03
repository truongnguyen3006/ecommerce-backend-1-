package com.myexampleproject.orderservice;

import com.myexampleproject.common.exception.GlobalExceptionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({ com.myexampleproject.common.health.ProductionDependenciesConfiguration.class, GlobalExceptionHandler.class, com.myexampleproject.common.outbox.OutboxConfiguration.class })
@org.springframework.boot.autoconfigure.domain.EntityScan(basePackages = {"com.myexampleproject.orderservice.model", "com.myexampleproject.common.outbox"})
public class OrderServiceApplication {

	public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
	}
}

