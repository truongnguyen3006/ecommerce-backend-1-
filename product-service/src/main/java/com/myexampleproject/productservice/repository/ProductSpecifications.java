package com.myexampleproject.productservice.repository;

import com.myexampleproject.productservice.model.*;
import org.springframework.data.jpa.domain.Specification;
import jakarta.persistence.criteria.*;
import java.math.BigDecimal;
import java.util.*;

public final class ProductSpecifications {
    private ProductSpecifications() {}
    public static Specification<Product> filter(String keyword, String category, BigDecimal minPrice, BigDecimal maxPrice, String color, String size) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (text(keyword)) {
                String pattern = "%" + keyword.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                predicates.add(cb.or(cb.like(cb.lower(root.get("name")), pattern, '\\'), cb.like(cb.lower(root.get("description")), pattern, '\\')));
            }
            if (text(category)) predicates.add(cb.equal(cb.lower(root.get("category")), category.trim().toLowerCase(Locale.ROOT)));
            if (text(color) || text(size) || minPrice != null || maxPrice != null) {
                Join<Product, ProductVariant> variant = root.join("variants", JoinType.INNER);
                query.distinct(true);
                predicates.add(cb.or(cb.isNull(variant.get("isActive")), cb.isTrue(variant.get("isActive"))));
                if (text(color)) predicates.add(cb.equal(cb.lower(variant.get("color")), color.trim().toLowerCase(Locale.ROOT)));
                if (text(size)) predicates.add(cb.equal(cb.lower(variant.get("size")), size.trim().toLowerCase(Locale.ROOT)));
                Expression<BigDecimal> price = cb.coalesce(variant.<BigDecimal>get("price"), root.<BigDecimal>get("basePrice"));
                if (minPrice != null) predicates.add(cb.greaterThanOrEqualTo(price, minPrice));
                if (maxPrice != null) predicates.add(cb.lessThanOrEqualTo(price, maxPrice));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
    private static boolean text(String value) { return value != null && !value.isBlank(); }
}
