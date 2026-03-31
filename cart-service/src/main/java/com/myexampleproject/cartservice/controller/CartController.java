package com.myexampleproject.cartservice.controller;

import com.myexampleproject.common.dto.CartItemRequest;
import com.myexampleproject.common.event.CartLineItem;
import com.myexampleproject.cartservice.model.CartEntity;
import com.myexampleproject.cartservice.service.CartService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @PostMapping("/add/{userId}")
    public ResponseEntity<?> addToCart(@PathVariable String userId, @RequestBody CartItemRequest item) {
        cartService.addItem(userId, item); // Sửa: Không gán vào biến
        return ResponseEntity.ok().build(); // Sửa: Trả về 200 OK rỗng
    }

    @PostMapping("/remove/{userId}/{sku}")
    public ResponseEntity<?> remove(@PathVariable String userId, @PathVariable String sku) { // Sửa 1: Đổi void -> ResponseEntity<?>
        cartService.removeItem(userId, sku); // Sửa 2: Không gán vào biến
        return ResponseEntity.ok().build(); // Sửa 3: Trả về 200 OK rỗng
    }

    @GetMapping("/view/{userId}")
    public ResponseEntity<?> view(@PathVariable String userId) {
        CartEntity cart = cartService.viewCart(userId);
        return ResponseEntity.ok(cart);
    }
    @PostMapping("/checkout/{userId}")
    public CompletableFuture<ResponseEntity<String>> checkout(@PathVariable String userId) {
        return cartService.checkoutAsync(userId)
                .thenApply(result -> {
                    // Trả về 202 Accepted
                    return ResponseEntity.accepted().body("Checkout queued");
                })
                .exceptionally(ex -> {
                    return ResponseEntity.badRequest().body(ex.getMessage());
                });
    }
}