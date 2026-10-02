package com.myexampleproject.paymentservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentTransactionResponse {
    private String orderNumber;
    private String provider;
    private String status;
    private BigDecimal amount;
    private String paymentUrl;
    private String txnRef;
    private String gatewayMessage;
}
