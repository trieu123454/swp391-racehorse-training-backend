# SWP391 - Hệ thống quản lý huấn luyện ngựa đua - Backend

Backend REST API cho đề tài **Hệ thống quản lý huấn luyện ngựa đua**, môn **SWP391**, sử dụng Spring Boot và Supabase PostgreSQL.

Tên dự án: `swp391-racehorse-training-backend`.

## Cấu trúc mã nguồn

Mã nguồn được chia theo chức năng (`auth`, `horse`); bên trong mỗi chức năng chia theo nhiệm vụ. Xem [phân tích yêu cầu và roadmap](docs/product-requirements-analysis.md) trước khi thêm flow mới, [hướng dẫn tổ chức code](docs/project-structure.md) khi thêm chức năng, [API Flow 1](docs/flow1-api.md) khi nối frontend và [API RBAC Club Manager](docs/club-manager-rbac-api.md) khi làm phân quyền tài khoản.

## Database

Database được quản lý bằng Flyway, Hibernate chỉ kiểm tra schema (`ddl-auto=validate`). Xem [cấu trúc và hướng dẫn migration](docs/database/README.md) để biết schema nghiệp vụ, view phân quyền và cách nâng cấp database đang có tài khoản. Backend Flow 1 đã có API quản lý hồ sơ ngựa và upload ảnh; V4/V5 bổ sung bảng theo dõi ảnh và bảo vệ truy cập.

## Yeu cau

- Java 25 de chay Maven/app, project compile target Java 21 de tuong thich Spring Boot 3.3.x
- Maven 3.9+

## Chay project

Mở terminal tại thư mục `swp391-racehorse-training-backend` trước khi chạy các lệnh bên dưới.

Thành viên mới sao chép `application-local.properties.example` thành `application-local.properties` và điền cấu hình riêng. File cấu hình local được loại khỏi Git để bảo vệ thông tin đăng nhập.

Neu Maven tren may van dang tro den JDK khac, set `JAVA_HOME` ve JDK 25 truoc:

```powershell
$env:JAVA_HOME="C:\Program Files\Java\jdk-25.0.4.1"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -version
```

Neu da co file `application-local.properties` o thu muc project, ban co the chay thang `mvn spring-boot:run`. Neu chay tren may khac, thiet lap bien moi truong Supabase truoc khi chay:

```powershell
$env:SUPABASE_DB_URL="jdbc:postgresql://aws-0-ap-northeast-2.pooler.supabase.com:6543/postgres?sslmode=require"
$env:SUPABASE_DB_USER="postgres.fwblgvygdbxlwgenqslz"
$env:SUPABASE_DB_PASSWORD="YOUR_DATABASE_PASSWORD"
$env:AUTH_JWT_SECRET="CHANGE_THIS_TO_A_LONG_RANDOM_SECRET"
$env:GOOGLE_CLIENT_ID="YOUR_GOOGLE_CLIENT_ID"
```

Project da tat PostgreSQL server-side prepared statements bang `prepareThreshold=0` trong Hikari de tuong thich Supabase Transaction Pooler.

```bash
mvn spring-boot:run
```

Server mac dinh chay tai:

```text
http://localhost:8080
```

Swagger UI:

```text
http://localhost:8080/swagger-ui/index.html
```

## API mau

```http
GET /api/health
POST /api/auth/register
POST /api/auth/login
POST /api/auth/google
POST /api/auth/refresh
POST /api/auth/logout
POST /api/auth/users/{userId}/approve
POST /api/auth/users/{userId}/reject
```

Register body:

```json
{
  "fullName": "Club Manager",
  "email": "manager@example.com",
  "phone": "0900000000",
  "password": "password123",
  "roleName": "CLUB_MANAGER"
}
```

Role hop le:

```text
HEAD_TRAINER
VETERINARIAN
GROOM
HORSE_OWNER
CLUB_MANAGER
```

Chỉ `HORSE_OWNER` được kích hoạt ngay sau khi đăng ký. Chủ ngựa ở trạng thái `PENDING` từ trước cũng được kích hoạt khi đăng nhập thành công. Tài khoản `REJECTED` hoặc `LOCKED` vẫn bị chặn.

Các vai trò `HEAD_TRAINER`, `VETERINARIAN`, `GROOM`, `CLUB_MANAGER` cần được Club Manager phê duyệt trước khi đăng nhập, kể cả tài khoản đăng ký đầu tiên. Quy tắc này áp dụng cho đăng ký bằng mật khẩu và Google. Tài khoản Google đang chờ duyệt được lưu lại để người quản lý có thể phê duyệt. Tài khoản đã mang trạng thái `APPROVED` được giữ nguyên; thay đổi này không tự thu hồi quyền của tài khoản cũ.

Đăng nhập của chủ ngựa không đồng nghĩa với xác nhận quyền sở hữu ngựa. Backend hiện mới có module xác thực; module hồ sơ ngựa và xác nhận quyền sở hữu chưa được triển khai. Khi bổ sung module này, các API dữ liệu ngựa phải kiểm tra liên kết sở hữu đã được xác nhận.

Một `CLUB_MANAGER` đã được phê duyệt sử dụng API `/api/auth/users/{userId}/approve` để duyệt tài khoản mới. Khi triển khai với cơ sở dữ liệu trống, người quản trị cơ sở dữ liệu cần thiết lập một tài khoản `CLUB_MANAGER` được phê duyệt trước; đăng ký công khai không tự cấp quyền quản lý.

Login body:

```json
{
  "email": "manager@example.com",
  "password": "password123"
}
```

Google login body:

```json
{
  "idToken": "GOOGLE_ID_TOKEN",
  "roleName": "HORSE_OWNER"
}
```

`roleName` chi bat buoc trong lan Google login dau tien neu email chua ton tai.
