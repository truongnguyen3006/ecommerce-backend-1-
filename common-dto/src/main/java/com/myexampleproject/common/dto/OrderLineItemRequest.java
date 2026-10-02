package com.myexampleproject.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OrderLineItemRequest {
    @NotBlank // Thêm validation
    private String skuCode;
    @Min(1) // Thêm validation
    private Integer quantity;
}
