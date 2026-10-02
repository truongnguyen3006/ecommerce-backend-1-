package com.myexampleproject.userservice.config;

import com.myexampleproject.userservice.dto.UserRequest;
import com.myexampleproject.userservice.model.User;
import com.myexampleproject.userservice.repository.UserRepository;
import com.myexampleproject.userservice.service.KeycloakService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="app.seed-admin.enabled", havingValue="true", matchIfMissing=true)
@Component
@RequiredArgsConstructor
@Slf4j
public class UserSeeder implements CommandLineRunner {

    private final KeycloakService keycloakService;
    private final UserRepository userRepository;

    @org.springframework.beans.factory.annotation.Value("${app.seed-admin.password:admin123456@}")
    private String adminPassword;

    @Override
    public void run(String... args) {
        log.info("🛡️ Đang kiểm tra tài khoản ADMIN...");
        seedAdminUser();
    }

    private void seedAdminUser() {
        String adminUsername = "admin";
        String adminEmail = "admin@example.com";
        String roleAdmin = "admin";
        String roleUser = "user";

        // 1. Đảm bảo Role tồn tại trong Keycloak
        keycloakService.createRoleIfNotExists(roleAdmin);
        keycloakService.createRoleIfNotExists(roleUser);

        String keycloakId = keycloakService.getKeycloakIdByUsername(adminUsername);

        // 2. Nếu Admin chưa có trên Keycloak -> Tạo mới
        if (keycloakId == null) {
            log.info("Admin chưa có trên Keycloak. Đang tạo mới...");
            UserRequest adminReq = UserRequest.builder()
                    .username(adminUsername)
                    .password(adminPassword)
                    .email(adminEmail)
                    .fullName("System Administrator")
                    .build();
            keycloakId = keycloakService.createUserInKeycloak(adminReq);
        } else {
            log.info("Admin đã tồn tại trên Keycloak (ID: {})", keycloakId);
        }

        // 3. Gán quyền ADMIN cho user này (Quan trọng!)
        try {
            keycloakService.assignRealmRoleToUser(keycloakId, roleAdmin);
            keycloakService.assignRealmRoleToUser(keycloakId, roleUser);
        } catch (Exception e) {
            log.warn("Lỗi khi gán role: " + e.getMessage());
        }

        // 4. Đồng bộ vào Database MySQL (Quan trọng nhất)
        // Kiểm tra xem trong DB đã có user với keycloakId này chưa
        if (!userRepository.findByKeycloakId(keycloakId).isPresent()) {
            // Nếu chưa có, hoặc ID bị lệch -> Xóa user cũ (nếu trùng email) và tạo lại
            // (Đoạn này xử lý trường hợp database cũ lưu ID rác)
            User existingByEmail = userRepository.findByEmail(adminEmail).orElse(null);
            if (existingByEmail != null) {
                userRepository.delete(existingByEmail);
                log.info("♻️ Đã xóa Admin cũ trong DB do sai ID.");
            }

            User adminUser = User.builder()
                    .keycloakId(keycloakId) // Lưu ID thật từ Keycloak
                    .email(adminEmail)
                    .fullName("System Administrator")
                    .status(true)
                    .address("Headquarters")
                    .phoneNumber("0000000000")
                    .build();

            userRepository.save(adminUser);
            log.info("Đã đồng bộ Admin vào MySQL thành công!");
        } else {
            log.info("Admin trong MySQL đã khớp với Keycloak.");
        }
    }
}