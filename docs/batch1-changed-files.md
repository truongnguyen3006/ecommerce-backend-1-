# Batch 1 changed files

A = new; M = modified; D = removed. Paths are relative to repository root.

## 1. Normalize backend configuration and validation (39 files)

| Change | Path |
|---|---|
| M | `.env.example` |
| M | `api-gateway/src/main/resources/application.properties` |
| M | `cart-service/pom.xml` |
| A | `cart-service/src/main/java/com/myexampleproject/cartservice/config/HttpClientConfig.java` |
| M | `cart-service/src/main/resources/application.properties` |
| M | `common-dto/pom.xml` |
| M | `common-dto/src/main/java/com/myexampleproject/common/dto/CartItemRequest.java` |
| M | `common-dto/src/main/java/com/myexampleproject/common/dto/ErrorResponse.java` |
| M | `common-dto/src/main/java/com/myexampleproject/common/dto/OrderLineItemRequest.java` |
| M | `common-dto/src/main/java/com/myexampleproject/common/exception/GlobalExceptionHandler.java` |
| M | `discovery-server/src/main/resources/application.properties` |
| M | `docker-compose.yml` |
| M | `inventory-service/pom.xml` |
| M | `inventory-service/src/main/resources/application.properties` |
| M | `notification-service/pom.xml` |
| A | `notification-service/src/main/java/com/myexampleproject/notificationservice/config/HttpClientConfig.java` |
| M | `notification-service/src/main/resources/application.properties` |
| M | `order-service/pom.xml` |
| A | `order-service/src/main/java/com/myexampleproject/orderservice/config/HttpClientConfig.java` |
| M | `order-service/src/main/java/com/myexampleproject/orderservice/dto/CancelOrderRequest.java` |
| M | `order-service/src/main/java/com/myexampleproject/orderservice/dto/OrderRequest.java` |
| M | `order-service/src/main/resources/application.properties` |
| M | `payment-service/pom.xml` |
| A | `payment-service/src/main/java/com/myexampleproject/paymentservice/config/HttpClientConfig.java` |
| M | `payment-service/src/main/java/com/myexampleproject/paymentservice/dto/CreateVnpayPaymentRequest.java` |
| M | `payment-service/src/main/resources/application.properties` |
| M | `pom.xml` |
| M | `product-service/pom.xml` |
| A | `product-service/src/main/java/com/myexampleproject/productservice/dto/ProductPage.java` |
| M | `product-service/src/main/java/com/myexampleproject/productservice/dto/ProductRequest.java` |
| M | `product-service/src/main/java/com/myexampleproject/productservice/dto/ProductVariantRequest.java` |
| M | `product-service/src/main/resources/application.properties` |
| M | `user-service/pom.xml` |
| A | `user-service/src/main/java/com/myexampleproject/userservice/config/KeycloakHttpClientConfig.java` |
| M | `user-service/src/main/java/com/myexampleproject/userservice/dto/LoginRequest.java` |
| M | `user-service/src/main/java/com/myexampleproject/userservice/dto/TokenRefreshRequest.java` |
| M | `user-service/src/main/java/com/myexampleproject/userservice/dto/UserAddressRequest.java` |
| M | `user-service/src/main/java/com/myexampleproject/userservice/dto/UserRequest.java` |
| M | `user-service/src/main/resources/application.properties` |

## 2. Complete ecommerce backend business rules (29 files)

| Change | Path |
|---|---|
| A | `cart-service/src/main/java/com/myexampleproject/cartservice/config/CatalogConfig.java` |
| M | `cart-service/src/main/java/com/myexampleproject/cartservice/config/RedisConfig.java` |
| M | `cart-service/src/main/java/com/myexampleproject/cartservice/controller/CartController.java` |
| M | `cart-service/src/main/java/com/myexampleproject/cartservice/service/CartService.java` |
| A | `cart-service/src/main/java/com/myexampleproject/cartservice/service/StockClient.java` |
| A | `common-dto/src/main/java/com/myexampleproject/common/client/ProductCatalogClient.java` |
| M | `common-events/src/main/java/com/myexampleproject/common/event/CartCheckoutEvent.java` |
| M | `inventory-service/src/main/java/com/myexampleproject/inventoryservice/config/InventoryTopology.java` |
| M | `inventory-service/src/main/java/com/myexampleproject/inventoryservice/controller/InventoryController.java` |
| M | `inventory-service/src/main/java/com/myexampleproject/inventoryservice/service/InventoryService.java` |
| A | `order-service/src/main/java/com/myexampleproject/orderservice/config/CatalogConfig.java` |
| M | `order-service/src/main/java/com/myexampleproject/orderservice/controller/OrderController.java` |
| M | `order-service/src/main/java/com/myexampleproject/orderservice/repository/OrderRepository.java` |
| M | `order-service/src/main/java/com/myexampleproject/orderservice/service/OrderService.java` |
| M | `payment-service/src/main/java/com/myexampleproject/paymentservice/controller/PaymentController.java` |
| M | `payment-service/src/main/java/com/myexampleproject/paymentservice/repository/PaymentTransactionRepository.java` |
| M | `payment-service/src/main/java/com/myexampleproject/paymentservice/service/PaymentService.java` |
| M | `product-service/src/main/java/com/myexampleproject/productservice/config/ProductSeeder.java` |
| M | `product-service/src/main/java/com/myexampleproject/productservice/controller/ProductController.java` |
| M | `product-service/src/main/java/com/myexampleproject/productservice/repository/ProductRepository.java` |
| A | `product-service/src/main/java/com/myexampleproject/productservice/repository/ProductSpecifications.java` |
| A | `product-service/src/main/java/com/myexampleproject/productservice/repository/ProductVariantRepository.java` |
| M | `product-service/src/main/java/com/myexampleproject/productservice/service/ProductService.java` |
| M | `user-service/src/main/java/com/myexampleproject/userservice/config/UserSeeder.java` |
| M | `user-service/src/main/java/com/myexampleproject/userservice/controller/AuthController.java` |
| M | `user-service/src/main/java/com/myexampleproject/userservice/controller/UserAddressController.java` |
| M | `user-service/src/main/java/com/myexampleproject/userservice/controller/UserController.java` |
| M | `user-service/src/main/java/com/myexampleproject/userservice/service/KeycloakService.java` |
| M | `user-service/src/main/java/com/myexampleproject/userservice/service/UserService.java` |

## 3. Harden backend security and database integrity (27 files)

| Change | Path |
|---|---|
| M | `api-gateway/pom.xml` |
| M | `api-gateway/src/main/java/com/myexampleproject/apigateway/config/SecurityConfig.java` |
| M | `cart-service/src/main/java/com/myexampleproject/cartservice/config/SecurityConfig.java` |
| A | `cart-service/src/main/java/db/migration/V2__adopt_current_schema.java` |
| A | `cart-service/src/main/resources/application-migrations.properties` |
| A | `cart-service/src/main/resources/db/migration/V1__current_schema.sql` |
| A | `common-dto/src/main/java/com/myexampleproject/common/security/ApiSecurityErrors.java` |
| A | `common-dto/src/main/java/com/myexampleproject/common/security/KeycloakRoles.java` |
| M | `inventory-service/src/main/java/com/myexampleproject/inventoryservice/config/SecurityConfig.java` |
| M | `notification-service/src/main/java/com/myexampleproject/notificationservice/config/SecurityConfig.java` |
| M | `notification-service/src/main/java/com/myexampleproject/notificationservice/config/WebSocketConfig.java` |
| M | `order-service/src/main/java/com/myexampleproject/orderservice/config/SecurityConfig.java` |
| A | `order-service/src/main/java/db/migration/V2__adopt_current_schema.java` |
| A | `order-service/src/main/resources/application-migrations.properties` |
| A | `order-service/src/main/resources/db/migration/V1__current_schema.sql` |
| M | `payment-service/src/main/java/com/myexampleproject/paymentservice/config/SecurityConfig.java` |
| A | `payment-service/src/main/java/db/migration/V2__adopt_current_schema.java` |
| A | `payment-service/src/main/resources/application-migrations.properties` |
| A | `payment-service/src/main/resources/db/migration/V1__current_schema.sql` |
| M | `product-service/src/main/java/com/myexampleproject/productservice/config/SecurityConfig.java` |
| A | `product-service/src/main/java/db/migration/V2__adopt_current_schema.java` |
| A | `product-service/src/main/resources/application-migrations.properties` |
| A | `product-service/src/main/resources/db/migration/V1__current_schema.sql` |
| M | `user-service/src/main/java/com/myexampleproject/userservice/config/SecurityConfig.java` |
| A | `user-service/src/main/java/db/migration/V2__adopt_current_schema.java` |
| A | `user-service/src/main/resources/application-migrations.properties` |
| A | `user-service/src/main/resources/db/migration/V1__current_schema.sql` |

## 4. Improve backend reliability and tests (41 files)

| Change | Path |
|---|---|
| M | `Jmeter Script/multi-sku-concurrent-order.jmx` |
| M | `Jmeter Script/oversell-single-sku.jmx` |
| M | `README.md` |
| A | `api-gateway/src/test/java/com/myexampleproject/apigateway/GatewaySecurityTests.java` |
| A | `api-gateway/src/test/java/com/myexampleproject/apigateway/TokenValidationTests.java` |
| M | `cart-service/src/main/java/com/myexampleproject/cartservice/config/CartKafkaConsumerConfig.java` |
| A | `cart-service/src/test/java/com/myexampleproject/cartservice/CartBusinessTests.java` |
| A | `cart-service/src/test/java/com/myexampleproject/cartservice/CartSecurityTests.java` |
| A | `cart-service/src/test/java/com/myexampleproject/cartservice/KafkaConfigurationTests.java` |
| A | `cart-service/src/test/java/com/myexampleproject/cartservice/MigrationTests.java` |
| A | `common-dto/src/test/java/com/myexampleproject/common/ValidationAndRolesTests.java` |
| A | `docs/batch1-changed-files.md` |
| A | `docs/batch1-finalization.md` |
| M | `docs/local-startup-audit.md` |
| A | `inventory-service/src/main/java/com/myexampleproject/inventoryservice/config/InventoryHealthIndicator.java` |
| M | `inventory-service/src/main/java/com/myexampleproject/inventoryservice/config/KafkaStreamsConfig.java` |
| D | `inventory-service/src/test/java/com/myexampleproject/inventoryservice/InventoryServiceApplicationTests.java` |
| A | `inventory-service/src/test/java/com/myexampleproject/inventoryservice/InventoryTopologyTests.java` |
| M | `notification-service/src/main/java/com/myexampleproject/notificationservice/config/KafkaConsumerConfig.java` |
| M | `notification-service/src/main/java/com/myexampleproject/notificationservice/service/NotificationService.java` |
| A | `notification-service/src/test/java/com/myexampleproject/notificationservice/KafkaConfigurationTests.java` |
| D | `notification-service/src/test/java/com/myexampleproject/notificationservice/NotificationServiceApplicationTests.java` |
| A | `notification-service/src/test/java/com/myexampleproject/notificationservice/WebSocketOwnershipTests.java` |
| M | `order-service/src/main/java/com/myexampleproject/orderservice/config/KafkaConsumerConfig.java` |
| A | `order-service/src/test/java/com/myexampleproject/orderservice/KafkaConfigurationTests.java` |
| A | `order-service/src/test/java/com/myexampleproject/orderservice/MigrationTests.java` |
| A | `order-service/src/test/java/com/myexampleproject/orderservice/OrderBusinessTests.java` |
| D | `order-service/src/test/java/com/myexampleproject/orderservice/OrderServiceApplicationTests.java` |
| M | `payment-service/src/main/java/com/myexampleproject/paymentservice/config/PaymentKafkaConsumerConfig.java` |
| A | `payment-service/src/test/java/com/myexampleproject/paymentservice/KafkaConfigurationTests.java` |
| A | `payment-service/src/test/java/com/myexampleproject/paymentservice/MigrationTests.java` |
| A | `payment-service/src/test/java/com/myexampleproject/paymentservice/PaymentCallbackTests.java` |
| D | `payment-service/src/test/java/com/myexampleproject/paymentservice/PaymentServiceApplicationTests.java` |
| A | `product-service/src/test/java/com/myexampleproject/productservice/MigrationTests.java` |
| A | `product-service/src/test/java/com/myexampleproject/productservice/ProductBusinessTests.java` |
| A | `product-service/src/test/java/com/myexampleproject/productservice/ProductSearchTests.java` |
| A | `product-service/src/test/java/com/myexampleproject/productservice/ProductSecurityTests.java` |
| D | `product-service/src/test/java/com/myexampleproject/productservice/ProductServiceApplicationTests.java` |
| A | `user-service/src/test/java/com/myexampleproject/userservice/MigrationTests.java` |
| A | `user-service/src/test/java/com/myexampleproject/userservice/UserOwnershipTests.java` |
| D | `user-service/src/test/java/com/myexampleproject/userservice/UserServiceApplicationTests.java` |
