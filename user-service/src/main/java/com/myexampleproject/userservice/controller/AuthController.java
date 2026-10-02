package com.myexampleproject.userservice.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.myexampleproject.userservice.dto.LoginRequest;
import com.myexampleproject.userservice.dto.TokenRefreshRequest;
import com.myexampleproject.userservice.dto.UserRequest;
import com.myexampleproject.userservice.dto.UserResponse;
import com.myexampleproject.userservice.service.KeycloakService;
import com.myexampleproject.userservice.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.groups.Default;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
    private final WebClient.Builder clientBuilder;

    @Value("${keycloak.server-url}")
    private String keycloakServerUrl;

    @Value("${keycloak.realm}")
    private String keycloakRealm;

    @Value("${keycloak.client-id}")
    private String keycloakClientId;

    @Value("${keycloak.client-secret}")
    private String keycloakClientSecret;


    private final KeycloakService keycloakService;
    private final UserService userService;

    private WebClient getWebClient() {
        return clientBuilder.clone()
                .baseUrl(keycloakServerUrl + "/realms/" + keycloakRealm + "/protocol/openid-connect")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .build();
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Validated({Default.class, UserRequest.Creation.class}) @RequestBody UserRequest userRequest) {
        return userService.createUser(userRequest);
    }

    // Login bằng username + password
    @PostMapping("/login")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest loginRequest) {
        WebClient client = getWebClient();
        JsonNode response;
        try {
        response = client.post()
                .uri("/token")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .body(BodyInserters.fromFormData("grant_type", "password")
                        .with("client_id", keycloakClientId)
                        .with("client_secret", keycloakClientSecret)
                        .with("username", loginRequest.getUsername())
                        .with("password", loginRequest.getPassword()))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(10));

        } catch (WebClientResponseException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login failed");
        }
        return ResponseEntity.ok(response);
    }

    // Refresh token khi hết hạn
    @PostMapping("/refresh")
    public ResponseEntity<?> refreshToken(@Valid @RequestBody TokenRefreshRequest request) {
        try {
            WebClient client = getWebClient();
            JsonNode response = client.post()
                    .uri("/token")
                    .body(BodyInserters.fromFormData("grant_type", "refresh_token")
                            .with("client_id", keycloakClientId)
                            .with("client_secret", keycloakClientSecret)
                            .with("refresh_token", request.getRefreshToken()))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(10)); // <--- Chỗ này ném lỗi nếu token hết hạn

            return ResponseEntity.ok(response);
        } catch (WebClientResponseException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token is invalid or expired");
        }
    }

    // Đăng xuất (xóa refresh token)
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<JsonNode> logout(@RequestParam String refresh_token) {
        WebClient client = getWebClient();
        JsonNode response = client.post()
                .uri("/logout")
                .body(BodyInserters.fromFormData("client_id", keycloakClientId)
                        .with("client_secret", keycloakClientSecret)
                        .with("refresh_token", refresh_token))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(10));

        return ResponseEntity.ok(response);
    }
}