package com.myexampleproject.paymentservice.repository;

import com.myexampleproject.paymentservice.model.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {
    Optional<PaymentTransaction> findByOrderNumber(String orderNumber);
    Optional<PaymentTransaction> findByTxnRef(String txnRef);
}
