package com.myexampleproject.userservice.controller;

import com.myexampleproject.userservice.dto.AdminUpdateUserRequest;
import com.myexampleproject.userservice.dto.UserRequest;
import com.myexampleproject.userservice.dto.UserResponse;
import com.myexampleproject.userservice.model.User;
import com.myexampleproject.userservice.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

//@Controller: Báo cho Spring biết đây là một "Bean",nó chuyên xử lý các request
//@ResponseBody: Tự động chuyển đổi kết quả trả về từ các phương thức
// (ví dụ: một đối tượng User hoặc List<User>) thành dạng dữ liệu JSON để gửi về cho client.
//một Lễ tân chuyên xử lý API và tự động trả về JSON
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    // ✅ Cho phép đăng ký public (không cần token)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse createUser(@RequestBody UserRequest userRequest) {
        return userService.createUser(userRequest);
    }

    // ✅ User có thể tự cập nhật thông tin của chính mình
    @PatchMapping("/me")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<UserResponse> updateSelf(Authentication authentication,
                                                   @RequestBody UserRequest req) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        String keycloakId = jwt.getClaim("sub");
        return ResponseEntity.ok(userService.updateSelfUser(keycloakId, req));
    }

    // ✅ API lấy thông tin người dùng hiện tại (Dựa trên Token gửi lên)
    @GetMapping("/me")
    @ResponseStatus(HttpStatus.OK)
    public UserResponse getCurrentUser(@AuthenticationPrincipal Jwt jwt) {
        // Lấy keycloakId từ token ("sub" là standard claim chứa ID)
        String keycloakId = jwt.getClaimAsString("sub");
        UserResponse userResponse = userService.getUserByKeycloakId(keycloakId);

        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess != null && realmAccess.containsKey("roles")) {
            List<String> roles = (List<String>) realmAccess.get("roles");
            userResponse.setRoles(roles); // Nhét role vào để Frontend thấy
        }
        return userResponse;
    }

    // ✅ Admin cập nhật trạng thái user
    @PatchMapping("/admin/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse updateUserStatus(@PathVariable Long id,
                                         @RequestBody AdminUpdateUserRequest request) {
        return userService.updateUserByAdmin(id, request.isEnabled());
    }

    // ✅ Chỉ admin mới xem danh sách người dùng
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.OK)
    public List<UserResponse> getAllUsers() {
        return userService.getAllUsers();
    }

    // ✅ Chỉ admin mới xem thông tin người khác
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.OK)
    public UserResponse getUserById(@PathVariable Long id) {
        return userService.getUserById(id);
    }

    // ✅ Chỉ admin được quyền xóa
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUserById(@PathVariable Long id) {
        userService.deleteUserById(id);
    }
}

