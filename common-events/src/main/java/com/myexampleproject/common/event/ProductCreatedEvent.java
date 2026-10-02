package com.myexampleproject.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ProductCreatedEvent {
    // Event này chỉ cần 2 thông tin cho inventory-service
    private String skuCode;
    private Integer initialQuantity;
}
