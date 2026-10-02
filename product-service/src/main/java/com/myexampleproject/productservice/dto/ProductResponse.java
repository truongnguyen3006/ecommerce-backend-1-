package com.myexampleproject.productservice.dto;

import java.math.BigDecimal;
import java.util.List;
import lombok.*;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ProductResponse {
    private Long id;
    private String name;
    private String description;
    private BigDecimal price; // Giá hiển thị
    private String imageUrl;
    private String category;

    private List<ProductVariantResponse> variants;
}