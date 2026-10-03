package com.myexampleproject.productservice.dto;

import java.math.BigDecimal;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ProductRequest {
    @PositiveOrZero
    private Long revision;
    public interface Creation {}
    @NotBlank(groups = Creation.class)
    @Pattern(regexp = "(?s).*\\S.*") @Size(max = 255)
    private String name;
    @Size(max = 255)
    private String description;
    @Size(max = 255)
    private String category;
    @NotNull(groups = Creation.class) @PositiveOrZero
    private BigDecimal basePrice; // Giá gốc hiển thị
    @Size(max = 255)
    private String imageUrl;      // Ảnh đại diện chung

    @Valid @Size(max = 100) @NotEmpty(groups = Creation.class)
    private List<@NotNull ProductVariantRequest> variants;
}
