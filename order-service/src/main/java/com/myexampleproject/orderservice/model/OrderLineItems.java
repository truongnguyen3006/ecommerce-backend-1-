package com.myexampleproject.orderservice.model;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name="t_orders_line_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OrderLineItems {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY )
	private Long id;
	//Mã sản phẩm (SKU) của dòng sản phẩm trong đơn hàng.
	private String skuCode;
	private BigDecimal price;
	private Integer quantity;

    private String productName;
    // Thêm 2 trường này để in hóa đơn cho dễ
    private String color;
    private String size;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    @JsonIgnore
    private Order order;
}
