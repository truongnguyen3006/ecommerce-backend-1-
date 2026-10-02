package com.myexampleproject.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class InventoryAdjustmentEvent {
    private String skuCode;
    // Số lượng điều chỉnh, có thể là +10 (thêm) hoặc -5 (giảm)
    private int adjustmentQuantity;
    private String reason; // (Ghi chú: "Admin nhập kho", "Hàng hỏng"...)
}
