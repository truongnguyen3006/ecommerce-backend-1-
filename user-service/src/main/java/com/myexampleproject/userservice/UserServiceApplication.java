package com.myexampleproject.userservice;

import com.myexampleproject.common.exception.GlobalExceptionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
@SpringBootApplication
@Import({ com.myexampleproject.common.health.ProductionDependenciesConfiguration.class, GlobalExceptionHandler.class })
public class UserServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(UserServiceApplication.class, args);
	}

}
