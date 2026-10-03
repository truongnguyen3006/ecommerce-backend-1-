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
import com.myexampleproject.common.outbox.JdbcOutbox;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myexampleproject.productservice.dto.ProductRequest;
import com.myexampleproject.productservice.dto.ProductResponse;
import com.myexampleproject.productservice.model.Product;
import com.myexampleproject.productservice.repository.ProductRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.myexampleproject.common.client.ProductCatalogClient.CatalogItem;
import com.myexampleproject.productservice.dto.ProductPage;
import com.myexampleproject.productservice.repository.ProductSpecifications;
import com.myexampleproject.productservice.repository.ProductVariantRepository;
import java.util.HashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductService {
    private final ProductRepository productRepository;
    private final JdbcOutbox outbox;
    private final ProductVariantRepository variantRepository;
    private final SkuIdentityService skuIdentity;

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
        validateRequest(request, true);
        if (request.getVariants() != null) request.getVariants().forEach(v -> skuIdentity.reserveNew(v.getSkuCode()));

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
        variants.forEach(v -> skuIdentity.attach(v.getSkuCode(),savedProduct.getId()));

        // Gửi Kafka Event
        if (request.getVariants() != null) {
            for (ProductVariantRequest vReq : request.getVariants()) {
                ProductCreatedEvent inventoryEvent = ProductCreatedEvent.builder()
                        .skuCode(vReq.getSkuCode())
                        .initialQuantity(vReq.getInitialQuantity() == null ? 0 : vReq.getInitialQuantity())
                        .build();
                outbox.append("product-created-topic", vReq.getSkuCode(), inventoryEvent);

                ProductCacheEvent cacheEvent = ProductCacheEvent.builder()
                        .skuCode(vReq.getSkuCode())
                        .name(product.getName())
                        .price(vReq.getPrice() != null ? vReq.getPrice() : product.getBasePrice())
                        .imageUrl(vReq.getImageUrl() != null ? vReq.getImageUrl() : product.getImageUrl())
                        .color(vReq.getColor())
                        .size(vReq.getSize())
                        .build();
                outbox.append("product-cache-update-topic", vReq.getSkuCode(), cacheEvent);
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
        validateRequest(request, false);

        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));

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
            sendKafkaEvents(savedProduct);
            return mapToProductResponse(savedProduct);
        }

        // --- Logic xử lý biến thể nếu request có gửi danh sách biến thể ---
        List<ProductVariant> currentVariants = product.getVariants();
        if (currentVariants == null) {
            currentVariants = new ArrayList<>();
            product.setVariants(currentVariants);
        }

        Set<String> oldSkus = currentVariants.stream().map(ProductVariant::getSkuCode).collect(Collectors.toSet());
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
                outbox.append("product-cache-update-topic", sku, null);
                skuIdentity.retire(sku);
                iterator.remove();
            }
        }

        // B. THÊM MỚI (NEW VARIANTS)
        for (ProductVariantRequest newReq : requestMap.values()) {
            skuIdentity.reserveNew(newReq.getSkuCode());
            skuIdentity.attach(newReq.getSkuCode(),id);
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
        for (ProductVariantRequest variant : request.getVariants()) {
            if (!oldSkus.contains(variant.getSkuCode())) {
                outbox.append("product-created-topic", variant.getSkuCode(), new ProductCreatedEvent(variant.getSkuCode(),
                        variant.getInitialQuantity() == null ? 0 : variant.getInitialQuantity()));
            }
        }
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
            outbox.append("product-cache-update-topic", v.getSkuCode(), cacheEvent);

        }
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "products_json_v5")
    public List<ProductResponse> getAllProducts() {
        List<Product> products = productRepository.findAll();
        return products.stream().map(this::mapToProductResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "product_item_json_v5", key = "#id")
    public ProductResponse getProductById(Long id){
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
        return mapToProductResponse(product);
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = "product_item_json_v5", key = "#id"),
            @CacheEvict(cacheNames = "products_json_v5", allEntries = true)
    })
    @Transactional
    public void deleteProductById(Long id){
        if(!productRepository.existsById(id)){
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
        }
        Product product = productRepository.findById(id).orElseThrow();
        product.getVariants().forEach(v -> {skuIdentity.retire(v.getSkuCode());outbox.append("product-cache-update-topic", v.getSkuCode(), null);});
        productRepository.deleteById(id);
    }

    private void validateRequest(ProductRequest request, boolean creation) {
        if (request == null || (creation && (request.getName() == null || request.getBasePrice() == null
                || request.getVariants() == null || request.getVariants().isEmpty()))
                || (request.getName() != null && request.getName().isBlank())
                || (request.getBasePrice() != null && request.getBasePrice().signum() < 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid product data");
        }
        Set<String> skus = new HashSet<>();
        if (request.getVariants() != null) for (ProductVariantRequest variant : request.getVariants()) {
            if (variant == null || variant.getSkuCode() == null || variant.getSkuCode().isBlank()
                    || !skus.add(variant.getSkuCode()) || (variant.getPrice() != null && variant.getPrice().signum() < 0)
                    || (variant.getInitialQuantity() != null && variant.getInitialQuantity() < 0)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or duplicate product variant");
            }
        }
    }

    @Transactional(readOnly = true)
    public ProductPage search(String keyword, String category, BigDecimal minPrice, BigDecimal maxPrice,
                              String color, String size, int page, int sizeLimit, String sort) {
        if (page < 0 || sizeLimit < 1 || sizeLimit > 100 || (minPrice != null && minPrice.signum() < 0)
                || (maxPrice != null && maxPrice.signum() < 0)
                || (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pagination or price range");
        }
        String[] parts = sort.split(",");
        String field = parts[0].equals("price") ? "basePrice" : parts[0];
        if (!Set.of("id", "name", "basePrice", "createdAt").contains(field) || parts.length > 2
                || (parts.length == 2 && !parts[1].equalsIgnoreCase("asc") && !parts[1].equalsIgnoreCase("desc"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported product sort");
        }
        Sort ordering = Sort.by(parts.length == 2 && parts[1].equalsIgnoreCase("desc") ? Sort.Direction.DESC : Sort.Direction.ASC, field);
        if (!field.equals("id")) ordering = ordering.and(Sort.by("id"));
        Page<Product> products = productRepository.findAll(ProductSpecifications.filter(keyword, category, minPrice, maxPrice, color, size),
                PageRequest.of(page, sizeLimit, ordering));
        return new ProductPage(products.getContent().stream().map(this::mapToProductResponse).toList(), page, sizeLimit,
                products.getTotalElements(), products.getTotalPages());
    }

    @Transactional(readOnly = true)
    public CatalogItem getVariant(String sku) {
        ProductVariant v = variantRepository.findBySkuCode(sku)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product variant not found"));
        if (Boolean.FALSE.equals(v.getIsActive())) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product variant is unavailable");
        Product product = v.getProduct();
        return new CatalogItem(v.getSkuCode(), product.getName(), v.getPrice() == null ? product.getBasePrice() : v.getPrice(),
                v.getImageUrl() == null ? product.getImageUrl() : v.getImageUrl(), v.getColor(), v.getSize(), v.getIsActive());
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
                            .galleryImages((v.getImages() == null ? java.util.Collections.<ProductImage>emptyList() : v.getImages()).stream()
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