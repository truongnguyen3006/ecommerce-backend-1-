package com.myexampleproject.orderservice;

import com.myexampleproject.common.exception.GlobalExceptionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({ GlobalExceptionHandler.class })
public class OrderServiceApplication {

	public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
	}
}

