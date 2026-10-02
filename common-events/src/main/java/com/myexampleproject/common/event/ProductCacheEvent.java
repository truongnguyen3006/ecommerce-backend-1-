package com.myexampleproject.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ProductCacheEvent {
    private String skuCode;
    private String name;
    private BigDecimal price;
    private String imageUrl;

    private String color;
    private String size;
}