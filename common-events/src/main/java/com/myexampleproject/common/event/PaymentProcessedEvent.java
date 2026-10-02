package com.myexampleproject.common.event;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PaymentProcessedEvent {
    private String orderNumber;
    private String paymentId; // Có thể thêm ID giao dịch thanh toán
}