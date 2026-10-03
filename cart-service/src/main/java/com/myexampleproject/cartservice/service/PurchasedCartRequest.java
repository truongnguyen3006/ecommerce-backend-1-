package com.myexampleproject.cartservice.service;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
public record PurchasedCartRequest(@NotBlank @Pattern(regexp="[A-Za-z0-9-]{1,64}") String orderNumber,
    @NotNull @Size(min=1,max=200) List<@Valid Line> items) {
    public record Line(@NotBlank @Size(max=255) String skuCode, @Positive int quantity, @Size(max=512) String revision) {}
}
