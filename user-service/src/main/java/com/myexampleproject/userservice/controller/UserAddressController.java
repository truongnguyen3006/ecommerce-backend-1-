package com.myexampleproject.userservice.controller;

import com.myexampleproject.userservice.dto.UserAddressRequest;
import com.myexampleproject.userservice.dto.UserAddressResponse;
import com.myexampleproject.userservice.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/user/addresses")
@RequiredArgsConstructor
public class UserAddressController {

    private final UserService userService;

    @GetMapping
    public List<UserAddressResponse> getMyAddresses(@AuthenticationPrincipal Jwt jwt) {
        return userService.getAddresses(extractKeycloakId(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserAddressResponse createAddress(@AuthenticationPrincipal Jwt jwt,
                                             @RequestBody UserAddressRequest request) {
        return userService.createAddress(extractKeycloakId(jwt), request);
    }

    @PutMapping("/{id}")
    public UserAddressResponse updateAddress(@AuthenticationPrincipal Jwt jwt,
                                             @PathVariable Long id,
                                             @RequestBody UserAddressRequest request) {
        return userService.updateAddress(extractKeycloakId(jwt), id, request);
    }

    @PatchMapping("/{id}/default")
    public UserAddressResponse setDefaultAddress(@AuthenticationPrincipal Jwt jwt,
                                                 @PathVariable Long id) {
        return userService.setDefaultAddress(extractKeycloakId(jwt), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAddress(@AuthenticationPrincipal Jwt jwt,
                              @PathVariable Long id) {
        userService.deleteAddress(extractKeycloakId(jwt), id);
    }

    private String extractKeycloakId(Jwt jwt) {
        return jwt.getClaimAsString("sub");
    }
}
