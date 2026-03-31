package com.myexampleproject.common.event;

// Dùng DTO có sẵn cũng được, nhưng tạo riêng sẽ rõ ràng hơn
import com.myexampleproject.common.dto.OrderLineItemRequest;
import com.myexampleproject.common.dto.OrderLineItemsDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class InventoryCheckRequest {
    private String orderNumber;
    private OrderLineItemRequest item;

}