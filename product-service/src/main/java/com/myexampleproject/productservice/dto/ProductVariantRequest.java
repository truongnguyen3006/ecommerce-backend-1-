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
public class ProductVariantRequest {
    private String skuCode;       // Ví dụ: JD1-RED-40
    private String color;         // Red
    private String size;          // 40
    private BigDecimal price;     // Giá riêng (nếu có), không thì lấy basePrice
    private Integer initialQuantity; // Số lượng nhập kho
    private String imageUrl;      // Ảnh riêng cho màu này
    private Boolean isActive;
    private List<String> galleryImages;
}