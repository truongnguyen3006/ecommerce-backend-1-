package com.myexampleproject.orderservice.dto;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import com.myexampleproject.common.dto.OrderLineItemRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class OrderRequest {
    @NotEmpty @Size(max = 100) @Valid
    private List<@NotNull OrderLineItemRequest> items;
    @Pattern(regexp = "(?i)COD|VNPAY")
    private String paymentMethod;
    @Size(max=128)
    private String shippingAddressLabel;
    @Size(max=128)
    private String shippingRecipientName;
    @Size(max=32)
    private String shippingRecipientPhone;
    @Size(max=512)
    private String shippingAddressLine;
}
