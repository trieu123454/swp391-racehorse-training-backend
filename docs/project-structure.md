# Tổ chức backend

Chia theo chức năng trước, sau đó chia theo nhiệm vụ. Khi thêm huấn luyện, y tế hay chăm sóc, tạo module tương ứng cạnh `auth` và `horse` để tìm toàn bộ code của một chức năng tại một chỗ.

```text
src/main/java/com/example/springbootbackend/
├── auth/
│   ├── controller/
│   ├── dto/request/
│   ├── dto/response/
│   ├── entity/
│   ├── exception/
│   ├── repository/
│   └── service/
├── horse/
│   ├── controller/HorseController.java
│   ├── dto/request/HorseRequest.java
│   ├── exception/HorseExceptionHandler.java
│   ├── service/HorseService.java
│   └── storage/HorseStorage.java
├── health/
├── security/
└── SpringbootBackendApplication.java
```

| Thư mục | Trách nhiệm |
| --- | --- |
| `controller` | Nhận request HTTP và gọi nghiệp vụ |
| `dto/request`, `dto/response` | Dữ liệu vào/ra và validation |
| `service` | Nghiệp vụ, phân quyền dữ liệu, giao dịch |
| `repository` | Truy cập database khi module có repository riêng |
| `entity` | Mapping JPA khi module sử dụng JPA |
| `exception` | Lỗi và chuyển lỗi thành HTTP response |
| `storage` | Upload, cấp link ảnh và giao tiếp kho lưu trữ |
| `security` | JWT, xác thực và cấu hình bảo mật dùng chung |

Hiện `horse` dùng JDBC trực tiếp trong service, chưa có entity/repository riêng. Không tạo thư mục rỗng hoặc lớp trung gian chỉ để đủ cấu trúc. `HorseStorage` vẫn thuộc `horse` vì có quy tắc ảnh ngựa và bảng theo dõi upload riêng; khi nhiều module dùng chung Supabase, có thể tách client kỹ thuật dùng chung sau.

`src/test/java` phản chiếu package của mã nguồn được kiểm thử: `horse/controller`, `horse/storage`. Migration nằm trong `src/main/resources/db/migration`; SQL riêng PostgreSQL nằm trong `db/postgresql`. Không chuyển hoặc đổi số migration đã áp dụng.

`docs/` chứa tài liệu; `tools/` chứa công cụ quản trị; `target/` là kết quả Maven tự sinh, không chỉnh sửa hoặc commit. Cấu hình local giữ ở gốc dự án, được Git bỏ qua.

Sau khi đổi package, chạy `mvn clean test` để xóa class cũ rồi kiểm thử. Dừng backend cũ trước khi chạy lại `mvn spring-boot:run`.
