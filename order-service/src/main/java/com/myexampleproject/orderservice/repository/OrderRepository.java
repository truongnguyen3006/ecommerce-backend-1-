package com.myexampleproject.orderservice.repository;

import com.myexampleproject.orderservice.model.Order;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    @Query("SELECT o.orderNumber FROM Order o WHERE o.status IN ('PENDING','VALIDATED') AND o.workflowInvestigationRequired = false AND o.orderDate < :cutoff AND (o.recoveryNextAt IS NULL OR o.recoveryNextAt <= :now) ORDER BY o.orderDate")
    List<String> findRecoveryCandidates(@Param("cutoff") java.time.LocalDateTime cutoff,
            @Param("now") java.time.LocalDateTime now, org.springframework.data.domain.Pageable page);
    Optional<Order> findByOrderNumber(String orderNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.orderNumber = :orderNumber")
    Optional<Order> findByOrderNumberForUpdate(@Param("orderNumber") String orderNumber);

    @EntityGraph(attributePaths = "orderLineItemsList")
    @Query("SELECT o FROM Order o WHERE o.orderNumber = :orderNumber")
    Optional<Order> findByOrderNumberWithItems(@Param("orderNumber") String orderNumber);

    @EntityGraph(attributePaths = "orderLineItemsList")
    List<Order> findAllByUserIdOrderByOrderDateDesc(String userId);

    @EntityGraph(attributePaths = "orderLineItemsList")
    List<Order> findAllByOrderByOrderDateDesc();
}
