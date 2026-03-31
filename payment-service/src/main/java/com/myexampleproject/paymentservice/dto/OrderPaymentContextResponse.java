package com.myexampleproject.paymentservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderPaymentContextResponse {
    private String orderNumber;
    private String userId;
    private String status;
    private String paymentMethod;
    private BigDecimal totalPrice;
    private String shippingRecipientName;
    private String shippingRecipientPhone;
    private String shippingAddressLine;
}
