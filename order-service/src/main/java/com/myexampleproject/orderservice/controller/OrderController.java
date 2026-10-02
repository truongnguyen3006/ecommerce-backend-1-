package com.myexampleproject.orderservice.controller;

import com.myexampleproject.orderservice.dto.CancelOrderRequest;
import com.myexampleproject.orderservice.dto.OrderPaymentContextResponse;
import com.myexampleproject.orderservice.dto.OrderRequest;
import com.myexampleproject.orderservice.dto.OrderResponse;
import com.myexampleproject.orderservice.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
@Slf4j
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> placeOrder(@RequestBody OrderRequest orderRequest,
                                          @AuthenticationPrincipal Jwt jwt) {
        String userId = extractUserId(jwt);
        log.info("Placing order for user {}", userId);

        String orderNumber = orderService.placeOrder(orderRequest, userId);
        return Map.of("orderNumber", orderNumber, "message", "Order received");
    }

    @GetMapping("/me")
    @ResponseStatus(HttpStatus.OK)
    public List<OrderResponse> getMyOrders(@AuthenticationPrincipal Jwt jwt) {
        return orderService.getOrdersForUser(extractUserId(jwt));
    }

    @GetMapping("/admin")
    @ResponseStatus(HttpStatus.OK)
    public List<OrderResponse> getAdminOrders(@AuthenticationPrincipal Jwt jwt) {
        requireAdmin(jwt);
        return orderService.getAllOrdersForAdmin();
    }

    @GetMapping("/{orderNumber}")
    @ResponseStatus(HttpStatus.OK)
    public OrderResponse getOrderDetails(@PathVariable String orderNumber,
                                         @AuthenticationPrincipal Jwt jwt) {
        return orderService.getOrderDetails(orderNumber, extractUserId(jwt), isAdmin(jwt));
    }

    @PostMapping("/{orderNumber}/cancel")
    @ResponseStatus(HttpStatus.OK)
    public OrderResponse cancelOrder(@PathVariable String orderNumber,
                                     @RequestBody(required = false) CancelOrderRequest request,
                                     @AuthenticationPrincipal Jwt jwt) {
        String reason = request != null ? request.getReason() : null;
        return orderService.cancelOrder(orderNumber, extractUserId(jwt), isAdmin(jwt), reason);
    }

    @GetMapping("/internal/{orderNumber}/payment-context")
    @ResponseStatus(HttpStatus.OK)
    public OrderPaymentContextResponse getPaymentContext(@PathVariable String orderNumber) {
        return orderService.getPaymentContext(orderNumber);
    }

    private String extractUserId(Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bạn cần đăng nhập");
        }

        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token không chứa subject hợp lệ");
        }

        return subject;
    }

    private boolean isAdmin(Jwt jwt) {
        if (jwt == null) {
            return false;
        }

        Object realmAccessClaim = jwt.getClaim("realm_access");
        if (!(realmAccessClaim instanceof Map<?, ?> realmAccess)) {
            return false;
        }

        Object rolesClaim = realmAccess.get("roles");
        if (!(rolesClaim instanceof List<?> roles)) {
            return false;
        }

        return roles.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .anyMatch(role -> "admin".equalsIgnoreCase(role));
    }

    private void requireAdmin(Jwt jwt) {
        if (!isAdmin(jwt)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ admin mới được xem toàn bộ đơn hàng");
        }
    }
}
