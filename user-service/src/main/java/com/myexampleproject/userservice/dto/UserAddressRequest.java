package com.myexampleproject.userservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserAddressRequest {
    @jakarta.validation.constraints.Size(max=64)
    private String label;
    @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=128)
    private String recipientName;
    @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=32)
    private String recipientPhone;
    @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=512)
    private String addressLine;
    private Boolean isDefault;
}
