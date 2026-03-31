package com.myexampleproject.userservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.myexampleproject.userservice.dto.UserRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.util.Map;

@Service
public class KeycloakService {
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
        return WebClient.builder()
                .baseUrl(keycloakServerUrl + "/admin/realms/" + keycloakRealm)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
    }

    private String getAdminAccessToken() {
        WebClient tokenClient = WebClient.builder()
                .baseUrl(keycloakServerUrl + "/realms/" + keycloakRealm + "/protocol/openid-connect/token")
                .build();

        JsonNode response = tokenClient.post()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .body(BodyInserters.fromFormData("client_id", keycloakClientId)
                        .with("client_secret", keycloakClientSecret)
                        .with("grant_type", "client_credentials"))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
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
                .block();
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
                .block();
    }

    private boolean userExists(String username, String token) {
        WebClient client = getClient(token);

        List<JsonNode> users = client.get()
                .uri(uriBuilder -> uriBuilder.path("/users")
                        .queryParam("username", username)
                        .build())
                .retrieve()
                .bodyToFlux(JsonNode.class)
                .collectList()
                .block();

        return users != null && !users.isEmpty();
    }

    public String createUserInKeycloak(UserRequest req) {
        String token = getAdminAccessToken();
        WebClient client = getClient(token);
        if (userExists(req.getUsername(), token)) {
            throw new RuntimeException("User đã tồn tại trong Keycloak");
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
                    .block();

            if (response == null || response.getHeaders().getFirst("Location") == null) {
                throw new RuntimeException("Không nhận được phản hồi hợp lệ từ Keycloak khi tạo user");
            }

            String location = response.getHeaders().getFirst("Location");
            String userId = location.substring(location.lastIndexOf('/') + 1);
            return userId;
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 409) {
                throw new RuntimeException("⚠User đã tồn tại trong Keycloak");
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
                    .block();

            if (role == null) {
                throw new RuntimeException("Không tìm thấy role: " + roleName);
            }

            // Gán role cho user
            client.post()
                    .uri("/users/{id}/role-mappings/realm", userId)
                    .bodyValue(List.of(role))
                    .retrieve()
                    .toBodilessEntity()
                    .block();
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
                    .block();

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
        WebClient client = getClient(token);
        List<JsonNode> users = client.get()
                .uri(uriBuilder -> uriBuilder.path("/users")
                        .queryParam("username", username)
                        .build())
                .retrieve()
                .bodyToFlux(JsonNode.class)
                .collectList()
                .block();

        if (users != null && !users.isEmpty()) {
            return users.get(0).get("id").asText();
        }
        return null;
    }

    // 2. Hàm tạo Role nếu chưa tồn tại (Đảm bảo role ADMIN luôn có)
    public void createRoleIfNotExists(String roleName) {
        String token = getAdminAccessToken();
        WebClient client = getClient(token);

        try {
            // Check xem role có chưa
            client.get().uri("/roles/" + roleName).retrieve().toBodilessEntity().block();
        } catch (WebClientResponseException.NotFound e) {
            // Nếu chưa có (404) thì tạo mới
            Map<String, String> role = Map.of("name", roleName);
            client.post().uri("/roles").bodyValue(role).retrieve().toBodilessEntity().block();
            System.out.println("⚠️ Đã tạo mới Role: " + roleName);
        } catch (Exception e) {
            // Ignore nếu lỗi khác
        }
    }

}
