package com.myexampleproject.productservice.config;

import com.myexampleproject.common.event.ProductCacheEvent;
import com.myexampleproject.common.event.ProductCreatedEvent;
import com.myexampleproject.productservice.model.Product;
import com.myexampleproject.productservice.model.ProductVariant;
import com.myexampleproject.productservice.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import com.myexampleproject.common.outbox.JdbcOutbox;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@ConditionalOnProperty(name = "app.seed-products.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class ProductSeeder implements CommandLineRunner {

    private final ProductRepository productRepository;
    private final JdbcOutbox outbox;

    @Value("${app.seed-products.initial-stock:10000}")
    private int initialStock;

    @Override
    @Transactional
    public void run(String... args) {
        long count = productRepository.count();
        if (count == 0) {
            log.info("🚫 Database MySQL trống. Vui lòng kiểm tra file init.sql.");
            return;
        }

        log.info("Refreshing catalog and initializing missing inventory for {} products", count);
        syncData();
    }

    private void syncData() {
        List<Product> products = productRepository.findAll();
        int variantCount = 0;

        for (Product product : products) {
            if (product.getVariants() == null) continue;

            for (ProductVariant variant : product.getVariants()) {
                String sku = variant.getSkuCode();

                // Inventory initializes a SKU only when its persistent stock record is absent.
                ProductCreatedEvent inventoryEvent = ProductCreatedEvent.builder()
                        .skuCode(sku)
                        .initialQuantity(Math.max(0, initialStock))
                        .build();
                send("product-created-topic", sku, inventoryEvent);

                // 2. Gửi Event cho Redis (Cache thông tin hiển thị)
                ProductCacheEvent cacheEvent = ProductCacheEvent.builder()
                        .skuCode(sku)
                        .name(product.getName())
                        .price(variant.getPrice() != null ? variant.getPrice() : product.getBasePrice())
                        .imageUrl(variant.getImageUrl())
                        .color(variant.getColor())
                        .size(variant.getSize())
                        .build();
                send("product-cache-update-topic", sku, cacheEvent);

                variantCount++;
            }
        }



        log.info("Catalog initialization intents persisted for {} SKUs", variantCount);
    }

    private void send(String topic, String sku, Object event) {
        outbox.append(topic, sku, event);
    }
}
