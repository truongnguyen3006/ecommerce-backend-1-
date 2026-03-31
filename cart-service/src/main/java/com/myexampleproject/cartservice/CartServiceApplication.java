package com.myexampleproject.cartservice;

import com.myexampleproject.common.exception.GlobalExceptionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableAsync;

//đánh dấu đây là một ứng dụng Spring Boot,
// cho phép tự động cấu hình và quét các thành phần trong dự án.
@SpringBootApplication
@Import({ GlobalExceptionHandler.class })
@EnableAsync
public class CartServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CartServiceApplication.class, args);
    }

}
