package com.myexampleproject.userservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "t_user_address")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserAddress {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userKeycloakId;

    @Column(length = 64)
    private String label;

    @Column(nullable = false, length = 128)
    private String recipientName;

    @Column(nullable = false, length = 32)
    private String recipientPhone;

    @Column(nullable = false, length = 512)
    private String addressLine;

    @Column(nullable = false)
    private boolean isDefault;

    @CreationTimestamp
    private LocalDateTime createdDate;

    @UpdateTimestamp
    private LocalDateTime updatedDate;
}
