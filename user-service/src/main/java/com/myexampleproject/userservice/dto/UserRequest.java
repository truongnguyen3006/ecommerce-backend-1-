package com.myexampleproject.userservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UserRequest {
    public interface Creation {}
    @jakarta.validation.constraints.NotBlank(groups=Creation.class)
    @jakarta.validation.constraints.Size(max=255)
    private String username;    // Dùng để tạo user trong Keycloak
    @jakarta.validation.constraints.NotBlank(groups=Creation.class)
    @jakarta.validation.constraints.Email @jakarta.validation.constraints.Size(max=255)
    private String email;
    @jakarta.validation.constraints.NotBlank(groups=Creation.class)
    @jakarta.validation.constraints.Size(min=8, max=128)
    private String password;    // Gửi cho Keycloak, không lưu trong DB
    @jakarta.validation.constraints.Size(max=255)
    private String fullName;
    @jakarta.validation.constraints.Size(max=255)
    private String phoneNumber;
    @jakarta.validation.constraints.Size(max=255)
    private String address;
}
