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
    private String username;    // Dùng để tạo user trong Keycloak
    private String email;
    private String password;    // Gửi cho Keycloak, không lưu trong DB
    private String fullName;
    private String phoneNumber;
    private String address;
}
