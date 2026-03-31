package com.myexampleproject.paymentservice.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Getter
@Configuration
public class VNPayConfig {
    @Value("${vnpay.api.url:https://sandbox.vnpayment.vn/paymentv2/vpcpay.html}")
    private String apiUrl;

    @Value("${vnpay.tmn.code:}")
    private String tmnCode;

    @Value("${vnpay.secret.key:}")
    private String secretKey;

    @Value("${vnpay.return.url:http://localhost:8080/api/payment/vnpay/return}")
    private String returnUrl;

    @Value("${vnpay.ipn.url:http://localhost:8080/api/payment/vnpay/ipn}")
    private String ipnUrl;

    @Value("${vnpay.api.version:2.1.0}")
    private String version;

    @Value("${vnpay.command:pay}")
    private String command;

    @Value("${vnpay.order.type:other}")
    private String orderType;

    @Value("${app.frontend-base-url:http://localhost:3001}")
    private String frontendBaseUrl;
}
