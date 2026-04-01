# Ecommerce Backend

Backend microservices cho bài test tải theo kịch bản **oversell**. Trọng tâm của project là kiểm tra khả năng xử lý khi nhiều người cùng đặt một SKU trong cùng thời điểm, thay vì phát triển một hệ thống ecommerce đầy đủ tính năng.

## Mục tiêu chính

- Mô phỏng luồng đặt hàng trong môi trường nhiều request đồng thời
- Kiểm tra nguy cơ oversell khi nhiều người tranh mua cùng một SKU
- Đánh giá cách hệ thống phối hợp giữa gateway, service business, cache, database và event bus
- Theo dõi trạng thái đơn hàng trong flow bất đồng bộ

## Kiến trúc chính

Project được tách thành nhiều service:

- `api-gateway`: điểm vào chung cho frontend
- `discovery-server`: service discovery
- `user-service`: đăng ký, đăng nhập, thông tin người dùng
- `product-service`: quản lý sản phẩm
- `inventory-service`: quản lý tồn kho
- `cart-service`: giỏ hàng
- `order-service`: tạo đơn và điều phối luồng đặt hàng
- `payment-service`: xử lý payment mock
- `notification-service`: đẩy trạng thái đơn hàng realtime

## Hạ tầng local

Repo có sẵn `docker-compose.yml` để chạy các thành phần chính:

- MySQL
- Redis
- Kafka
- Schema Registry
- Keycloak
- Prometheus
- Grafana
- Zipkin
- Nginx

## Công nghệ sử dụng

- Java – ngôn ngữ chính của backend
- Spring Boot – xây dựng API và các microservice
- Spring Cloud Gateway – gateway cho hệ thống
- Eureka – service discovery giữa các service
- MySQL – cơ sở dữ liệu chính
- Redis – cache và tăng tốc truy xuất
- Kafka – xử lý giao tiếp bất đồng bộ
- Keycloak – xác thực và phân quyền
- Prometheus / Grafana / Zipkin – giám sát hệ thống và theo dõi request
- Nginx – reverse proxy và điều hướng truy cập

## Bài toán mà repo này tập trung

Khi nhiều request đặt hàng đến cùng lúc cho một SKU:

- hệ thống có chặn được bán vượt tồn kho hay không
- trạng thái đơn hàng có còn nhất quán hay không
- service nào trở thành nút thắt khi tải tăng cao
- backend phản ứng thế nào khi flow phải đi qua nhiều service và nhiều bước bất đồng bộ

## Yêu cầu môi trường

- JDK 24
- Maven 3.9+
- Docker và Docker Compose

> Lưu ý: một số giá trị trong `.env.example` chỉ mang tính minh họa. Khi chạy local nên ưu tiên giá trị đang được dùng thực tế trong `application.properties` và `docker-compose.yml`.

## Biến môi trường

Project sử dụng một số biến môi trường để cấu hình database, Redis, Kafka, Keycloak và các service nội bộ.

Tạo file `.env` từ file mẫu:

```bash
cp .env.example .env
```

### Các biến chính

- `MYSQL_HOST`, `MYSQL_PORT`, `MYSQL_USER`, `MYSQL_PASSWORD`, `MYSQL_DATABASE`: cấu hình MySQL
- `REDIS_HOST`, `REDIS_PORT`: cấu hình Redis
- `KAFKA_BOOTSTRAP_SERVERS`: địa chỉ Kafka broker
- `SCHEMA_REGISTRY_URL`: địa chỉ Schema Registry
- `EUREKA_SERVER_URL`: địa chỉ Eureka
- `KEYCLOAK_SERVER_URL`, `KEYCLOAK_REALM`, `KEYCLOAK_CLIENT_ID`: cấu hình xác thực Keycloak
- `ZIPKIN_BASE_URL`: địa chỉ Zipkin
- `NGINX_PORT`: cổng Nginx local

### Biến môi trường liên quan đến VNPAY

Nếu muốn chạy luồng thanh toán VNPAY ở môi trường local, cần cấu hình thêm:

- `VNPAY_TMN_CODE`
- `VNPAY_HASH_SECRET`
- `VNPAY_PAY_URL`
- `VNPAY_RETURN_URL`

Nếu không kiểm thử luồng thanh toán, có thể bỏ qua phần cấu hình này.

## Cách chạy local

## Dữ liệu và thành phần đã có sẵn trong repo

Repo local này đã chuẩn bị sẵn một phần dữ liệu/hạ tầng để demo:

- `docker-compose.yml`: dựng MySQL, Redis, Kafka, Schema Registry, Keycloak, Prometheus, Grafana, Zipkin
- `mysql-init/init.sql`: khởi tạo database business ban đầu
- `keycloak-data/realm-export.json`: import realm cho Keycloak
- `product-service`: có logic seed dữ liệu phục vụ demo inventory / benchmark

Điều này giúp người clone có thể tái hiện môi trường local dễ hơn, nhưng vẫn nên đọc kỹ phần cấu hình trước khi chạy.

### 1. Clone project

```bash
git clone https://github.com/truongnguyen3006/ecommerce-backend-1-.git
cd <project-folder>
```

### 2. Chạy hạ tầng

Tại thư mục gốc backend:

```bash
docker compose up -d
```

### 3. Chạy các service Spring Boot

Có thể chạy bằng IDE hoặc Maven. Thứ tự nên chạy:

1. `discovery-server`
2. `api-gateway`
3. `user-service`
4. `product-service`
5. `inventory-service`
6. `order-service`
7. `payment-service`
8. `cart-service`
9. `notification-service`

Ví dụ:

```bash
cd order-service
mvn spring-boot:run
```

## Kiểm thử tải với JMeter

Repo có 2 kịch bản [Jmeter Script](./Jmeter%20Script/) để kiểm thử luồng đặt hàng đồng thời:

- `oversell-single-sku.jmx`: nhiều request cùng đặt mua một SKU
- `multi-sku-concurrent-order.jmx`: nhiều request đồng thời đặt mua nhiều SKU khác nhau

Hai file CSV đi kèm:

- `data_oversell.csv`: chứa một `skuCode` dùng chung cho toàn bộ request
- `data_multi.csv`: chứa danh sách nhiều `skuCode` để phân tán tải trên nhiều biến thể sản phẩm

### Cách setup kịch bản

1. Mở file `.jmx` bằng JMeter.
2. Sửa lại địa chỉ host và port của các HTTP Request theo môi trường đang chạy.
3. Sửa `CSV Data Set Config` để trỏ tới file CSV trong máy của bạn.
4. Thay `Authorization: Bearer ...` bằng access token mới.
5. Kiểm tra lại dữ liệu test như user, SKU, tồn kho và trạng thái dịch vụ trước khi chạy.

### Lưu ý khi benchmark

Khi chạy JMeter, nên gửi request trực tiếp tới IP của môi trường chạy backend thay vì `localhost`, để hạn chế ảnh hưởng từ lớp proxy/NAT của Docker Desktop.

Ví dụ:
- dùng IP của WSL2 nếu backend chạy trong WSL2
- hoặc dùng IP LAN / DNS nội bộ của máy chạy backend
- tránh trộn `localhost` và IP khác nhau trong cùng một file test

### Tìm IP WSL2 để chạy JMeter

Nếu backend hoặc hạ tầng đang chạy trong WSL2, có thể lấy IP của WSL2 bằng lệnh:

```bash
ip -4 addr show eth0
```

### Chuẩn bị access token cho JMeter

#### Lấy token từ Postman

1. Đăng nhập bằng tài khoản test qua API hoặc collection Postman của project.
2. Sau khi đăng nhập thành công, copy `access_token` từ response.
3. Mở file `.jmx` và thay giá trị trong header:

```text
Authorization: Bearer <access_token>
```

## Kết quả kiểm thử tải

### Kịch bản 1: Oversell trên một SKU

#### JMeter Test Plan
<img src="screenshots/testplan_oversell.png" alt="Admin" width="1507">

#### JMeter Summary Report
<img src="screenshots/Oversell_1500.png" alt="Admin" width="1504">

#### Grafana dashboard Oversell success result
<img src="screenshots/result_oversell_1500_success.png" alt="Admin" width="746">

#### Grafana dashboard Oversell fail result
<img src="screenshots/result_oversell_1500_fail.png" alt="Admin" width="773">

#### Kết quả tồn kho sau test
<img src="screenshots/UI_oversell_1500.png" alt="Admin" width="1876">

### Kịch bản 2: Tải đồng thời trên nhiều SKU

#### JMeter Test Plan
<img src="screenshots/test_plan_multi.png" alt="Admin" width="1515">

#### JMeter Summary Report
<img src="screenshots/multi_1000.png" alt="Admin" width="1513">

#### Grafana dashboard Oversell
<img src="screenshots/grafana_multi.png" alt="Admin" width="791">

## Cổng mặc định

- Gateway: `8080`
- Eureka: `8761`
- Inventory: `8082`
- Product: `8083`
- Cart: `8084`
- Keycloak: `8085`
- Order: `8086`
- Notification: `8087`
- User: `8088`
- Payment: `8089`
- Nginx: `8000`

## Hạn chế hiện tại

- Mục tiêu chính là kiểm thử tải và oversell, không phải hoàn thiện toàn bộ nghiệp vụ ecommerce
- Payment hiện là luồng mô phỏng/tích hợp VNPay nếu không test benchmark
- Dự án phù hợp cho chạy local và benchmark hơn là triển khai production ngay

## Tác giả

- **Tên:** Nguyễn Lâm Trường
- **Email:** lamtruongnguyen2004@gmail.com
- **GitHub:** [https://github.com/truongnguyen3006](https://github.com/truongnguyen3006)
