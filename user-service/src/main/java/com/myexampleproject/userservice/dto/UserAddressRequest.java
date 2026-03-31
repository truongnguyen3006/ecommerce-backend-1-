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
    private String label;
    private String recipientName;
    private String recipientPhone;
    private String addressLine;
    private Boolean isDefault;
}
