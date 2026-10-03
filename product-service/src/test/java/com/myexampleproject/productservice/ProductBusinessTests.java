package com.myexampleproject.productservice;

import com.myexampleproject.productservice.dto.*;
import com.myexampleproject.productservice.model.*;
import com.myexampleproject.productservice.repository.*;
import com.myexampleproject.productservice.service.ProductService;
import com.myexampleproject.common.event.*;
import org.junit.jupiter.api.*;
import com.myexampleproject.common.outbox.JdbcOutbox;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ProductBusinessTests {
    ProductRepository repository = mock(ProductRepository.class);
    ProductVariantRepository variants = mock(ProductVariantRepository.class);
    JdbcOutbox kafka = mock(JdbcOutbox.class);
    ProductService service = new ProductService(repository,kafka,variants);
    @Test void missingProductReturns404() {
        when(repository.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getProductById(9L)).isInstanceOfSatisfying(ResponseStatusException.class,e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
    }
    @Test void negativePriceAndDuplicateSkuUpdatesAreRejectedBeforePersistence() {
        assertThatThrownBy(() -> service.updateProduct(1L,ProductRequest.builder().basePrice(BigDecimal.valueOf(-1)).build())).isInstanceOf(ResponseStatusException.class);
        ProductVariantRequest variant = ProductVariantRequest.builder().skuCode("X").build();
        assertThatThrownBy(() -> service.updateProduct(1L,ProductRequest.builder().variants(List.of(variant,variant)).build())).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(repository,kafka);
    }
    @Test void partialUpdatePreservesVariantsAndRefreshesCatalogWithoutAddingStock() {
        Product p = Product.builder().id(1L).name("Old").basePrice(BigDecimal.TEN).build();
        p.setVariants(new ArrayList<>(List.of(ProductVariant.builder().skuCode("SKU").price(BigDecimal.TEN).product(p).build())));
        when(repository.findById(1L)).thenReturn(Optional.of(p));when(repository.save(any())).thenAnswer(a -> a.getArgument(0));
        ProductResponse result = service.updateProduct(1L,ProductRequest.builder().name("New").build());
        assertThat(result.getVariants()).hasSize(1);assertThat(result.getVariants().getFirst().getGalleryImages()).isEmpty();
        verify(kafka).append(eq("product-cache-update-topic"),eq("SKU"),any(ProductCacheEvent.class));
        verify(kafka,never()).append(eq("product-created-topic"),anyString(),any());
    }
    @Test void badPageRangeAndSortAreRejected() {
        assertThatThrownBy(() -> service.search(null,null,BigDecimal.TEN,BigDecimal.ONE,null,null,0,20,"id,asc")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.search(null,null,null,null,null,null,0,1000,"id,asc")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.search(null,null,null,null,null,null,0,20,"description,anything")).isInstanceOf(ResponseStatusException.class);
    }
}
