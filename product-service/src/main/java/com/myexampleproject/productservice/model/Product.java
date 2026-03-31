package com.myexampleproject.productservice.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "product")
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Data
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String description;

    private String category; // Ví dụ: "Men's Shoes"

    // Giá hiển thị mặc định (ví dụ giá thấp nhất)
    private BigDecimal basePrice;

    private String imageUrl; // Ảnh đại diện chung

    @CreationTimestamp
    private LocalDateTime createdAt;
    @UpdateTimestamp
    private LocalDateTime updatedAt;

    // QUAN TRỌNG: Mối quan hệ 1-N (Một sản phẩm có nhiều Size/Màu)
    // CascadeType.ALL: Xóa cha thì xóa luôn con
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
    private List<ProductVariant> variants;
}