package com.myexampleproject.userservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.myexampleproject.userservice.dto.AdminUpdateUserRequest;
import com.myexampleproject.userservice.dto.UserAddressRequest;
import com.myexampleproject.userservice.dto.UserAddressResponse;
import com.myexampleproject.userservice.dto.UserRequest;
import com.myexampleproject.userservice.dto.UserResponse;
import com.myexampleproject.userservice.model.User;
import com.myexampleproject.userservice.model.UserAddress;
import com.myexampleproject.userservice.repository.UserAddressRepository;
import com.myexampleproject.userservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Service // Đánh dấu đây là một Bean Service
public class UserService {
    private final UserRepository userRepository;
    private final UserAddressRepository userAddressRepository;
    private final KeycloakService keycloakService;

    // Tạo user trong  DB
    public UserResponse createUser(UserRequest userRequest){
        // 1️⃣ Gọi API Keycloak để tạo user
        String keycloakId = keycloakService.createUserInKeycloak(userRequest);

        // 2️⃣ Gán role mặc định ("user") cho user vừa tạo
        keycloakService.assignRealmRoleToUser(keycloakId, "user");

        // 2️⃣ Lưu user profile vào DB
        User user = User.builder()
                .keycloakId(keycloakId)
                .fullName(userRequest.getFullName())
                .email(userRequest.getEmail())
                .phoneNumber(userRequest.getPhoneNumber())
                .address(userRequest.getAddress())
                .status(true)
                .build();
        userRepository.save(user);
        return mapToUserResponse(user);
    }

    // ✅ HÀM MỚI: Lấy thông tin user để trả về cho API /me
    public UserResponse getUserByKeycloakId(String keycloakId) {
        User user = userRepository.findByKeycloakId(keycloakId)
                .orElseThrow(() -> new RuntimeException("User not found with Keycloak ID: " + keycloakId));

        return mapToUserResponse(user);
    }

    //    Người dùng tự cập nhật
    public UserResponse updateSelfUser(String keycloakId, UserRequest request) {
        User user = userRepository.findByKeycloakId(keycloakId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy user"));

        // Cập nhật thông tin trong DB
        if (request.getFullName() != null) user.setFullName(request.getFullName());
        if (request.getEmail() != null) user.setEmail(request.getEmail());
        if (request.getPhoneNumber() != null) user.setPhoneNumber(request.getPhoneNumber());
        if (request.getAddress() != null) user.setAddress(request.getAddress());
        userRepository.save(user);

        // Đồng bộ lên Keycloak
        Map<String, Object> body = new HashMap<>();
        if (request.getEmail() != null) body.put("email", request.getEmail());
        if (request.getFullName() != null) body.put("firstName", request.getFullName());
        if (request.getPhoneNumber() != null) body.put("attributes", Map.of("phoneNumber", request.getPhoneNumber()));
        if (request.getAddress() != null) body.put("attributes", Map.of("address", request.getAddress()));


        keycloakService.updateUserInKeycloak(user.getKeycloakId(), body);

        // Nếu người dùng đổi mật khẩu
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            keycloakService.updatePasswordInKeycloak(user.getKeycloakId(), request.getPassword());
        }

        return mapToUserResponse(user);
    }

    // Admin cập nhật người dùng
    public UserResponse updateUserByAdmin(Long id,  boolean enabled) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy user"));

        keycloakService.updateUserInKeycloak(user.getKeycloakId(), Map.of("enabled", enabled));
        user.setStatus(enabled); // 🟢 Cập nhật field mới
        userRepository.save(user); // 🟢 Lưu vào DB
        return mapToUserResponse(user);
    }


    public List<UserResponse> getAllUsers(){
        List<User> users = userRepository.findAll();
        return users.stream().map(this::mapToUserResponse).toList();
    }

    public UserResponse getUserById(Long id){
        User user = userRepository.findById(id).orElseThrow(() -> new RuntimeException("User not found"));
        return mapToUserResponse(user);
    }

    public void deleteUserById(Long id){
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));
        // 1️⃣ Xóa trên Keycloak
        keycloakService.deleteUser(user.getKeycloakId());
        userRepository.deleteById(id);
    }

    public java.util.List<UserAddressResponse> getAddresses(String keycloakId) {
        return userAddressRepository.findAllByUserKeycloakIdOrderByIsDefaultDescUpdatedDateDesc(keycloakId)
                .stream()
                .map(this::mapToAddressResponse)
                .toList();
    }

    public UserAddressResponse createAddress(String keycloakId, UserAddressRequest request) {
        validateAddressRequest(request);
        boolean makeDefault = Boolean.TRUE.equals(request.getIsDefault())
                || userAddressRepository.findAllByUserKeycloakIdOrderByIsDefaultDescUpdatedDateDesc(keycloakId).isEmpty();
        if (makeDefault) {
            clearDefaultAddress(keycloakId);
        }

        UserAddress address = UserAddress.builder()
                .userKeycloakId(keycloakId)
                .label(cleanText(request.getLabel(), 64))
                .recipientName(cleanText(request.getRecipientName(), 128))
                .recipientPhone(cleanText(request.getRecipientPhone(), 32))
                .addressLine(cleanText(request.getAddressLine(), 512))
                .isDefault(makeDefault)
                .build();

        return mapToAddressResponse(userAddressRepository.save(address));
    }

    public UserAddressResponse updateAddress(String keycloakId, Long id, UserAddressRequest request) {
        validateAddressRequest(request);
        UserAddress address = userAddressRepository.findByIdAndUserKeycloakId(id, keycloakId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy địa chỉ"));

        boolean makeDefault = Boolean.TRUE.equals(request.getIsDefault());
        if (makeDefault) {
            clearDefaultAddress(keycloakId);
        }

        address.setLabel(cleanText(request.getLabel(), 64));
        address.setRecipientName(cleanText(request.getRecipientName(), 128));
        address.setRecipientPhone(cleanText(request.getRecipientPhone(), 32));
        address.setAddressLine(cleanText(request.getAddressLine(), 512));
        address.setDefault(makeDefault || address.isDefault());

        return mapToAddressResponse(userAddressRepository.save(address));
    }

    public UserAddressResponse setDefaultAddress(String keycloakId, Long id) {
        UserAddress address = userAddressRepository.findByIdAndUserKeycloakId(id, keycloakId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy địa chỉ"));
        clearDefaultAddress(keycloakId);
        address.setDefault(true);
        return mapToAddressResponse(userAddressRepository.save(address));
    }

    public void deleteAddress(String keycloakId, Long id) {
        UserAddress address = userAddressRepository.findByIdAndUserKeycloakId(id, keycloakId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy địa chỉ"));
        boolean wasDefault = address.isDefault();
        userAddressRepository.delete(address);

        if (wasDefault) {
            userAddressRepository.findAllByUserKeycloakIdOrderByIsDefaultDescUpdatedDateDesc(keycloakId)
                    .stream()
                    .findFirst()
                    .ifPresent(next -> {
                        next.setDefault(true);
                        userAddressRepository.save(next);
                    });
        }
    }

    private void clearDefaultAddress(String keycloakId) {
        userAddressRepository.findAllByUserKeycloakIdOrderByIsDefaultDescUpdatedDateDesc(keycloakId)
                .forEach(address -> {
                    if (address.isDefault()) {
                        address.setDefault(false);
                        userAddressRepository.save(address);
                    }
                });
    }

    private void validateAddressRequest(UserAddressRequest request) {
        if (request == null) {
            throw new RuntimeException("Dữ liệu địa chỉ không hợp lệ");
        }
        if (cleanText(request.getRecipientName(), 128) == null) {
            throw new RuntimeException("Tên người nhận là bắt buộc");
        }
        if (cleanText(request.getRecipientPhone(), 32) == null) {
            throw new RuntimeException("Số điện thoại là bắt buộc");
        }
        if (cleanText(request.getAddressLine(), 512) == null) {
            throw new RuntimeException("Địa chỉ giao hàng là bắt buộc");
        }
    }

    private UserAddressResponse mapToAddressResponse(UserAddress address) {
        return UserAddressResponse.builder()
                .id(address.getId())
                .label(address.getLabel())
                .recipientName(address.getRecipientName())
                .recipientPhone(address.getRecipientPhone())
                .addressLine(address.getAddressLine())
                .isDefault(address.isDefault())
                .build();
    }

    private String cleanText(String value, int maxLength) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    private UserResponse mapToUserResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .keycloakId(user.getKeycloakId())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .phoneNumber(user.getPhoneNumber())
                .address(user.getAddress())
                .status(user.isStatus())
                .build();
    }

}
