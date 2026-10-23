# Cấu trúc backend

Project dùng cấu trúc source chuẩn của Maven và tổ chức theo layer của Spring
Boot. Lớp khởi động nằm ở package gốc để component scan, entity scan và JPA
repository scan bao phủ toàn ứng dụng.

```text
src/main/java/com/example/springbootbackend/
├── RacehorseTrainingBackendApplication.java
├── config/               # cấu hình Spring, OpenAPI, khởi tạo role mặc định
├── controller/           # REST controller (*Controller)
├── dto/
│   ├── request/          # payload đầu vào (*Request)
│   └── response/         # payload trả về (*Response)
├── entity/               # JPA @Entity và enum trạng thái
├── exception/            # exception nghiệp vụ và @RestControllerAdvice
├── repository/           # Spring Data JPA repository (*Repository)
├── security/             # JWT, filter xác thực, SecurityConfig
└── service/              # nghiệp vụ, transaction, lưu trữ ảnh

src/main/resources/
├── application.properties
└── db/
    ├── migration/        # Flyway migration dùng chung (PostgreSQL + H2)
    └── postgresql/       # Flyway migration riêng PostgreSQL (db/{vendor})

src/test/java/com/example/springbootbackend/
├── DatabaseMigrationTests.java, PostgresTestDatabase.java, ...ApplicationTests.java
├── config/               # OpenApiConfigTests
├── controller/           # test tích hợp MockMvc cho từng controller
└── service/              # unit test service (HorseStorage, RaceSimulationFormula)

docs/                     # tài liệu API, database, chức năng
target/                   # output Maven tự tạo, không sửa tay
```

## Trách nhiệm của từng package

- Đặt HTTP endpoint trong `controller`; controller nhận/kiểm tra request rồi
  chuyển xử lý cho service.
- Đặt quy tắc nghiệp vụ và ranh giới transaction (`@Transactional`) trong `service`.
- Đặt truy cập database trong `repository` (Spring Data JPA); không để
  controller tự truy vấn database.
- Đặt contract request/response trong `dto/request` và `dto/response`.
- Để cấu hình toàn ứng dụng trong `config`, hạ tầng xác thực trong `security`.
- Đặt test dưới package tương ứng trong `src/test/java`; test tích hợp toàn ứng
  dụng hoặc migration nằm ở package gốc.

Quy ước đặt tên: package viết thường, class PascalCase, method/biến camelCase,
hằng số UPPER_SNAKE_CASE; dùng hậu tố `Controller`, `Service`, `Repository`,
`Request`, `Response` thống nhất.

## Truy cập dữ liệu và migration

- Persistence dùng **Spring Data JPA**: entity trong `entity/`, repository kế
  thừa `JpaRepository` trong `repository/`. Mã nguồn chính không còn dùng
  `JdbcTemplate` (test vẫn dùng để seed/kiểm tra dữ liệu).
- Schema do **Flyway** quản lý duy nhất. Hibernate chạy với
  `spring.jpa.hibernate.ddl-auto=validate` — chỉ kiểm tra entity khớp schema,
  không bao giờ tự tạo/sửa/xóa bảng.
- Các version Flyway có thể đã chạy trên database triển khai. Khi đổi schema,
  tạo migration version mới; không đổi tên, thứ tự hoặc nội dung migration đã
  áp dụng. Đặt policy và DDL riêng PostgreSQL trong `db/postgresql`.

## Cấu hình local

`src/main/resources/application.properties` khai báo giá trị mặc định từ biến
môi trường và import `application-local.properties` tùy chọn ở thư mục gốc.
File local được Git bỏ qua; giữ thông tin đăng nhập ngoài `src/main/resources`
để chúng không bị đóng gói vào JAR và không commit file này.
