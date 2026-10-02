# Batch 1 — backend finalization

Repository: `truongnguyen3006/ecommerce-backend-1-`. Branch duy nhất nhận thay đổi: `project1-recovery`. Ngày kiểm chứng: 2026-10-02.

Đã đọc toàn bộ prompt trước khi thao tác repository và audit source/config/test/schema của mọi module trước khi sửa. Kết quả cuối: **12 module build thành công; 61 test qua, 0 failure, 0 error, 0 skipped**, bằng Maven 3.9.11 và JDK 24.0.2. Kiến trúc, Keycloak, Kafka và tên trạng thái hiện có được giữ lại. Không thay đổi frontend, không merge hoặc cập nhật `main`.

## 1. Audit summary

Những phần đã đúng và được giữ:

- Chín service, hai shared module, Gateway/Eureka; không thêm hoặc tách service/framework.
- Port và service identity đã được phục hồi trong commit trước Batch 1, gồm Payment `8089` và database riêng. Không sửa lại lỗi Keycloak token hoặc Docker MySQL host port đã được khắc phục.
- Realm/client demo, OAuth2 password/refresh/client-credentials, role `admin/user`; không thay nhà cung cấp xác thực.
- Order đã kiểm tra chủ sở hữu khi đọc, liệt kê đơn riêng và hủy; profile lấy identity từ JWT, address query lọc theo owner. Bổ sung test và bảo vệ payment context còn hở.
- Product có variants/gallery/upload Cloudinary, partial update giữ variants khi trường này vắng mặt. Giữ endpoint danh sách cũ và tính năng upload.
- Kafka Streams vốn xử lý stock theo SKU với `exactly_once_v2`; giữ store `inventory-store`, application ID `inventory-streams-v11` và các business topic.
- Order đã lấy giá từ backend, nhưng dựa vào Redis cache có thể thiếu/cũ; chuyển nguồn quyết định sang catalog hiện tại.
- Compose, mysql-init, realm export, Nginx, Prometheus, Grafana và các volume MySQL đã được đối chiếu. Không xóa seed/data hoặc thay networking.

Những lỗi/khoảng thiếu thực tế:

- Shared DTO dùng validation `javax`; nhiều controller thiếu validation; handler trả runtime exception thành 400 và có thể lộ chi tiết nội bộ.
- Product mutation/warm-cache thiếu ranh giới admin; Gateway cache token đã decode 10 phút có thể vượt expiry; Notification cho subscribe đơn bất kỳ.
- Cart tin user ID trên URL; thiếu update/clear; increment không giới hạn stock; checkout publish chưa được xác nhận rồi có thể xóa cart quá sớm.
- Inventory init cộng lại stock sau seed/replay, quantity không hợp lệ và event trùng có thể làm sai stock.
- Order đếm event thay vì kết quả riêng từng SKU; lỗi nhiều SKU có thể để mất phần stock đã trừ; hủy đơn PAYMENT_FAILED có thể hoàn stock lần hai.
- Kafka listener nuốt exception, notification failed-event đọc sai JSON Schema payload; một số HTTP call không có timeout.
- VNPay signature sai có thể sửa transaction và phát payment-failed; chưa đối chiếu signed amount/merchant đầy đủ.
- Schema phụ thuộc Hibernate update, SQL init Order thiếu cột hiện tại; chỉ có các test `contextLoads()` phụ thuộc hạ tầng.

## 2. Files changed by group

[Danh sách đầy đủ, gồm file mới/sửa/xóa](batch1-changed-files.md).

| Nhóm | File chính và phạm vi |
|---|---|
| Config/validation | Root/module `pom.xml`, 9 `application.properties`, `.env.example`, `docker-compose.yml`; DTO Cart/Order/Product/User/Payment; shared `ErrorResponse`, `GlobalExceptionHandler` |
| Business | Product service/controller/specification/repository/DTO page/seeder; Inventory topology/controller/service; Cart service/controller/stock client; Order service/controller/repository; Payment service/controller/repository; `CartCheckoutEvent`; User service |
| Security/database | Gateway và 7 servlet `SecurityConfig`; shared role/error helpers; Notification `WebSocketConfig`; 5 bộ V1 SQL + V2 Java migration và profile `migrations` |
| Reliability/tests/docs | HTTP client configs, Kafka consumer configs, Inventory health/Streams config, typed Notification listeners; 22 test classes thay 6 context-only test; hai JMeter plan bỏ token nhúng; README và tài liệu audit/runbook |

Discovery tiếp tục dùng HTTP Basic riêng; cấu hình credentials lấy env như trước. Shared libraries không chạy server.

## 3. Behavior changes

### Product

- `GET /api/product` vẫn trả danh sách như cũ; `GET /api/product/{id}` vẫn giữ contract.
- API thêm: `GET /api/product/search?keyword=&category=&minPrice=&maxPrice=&color=&size=&page=0&pageSize=20&sort=id,asc`.
- Response search: `{content,page,size,totalElements,totalPages}`. Page bắt đầu từ 0, pageSize 1–100. Sort cho phép `id`, `name`, `basePrice`/`price`, `createdAt`, chiều `asc/desc`; thêm `id` làm tie-breaker.
- Lọc/pagination thực hiện ở database bằng Specification; color/size/price phải khớp cùng active variant. LIKE escape ký tự `%/_`. Hibernate batch fetching giảm N+1 khi map variants/images.
- SKU browsing: `GET /api/product/sku/{sku}` trả catalog hiện tại cho Cart/Order. SKU không có hoặc inactive bị từ chối.
- Validation giá/quantity/SKU trùng; partial update giữ variants nếu không truyền danh sách. Warm-cache chỉ refresh catalog, không tăng inventory. Restart seeder chỉ khởi tạo SKU còn thiếu, chờ Kafka acknowledgment; có env tắt seed.

### Inventory

- INIT không tăng stock của SKU đã có. Điều chỉnh vượt miền `[0,Integer.MAX_VALUE]` bị từ chối; không âm thầm clamp về 0.
- Kiểm tra quantity dương, stock tồn tại và đủ; serialize xử lý cùng SKU qua repartition 10 partition và cùng persistent stock store.
- Lưu kết quả theo `orderNumber + SKU`: replay trả kết quả trước đó, không trừ thêm. Restock từ cancellation/payment failure/inventory failure được deduplicate bằng reason ổn định.
- Admin adjustment HTTP trả **202 queued** sau Kafka acknowledgment. Stream kiểm tra lại stock khi xử lý; 202 chưa phải xác nhận stock đã đổi. Unknown SKU trả 404, store chưa sẵn sàng trả 503.

### Cart

- API dùng JWT subject: `GET/DELETE /api/cart/me`, `POST /api/cart/items`, `PUT/DELETE /api/cart/items/{sku}`, `POST /api/cart/checkout`. PUT body gồm `skuCode` khớp path và `quantity` dương.
- Giữ legacy `/add/{userId}`, `/remove/{userId}/{sku}`, `/view/{userId}`, `/checkout/{userId}`; URL user ID phải khớp JWT. Thêm legacy update/clear.
- Catalog/stock được kiểm tra trước khi thêm/cập nhật. Redis Lua kiểm tra cumulative quantity và thay đổi cart nguyên tử; update/remove/clear bị chặn khi checkout đang chạy.
- Product bị xóa vẫn hiển thị snapshot để owner có thể remove; checkout revalidate và từ chối SKU đó.
- Checkout khóa cart 15 phút, lưu snapshot/order number và chờ publish acknowledgment. Retry dùng lại ID; cạnh tranh khi snapshot chưa sẵn sàng trả 409.
- Cart chỉ được xóa khi nhận VALIDATED/COMPLETED cho đúng checkout ID. FAILED/CANCELLED/PAYMENT_FAILED mở khóa, không xóa cart vì vừa queue event.
- Legacy checkout vẫn trả text 202, thêm `X-Order-Number`; API mới trả JSON `orderNumber`.

### Order/payment/user

- POST Order vẫn trả 202 + `orderNumber`; price/total do catalog backend quyết định, không nhận frontend total.
- `Idempotency-Key` tùy chọn tối đa 128 ký tự, lưu 24 giờ theo user; cùng key/body trả cùng order number, khác body trả 409. Không có header: identical request được deduplicate trong 10 giây.
- Listener dùng transaction thực qua `TransactionTemplate`, row lock trên Order, mỗi SKU có một kết quả. Chờ đủ kết quả; nếu có failure chỉ hoàn các SKU đã trừ, rồi FAILED.
- Giữ trạng thái `PENDING`, `VALIDATED`, `COMPLETED`, `FAILED`, `PAYMENT_FAILED`, `CANCELLED`. Không thêm shipping lifecycle giả định.
- COD vẫn dùng payment simulation hiện có và thường chuyển VALIDATED → COMPLETED nhanh. VNPay chờ callback ký hợp lệ. Hủy chỉ cho VALIDATED/PAYMENT_FAILED; COMPLETED/CANCELLED bị 409. Hủy PAYMENT_FAILED không hoàn kho lần hai.
- Payment create/read chuyển bearer JWT tới Order internal context và kiểm tra owner. Callback xác minh HMAC trước khi sửa DB, đối chiếu merchant/amount, khóa transaction và xử lý callback trùng. SUCCESS không bị đảo thành FAILED; late success sau FAILED yêu cầu đối soát thủ công.
- User/address ownership được giữ; validation và lỗi 404/409 đúng nghĩa hơn. Đồng bộ phone/address lên Keycloak không ghi đè lẫn nhau; các thay đổi JPA nằm trong transaction.

## 4. Security changes

| Ranh giới | Hành vi |
|---|---|
| Public | Browse Product/Inventory; register/login/refresh; VNPay callback có kiểm tra chữ ký; health/info và Prometheus trên business services |
| USER/ADMIN | Cart của mình, checkout, profile/address của mình, own Order/Payment; identity lấy từ JWT |
| ADMIN | Product mutation/upload/warm-cache; inventory adjustment; danh sách/admin Order/User và sensitive actuator |
| Internal Order context | Không đi qua public Gateway; gọi direct service với JWT, có ownership/admin check |
| Eureka | Gateway từ chối `/eureka/**`; quản trị trực tiếp `8761` bằng HTTP Basic hiện có |
| WebSocket | Handshake vẫn hỗ trợ SockJS; STOMP CONNECT cần native header `Authorization: Bearer ...`; SUBSCRIBE kiểm tra JWT và owner qua Order; chặn wildcard topic/client SEND |

Gateway bỏ cache successful-token validation; Nimbus cache JWK, nhưng validate issuer/expiry mỗi request. Converter đọc realm roles an toàn và chuyển `admin/user` thành Spring authorities. Backend có thể tự bảo vệ nếu được gọi trực tiếp.

CORS Gateway/WebSocket dùng explicit origins qua `CORS_ALLOWED_ORIGINS`; từ chối `*` với credentials. Servlet services dành cho call qua Gateway; không mở CORS wildcard ở từng service.

Servlet error/filter response gồm `timestamp,status,code,message,path`, giữ alias `errorCode` cho tương thích. Validation 400, missing 404, conflict 409, auth 401/403, invalid method/media 405/415; unexpected 500 và service failure được sanitize. Gateway vẫn sử dụng error handling chuẩn của WebFlux/Spring Security, không tuyên bố mọi edge error có cùng JSON shape.

Frontend source không được sửa. Client WebSocket cần gửi Authorization ở STOMP CONNECT; subscribe trước khi Order được persist có thể phải retry sau 404. Đây là thay đổi contract cần kiểm tra local.

## 5. Database changes and adoption

Flyway được thêm vào Product, Order, User, Cart, Payment:

- **V1 SQL:** `CREATE TABLE IF NOT EXISTS` cho toàn bộ current entity tables, PK/unique/FK cùng service, không seed lại, không DROP/DELETE.
- **V2 Java:** kiểm tra metadata, bổ sung bảy cột Order còn thiếu trong SQL init cũ; index cho product `(category,base_price)`, orders `(user_id,order_date)`, user email, address owner/default/date, cart item owner.
- CHECK: Product base price không null/âm; nullable variant price giữ fallback về base nhưng không âm và phải có parent; Order line quantity >0, price ≥0 và parent/SKU có giá trị; Cart quantity >0; Payment amount >0. V2 kiểm tra dữ liệu cũ trước và **fail nếu dữ liệu vi phạm**, không tự xóa/sửa row.
- Giữ unique SKU/order number/Keycloak ID/payment order/txn reference. Không tạo FK giữa database của hai service; không thêm optimistic lock khi Order/Payment đã dùng pessimistic row lock.

**Default local:** vẫn `ddl-auto=${JPA_DDL_AUTO:update}`, Flyway disabled. Đây là lựa chọn an toàn vì Work không có MySQL/Docker để kiểm thử existing volume. **Profile `migrations`:** Flyway enabled, `ddl-auto=validate`, baseline version **0**, baseline-on-migrate mặc định **false**.

Adoption từng database, trên bản backup/copy trước:

1. Dừng service ghi dữ liệu; backup database hiện có và đối chiếu entity/tables. Không chạy lại mysql-init vào volume đang có; không dùng `docker compose down -v` để sửa.
2. Chạy service trên copy với `SPRING_PROFILES_ACTIVE=migrations`. Nếu schema đã có tables nhưng chưa có history (kể cả schema mới được mysql-init seed), đặt **`FLYWAY_BASELINE_ON_MIGRATE=true` chỉ cho lần adoption đầu**. Baseline 0 cho phép V1/V2 chạy; không baseline thành 1/2 để bỏ qua sửa schema.
3. Xem migration/check failure và sửa dữ liệu được xác định sau backup. MySQL DDL có thể commit từng câu lệnh; không giả định toàn bộ V2 rollback. Không chạy `repair` hoặc sửa history để che lỗi trước khi đối chiếu thực tế.
4. Kiểm tra `flyway_schema_history`, Hibernate validate, record counts và smoke test; sau adoption tắt baseline-on-migrate, tiếp tục profile migrations.
5. Chỉ áp dụng volume thật sau khi copy MySQL và luồng local đã qua. Dữ liệu Payment từng nằm nhầm Product DB không tự được chuyển; giữ khuyến cáo kiểm tra từ audit khôi phục trước.

Fresh migration + rerun + Hibernate entity validation đã qua trên H2 MySQL mode cho cả năm service. Đây chưa phải kiểm chứng DDL/constraints trên MySQL thật hoặc mọi schema legacy bị chỉnh thủ công.

## 6. Reliability changes

- RestTemplate ở Cart/Order/Payment/Notification: connect 2s, read 5s (property override); Keycloak WebClient connect 2s/read 5s, tổng block tối đa 10s. Không thêm retry HTTP cho mutation.
- Gateway connect timeout 2s/response 15s; Redis connect/read 2s; giảm Hikari pool mặc định ở các service đã cấu hình pool lớn về 20. Không đưa connection budget 200 mỗi service vào local MySQL.
- Checkout, inventory administration, Order/Payment publication và startup Product seed chờ Kafka acknowledgment tối đa 10s. Producer request/delivery/max-block lần lượt 5s/15s/5s; timeout không được trả thành thao tác đã hoàn tất.
- Kafka listener không nuốt lỗi; lỗi batch chỉ định đúng failing record. DefaultErrorHandler retry tối đa hai lần, backoff 1s, một số invalid payload không retry; exhaustion gửi `<original-topic>.DLT`, publish DLT failure không coi là recover thành công.
- Consumer manual offsets và `read_committed`; giữ intentional listener group IDs. Notification dùng typed JSON Schema deserializer thay raw payload stripping.
- Inventory readiness chỉ UP khi Streams RUNNING. Actuator probes bật; health details hạn chế. MySQL health dùng authenticated SELECT, Redis health dùng PING. Không đổi networking hoặc volume hiện có.

Giới hạn có chủ ý: Kafka Streams exactly-once không làm MySQL/Redis/Kafka thành một transaction chung. Crash sau event publish nhưng trước DB commit vẫn cần đối soát/replay; không thêm outbox/saga framework. Dedup stores cần chính sách retention về sau. Listener-level DLT là cấu hình đã build/test context, chưa chạy broker thật; deserialization failure trước listener còn cần xác nhận/thiết lập recovery riêng. DLT topics cần pre-create hoặc broker cho auto-create, producer có quyền ghi.

Compose Redis/Kafka vẫn giữ cách lưu dữ liệu hiện có; chưa thêm persistence/backup cho hai dependency này. Nếu Redis mất dữ liệu thì cart/dedup/workflow state mất; không có fallback giả thành thành công. Chưa hỗ trợ multi-instance Inventory interactive-query routing; runbook dùng một Inventory instance.

## 7. Tests

| Module | Test count | Nội dung |
|---|---:|---|
| common-dto | 2 | Jakarta quantity validation; realm roles malformed/case handling |
| Product | 12 | SQL pagination/filter/same-variant/distinct; missing/invalid/partial update; public/admin boundary; migrations + Hibernate validate |
| Inventory | 6 | Real TopologyTestDriver/RocksDB + mock Schema Registry: valid/invalid/insufficient stock, oversell pressure, init/check/compensation replay |
| Cart | 11 | Quantity/stock guard, pending-checkout race, removed SKU; owner/auth validation/update/remove; consumer context; migration |
| Order | 11 | Server prices, placement/key replay, invalid quantity/state, SKU-result dedup and selective restock, owner/internal context, consumer context, migration |
| Payment | 7 | Invalid signature/amount, duplicate/terminal callback transitions, consumer context, migration |
| Gateway | 4 | WebFlux public/user/admin/internal rules; signed RSA JWT/JWK HTTP fixture proves previously accepted token is revalidated after expiry |
| User | 3 | JWT-subject profile query; deny address update/delete/default for another owner; migration |
| Notification | 5 | STOMP authentication/ownership/wildcard/SEND denial; typed consumer configuration context |
| **Total** | **61** | **0 failures, 0 errors, 0 skipped** |

22 substantive test classes replace six infrastructure-only `contextLoads()` classes. Security filters/converters remain enabled in MVC/WebFlux tests; mocks supply identities/dependencies, not a permit-all config. Migration tests execute SQL/Java and validate entities, Product tests query real H2, Inventory tests execute the topology. Cart Lua and cross-service integration still require Redis/Kafka local verification.

## 8. Build result and exact commands

Toolchain supplied in scratch only; no downloaded binaries/settings/target/logs are committed. Initial environment had Java 17 JRE and no Maven/JDK compiler/Docker. Maven 3.9.11 + JDK 24.0.2 were downloaded; temporary proxy settings were recreated for each Maven invocation.

Executed Maven commands from repository root:

```text
mvn -B -DskipTests package
mvn -s /workspace/scratch/3db45bce219c/toolchain/maven-settings.xml -B -DskipTests package
mvn -s /workspace/scratch/3db45bce219c/toolchain/maven-current-settings.xml -B -DskipTests package
mvn -s /workspace/scratch/3db45bce219c/toolchain/maven-current-settings.xml -B clean verify
mvn -s /workspace/scratch/3db45bce219c/toolchain/maven-current-settings.xml -B verify
mvn -s /workspace/scratch/3db45bce219c/toolchain/maven-current-settings.xml -B clean verify
```

The final clean-verify command was repeated after actual code/test corrections; the final run completed in **44.080 seconds**, all 12 modules SUCCESS. Earlier attempts failed on network/proxy, missing spring-tx dependency, ambiguous test import, test naming-strategy setup, and a protected test API; corrected before final delivery. The non-clean verify attempt failed while repackaging an already repackaged Product jar; use clean lifecycle for a reproducible full build. Cart's redundant repackage execution was removed.

Additional checks: `git diff --check`; POM/JMeter XML parse; Compose YAML parse; static placeholder resolution against module properties/defaults for **435 config entries/@Value expressions**, no unresolved required source; nine unique historical default service ports. Consumer Spring contexts bind the checked Kafka properties. Full applications were not started against live dependencies.

Portable local verification command: **`mvn clean verify` on JDK 24**, followed by `mvn -DskipTests install` if using `mvn -pl ... spring-boot:run` independently.

## 9. Unverified items

- Docker Compose execution/healthchecks and actual MySQL 8 migrations on fresh/legacy volumes; no Docker daemon/CLI or running business dependencies in Work.
- Redis Lua execution/concurrent cart edits and persistence-loss recovery; tests verify boundaries/commands with mocks.
- Real Kafka/Schema Registry schema compatibility, offset/state restoration for the modified topology, DLT recovery, broker interruption and duplicate delivery across running services. Keep existing Streams state; test copied Kafka data before deployment, do not reset application ID/changelogs blindly.
- Live Keycloak login/refresh/role/seed and existing realm credentials. Existing realm import does not replace client settings; use its current secret via env.
- End-to-end checkout/COD/VNPay/Notification, frontend STOMP header compatibility, Cloudinary upload and actual VNPay merchant/IPN credentials/public callback.
- JMeter performance after changes, MySQL/HTTP/Redis/Kafka concurrency and failure injection across processes. Historical README screenshots are not new validation evidence.
- Public deployment hardening, TLS, secret rotation, Redis/Kafka persistence and operational reconciliation remain future work; this batch does not claim production deployment readiness.

## 10. Local verification checklist

### Startup order

1. Checkout `project1-recovery`; JDK 24/Maven 3.9+, Docker Desktop Linux containers. Verify `java -version` and `mvn -version` agree.
2. `cp .env.example .env`; set real/local values without committing `.env`. Compose loads `.env`; Spring JVMs need the same OS/IDE variables. In Bash for this env file: `set -a; source .env; set +a`. In Windows, set IDE Run Configuration environment. Existing Keycloak client secret must match current Credentials, not a forced demo reset.
3. Run `docker compose config`, then:

   ```bash
   docker compose up -d mysql-business redis keycloak-mysql keycloak kafka schema-registry
   docker compose ps
   docker compose logs --tail=100 mysql-business keycloak kafka schema-registry
   docker compose exec redis redis-cli ping
   curl -fsS http://localhost:8081/subjects
   curl -fsS http://localhost:8085/realms/spring-boot-microservices-realm/.well-known/openid-configuration
   ```

   Wait for healthy MySQL/Redis/Kafka and usable Keycloak/Schema Registry HTTP responses. Use authenticated MySQL SELECT 1 with the configured password; do not reset volumes for a failed readiness check.
4. Run `mvn clean verify`, then `mvn -DskipTests install` from root. Start each service in its own terminal with `mvn -pl SERVICE spring-boot:run`, in this exact order, waiting for startup/readiness:

   **discovery-server → inventory-service → order-service → payment-service → cart-service → notification-service → user-service → product-service → api-gateway**.

   Inventory/Order/Cart listeners must be ready before Product seed; User requires Keycloak ready. Verify Eureka directly at `http://localhost:8761` with configured Basic credentials and Inventory `http://localhost:8082/actuator/health/readiness` is UP. Product seed must complete without Kafka errors.
5. Use Gateway `http://localhost:8080` for one instance. Optional benchmark entrypoint: start a second Gateway with `--server.port=8090`, then `docker compose up -d nginx prometheus grafana zipkin`; Nginx on `8000` expects both Gateway upstreams. Do not mistake dependency Compose for containerized Spring services.

Default ports: Gateway 8080, Discovery 8761, Inventory 8082, Product 8083, Cart 8084, Keycloak 8085, Order 8086, Notification 8087, User 8088, Payment 8089. Changing a port also requires the matching caller base URL/Eureka URL and monitoring/upstream config.

### Smoke tests (Gateway 8080)

- **Login:** `POST /auth/login` JSON `{"username":"admin","password":"admin123456@"}` for a freshly seeded application admin. Existing user's password is unchanged. This differs from Keycloak console admin/admin. Register two normal users A/B and obtain their tokens. Check invalid credentials/expired token return 401 and refresh works.
- **Product:** anonymous `GET /api/product`, `/api/product/search?page=0&pageSize=2&sort=price,asc`, keyword/category/color/size/min/max combinations; response pagination metadata, unknown product 404 and malformed price/page 400. Select an active SKU and read `/api/inventory/{sku}`.
- **Cart A:** `POST /api/cart/items` with `{"skuCode":"SKU","quantity":1}`; GET `/api/cart/me`; PUT `/api/cart/items/SKU` with matching SKU/positive quantity; DELETE item; DELETE `/api/cart/me`. Quantity 0/negative →400; cumulative stock overflow →409. With A's JWT, legacy `/api/cart/view/B` and `/add/B` →403.
- **Checkout:** populate A cart and `POST /api/cart/checkout`; 202 must contain order number. Poll `GET /api/order/{orderNumber}` (early 404 is possible during asynchronous persistence). Also POST `/api/order` with `{"items":[{"skuCode":"SKU","quantity":1}],"paymentMethod":"COD"}` and stable `Idempotency-Key`. Repeat same request →same ID; change body with same key →409. Use one checkout API per intended order.
- **Inventory failure:** create a multi-SKU direct Order with one insufficient SKU; wait for FAILED, verify successfully deducted SKUs are restored once. Retry/replay should not reduce/inflate stock. Do not compare only the initial 202 response.
- **Orders/ownership:** A reads `/api/order/me` and its own order; B cannot read/cancel A order (403). COMPLETED cancel →409. VNPay VALIDATED order may be cancelled; payment-failed cancellation must not restock twice. Test address ID from A with B JWT returns 404.
- **Admin:** normal user Product mutation/upload/warm-cache, inventory adjust, admin order/user list →403; no JWT →401. Admin succeeds with valid input. Internal context and Eureka proxy are denied through Gateway; direct owner-authenticated internal Order context works for Payment.
- **Payment/WebSocket:** with sandbox credentials/public callback configured, test signed success/failure/duplicate and tampered callbacks; tampering must not mutate payment/order. STOMP CONNECT includes Authorization; subscribe owner order succeeds, another owner's/wildcard subscription fails. Refresh connection/token if expired.
- **Ops:** inspect listener errors/DLT and readiness. JMeter plans require `-Jaccess_token=...` supplied at runtime; obtain current token privately and never paste it into tracked `.jmx`.

## 11. Commits and Git safety

Four logical commits are created on the existing `project1-recovery` lineage; their hashes are supplied in the delivery report and visible in `git log -4` / GitHub branch history. No force push, merge, main update or frontend repository operation.

| Commit | SHA |
|---|---|
| Normalize backend configuration and validation | `f40d6e3818715987db0d6de59e951a784ec5798b` |
| Complete ecommerce backend business rules | `d1d7a99b9faceea3a49028f9a29908f43dbc9e2b` |
| Harden backend security and database integrity | `86ad3b4f1d11d5555cffa10e6e3b305bd6784c0d` |
| Improve backend reliability and tests | Commit chứa bản báo cáo này; xem HEAD trên `project1-recovery` và SHA trong delivery report |

Branch before Batch 1: `70c3ef5333f70aa7f27d4774ba029b870ac39fa5`. `main` before/after verification: `23bafa4da55ffe5cbb0ed303cd5840c22cbfe2ea`.

Before publication the diff was reviewed for `.env`, credentials/tokens, `target/`, runtime state, downloaded/generated binaries and unrelated files. Expired bearer tokens previously embedded in JMeter were replaced by runtime properties. Only demo defaults remain in env example/realm; real credentials were not added.
