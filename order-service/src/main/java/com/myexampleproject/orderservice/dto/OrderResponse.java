package com.myexampleproject.orderservice.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.myexampleproject.common.dto.OrderLineItemsDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OrderResponse {
    private Long id;
    private String orderNumber;
    private String status;
    private BigDecimal totalPrice;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime orderDate;
    private List<OrderLineItemsDto> orderLineItemsList;
    private String userId;
    private String paymentMethod;
    private String shippingAddressLabel;
    private String shippingRecipientName;
    private String shippingRecipientPhone;
    private String shippingAddressLine;
    private String cancelReason;
    private LocalDateTime cancelledAt;
}
