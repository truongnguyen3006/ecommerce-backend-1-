package com.myexampleproject.userservice.model;
import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.data.annotation.CreatedDate;

@Entity
@Table(name ="t_user")
@AllArgsConstructor
//Tự động tạo constructor rỗng.
@NoArgsConstructor
//Tạo ra Builder Pattern để khởi tạo object gọn hơn.
@Builder
//auto getter/setter/toString/equals/hashCode.
@Data
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    // Liên kết user trong Keycloak
    @Column(unique = true, nullable = false)
    private String keycloakId;

    // Các thông tin nghiệp vụ
    private String fullName;
    private String email;
    private String phoneNumber;
    private String address;
//    private int points = 0;
    private boolean status;
    @CreationTimestamp
    private LocalDateTime createdDate;

    @UpdateTimestamp
    private LocalDateTime updatedDate;
}
