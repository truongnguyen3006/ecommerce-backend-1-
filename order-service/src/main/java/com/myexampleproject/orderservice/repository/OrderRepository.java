package com.myexampleproject.orderservice.repository;

import com.myexampleproject.orderservice.model.Order;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByOrderNumber(String orderNumber);

    @EntityGraph(attributePaths = "orderLineItemsList")
    @Query("SELECT o FROM Order o WHERE o.orderNumber = :orderNumber")
    Optional<Order> findByOrderNumberWithItems(@Param("orderNumber") String orderNumber);

    @EntityGraph(attributePaths = "orderLineItemsList")
    List<Order> findAllByUserIdOrderByOrderDateDesc(String userId);

    @EntityGraph(attributePaths = "orderLineItemsList")
    List<Order> findAllByOrderByOrderDateDesc();
}
