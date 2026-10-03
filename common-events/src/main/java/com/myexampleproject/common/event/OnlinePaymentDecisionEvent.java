package com.myexampleproject.common.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A rejected allocation decision means durable monetary reconciliation, never discarded money. */
@Data @NoArgsConstructor @AllArgsConstructor
public class OnlinePaymentDecisionEvent {
    private String orderNumber;
    private String txnRef;
    private boolean accepted;
    private String reason;
}
