package com.myexampleproject.apigateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

//đánh dấu đây là một ứng dụng Spring Boot,
// cho phép tự động cấu hình và quét các thành phần trong dự án.
@SpringBootApplication
public class ApiGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }

}
