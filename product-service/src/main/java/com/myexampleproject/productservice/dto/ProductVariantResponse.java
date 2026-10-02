package com.myexampleproject.productservice.dto;


import java.math.BigDecimal;
import java.util.List;
import lombok.*;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ProductVariantResponse {
    private String skuCode;
    private String color;
    private String size;
    private String imageUrl;
    private BigDecimal price;
    private Boolean isActive;
    private List<String> galleryImages;
}