package com.myexampleproject.productservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myexampleproject.productservice.model.Product;

public interface ProductRepository extends JpaRepository<Product, Long> {
	
}
