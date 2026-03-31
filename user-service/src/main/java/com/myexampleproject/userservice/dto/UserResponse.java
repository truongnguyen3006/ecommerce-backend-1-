package com.myexampleproject.userservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UserResponse {
    private Long id;
    private String keycloakId;
    private String fullName;
    private String email;
    private String phoneNumber;
    private String address;
    private boolean status;
    private List<String> roles;
}
