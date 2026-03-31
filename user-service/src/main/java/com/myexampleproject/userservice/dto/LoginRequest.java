// src/main/java/.../dto/LoginRequest.java
package com.myexampleproject.userservice.dto;

import lombok.Data;

@Data
public class LoginRequest {
    private String username;
    private String password;
}