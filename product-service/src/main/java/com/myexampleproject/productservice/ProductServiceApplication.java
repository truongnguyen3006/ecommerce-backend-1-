package com.myexampleproject.productservice;

import com.myexampleproject.common.exception.GlobalExceptionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Import;
@SpringBootApplication
@Import({ GlobalExceptionHandler.class, com.myexampleproject.common.outbox.OutboxConfiguration.class })
@org.springframework.boot.autoconfigure.domain.EntityScan(basePackages = {"com.myexampleproject.productservice.model", "com.myexampleproject.common.outbox"})
@EnableCaching // <-- Kích hoạt tính năng cache
public class ProductServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(ProductServiceApplication.class, args);
	}

}
