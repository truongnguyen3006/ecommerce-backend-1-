package com.myexampleproject.paymentservice.repository;

import com.myexampleproject.paymentservice.model.PaymentTransaction;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {
    @Query("SELECT p.txnRef FROM PaymentTransaction p WHERE (p.status = 'PENDING' AND p.expiresAt <= :now) OR (p.status = 'SUCCESS_PENDING_ORDER' AND p.lastCallbackAt < :cutoff AND (p.recoveryNextAt IS NULL OR p.recoveryNextAt <= :now)) ORDER BY p.id")
    java.util.List<String> findRecoveryCandidates(@Param("now") java.time.LocalDateTime now,
            @Param("cutoff") java.time.LocalDateTime cutoff, org.springframework.data.domain.Pageable page);
    Optional<PaymentTransaction> findByOrderNumber(String orderNumber);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentTransaction p WHERE p.orderNumber = :orderNumber")
    Optional<PaymentTransaction> findByOrderNumberForUpdate(@Param("orderNumber") String orderNumber);
    Optional<PaymentTransaction> findByTxnRef(String txnRef);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentTransaction p WHERE p.txnRef = :txnRef")
    Optional<PaymentTransaction> findByTxnRefForUpdate(@Param("txnRef") String txnRef);
}
