// src/main/java/.../dto/LoginRequest.java
package com.myexampleproject.userservice.dto;

import lombok.Data;

@Data
public class LoginRequest {
    @jakarta.validation.constraints.NotBlank
    private String username;
    @jakarta.validation.constraints.NotBlank
    private String password;
}