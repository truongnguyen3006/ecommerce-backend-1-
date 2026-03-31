package com.myexampleproject.orderservice.dto;

import java.util.List;

import com.myexampleproject.common.dto.OrderLineItemRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class OrderRequest {
    private List<OrderLineItemRequest> items;
    private String paymentMethod;
    private String shippingAddressLabel;
    private String shippingRecipientName;
    private String shippingRecipientPhone;
    private String shippingAddressLine;
}
