# Cấu trúc backend

Project dùng cấu trúc source chuẩn của Maven và nhóm code theo chức năng. Giữ
lớp khởi động Spring Boot ở package gốc để component scan bao phủ toàn ứng dụng.

```text
src/main/java/com/example/springbootbackend/
├── RacehorseTrainingBackendApplication.java
├── auth/                 # đăng nhập, người dùng, token, tạo role mặc định
│   ├── controller/
│   ├── dto/
│   │   ├── request/
│   │   └── response/
│   ├── entity/
│   ├── exception/
│   ├── repository/
│   └── service/
├── clubmanager/          # quản lý nhân sự và phân quyền
│   ├── controller/
│   ├── dto/
│   ├── exception/
│   └── service/
├── config/               # cấu hình Spring/OpenAPI dùng chung
├── health/               # endpoint kiểm tra ứng dụng
├── horse/                # hồ sơ ngựa và lưu trữ ảnh
│   ├── controller/
│   ├── dto/request/
│   ├── exception/
│   ├── service/
│   └── storage/
├── horseowner/           # chức năng xem dành cho chủ ngựa
│   ├── controller/       # endpoint HTTP
│   ├── repository/       # truy vấn SQL và dữ liệu
│   └── service/          # phân quyền và tổng hợp nghiệp vụ/báo cáo
├── headtrainer/          # giáo án, lịch tập, chỉ số và đăng ký giải
│   ├── HeadTrainerController.java
│   ├── HeadTrainerService.java
│   └── RaceSimulationService.java
├── groom/                # chăm sóc chuồng trại, sự cố và ảnh đính kèm
│   ├── GroomController.java
│   ├── GroomHorseService.java
│   ├── GroomWorkspaceService.java
│   └── StableIncidentImageController.java
├── security/             # JWT, xác thực, quy tắc bảo mật
└── veterinarian/         # nghiệp vụ thú y, chia theo phân hệ
    ├── care/
    ├── exam/
    ├── horse/
    ├── medical/
    ├── notification/
    └── support/

src/main/resources/
├── application.properties
└── db/
    ├── migration/        # migration dùng chung
    └── postgresql/       # migration riêng cho PostgreSQL

src/test/java/com/example/springbootbackend/  # test theo package mã nguồn
docs/                                          # tài liệu API, database, chức năng
tools/database/                                # công cụ quản trị database
target/                                        # output Maven tự tạo, không sửa tay
```

## Trách nhiệm của từng package

- Đặt HTTP endpoint trong `controller`; controller nhận/kiểm tra request rồi
  chuyển xử lý cho service.
- Đặt quy tắc nghiệp vụ và ranh giới transaction trong `service`.
- Đặt truy vấn SQL/JPA phức tạp hoặc dùng chung trong `repository`; không để
  controller tự truy vấn database.
- Đặt contract request/response trong `dto/request` và `dto/response` khi phân
  hệ có DTO riêng.
- Để cấu hình toàn ứng dụng trong `config`, hạ tầng xác thực trong `security`.
  Helper riêng của chức năng nên nằm cạnh chức năng đó thay vì tạo `utils` chung.
- Đặt test dưới package tương ứng trong `src/test/java`; test tích hợp toàn ứng
  dụng hoặc migration có thể nằm ở package gốc.

Một số service hiện có, như `horse/HorseService`, dùng `JdbcTemplate` trực tiếp.
Giữ truy vấn nhỏ, chỉ phục vụ một chức năng tại service nếu repository chỉ là
lớp bọc rỗng. Tách repository khi truy vấn nhiều, cần dùng lại hoặc cần kiểm thử
riêng. `horseowner` có repository vì dashboard tổng hợp dữ liệu từ nhiều bảng.

Phân hệ thú y được nhóm theo nghiệp vụ con trước, sau đó đặt controller/service/
support liên quan cùng package. Tiếp tục cách này khi thêm chức năng thú y để
package gọn và code liên quan nằm gần nhau.

## Cấu hình local và migration

`src/main/resources/application.properties` khai báo giá trị mặc định từ biến
môi trường và import `application-local.properties` tùy chọn ở thư mục gốc.
File local được Git bỏ qua; giữ thông tin đăng nhập ngoài `src/main/resources`
để chúng không bị đóng gói vào JAR và không commit file này.

Các version Flyway có thể đã chạy trên database triển khai. Khi đổi schema, tạo
migration version mới; không đổi tên, thứ tự hoặc nội dung migration đã áp dụng.
Đặt policy và DDL riêng PostgreSQL trong `db/postgresql`.
