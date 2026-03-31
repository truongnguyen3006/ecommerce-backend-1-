package com.myexampleproject.productservice.dto;

import java.math.BigDecimal;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ProductRequest {
    private String name;
    private String description;
    private String category;
    private BigDecimal basePrice; // Giá gốc hiển thị
    private String imageUrl;      // Ảnh đại diện chung

    private List<ProductVariantRequest> variants;
}