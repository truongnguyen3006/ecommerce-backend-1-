package com.myexampleproject.productservice.controller;

import java.util.List;

import com.myexampleproject.common.event.ProductCacheEvent;
import com.myexampleproject.common.event.ProductCreatedEvent;
import com.myexampleproject.productservice.model.Product;
import com.myexampleproject.productservice.model.ProductVariant;
import com.myexampleproject.productservice.repository.ProductRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import com.myexampleproject.productservice.dto.ProductRequest;
import com.myexampleproject.productservice.dto.ProductResponse;
import com.myexampleproject.productservice.service.ProductService;

import lombok.RequiredArgsConstructor;
//method trong class sẽ trả về JSON hoặc XML (không trả về view)
@RestController
//mapping URL đến method hoặc class
@RequestMapping("/api/product")
@RequiredArgsConstructor
@Slf4j
public class ProductController {
	
	private final ProductService productService;
	
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ProductResponse createProduct(@RequestBody ProductRequest productRequest) {
        return productService.createProduct(productRequest);
	}

    @PutMapping("/{id}")
    @ResponseStatus(HttpStatus.OK)
    public ProductResponse updateProduct(@PathVariable Long id, @RequestBody ProductRequest productRequest) {
        return productService.updateProduct(id, productRequest);
    }
	
	@GetMapping
	@ResponseStatus(HttpStatus.OK)
	public List<ProductResponse> getAllProducts(){
		return productService.getAllProducts();
	}

    @GetMapping("/{id}")
    public ProductResponse getProductById(@PathVariable Long id){
        return productService.getProductById(id);
    }

    @DeleteMapping("/{id}")
    public void deleteProductById(@PathVariable Long id){
        productService.deleteProductById(id);
    }

    // Inject thêm 2 bean này vào Controller (nếu chưa có)
    private final ProductRepository productRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    // Trong ProductController.java

    @GetMapping("/admin/warm-cache")
    public String warmProductCache() {
        log.info("Starting FULL cache warm-up (Parent + Variants)...");

        // 1. Xóa cache danh sách cũ
        productService.clearProductListCache();

        List<Product> allProducts = productRepository.findAll();
        int variantCount = 0;

        // 2. Lặp qua từng Sản phẩm cha
        for (Product product : allProducts) {

            // Nếu sản phẩm chưa có biến thể nào thì bỏ qua
            if (product.getVariants() == null || product.getVariants().isEmpty()) {
                continue;
            }

            // 3. QUAN TRỌNG: Lặp qua từng Biến thể (Variant) để lấy SKU Code
            for (ProductVariant variant : product.getVariants()) {

                // --- Gửi Event cho Order Service (Redis) ---
                ProductCacheEvent cacheEvent = ProductCacheEvent.builder()
                        .skuCode(variant.getSkuCode())      // ✅ LẤY TỪ VARIANT
                        .name(product.getName())            // Tên lấy từ Cha
                        .price(variant.getPrice() != null ? variant.getPrice() : product.getBasePrice())
                        .imageUrl(variant.getImageUrl() != null ? variant.getImageUrl() : product.getImageUrl())
                        .color(variant.getColor())          // ✅ LẤY TỪ VARIANT
                        .size(variant.getSize())            // ✅ LẤY TỪ VARIANT
                        .build();

                kafkaTemplate.send("product-cache-update-topic", variant.getSkuCode(), cacheEvent);

                // --- Gửi Event cho Inventory Service ---
                ProductCreatedEvent inventoryEvent = ProductCreatedEvent.builder()
                        .skuCode(variant.getSkuCode())      // ✅ LẤY TỪ VARIANT
                        .initialQuantity(1000)
                        .build();
                kafkaTemplate.send("product-created-topic", variant.getSkuCode(), inventoryEvent);

                variantCount++;
            }
        }

        return "Warm-up hoàn tất. Đã gửi event cho " + variantCount + " biến thể (SKU).";
    }
}
