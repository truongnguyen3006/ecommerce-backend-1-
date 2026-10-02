package com.myexampleproject.orderservice.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name="t_orders", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"orderNumber"})})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private String userId;

    @Column(name = "orderNumber", nullable = false, unique = true)
    private String orderNumber;
    private BigDecimal totalPrice;
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
    private List<OrderLineItems> orderLineItemsList = new ArrayList<>();
    @CreationTimestamp
    private LocalDateTime orderDate;
    private String status;

    @Column(length = 32)
    private String paymentMethod;

    @Column(length = 128)
    private String shippingAddressLabel;

    @Column(length = 128)
    private String shippingRecipientName;

    @Column(length = 32)
    private String shippingRecipientPhone;

    @Column(length = 512)
    private String shippingAddressLine;

    @Column(length = 255)
    private String cancelReason;

    private LocalDateTime cancelledAt;
}
