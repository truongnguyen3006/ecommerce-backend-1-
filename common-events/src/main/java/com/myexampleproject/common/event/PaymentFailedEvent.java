package com.myexampleproject.common.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PaymentFailedEvent {
    private String orderNumber;
    private String reason;
    private String txnRef;
    public PaymentFailedEvent(String orderNumber, String reason) {
        this(orderNumber, reason, null);
    }
}
