# Project 1 local startup audit

Phạm vi: branch `project1-recovery`, Spring services chạy trên Windows/IDE, dependencies chạy bằng Docker Desktop. Giữ nguyên microservices và nghiệp vụ hiện có.

## Nguyên nhân và sửa đổi

| Vấn đề | Nguyên nhân trong source | Xử lý |
|---|---|---|
| Keycloak token HTTP 401 | `user-service` và `.env.example` mặc định `local-demo-client-secret`, nhưng client `spring-cloud-client` trong realm export dùng secret khác | Đồng bộ realm export về secret demo đã được Spring cấu hình; realm đang lưu trong MySQL cần kiểm tra riêng theo hướng dẫn bên dưới |
| Realm không được import trên môi trường mới | Keycloak 18 `DirImportProvider` chỉ đọc file kết thúc bằng `-realm.json`; file được mount trước đây là `realm-export.json` | Compose mount cùng file source dưới tên `spring-boot-microservices-realm-realm.json` trong container, read-only |
| `${spring.kafka.consumer.group-id}` không resolve | `payment-service/.../config/PaymentKafkaConsumerConfig.java` inject property này nhưng payment properties bị copy nguyên từ product | Khôi phục payment properties, thêm duy nhất `payment-group`, khớp listener trong `PaymentService` |
| Payment trùng Product | Payment khai báo `spring.application.name=product-service`, cổng `8083`, database `product-service` | Khôi phục `payment-service`, `8089`, database `payment-service` |
| Cổng 8087 đang được sử dụng | Chỉ Notification khai báo 8087; không có service thứ hai trùng cổng này trong repo | Giữ nguyên 8087; kiểm tra PID/command line trên Windows. Không thể xác nhận PID từ môi trường audit |
| Gateway không kiểm tra được JWT trên Windows | Custom decoder hard-code `http://keycloak:8085`, hostname chỉ tồn tại trong Docker network | Lấy JWK URL từ `issuer-uri` local đã cấu hình; thêm validator issuer chuẩn, không tắt xác thực |
| Build/image Java không thống nhất | Module khai báo source/target 24 nhưng Boot parent mặc định Java 17, Jib base image Java 21 | Khai báo `java.version=24`, dùng `eclipse-temurin:24-jre` cho Jib |
| Gateway dùng starter khác patch version | Hai security starters pin 3.5.6 trong project Boot 3.5.7 | Bỏ pin để theo dependency management của parent |
| VNPay env không được bind | Payment properties bị copy từ Product và mất các mapping env VNPay | Khôi phục mapping theo `VNPayConfig` và `.env.example`; thiếu credential vẫn cho phép startup/mock COD, API VNPay yêu cầu credential thật |
| README login sai mật khẩu demo | README ghi `admin123`, `UserSeeder` thực tế tạo `admin123456@` | Sửa hướng dẫn; không đổi mật khẩu của tài khoản đã có |

## Bảng service đã đối chiếu

| Service | Cổng | Database | Kafka consumer |
|---|---:|---|---|
| API Gateway | 8080; instance benchmark 8090 được override khi chạy | Không dùng | Không dùng |
| Discovery Server | 8761 | Không dùng | Không dùng |
| Inventory Service | 8082 | Không dùng JPA | Kafka Streams application ID `inventory-streams-v11` |
| Product Service | 8083 | `product-service` | Producer; không cần consumer group |
| Cart Service | 8084 | `cart-service` | Default `cart-group`; cache `cart-product-cacher`, cleanup `cart-cleaner-group` |
| Order Service | 8086 | `order-service` | Default `order-updater-group`; listener saga/cache/update có group riêng |
| Notification Service | 8087 | Không dùng | `notification-group` |
| User Service | 8088 | `user-service` | Không dùng Kafka consumer |
| Payment Service | 8089 | `payment-service` | `payment-group` |

Không có cổng service trùng sau sửa. Shared modules `common-dto`, `common-events` không phải server.

Các điểm audit khác:

- Tất cả business datasource dùng `localhost:3306`, root/root, driver `com.mysql.cj.jdbc.Driver`, `ddl-auto=update`. Compose đã publish `3306:3306`; giữ cấu hình đã được sửa trên máy Windows. Không dùng MySQL riêng của Keycloak cho business services.
- `mysql-init/init.sql` tạo/seed User, Order và Product. Cart và Payment được Connector/J tạo database qua `createDatabaseIfNotExist=true`, Hibernate tạo bảng. Script init chỉ chạy khi volume MySQL mới; không chạy lại toàn bộ script vào DB đang có vì chứa CREATE TABLE/INSERT dữ liệu seed.
- Nếu cấu hình Payment sai trước đây đã tạo bảng giao dịch trong `product-service`, các bảng/record đó vẫn được giữ nguyên. Sửa này không tự chuyển dữ liệu cũ sang `payment-service`; cần kiểm tra và backup dữ liệu giao dịch cũ trước khi dùng luồng VNPay trên DB riêng.
- Eureka server dùng HTTP Basic `eureka/password`; các client dùng cùng credentials và địa chỉ `localhost:8761/eureka/`. Gateway routes `lb://...` khớp tên service. Đường dẫn `/eureka/web` vẫn cần Basic authentication khi được proxy tới Eureka.
- Redis local `localhost:6379`, không password. Product/Order dùng Boot connection factory; Cart có factory custom mặc định localhost/6379, không tự áp dụng các setting pool trong properties. Đây không phải blocker với cấu hình local hiện tại.
- Kafka: host services dùng `localhost:9092`; Docker internal listener là `kafka:29092`. KRaft broker có replication factor 1 cho offsets/transaction log, phù hợp một broker local và Streams `exactly_once_v2`.
- Schema Registry: host `http://localhost:8081`; trong Docker dùng `kafka:29092`. Producer/consumer/serde dùng Confluent JSON Schema, không chuyển sang serializer khác. Các custom consumer factory có thể override Boot listener settings; giữ nguyên group của các listener hiện có.
- Realm: `spring-boot-microservices-realm`; client: confidential `spring-cloud-client`, client-secret authenticator, enabled. `serviceAccountsEnabled=true` cho `client_credentials`, `directAccessGrantsEnabled=true` cho login password, refresh dùng `refresh_token`. Service account có realm-management roles gồm `realm-admin`, `manage-users`, `query-users`, `view-users`, `view-realm`; realm roles `admin/user` khớp converter tạo `ROLE_ADMIN/ROLE_USER`. UserSeeder gọi token endpoint trong startup nên Keycloak cần sẵn sàng trước User Service.
- Đã đọc cả 9 SecurityConfig. Giữ quy tắc route hiện có. Notification vẫn permit-all và Gateway vẫn cache kết quả decode JWT 10 phút như source gốc; cache có thể tái sử dụng token sau thời điểm hết hạn. Các điểm này cần một đợt security audit riêng trước public deployment. Thay đổi hiện tại không mở thêm endpoint để né lỗi authentication.
- `nginx.conf` vẫn có hai upstream `8080/8090` cho benchmark. Dùng trực tiếp gateway 8080 khi chạy một instance; chạy đủ hai instance khi dùng entrypoint 8000. Gateway WebSocket route `/ws/**` và các route nghiệp vụ giữ nguyên; chưa kiểm thử WebSocket end-to-end.
- Cloudinary credentials trống không chặn startup; upload mới yêu cầu credentials. `.env.example` không được Spring tự load. VNPay callback IPN không thể gọi từ bên ngoài tới localhost nếu chưa có public callback URL.

## Kiểm tra/căn chỉnh Keycloak không xóa dữ liệu

Fresh database: Compose import đúng filename và secret demo mới. Database đã có realm: `--import-realm` **bỏ qua realm đã tồn tại**, nên restart/recreate container không tự cập nhật client secret hay roles. Không xóa volume để sửa lỗi này.

1. Mở `http://localhost:8085`, đăng nhập admin console bằng tài khoản quản trị hiện tại (fresh demo: admin/admin).
2. Chọn realm `spring-boot-microservices-realm` → Clients → `spring-cloud-client`.
3. Kiểm tra client confidential/client authentication, enabled, Service Accounts và Direct Access Grants. Kiểm tra service account có realm-management roles như trên.
4. Trong Credentials, copy **secret hiện tại** vào biến môi trường `KEYCLOAK_CLIENT_SECRET` của IDE/terminal khởi chạy User Service. Nếu regenerate secret, lấy giá trị mới vào env này; không cần chỉnh DB hoặc bypass SecurityConfig. Secret demo trong export chỉ áp dụng fresh import.
5. Xóa/đổi env override cũ nếu đang khác secret của client. Sửa `.env` đơn thuần không đưa giá trị vào JVM; IDE run configuration cần env tương ứng. Các process đã chạy cần restart để nhận env mới.

PowerShell: kiểm tra đúng endpoint và service account trước khi chạy User Service (thay giá trị env bằng secret đang có; không chia sẻ access token):

```powershell
$env:KEYCLOAK_CLIENT_SECRET = 'local-demo-client-secret' # chỉ cho fresh demo; dùng secret thực tế nếu realm đã có
$realmUrl = 'http://localhost:8085/realms/spring-boot-microservices-realm'
$discovery = Invoke-RestMethod "$realmUrl/.well-known/openid-configuration"
$discovery.issuer
$token = Invoke-RestMethod -Method Post -Uri "$realmUrl/protocol/openid-connect/token" -Body @{
    grant_type = 'client_credentials'
    client_id = 'spring-cloud-client'
    client_secret = $env:KEYCLOAK_CLIENT_SECRET
}
if (-not $token.access_token) { throw 'Keycloak did not return an access token' }
Invoke-RestMethod -Uri 'http://localhost:8085/admin/realms/spring-boot-microservices-realm/users?max=1' -Headers @{
    Authorization = "Bearer $($token.access_token)"
} | Select-Object id, username
```

Token request 401: kiểm tra Credentials/client-id/env. Token thành công nhưng admin API 403: kiểm tra service account role mappings/scope. Issuer 404: realm chưa được import/chưa tồn tại. Login password grant còn cần đúng username/password người dùng; tài khoản admin ứng dụng do UserSeeder tạo mới có password `admin123456@`, khác Keycloak admin console.

## Cổng 8087 trên Windows

Chạy trong PowerShell. Đọc command line để tránh dừng nhầm service; chỉ stop PID đã xác nhận là process cũ của Notification hoặc tiến trình bạn không cần:

```powershell
$connections = Get-NetTCPConnection -LocalPort 8087 -State Listen -ErrorAction SilentlyContinue
$ownerIds = $connections.OwningProcess | Sort-Object -Unique
foreach ($ownerId in $ownerIds) {
    Get-CimInstance Win32_Process -Filter "ProcessId = $ownerId" |
        Select-Object ProcessId, Name, CommandLine
}
# Sau khi xác nhận PID cụ thể:
Stop-Process -Id <PID>
```

Hoặc CMD: `netstat -ano | findstr :8087`, `tasklist /FI "PID eq <PID>"`, rồi `taskkill /PID <PID>` sau khi xác nhận. Nếu process đang chạy thuộc IDE, ưu tiên nút Stop của run configuration. Kiểm tra thêm IDE arguments `--server.port` và biến `SERVER_PORT` nếu PID là service khác; repo không có duplicate 8087.

## Thứ tự chạy và kiểm tra local

1. JDK 24, Maven 3.9+, Docker Desktop Linux containers. `java -version`, `mvn -version` phải cùng JDK 24; IDE project SDK/run SDK cũng cần khớp.
2. Từ root: `docker compose config`, rồi `docker compose up -d mysql-business redis keycloak-mysql keycloak kafka schema-registry`. MySQL/Keycloak DB và Kafka cần healthy; Keycloak/Schema Registry còn cần kiểm tra HTTP readiness.
3. `docker compose ps`; kiểm tra `docker compose logs --tail=100 keycloak kafka schema-registry mysql-business` nếu chưa sẵn sàng.
4. MySQL: `docker compose exec mysql-business mysql -uroot -proot -e "SELECT 1; SHOW DATABASES;"`; Windows: `Test-NetConnection localhost -Port 3306`. Redis: `docker compose exec redis redis-cli ping` → PONG. Schema Registry: `Invoke-RestMethod http://localhost:8081/subjects` phải trả về danh sách (có thể rỗng).
5. Kiểm tra discovery/token/admin API Keycloak theo mục trên; đừng chạy UserSeeder khi Keycloak chưa sẵn sàng.
6. Từ root: `mvn -DskipTests install` để compile/package/install shared modules trước các service. Maven tự sắp xếp reactor theo dependencies, không phải thứ tự khai báo module.
7. Mỗi terminal từ root chạy `mvn -pl <service-name> spring-boot:run`. Thứ tự: Discovery → Inventory → Order → Payment → Cart → Notification → User → Product → Gateway. Đợi từng service startup xong trước bước tiếp theo. Inventory/Order/Cart sẵn sàng trước Product vì ProductSeeder phát inventory/cache events. Không xóa Redis seed marker hay Streams state trong lần khôi phục này; nghiệp vụ seed cộng tồn kho giữ nguyên.
8. Eureka: `http://localhost:8761`, Basic eureka/password; kiểm tra các service đã đăng ký. Product public: `http://localhost:8080/api/product`; login: POST `http://localhost:8080/auth/login` với admin/password hiện tại. Dùng token trả về để thử route cần auth.
9. Khi dùng Nginx, chạy gateway thứ hai: `mvn -pl api-gateway spring-boot:run -Dspring-boot.run.arguments=--server.port=8090`, rồi `docker compose up -d nginx`. Lúc này entrypoint là 8000. Monitoring tùy chọn: `docker compose up -d prometheus grafana zipkin phpmyadmin`.
10. Khi dependencies đang chạy, chạy `mvn test` từ root. Sáu test hiện có đều là `@SpringBootTest contextLoads`, không phải unit test độc lập: chúng cần dependencies local và có thể kích hoạt seeders/Streams trong môi trường demo.

## Validation của đợt audit

- **PASS**: Maven 3.9.9 + Temurin JDK 24.0.2, `mvn -DskipTests package`: cả 12 reactor modules compile/test-compile/package thành công với release 24. Đã kiểm tra cả 9 executable JAR đọc được và chứa đúng properties hiện tại sau khi dựng lại các artifact tạm. Không chạy Jib image build.
- **PASS**: `docker compose config --quiet` (Compose 2.39.4); parse tất cả POM/JSON/YAML; kiểm tra service/infrastructure ports không trùng, tên service/database/gateway route khớp, các required `@Value` property tồn tại, realm/client/grants/roles/secret/import filename khớp, `git diff --check` sạch.
- **PASS**: chạy JAR Discovery tại 8761 và Gateway tại 8080; Eureka từ chối request không auth (401), chấp nhận eureka/password (200); Gateway từ chối request Cart không token (401). Hai process đã dừng sau smoke test.
- **PASS**: kiểm tra trực tiếp compiled Gateway decoder với RSA token và HTTP JWK server local: token hợp lệ decode thành công qua issuer đã cấu hình; issuer sai, token hết hạn và chữ ký sai đều bị từ chối. Đây là kiểm tra với JWK fixture, chưa phải integration test với Keycloak thật.
- **PASS**: kiểm tra Spring context riêng của `PaymentKafkaConsumerConfig`: tái hiện lỗi missing group-id bằng properties gốc; properties sau sửa load context/factory thành công, group `payment-group`, broker `localhost:9092`, Schema Registry `http://localhost:8081`. Không cần kết nối broker cho kiểm tra binding này.
- Đã chạy `mvn -fae test` cho 6 test hiện có. Product/Order/User/Payment bị chặn do MySQL không chạy: `Communications link failure`, dẫn tới Hibernate không đọc được JDBC metadata. Không đổi dialect/datasource để che lỗi dependency của môi trường audit.
- Inventory/Notification ban đầu lỗi Mockito inline mock-maker do môi trường không cho JVM self-attach. Chạy lại riêng hai test này với Mockito premain Java agent (chỉ flag chạy test, không sửa repo): **2 tests PASS**, không failures/errors. Context load thành công không chứng minh Kafka traffic/Streams đã xử lý event khi không có broker.
- **Chưa xác minh**: Docker runtime, MySQL schema/credentials trên volume thực tế, Redis/Kafka/Schema Registry end-to-end, Keycloak fresh import và token/login/admin API thật, dữ liệu Payment cũ, toàn bộ checkout/WebSocket/VNPay/Cloudinary flow, PID chiếm 8087 trên Windows. Môi trường audit không có Docker daemon hoặc quyền truy cập máy Windows của người dùng. Cần chạy lại full `mvn test` với dependencies local sẵn sàng theo thứ tự ở trên.

Nguồn đối chiếu: [Keycloak 18 DirImportProvider](https://github.com/keycloak/keycloak/blob/18.0.0/services/src/main/java/org/keycloak/exportimport/dir/DirImportProvider.java), [Keycloak import/export](https://www.keycloak.org/server/importExport), [Spring Security reactive JWT](https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html), [Spring Cloud 2025.0 / Boot 3.5 compatibility](https://spring.io/blog/2025/05/29/spring-cloud-2025-0-0-is-abvailable/).
