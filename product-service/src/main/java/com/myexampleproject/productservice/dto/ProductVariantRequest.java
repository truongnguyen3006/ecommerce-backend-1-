package com.myexampleproject.productservice.dto;

import java.math.BigDecimal;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.validation.constraints.*;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ProductVariantRequest {
    @NotBlank @Size(max = 255)
    private String skuCode;       // Ví dụ: JD1-RED-40
    @Size(max = 255)
    private String color;         // Red
    @Size(max = 255)
    private String size;          // 40
    @PositiveOrZero
    private BigDecimal price;     // Giá riêng (nếu có), không thì lấy basePrice
    @PositiveOrZero
    private Integer initialQuantity; // Số lượng nhập kho
    @Size(max = 255)
    private String imageUrl;      // Ảnh riêng cho màu này
    private Boolean isActive;
    @Size(max = 30)
    private List<@NotBlank @Size(max = 255) String> galleryImages;
}