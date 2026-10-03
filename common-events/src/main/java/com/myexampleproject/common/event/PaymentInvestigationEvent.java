package com.myexampleproject.common.event;

public record PaymentInvestigationEvent(String orderNumber, String txnRef, String reason) {}
