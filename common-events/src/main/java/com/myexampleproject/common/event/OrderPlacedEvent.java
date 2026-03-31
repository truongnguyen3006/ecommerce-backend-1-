package com.myexampleproject.common.event;

import com.myexampleproject.common.dto.OrderLineItemRequest;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class OrderPlacedEvent {
    private String orderNumber;
    private String userId;
    private List<OrderLineItemRequest> orderLineItemsDtoList;
    private String paymentMethod;
    private String shippingAddressLabel;
    private String shippingRecipientName;
    private String shippingRecipientPhone;
    private String shippingAddressLine;
}
