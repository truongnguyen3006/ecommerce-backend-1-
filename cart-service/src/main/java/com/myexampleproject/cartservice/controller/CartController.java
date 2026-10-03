package com.myexampleproject.cartservice.controller;

import com.myexampleproject.common.dto.CartItemRequest;
import com.myexampleproject.cartservice.model.CartEntity;
import com.myexampleproject.cartservice.service.CartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {
    private final CartService cartService;
    private String owner(Jwt jwt, String supplied) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        if (supplied != null && !supplied.equals(jwt.getSubject())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot access another user's cart");
        return jwt.getSubject();
    }
    @PostMapping("/add/{userId}")
    public ResponseEntity<Void> addToCart(@PathVariable String userId, @Valid @RequestBody CartItemRequest item, @AuthenticationPrincipal Jwt jwt) {
        cartService.addItem(owner(jwt, userId), item);return ResponseEntity.ok().build();
    }
    @PostMapping("/remove/{userId}/{sku}")
    public ResponseEntity<Void> remove(@PathVariable String userId, @PathVariable String sku, @AuthenticationPrincipal Jwt jwt) {
        cartService.removeItem(owner(jwt, userId), sku);return ResponseEntity.ok().build();
    }
    @GetMapping("/view/{userId}")
    public CartEntity view(@PathVariable String userId, @AuthenticationPrincipal Jwt jwt) { return cartService.viewCart(owner(jwt, userId)); }
    @PutMapping("/update/{userId}")
    public ResponseEntity<Void> update(@PathVariable String userId, @Valid @RequestBody CartItemRequest item, @AuthenticationPrincipal Jwt jwt) {
        cartService.updateQuantity(owner(jwt, userId), item);return ResponseEntity.ok().build();
    }
    @DeleteMapping("/clear/{userId}")
    public ResponseEntity<Void> clear(@PathVariable String userId, @AuthenticationPrincipal Jwt jwt) {
        cartService.clearCart(owner(jwt, userId));return ResponseEntity.noContent().build();
    }
    @PostMapping("/checkout/{userId}")
    public CompletableFuture<ResponseEntity<String>> checkout(@PathVariable String userId, @AuthenticationPrincipal Jwt jwt) {
        return cartService.checkoutAsync(owner(jwt, userId)).thenApply(id -> ResponseEntity.accepted().header("X-Order-Number", id).body("Checkout queued"));
    }
    @GetMapping("/me")
    public CartEntity mine(@AuthenticationPrincipal Jwt jwt) { return cartService.viewCart(owner(jwt, null)); }
    @PostMapping("/items")
    public ResponseEntity<Void> add(@Valid @RequestBody CartItemRequest item, @AuthenticationPrincipal Jwt jwt) {
        cartService.addItem(owner(jwt, null), item);return ResponseEntity.ok().build();
    }
    @PutMapping("/items/{sku}")
    public ResponseEntity<Void> quantity(@PathVariable String sku, @Valid @RequestBody CartItemRequest item, @AuthenticationPrincipal Jwt jwt) {
        if (!sku.equals(item.getSkuCode())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SKU does not match request path");
        cartService.updateQuantity(owner(jwt, null), item);return ResponseEntity.ok().build();
    }
    @DeleteMapping("/items/{sku}")
    public ResponseEntity<Void> removeMine(@PathVariable String sku, @AuthenticationPrincipal Jwt jwt) {
        cartService.removeItem(owner(jwt, null), sku);return ResponseEntity.noContent().build();
    }
    @DeleteMapping("/me")
    public ResponseEntity<Void> clearMine(@AuthenticationPrincipal Jwt jwt) {
        cartService.clearCart(owner(jwt, null));return ResponseEntity.noContent().build();
    }
    @PostMapping("/purchased")
    public Map<String,Long> purchased(@Valid @RequestBody com.myexampleproject.cartservice.service.PurchasedCartRequest request, @AuthenticationPrincipal Jwt jwt) {
        return Map.of("removed", cartService.cleanupPurchased(owner(jwt,null), jwt.getTokenValue(), request));
    }
    @PostMapping("/checkout")
    public CompletableFuture<ResponseEntity<Map<String,String>>> checkoutMine(@AuthenticationPrincipal Jwt jwt) {
        return cartService.checkoutAsync(owner(jwt, null)).thenApply(id -> ResponseEntity.accepted().body(Map.of("orderNumber", id, "message", "Checkout queued")));
    }
}
