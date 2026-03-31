package com.myexampleproject.productservice.model;

import java.math.BigDecimal;
import java.util.List;

import jakarta.persistence.*;
import lombok.*;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
@Table(name = "product_variant")
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Data
public class ProductVariant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // QUAN TRỌNG: SKU Code giờ nằm ở đây.
    // Ví dụ: "JORDAN1-RED-40", "JORDAN1-RED-41"
    @Column(unique = true, nullable = false)
    private String skuCode;

    private String color; // Ví dụ: "Red", "Black/White"
    private String size;  // Ví dụ: "40", "41", "42"

    private BigDecimal price; // Giá riêng cho size này (nếu cần), hoặc dùng giá cha

    private String imageUrl; // Ảnh cụ thể cho màu này (Nike đổi màu giày khi chọn màu)

    @ManyToOne
    @JoinColumn(name = "product_id")
    @JsonIgnore // Ngắt vòng lặp JSON
    private Product product;
    @OneToMany(mappedBy = "variant", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ProductImage> images;
    @Column(name = "is_active")
    private Boolean isActive = true;
}