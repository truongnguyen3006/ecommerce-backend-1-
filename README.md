# Project 1 — Ecommerce Microservices Backend

Backend cho hệ thống ecommerce theo kiến trúc microservices, xây dựng bằng **Java 24 + Spring Boot 3.5.7**.

Mục tiêu chính:

- checkout đồng thời và chống overselling;
- đảm bảo consistency giữa Order, Inventory và Payment;
- xử lý bất đồng bộ bằng Kafka/Kafka Streams;
- xác thực/phân quyền bằng Keycloak;
- retry, idempotency, recovery và transactional outbox;
- tích hợp VNPay Sandbox, Cloudinary và observability.

> Source/configuration đã được harden theo hướng production và kiểm thử full-stack. Project chưa được deploy public production.

- [Frontend repository](https://github.com/truongnguyen3006/ecommerce-frontend-1-)

---

## Kiến trúc & công nghệ

Các service chính:

| Service | Chức năng | Port |
|---|---|---:|
| API Gateway | Routing / security | 8080 |
| Discovery Server | Eureka | 8761 |
| Inventory Service | Stock / Kafka Streams | 8082 |
| Product Service | Product / SKU / Cloudinary | 8083 |
| Cart Service | Redis cart | 8084 |
| Order Service | Order / saga / outbox | 8086 |
| Notification Service | Notification / STOMP | 8087 |
| User Service | User / Keycloak | 8088 |
| Payment Service | VNPay / payment state | 8089 |

**Stack:** Spring Cloud Gateway, Eureka, MySQL, Flyway, Redis, Kafka, Kafka Streams, Keycloak, WebSocket/STOMP, VNPay Sandbox, Cloudinary, Prometheus, Grafana, Zipkin, Docker Compose, Maven và JMeter.

---

## Điểm kỹ thuật chính

- Transactional Outbox cho Order, Product và Payment.
- Idempotency cho checkout, payment callback và điều chỉnh tồn kho.
- Permanent SKU identity để tránh tái sử dụng SKU lịch sử.
- Revision check khi cập nhật Product.
- Atomic cart cleanup theo SKU + quantity + revision.
- Payment fence và reconciliation để tránh trạng thái payment/order/inventory không nhất quán.
- Production profile, health checks, backup/restore và CI.

---

## Kết quả kiểm thử

| Hạng mục | Kết quả |
|---|---|
| Backend | **150 tests / 12 modules / 0 failures** |
| Keycloak lifecycle | **5 tests PASS** |
| Frontend liên kết | **79 tests PASS** |
| Production images | **12 images build PASS** |
| Disposable stack | **20 services healthy** |
| Restart / replay / backup / restore | **13/13 PASS** |
| VNPay Sandbox | **End-to-end VERIFIED** |
| Public production deployment | Chưa thực hiện |

---

## Chạy local

Yêu cầu: **JDK 24**, Maven 3.9+, Docker Desktop / Docker Compose.

```bash
git clone --branch production-ready-final https://github.com/truongnguyen3006/ecommerce-backend-1-.git
cd ecommerce-backend-1-

mvn clean install -DskipTests
docker compose up -d mysql-business redis keycloak-mysql keycloak kafka schema-registry
```

Thứ tự chạy service gợi ý:

```text
discovery-server
→ inventory-service
→ order-service
→ payment-service
→ cart-service
→ notification-service
→ user-service
→ product-service
→ api-gateway
```

Chạy test:

```bash
mvn clean verify
```

> Trên Windows, `RedisCartAtomicTests` cần WSL/Linux hoặc cấu hình `TEST_REDIS_EXECUTABLE` vì test mặc định tìm `/usr/bin/redis-server`.

---

## VNPay Sandbox

Flow đã test local thành công:

```text
Checkout
→ VNPay Sandbox
→ NCB test card
→ OTP
→ IPN / Return
→ Payment SUCCESS
→ Order COMPLETED
```

Không commit `VNPAY_SECRET_KEY` hoặc credential thật vào repository.

---

## Load Test

Các benchmark dưới đây là **kết quả local lịch sử**, dùng để kiểm tra concurrent checkout và overselling; không đại diện cho production capacity.

### Single-SKU Oversell

- SKU: `NIK1-GREEN-39`
- Tồn kho ban đầu: **100**
- Tải: **1.500 virtual users**
- Đơn hoàn tất: **100**
- Tồn kho cuối: **0**
- Không ghi nhận tồn kho âm
- Tổng throughput: **~343,8 req/s**

<details>
<summary>Xem ảnh JMeter / Grafana của Single-SKU</summary>

<img src="screenshots/testplan_oversell.png" alt="JMeter oversell test plan">

<img src="screenshots/Oversell_1500.png" alt="JMeter oversell summary">

<img src="screenshots/result_oversell_1500_success.png" alt="Grafana completed orders">

<img src="screenshots/result_oversell_1500_fail.png" alt="Grafana failed orders">

<img src="screenshots/UI_oversell_1500.png" alt="Inventory after oversell benchmark">

> Grafana hiển thị `completed = 1100` vì metric được cộng dồn từ benchmark single-SKU và multi-SKU.

</details>

### Multi-SKU Concurrent Checkout

- **1.000 checkout samples**
- **0% JMeter error**
- Response time trung bình: **~1.964 ms**
- Throughput: **~318,5 req/s**

<details>
<summary>Xem ảnh JMeter / Grafana của Multi-SKU</summary>

<img src="screenshots/test_plan_multi.png" alt="JMeter multi-SKU test plan">

<img src="screenshots/multi_1000.png" alt="JMeter multi-SKU summary">

<img src="screenshots/grafana_multi.png" alt="Grafana multi-SKU benchmark">

</details>

Test plan nằm trong [Jmeter Script](./Jmeter%20Script/).

---

## Tài liệu

- [DEPLOYMENT.md](DEPLOYMENT.md)
- [PRODUCTION_CHECKLIST.md](PRODUCTION_CHECKLIST.md)
- [BACKUP_RESTORE.md](BACKUP_RESTORE.md)
- [Production Recovery](docs/production-recovery.md)

---

## Tác giả

**Nguyễn Lâm Trường** — [GitHub](https://github.com/truongnguyen3006)
