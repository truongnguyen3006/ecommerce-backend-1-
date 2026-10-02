package com.myexampleproject.productservice.repository;
import com.myexampleproject.productservice.model.ProductVariant;
import org.springframework.data.jpa.repository.*;
import java.util.Optional;
public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {
    @EntityGraph(attributePaths = "product")
    Optional<ProductVariant> findBySkuCode(String skuCode);
}
