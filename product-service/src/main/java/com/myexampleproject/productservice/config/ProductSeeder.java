package com.myexampleproject.productservice.config;

import com.myexampleproject.common.event.ProductCacheEvent;
import com.myexampleproject.common.event.ProductCreatedEvent;
import com.myexampleproject.productservice.model.Product;
import com.myexampleproject.productservice.model.ProductVariant;
import com.myexampleproject.productservice.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class ProductSeeder implements CommandLineRunner {

    private final ProductRepository productRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    // Inject Redis để kiểm tra trạng thái seed
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String SEED_KEY = "system:data:seeded";

    @Override
    @Transactional
    public void run(String... args) {
        // 1. Kiểm tra xem đã seed dữ liệu chưa (Tránh cộng dồn tồn kho khi restart)
        if (Boolean.TRUE.equals(redisTemplate.hasKey(SEED_KEY))) {
            log.info("✅ Dữ liệu đã được đồng bộ trước đó. Bỏ qua bước Seeding.");
            return;
        }

        long count = productRepository.count();
        if (count == 0) {
            log.info("🚫 Database MySQL trống. Vui lòng kiểm tra file init.sql.");
            return;
        }

        log.info("🔄 PHÁT HIỆN KHỞI ĐỘNG LẦN ĐẦU - BẮT ĐẦU ĐỒNG BỘ {} SẢN PHẨM...", count);
        syncData();
    }

    private void syncData() {
        List<Product> products = productRepository.findAll();
        int variantCount = 0;

        for (Product product : products) {
            if (product.getVariants() == null) continue;

            for (ProductVariant variant : product.getVariants()) {
                String sku = variant.getSkuCode();

                // 1. Gửi Event cho Inventory (Khởi tạo kho)
                // Lưu ý: Logic aggregate của bạn là cộng dồn, nên chỉ gửi 1 lần duy nhất ở đây
                ProductCreatedEvent inventoryEvent = ProductCreatedEvent.builder()
                        .skuCode(sku)
                        .initialQuantity(10000) // Mặc định 100 cái cho mỗi SKU
                        .build();
                kafkaTemplate.send("product-created-topic", sku, inventoryEvent);

                // 2. Gửi Event cho Redis (Cache thông tin hiển thị)
                ProductCacheEvent cacheEvent = ProductCacheEvent.builder()
                        .skuCode(sku)
                        .name(product.getName())
                        .price(variant.getPrice())
                        .imageUrl(variant.getImageUrl())
                        .color(variant.getColor())
                        .size(variant.getSize())
                        .build();
                kafkaTemplate.send("product-cache-update-topic", sku, cacheEvent);

                variantCount++;
            }
        }

        // Đánh dấu đã seed xong (Lưu trong Redis 1 ngày hoặc vĩnh viễn tùy bạn)
        redisTemplate.opsForValue().set(SEED_KEY, "true", Duration.ofHours(24));

        log.info("✅ HOÀN TẤT SEEDING! Đã bắn event cho {} SKU.", variantCount);
    }
}