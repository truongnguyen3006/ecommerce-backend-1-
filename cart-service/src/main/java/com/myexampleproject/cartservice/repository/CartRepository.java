package com.myexampleproject.cartservice.repository;


import com.myexampleproject.cartservice.model.CartEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartRepository extends JpaRepository<CartEntity, String> {
    // userId is PK
}