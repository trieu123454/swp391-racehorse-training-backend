# Cấu trúc database

Nguồn yêu cầu: `schema.dbml` (bản gốc được bổ sung `Horses.image_url`). SQL thực thi nằm trong `src/main/resources/db/` và là cấu trúc **tương thích với backend đăng nhập hiện tại**, theo lựa chọn của chủ dự án.

## Khác biệt có chủ đích so với DBML

| DBML | Database thực tế |
| --- | --- |
| `Users.id CHAR(36)` | `users.user_id BIGINT`, giữ toàn bộ ID cũ |
| `Users.name`, `Users.password` | `full_name`, `password_hash`, giữ hash hiện tại |
| `Users.status = Active` | Giữ `PENDING / APPROVED / REJECTED / LOCKED` |
| `Roles.id CHAR(36)` | `roles.role_id INTEGER` |
| Các khóa ngoại tới user/role | `BIGINT`/`INTEGER` tương ứng |
| `User_Roles` nhiều-nhiều | View đọc `user_id, role_id` từ `users`, tiếp tục một role/tài khoản |
| `Supply_Requests.requested_by NOT NULL` + `SET NULL` | Giữ `NOT NULL`, chuyển `ON DELETE RESTRICT` để không mâu thuẫn |
| Ghi chú chỉ Admin xóa | Hệ thống hiện có Club Manager; kiểm soát quyền tại API khi triển khai Flow 1 |

Các ID nghiệp vụ mới giữ `CHAR(36)`; ứng dụng phải cấp chuỗi UUID khi tạo bản ghi. Các bảng và cột dùng tên chữ thường, snake_case trong PostgreSQL.

## Các migration

- V1: tạo `users`, `roles`, `refresh_tokens` khi database trống.
- V2: thêm `users.deleted_at`, view `user_roles`, 24 bảng nghiệp vụ, 51 khóa ngoại và index. `horses` có đủ 17 trường trong bản cập nhật, gồm `image_url VARCHAR(512)`.
- V3 (chỉ PostgreSQL): bật RLS cho 24 bảng nghiệp vụ và không cho các vai trò Supabase client đọc view phân quyền. Spring Boot truy cập bằng tài khoản DB phía server có quyền phù hợp. Không đặt DB password trong frontend.

Tổng cộng: **27 bảng ứng dụng + 1 view**, cộng `flyway_schema_history` để quản lý phiên bản. Trong 27 bảng ứng dụng có bảng `refresh_tokens` phục vụ đăng nhập, không có trong DBML gốc.

Database cũ chỉ có ba bảng xác thực được baseline ở V1, sau đó áp dụng V2 và V3. Không xóa hoặc đổi ID người dùng, password hash hay trạng thái tài khoản. Không dùng `ddl-auto=update` nữa; Flyway quản lý cấu trúc, Hibernate chỉ `validate`.

Không sửa migration đã áp dụng; tạo V4, V5… cho các thay đổi sau. Ứng dụng tắt `baseline-on-migrate` để không tự nhận nhầm một database bất kỳ làm phiên bản V1; chỉ công cụ chuyển đổi `--apply` bật tùy chọn này cho database xác thực cũ đã kiểm tra.

## Giới hạn của bước tạo database

- Chưa tạo API/CRUD, JPA entity nghiệp vụ, form Flow 1 hay tích hợp Firebase. `image_url` chỉ lưu URL.
- Mặc định ngựa: `Healthy`, `Unknown`, `is_training_locked=false`, ảnh và `deleted_at` là NULL.
- Xóa ngựa phải dùng `UPDATE horses SET deleted_at = CURRENT_TIMESTAMP ...`; tuyệt đối không gọi `DELETE FROM horses` trong CRUD vì các FK cascade theo DBML sẽ xóa lịch sử.
- DB chưa tự kiểm tra sức chứa chuồng hay role/status của chủ sở hữu. Service Flow 1 phải kiểm tra trong transaction, khóa chuồng khi cần để tránh hai request xếp quá sức chứa.
- Mọi truy vấn ngựa phải lọc `deleted_at IS NULL`; Horse Owner phải được giới hạn bằng `owner_id` ở backend.
- Không có trigger cập nhật `updated_at` hoặc dữ liệu realtime; chức năng tương ứng phải cập nhật khi ghi dữ liệu.
- RLS không thay thế kiểm tra role tại Spring API. Không thêm policy Supabase đọc/ghi công khai cho các bảng nghiệp vụ.

## Kiểm tra và chạy

`mvn test` chạy trên H2 PostgreSQL mode, kiểm tra schema mới, nâng cấp schema có tài khoản cũ, mặc định, khóa ngoại và soft delete. Tests không dùng cấu hình database thật khi chạy với `-Dspring.config.import=`.

```powershell
mvn '-Dspring.config.import=' test
mvn dependency:build-classpath '-Dmdep.outputFile=target/runtime-classpath.txt'
$databaseClasspath = Get-Content target/runtime-classpath.txt -Raw
java -cp $databaseClasspath tools/database/MigrateDatabase.java --check
java -cp $databaseClasspath tools/database/MigrateDatabase.java --apply
```

`--check` kiểm tra DDL PostgreSQL trong schema tạm và rollback toàn bộ. `--apply` nâng cấp database xác thực đang cấu hình trong `application-local.properties`, kiểm tra tài khoản được giữ nguyên và validate Flyway. Công cụ `--apply` phục vụ database xác thực đã tồn tại; với database trống, dùng `mvn spring-boot:run` để Flyway chạy từ V1 rồi RoleSeeder tạo năm role.
