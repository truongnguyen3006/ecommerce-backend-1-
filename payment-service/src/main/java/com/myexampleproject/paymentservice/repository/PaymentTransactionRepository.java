package com.myexampleproject.paymentservice.repository;

import com.myexampleproject.paymentservice.model.PaymentTransaction;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {
    Optional<PaymentTransaction> findByOrderNumber(String orderNumber);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentTransaction p WHERE p.orderNumber = :orderNumber")
    Optional<PaymentTransaction> findByOrderNumberForUpdate(@Param("orderNumber") String orderNumber);
    Optional<PaymentTransaction> findByTxnRef(String txnRef);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentTransaction p WHERE p.txnRef = :txnRef")
    Optional<PaymentTransaction> findByTxnRefForUpdate(@Param("txnRef") String txnRef);
}
