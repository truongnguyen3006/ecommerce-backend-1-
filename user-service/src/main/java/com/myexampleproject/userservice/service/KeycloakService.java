package com.myexampleproject.userservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.myexampleproject.userservice.dto.UserRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.time.Duration;
import java.util.Map;

@Service
@lombok.RequiredArgsConstructor
public class KeycloakService {
    private final WebClient.Builder clientBuilder;
    // ✅ Inject từ application.properties
    @Value("${keycloak.server-url}")
    private String keycloakServerUrl;

    @Value("${keycloak.realm}")
    private String keycloakRealm;

    @Value("${keycloak.client-id}")
    private String keycloakClientId;

    @Value("${keycloak.client-secret}")
    private String keycloakClientSecret;


    private WebClient getClient(String token) {
        return clientBuilder.clone()
                .baseUrl(keycloakServerUrl + "/admin/realms/" + keycloakRealm)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
    }

    private String getAdminAccessToken() {
        WebClient tokenClient = clientBuilder.clone()
                .baseUrl(keycloakServerUrl + "/realms/" + keycloakRealm + "/protocol/openid-connect/token")
                .build();

        JsonNode response = tokenClient.post()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .body(BodyInserters.fromFormData("client_id", keycloakClientId)
                        .with("client_secret", keycloakClientSecret)
                        .with("grant_type", "client_credentials"))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(10));
        if (response == null || response.get("access_token") == null) {
            throw new RuntimeException("Không thể lấy access token admin");
        }

        return response.get("access_token").asText();

    }


//    Cập nhật user trong Keycloak
    public void updateUserInKeycloak(String keycloakId, Map<String, Object> updates){
        String token = getAdminAccessToken();
        WebClient client = getClient(token);
        client.put()
                .uri("/users/{id}", keycloakId)
                .bodyValue(updates)
                .retrieve()
                .toBodilessEntity()
                .block(Duration.ofSeconds(10));
    }

//    Đổi mật khẩu Keycloak (nếu người dùng muốn đổi)
    public void updatePasswordInKeycloak(String keycloakId, String newPassword){
        String token = getAdminAccessToken();
        WebClient client = getClient(token);
        Map<String, Object> passwordCreds = Map.of(
                "type", "password",
                "value", newPassword,
                "temporary", false
        );
        client.put()
                .uri("/users/{id}/reset-password", keycloakId)
                .bodyValue(passwordCreds)
                .retrieve()
                .toBodilessEntity()
                .block(Duration.ofSeconds(10));
    }

    private boolean userExists(String username, String token) {
        return findUsersByExactUsername(username, token).stream()
                .anyMatch(user -> username.equals(user.path("username").asText()));
    }

    private List<JsonNode> findUsersByExactUsername(String username, String token) {
        if (username == null || username.isBlank()) throw new IllegalArgumentException("Username is required");
        List<JsonNode> users = getClient(token).get()
                .uri(uriBuilder -> uriBuilder.path("/users")
                        .queryParam("username", username)
                        .queryParam("exact", true)
                        .build())
                .retrieve()
                .bodyToFlux(JsonNode.class)
                .collectList()
                .block(Duration.ofSeconds(10));

        if (users == null) throw new IllegalStateException("Keycloak identity lookup returned no response");
        return users;
    }

    public String createUserInKeycloak(UserRequest req) {
        String token = getAdminAccessToken();
        WebClient client = getClient(token);
        if (userExists(req.getUsername(), token)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User already exists");
        }
        Map<String, Object> body = Map.of(
                "username", req.getUsername(),
                "email", req.getEmail(),
                "enabled", true,
                "credentials", List.of(Map.of(
                        "type", "password",
                        "value", req.getPassword(),
                        "temporary", false
                ))
        );

        try {
            ResponseEntity<Void> response = client.post()
                    .uri("/users")
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(10));

            if (response == null || response.getHeaders().getFirst("Location") == null) {
                throw new RuntimeException("Không nhận được phản hồi hợp lệ từ Keycloak khi tạo user");
            }

            String location = response.getHeaders().getFirst("Location");
            String userId = location.substring(location.lastIndexOf('/') + 1);
            return userId;
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 409) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "User already exists");
            } else {
                throw new RuntimeException("Lỗi khi tạo user trong Keycloak: " + e.getMessage(), e);
            }
        }
    }

    public void assignRealmRoleToUser(String userId, String roleName) {
        String token = getAdminAccessToken();
        WebClient client = getClient(token);
        try {
            // Lấy role object từ Keycloak
            JsonNode role = client.get()
                    .uri("/roles/{roleName}", roleName)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(10));

            if (role == null) {
                throw new RuntimeException("Không tìm thấy role: " + roleName);
            }

            // Gán role cho user
            client.post()
                    .uri("/users/{id}/role-mappings/realm", userId)
                    .bodyValue(List.of(role))
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(10));
            System.out.println("✅ Gán role '" + roleName + "' cho userId " + userId);
        }
        catch (WebClientResponseException e) {
            throw new RuntimeException("Lỗi khi gán role '" + roleName + "' cho user: " + e.getMessage(), e);
        }
    }



    public void deleteUser(String keycloakId) {
        String token = getAdminAccessToken();
        WebClient client = getClient(token);
        try {
            client.delete()
                    .uri("/users/{id}", keycloakId)
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(10));

            System.out.println("🗑️ Đã xóa user trong Keycloak: " + keycloakId);

        } catch (WebClientResponseException.NotFound e) {
            System.out.println("⚠️ User không tồn tại trong Keycloak: " + keycloakId);
        } catch (WebClientResponseException e) {
            throw new RuntimeException("Lỗi khi xóa user trong Keycloak: " + e.getMessage(), e);
        }
    }

    // 1. Hàm lấy ID của user dựa trên username (Để check xem admin có chưa)
    public String getKeycloakIdByUsername(String username) {
        String token = getAdminAccessToken();
        List<JsonNode> users = findUsersByExactUsername(username, token);
        if (users.isEmpty()) return null;
        // Fail closed even if an upstream provider ignores exact=true.
        if (users.size() != 1 || !username.equals(users.getFirst().path("username").asText())
                || users.getFirst().path("id").asText().isBlank()) {
            throw new IllegalStateException("Ambiguous or mismatching bootstrap identity");
        }
        return users.getFirst().path("id").asText();
    }

    // 2. Hàm tạo Role nếu chưa tồn tại (Đảm bảo role ADMIN luôn có)
    public void createRoleIfNotExists(String roleName) {
        String token = getAdminAccessToken();
        WebClient client = getClient(token);

        try {
            // Check xem role có chưa
            client.get().uri("/roles/" + roleName).retrieve().toBodilessEntity().block(Duration.ofSeconds(10));
        } catch (WebClientResponseException.NotFound e) {
            // Nếu chưa có (404) thì tạo mới
            Map<String, String> role = Map.of("name", roleName);
            client.post().uri("/roles").bodyValue(role).retrieve().toBodilessEntity().block(Duration.ofSeconds(10));
            System.out.println("⚠️ Đã tạo mới Role: " + roleName);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Keycloak role configuration unavailable");
        }
    }

}
