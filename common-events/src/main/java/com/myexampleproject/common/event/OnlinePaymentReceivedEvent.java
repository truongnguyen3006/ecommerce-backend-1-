package com.myexampleproject.common.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

/** Verified provider receipt awaiting the authoritative order decision. */
@Data @NoArgsConstructor @AllArgsConstructor
public class OnlinePaymentReceivedEvent {
    private String orderNumber;
    private String txnRef;
    private BigDecimal amount;
}
