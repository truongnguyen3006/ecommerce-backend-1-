package com.myexampleproject.inventoryservice.service;

import com.myexampleproject.inventoryservice.config.InventoryTopology;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.springframework.kafka.config.StreamsBuilderFactoryBean; // Import này là đúng
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    /*
     * Inject StreamsBuilderFactoryBean là đúng.
     */
    private final StreamsBuilderFactoryBean streamsBuilderFactory;


    /*
     * XÓA BỎ PHƯƠNG THỨC getKafkaStreams() GÂY LỖI
     */
    // private KafkaStreams getKafkaStreams() { ... }


    /**
     * Lấy tồn kho hiện tại theo SKU
     */
    public Integer getQuantity(String sku) {
        ReadOnlyKeyValueStore<String, Integer> store = waitUntilStoreIsReady();
        Integer qty = store.get(sku);
        return qty != null ? qty : 0;
    }

    /**
     * Chờ state store mở xong trước khi truy vấn.
     * (Phương thức này đã đúng logic từ trước)
     */
    private ReadOnlyKeyValueStore<String, Integer> waitUntilStoreIsReady() {

        for (int attempt = 1; attempt <= 30; attempt++) {

            // 1. Lấy KafkaStreams từ factory
            KafkaStreams streams = streamsBuilderFactory.getKafkaStreams();

            // 2. Nếu streams chưa khởi tạo xong (null), lặp lại
            if (streams == null) {
                log.warn("State store chưa ready (KafkaStreams is null)... thử lần {}", attempt);
                sleep(300);
                continue; // Bỏ qua phần còn lại của vòng lặp
            }

            // 3. Nếu streams đã có, lấy state của nó
            KafkaStreams.State s = streams.state();

            // 4. Nếu đang RUNNING hoặc REBALANCING, thử truy vấn
            if (s == KafkaStreams.State.RUNNING || s == KafkaStreams.State.REBALANCING) {
                try {
                    return streams.store(
                            StoreQueryParameters.fromNameAndType(
                                    InventoryTopology.INVENTORY_STORE,
                                    QueryableStoreTypes.keyValueStore()
                            )
                    );
                } catch (Exception e) {
                    // Store có thể chưa sẵn sàng, lặp lại
                    log.warn("Store query failed, retrying... ({})", e.getMessage());
                }
            }

            // 5. Nếu state chưa đúng, log và lặp lại
            log.warn("State store chưa ready (state={})... thử lần {}", s, attempt);
            sleep(300);
        }

        throw new IllegalStateException("StateStore chưa ready sau 30 lần retry!");
    }

    /**
     * Sau adjust inventory, chờ state được update rồi trả về kết quả.
     * SỬA LẠI LOGIC CHO ĐÚNG:
     */
    public Integer waitForUpdatedQuantity(String sku) {

        for (int attempt = 1; attempt <= 10; attempt++) {

            try {
                // 1. Lấy KafkaStreams từ factory
                KafkaStreams streams = streamsBuilderFactory.getKafkaStreams();

                // 2. Nếu streams null HOẶC state chưa ready, ném lỗi để sleep/retry
                if (streams == null || !streams.state().isRunningOrRebalancing()) {
                    throw new IllegalStateException("Streams chưa ready hoặc đang rebalance");
                }

                // 3. Truy vấn store
                ReadOnlyKeyValueStore<String, Integer> store =
                        streams.store(
                                StoreQueryParameters.fromNameAndType(
                                        InventoryTopology.INVENTORY_STORE,
                                        QueryableStoreTypes.keyValueStore()
                                )
                        );

                Integer qty = store.get(sku);
                if (qty != null) {
                    return qty; // Tìm thấy, trả về ngay
                }

            } catch (Exception ignored) {
                // Bị exception (ví dụ: IllegalStateException ở trên, hoặc store not ready)
                // -> Bỏ qua, sleep và lặp lại
            }

            sleep(50);
        }

        return null; // không có update sau 10 lần thử
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (Exception ignored) {
        }
    }
}