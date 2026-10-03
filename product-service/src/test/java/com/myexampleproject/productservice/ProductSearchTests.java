package com.myexampleproject.productservice;

import com.myexampleproject.productservice.model.*;
import com.myexampleproject.productservice.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.domain.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties = {"app.outbox.enabled=false", "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
class ProductSearchTests {
    @Autowired ProductRepository repository;
    private void seed(String name, String category, String color, String size, int price) {
        Product product = Product.builder().name(name).category(category).basePrice(BigDecimal.valueOf(price)).build();
        product.setVariants(List.of(ProductVariant.builder().skuCode(name + size).color(color).size(size).price(BigDecimal.valueOf(price)).isActive(true).product(product).build()));
        repository.saveAndFlush(product);
    }
    @Test void databaseSearchFiltersAndPaginatesWithStableTotals() {
        seed("Nike Red", "Shoes", "Red", "40", 100);seed("Nike Blue", "Shoes", "Blue", "41", 200);seed("Other", "Shirts", "Red", "40", 50);
        var spec = ProductSpecifications.filter("nike", "shoes", BigDecimal.valueOf(100), BigDecimal.valueOf(200), null, null);
        Page<Product> first = repository.findAll(spec, PageRequest.of(0, 1, Sort.by("basePrice").ascending()));
        assertThat(first.getTotalElements()).isEqualTo(2);assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.getContent()).extracting(Product::getName).containsExactly("Nike Red");
        assertThat(repository.findAll(spec, PageRequest.of(1,1,Sort.by("basePrice").ascending())).getContent()).extracting(Product::getName).containsExactly("Nike Blue");
        assertThat(repository.findAll(ProductSpecifications.filter(null,null,null,null,"red","40"))).hasSize(2);
    }
    @Test void legacyFacetSpacesAndCaseMatchWithoutRewritingLabelsOrSemanticInteriorSpaces() {
        seed("Legacy","  Giày  ","  Đen  "," XL ",100);seed("Same label","giày","đen","xl",120);seed("Interior","Gi  ày","Đen","XL",100);
        assertThat(repository.findAll(ProductSpecifications.filter(null," GIÀY ",null,null," ĐEN "," xl "))).hasSize(2);
        assertThat(com.myexampleproject.productservice.service.FacetValues.label("  Gi  ày  ")).isEqualTo("Gi  ày");
        assertThat(repository.findAll().stream().map(Product::getCategory)).contains("  Giày  ");
    }
    @Test void colorAndSizeMustMatchTheSameActiveVariant() {
        Product p = Product.builder().name("Combo").basePrice(BigDecimal.TEN).build();
        p.setVariants(List.of(ProductVariant.builder().skuCode("RED40").color("Red").size("40").isActive(true).product(p).build(),
                ProductVariant.builder().skuCode("BLUE41").color("Blue").size("41").isActive(true).product(p).build()));
        repository.saveAndFlush(p);
        assertThat(repository.findAll(ProductSpecifications.filter(null,null,null,null,"red","41"))).isEmpty();
    }
    @Test void repeatedMatchingVariantsDoNotDuplicateProducts() {
        Product p = Product.builder().name("Multi").basePrice(BigDecimal.TEN).build();
        p.setVariants(List.of(ProductVariant.builder().skuCode("RED1").color("Red").size("40").isActive(true).product(p).build(),
                ProductVariant.builder().skuCode("RED2").color("Red").size("41").isActive(true).product(p).build()));
        repository.saveAndFlush(p);
        assertThat(repository.findAll(ProductSpecifications.filter(null,null,null,null,"red",null), PageRequest.of(0,1)).getTotalElements()).isEqualTo(1);
    }
}
