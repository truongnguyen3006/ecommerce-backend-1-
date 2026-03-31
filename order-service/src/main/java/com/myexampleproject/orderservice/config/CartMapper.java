package com.myexampleproject.orderservice.config;

import com.myexampleproject.common.dto.OrderLineItemRequest;
import com.myexampleproject.common.dto.OrderLineItemsDto;
import com.myexampleproject.common.event.CartCheckoutEvent;
import com.myexampleproject.orderservice.dto.OrderRequest;

import java.math.BigDecimal;
import java.util.List;

public class CartMapper {

    public static OrderRequest fromCart(CartCheckoutEvent event) {

        // SỬA 1: Map sang "OrderLineItemRequest" (thay vì OrderLineItemsDto)
        List<OrderLineItemRequest> items = event.getItems().stream()
                .map(i -> OrderLineItemRequest.builder()
                        .skuCode(i.getSkuCode())
                        .quantity(i.getQuantity())
                        // CỐ Ý BỎ QUA 'PRICE'
                        // Chúng ta không tin tưởng giá từ giỏ hàng
                        .build()
                ).toList();

        // SỬA 2: Đặt vào trường "items" (hoặc tên bạn đã đổi trong OrderRequest)
        return OrderRequest.builder()
                .items(items) // (Đổi từ 'orderLineItemsDtoList' thành 'items')
                .build();
    }
}
