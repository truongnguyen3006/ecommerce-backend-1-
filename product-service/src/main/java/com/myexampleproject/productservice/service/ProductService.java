package com.myexampleproject.productservice.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.myexampleproject.common.event.*;
import com.myexampleproject.productservice.dto.ProductVariantRequest;
import com.myexampleproject.productservice.dto.ProductVariantResponse;
import com.myexampleproject.productservice.model.ProductImage;
import com.myexampleproject.productservice.model.ProductVariant;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myexampleproject.productservice.dto.ProductRequest;
import com.myexampleproject.productservice.dto.ProductResponse;
import com.myexampleproject.productservice.model.Product;
import com.myexampleproject.productservice.repository.ProductRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductService {
    private final ProductRepository productRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @CacheEvict(cacheNames = "products_json_v5", allEntries = true)
    public void clearProductListCache() {
        log.info("Đã xóa cache danh sách sản phẩm (products_json_v5)");
    }

    // =================================================================
    // 1. TẠO SẢN PHẨM
    // =================================================================
    @Transactional
    @CacheEvict(cacheNames = "products_json_v5", allEntries = true)
    public ProductResponse createProduct(ProductRequest request) {

        Product product = Product.builder()
                .name(request.getName())
                .description(request.getDescription())
                .basePrice(request.getBasePrice())
                .category(request.getCategory())
                .imageUrl(request.getImageUrl())
                .build();

        List<ProductVariant> variants = new ArrayList<>();
        if (request.getVariants() != null) {
            for (ProductVariantRequest vRequest : request.getVariants()) {
                ProductVariant variant = ProductVariant.builder()
                        .skuCode(vRequest.getSkuCode())
                        .color(vRequest.getColor())
                        .size(vRequest.getSize())
                        .price(vRequest.getPrice() != null ? vRequest.getPrice() : request.getBasePrice())
                        .imageUrl(vRequest.getImageUrl() != null ? vRequest.getImageUrl() : request.getImageUrl())
                        .isActive(vRequest.getIsActive() != null ? vRequest.getIsActive() : true)
                        .product(product)
                        .build();

                if (vRequest.getGalleryImages() != null) {
                    List<ProductImage> imageEntities = vRequest.getGalleryImages().stream()
                            .map(url -> ProductImage.builder().imageUrl(url).variant(variant).build())
                            .collect(Collectors.toList());
                    variant.setImages(imageEntities);
                }
                variants.add(variant);
            }
        }
        product.setVariants(variants);

        Product savedProduct = productRepository.save(product);

        // Gửi Kafka Event
        if (request.getVariants() != null) {
            for (ProductVariantRequest vReq : request.getVariants()) {
                ProductCreatedEvent inventoryEvent = ProductCreatedEvent.builder()
                        .skuCode(vReq.getSkuCode())
                        .initialQuantity(vReq.getInitialQuantity())
                        .build();
                kafkaTemplate.send("product-created-topic", vReq.getSkuCode(), inventoryEvent);

                ProductCacheEvent cacheEvent = ProductCacheEvent.builder()
                        .skuCode(vReq.getSkuCode())
                        .name(product.getName())
                        .price(vReq.getPrice() != null ? vReq.getPrice() : product.getBasePrice())
                        .imageUrl(vReq.getImageUrl())
                        .color(vReq.getColor())
                        .size(vReq.getSize())
                        .build();
                kafkaTemplate.send("product-cache-update-topic", vReq.getSkuCode(), cacheEvent);
            }
        }

        return mapToProductResponse(savedProduct);
    }

    // =================================================================
    // 2. CẬP NHẬT SẢN PHẨM (FIXED: PARTIAL UPDATE & PRICE LOGIC)
    // =================================================================
    @Transactional
    @CacheEvict(cacheNames = {"products_json_v5", "product_item_json_v5"}, allEntries = true)
    public ProductResponse updateProduct(Long id, ProductRequest request) {

        Product product = productRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found"));

        // [FIX 1] Partial Update: Chỉ cập nhật nếu request có gửi dữ liệu (khác null)
        // Ngăn chặn việc mất dữ liệu khi frontend gửi update từng phần.
        if (request.getName() != null) product.setName(request.getName());
        if (request.getDescription() != null) product.setDescription(request.getDescription());
        if (request.getBasePrice() != null) product.setBasePrice(request.getBasePrice());
        if (request.getCategory() != null) product.setCategory(request.getCategory());
        if (request.getImageUrl() != null) product.setImageUrl(request.getImageUrl());

        // [FIX 2] Nếu danh sách variants trong request là NULL -> GIỮ NGUYÊN biến thể cũ, không xóa.
        if (request.getVariants() == null) {
            Product savedProduct = productRepository.save(product);
            return mapToProductResponse(savedProduct);
        }

        // --- Logic xử lý biến thể nếu request có gửi danh sách biến thể ---
        List<ProductVariant> currentVariants = product.getVariants();
        if (currentVariants == null) {
            currentVariants = new ArrayList<>();
            product.setVariants(currentVariants);
        }

        List<ProductVariantRequest> incomingVariants = request.getVariants();
        Map<String, ProductVariantRequest> requestMap = incomingVariants.stream()
                .collect(Collectors.toMap(ProductVariantRequest::getSkuCode, v -> v));

        // A. DUYỆT LIST CŨ: Cập nhật hoặc Xóa
        Iterator<ProductVariant> iterator = currentVariants.iterator();
        while (iterator.hasNext()) {
            ProductVariant existingVariant = iterator.next();
            String sku = existingVariant.getSkuCode();

            if (requestMap.containsKey(sku)) {
                // UPDATE
                ProductVariantRequest req = requestMap.get(sku);
                existingVariant.setColor(req.getColor());
                existingVariant.setSize(req.getSize());

                // [FIX 3] Logic giá update: Ưu tiên giá riêng -> Giá base mới -> Giá base cũ
                BigDecimal newPrice = req.getPrice();
                if (newPrice == null) {
                    newPrice = product.getBasePrice(); // Lấy từ entity cha (đảm bảo không null)
                }
                existingVariant.setPrice(newPrice);

                existingVariant.setImageUrl(req.getImageUrl() != null ? req.getImageUrl() : product.getImageUrl());
                existingVariant.setIsActive(req.getIsActive() != null ? req.getIsActive() : true);

                if (req.getGalleryImages() != null) {
                    if (existingVariant.getImages() != null) existingVariant.getImages().clear();
                    else existingVariant.setImages(new ArrayList<>());

                    List<ProductImage> newImages = req.getGalleryImages().stream()
                            .map(url -> ProductImage.builder().imageUrl(url).variant(existingVariant).build())
                            .collect(Collectors.toList());
                    existingVariant.getImages().addAll(newImages);
                }
                requestMap.remove(sku);
            } else {
                // DELETE: Nếu Frontend gửi danh sách biến thể nhưng thiếu SKU này -> Xóa
                iterator.remove();
            }
        }

        // B. THÊM MỚI (NEW VARIANTS)
        for (ProductVariantRequest newReq : requestMap.values()) {
            // [FIX 4] Logic giá create variant: Nếu không nhập giá riêng, lấy giá Base từ Product Entity
            BigDecimal variantPrice = newReq.getPrice();
            if (variantPrice == null) {
                variantPrice = product.getBasePrice();
            }

            ProductVariant newVariant = ProductVariant.builder()
                    .skuCode(newReq.getSkuCode())
                    .color(newReq.getColor())
                    .size(newReq.getSize())
                    .price(variantPrice) // Đã xử lý null
                    .imageUrl(newReq.getImageUrl() != null ? newReq.getImageUrl() : product.getImageUrl())
                    .isActive(newReq.getIsActive() != null ? newReq.getIsActive() : true)
                    .product(product)
                    .build();

            if (newReq.getGalleryImages() != null) {
                List<ProductImage> imgEntities = newReq.getGalleryImages().stream()
                        .map(url -> ProductImage.builder().imageUrl(url).variant(newVariant).build())
                        .collect(Collectors.toList());
                newVariant.setImages(imgEntities);
            }
            currentVariants.add(newVariant);
        }

        Product savedProduct = productRepository.save(product);
        sendKafkaEvents(savedProduct);
        return mapToProductResponse(savedProduct);
    }

    private void sendKafkaEvents(Product product) {
        if (product.getVariants() == null) return;

        for (ProductVariant v : product.getVariants()) {
            ProductCacheEvent cacheEvent = ProductCacheEvent.builder()
                    .skuCode(v.getSkuCode())
                    .name(product.getName())
                    .price(v.getPrice() != null ? v.getPrice() : product.getBasePrice())
                    .imageUrl(v.getImageUrl() != null ? v.getImageUrl() : product.getImageUrl())
                    .color(v.getColor())
                    .size(v.getSize())
                    .build();
            kafkaTemplate.send("product-cache-update-topic", v.getSkuCode(), cacheEvent);

            // Chỉ gửi event tạo kho nếu cần thiết (logic này tùy nghiệp vụ, ở đây giữ nguyên)
            // Lưu ý: Update thường không reset kho về 0, nhưng code gốc của bạn đang set 0.
            // Nếu bạn muốn giữ kho cũ, InventoryService cần check tồn tại trước khi reset.
            ProductCreatedEvent inventoryEvent = ProductCreatedEvent.builder()
                    .skuCode(v.getSkuCode())
                    .initialQuantity(0)
                    .build();
            kafkaTemplate.send("product-created-topic", v.getSkuCode(), inventoryEvent);
        }
    }

    @Cacheable(cacheNames = "products_json_v5")
    public List<ProductResponse> getAllProducts() {
        List<Product> products = productRepository.findAll();
        return products.stream().map(this::mapToProductResponse).collect(Collectors.toList());
    }

    @Cacheable(cacheNames = "product_item_json_v5", key = "#id")
    public ProductResponse getProductById(Long id){
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found"));
        return mapToProductResponse(product);
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = "product_item_json_v5", key = "#id"),
            @CacheEvict(cacheNames = "products_json_v5", allEntries = true)
    })
    public void deleteProductById(Long id){
        if(!productRepository.existsById(id)){
            throw new RuntimeException("Product not found");
        }
        productRepository.deleteById(id);
    }

    private ProductResponse mapToProductResponse(Product product) {
        List<ProductVariantResponse> variantResponses = new ArrayList<>();
        if (product.getVariants() != null) {
            variantResponses = product.getVariants().stream()
                    .map(v -> ProductVariantResponse.builder()
                            .skuCode(v.getSkuCode())
                            .color(v.getColor())
                            .size(v.getSize())
                            .price(v.getPrice())
                            .imageUrl(v.getImageUrl())
                            .isActive(v.getIsActive())
                            .galleryImages(v.getImages().stream()
                                    .map(ProductImage::getImageUrl)
                                    .collect(Collectors.toList()))
                            .build())
                    .collect(Collectors.toList());
        }

        return ProductResponse.builder()
                .id(product.getId())
                .name(product.getName())
                .description(product.getDescription())
                .price(product.getBasePrice())
                .category(product.getCategory())
                .imageUrl(product.getImageUrl())
                .variants(variantResponses)
                .build();
    }
}