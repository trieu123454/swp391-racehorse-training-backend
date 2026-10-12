# Backend Bác sĩ thú y

Triển khai nhóm A–F (#24–41 theo đặc tả), Spring Boot, PostgreSQL, JWT.
Swagger: `/swagger-ui/index.html`, nhóm `Veterinarian` và `Notifications`.
Các API mới trả JSON snake_case; lỗi có dạng `error: {code,message,details}`.

## Tài khoản và phân quyền

- Giữ ID tài khoản `BIGINT` và trạng thái `APPROVED` của backend hiện tại,
  tương đương `Active` trong đặc tả. ID ngựa/hồ sơ/sự kiện dùng UUID.
- Tài khoản bác sĩ do Club Manager tạo. Phải đổi mật khẩu cấp lần đầu trước
  khi dùng API nghiệp vụ. Kiểm tra role/status hiện tại trong DB ở mỗi yêu cầu.
- Hồ sơ khám, chẩn đoán, đơn thuốc, khẩu phần, chấn thương và lịch bác sĩ chỉ
  mở cho `VETERINARIAN`. Thông báo mở cho tất cả tài khoản đang hoạt động và
  chỉ trả dữ liệu của chính người đăng nhập.
- Bỏ qua các trường ngoài allowlist, gồm trường tác giả do client giả mạo.
  Không có API xóa hồ sơ y tế hoặc sửa điểm chấn thương cũ.

## API

| Method | Đường dẫn | Chức năng |
|---|---|---|
| POST, GET | `/api/horses/{horseId}/health-exams` | Tạo, danh sách khám; lọc `from`, `to` |
| GET, PATCH | `/api/health-exams/{id}` | Chi tiết, sửa và lưu chênh lệch |
| GET | `/api/health-exams/{id}/logs` | Lịch sử sửa |
| POST, GET | `/api/horses/{horseId}/medical-records` | Chẩn đoán |
| GET, PATCH | `/api/medical-records/{id}` | Chi tiết kèm đơn và chấn thương; sửa |
| POST | `/api/medical-records/{id}/prescriptions` | Kê đơn |
| PATCH | `/api/prescriptions/{id}` | Sửa/kết thúc đơn Active |
| GET | `/api/horses/{horseId}/prescriptions` | Lọc `status` |
| POST, GET | `/api/horses/{horseId}/diet-records` | Khẩu phần; `active_on`, `include_history` |
| PATCH | `/api/diet-records/{id}` | Sửa hoặc kết thúc khẩu phần |
| POST, GET | `/api/horses/{horseId}/injury-markers` | Thêm đánh giá; `latest_only`, `body_part` |
| GET | `/api/vet/health-overview` | Tổng quan đàn; lọc `section` |
| PATCH | `/api/horses/{id}/health-status` | Cập nhật trạng thái sức khỏe |
| PUT | `/api/horses/{id}/training-lock` | Khóa/cập nhật lý do khóa |
| POST | `/api/horses/{id}/training-unlock` | Mở khóa |
| POST, GET | `/api/periodic-care-schedules` | Tạo/danh sách quy tắc; `horse_id`, `care_type`, `due_within_days` |
| POST | `/api/periodic-care-schedules/{id}/book` | Chốt lịch chưa đặt |
| PATCH | `/api/periodic-care-schedules/{id}/event` | Dời ngày/giờ |
| POST | `/api/periodic-care-schedules/{id}/cancel-event` | Hủy sự kiện, giữ quy tắc |
| POST | `/api/periodic-care-schedules/{id}/complete` | Hoàn thành và tính kỳ tiếp theo |
| GET | `/api/vet/calendar` | `from`, `to`, `scope=mine/all`, `horse_id`, `care_type`, `include_context` |
| GET | `/api/notifications` | `unread_only`, kèm `unread_count` |
| PATCH | `/api/notifications/{id}/read` | Đọc một thông báo đã đến hạn |
| POST | `/api/notifications/read-all` | Đọc tất cả thông báo đã đến hạn |

Danh sách dùng `page=1&limit=20`, tối đa 100, trả `data,total,page,limit`.
Health overview trả toàn đàn theo đặc tả. Calendar giữ cả các lần chăm sóc
đã hoàn thành; join bằng `source_id` để lịch sử không biến mất khi con trỏ
`calendar_event_id` chuyển sang kỳ mới. `include_context=true` thêm
`context_events` và `context_total` của các ngựa trên trang hiện tại, giới hạn
`limit` sự kiện context và đánh dấu `context:true`.

## Quy tắc xử lý

- TIMESTAMP mới ghi theo UTC, response dạng ISO-8601 với `Z`. DATE và giờ
  hẹn theo `Asia/Ho_Chi_Minh`. Bộ lọc ngày khám tính theo ngày Việt Nam.
- Chỉ số ngoài ngưỡng bình thường vẫn lưu, trả `alerts`. Các khoảng mềm cấu
  hình qua `app.vet.normal.temperature-min/max`, `heart-rate-min/max`,
  `respiratory-rate-min/max`. Giá trị mặc định đúng theo đặc tả.
- Cột số thập phân được kiểm tra độ chính xác trước khi lưu, tránh DB tự
  làm tròn. Sửa khám không đổi dữ liệu trả `changed:false`, không tạo log.
- Khẩu phần dùng khoảng ngày bao gồm cả ngày kết thúc. Cùng loại thức ăn
  (trim, không phân biệt hoa thường) không được chồng ngày trên cùng ngựa.
- Mọi lần ghi nghiệp vụ, log sửa và thông báo nằm trong cùng transaction;
  audit ghi actor, địa chỉ kết nối trực tiếp, nội dung tối đa 255 ký tự.
- Lịch bác sĩ khóa hàng bác sĩ và ngựa trước khi kiểm tra xung đột. Các
  service Flow 2 khi tạo/dời lịch phải dùng cùng quy tắc khóa hàng ngựa.
- Hủy rồi đặt lại cùng ngày tái sử dụng sự kiện Cancelled. Dời sang ngày có
  sự kiện Cancelled cũ cũng tái sử dụng sự kiện đó, hủy sự kiện đang dời.
- Hoàn thành lịch không thất bại chỉ vì kỳ tiếp theo trùng/thiếu giờ: trả
  `NEXT_EVENT_NOT_BOOKED` và giữ quy tắc chưa chốt. Không cho hoàn thành lại
  với `done_date <= last_done_date`, tránh request lặp hoàn thành nhầm kỳ sau.
- Lịch một lần chưa chốt khi hoàn thành tạo một sự kiện Completed để lưu
  trạng thái hoàn tất. Lịch có chu kỳ giữ sự kiện cũ làm lịch sử.
- Nhắc lịch là thông báo lưu sẵn, không cron/email. Giờ mặc định 08:00 VN
  hôm trước, cấu hình `app.vet.reminder-time`. Thông báo chưa đến hạn được
  xóa khi dời/hủy/hoàn thành; thông báo đã hiện được giữ làm lịch sử.

## Hợp đồng Flow 2

Hiện repository chưa có API tạo/dời giáo án hoặc lịch tập của Head Trainer.
Đã có `VetHorseService.assertHorseNotLocked(horseId)`: gọi bên trong
transaction ghi, **trước** khi tạo/sửa giáo án/sự kiện. Hàm giữ khóa hàng
ngựa đến hết transaction, trả `409 TRAINING_LOCKED` kèm lý do/mức độ.
Không có API giả lập Flow 2; tích hợp hàm này khi triển khai flow đó.

## Migration và chạy

V10 bổ sung `injury_markers.recovery_status`, `marked_by`, index; đổi unique
lịch thành `(source_table,source_id,event_date)`; chuẩn hóa sự kiện chăm sóc
`MedicalCheckup` và `source_table='Periodic_Care_Schedules'`. Giữ RLS hiện có.

```powershell
mvn '-Dspring.config.import=' test
mvn '-Dspring.config.import=' '-Dvet.postgres=true' '-Dtest=PostgresVeterinarianTests' test
```

Lệnh thứ nhất chỉ dùng H2. Lệnh PostgreSQL là opt-in, đọc cấu hình kết nối
local, tạo schema `vet_test_<uuid>`, chạy các ca tích hợp và xóa schema tạm.
Không chạy test trên schema `public`. Nếu JVM bị cưỡng bức tắt, cần dọn schema
tạm còn sót sau khi kiểm tra tên. Không đưa mật khẩu/token vào tài liệu.

Ví dụ tạo khám (Bearer JWT bác sĩ đã đổi mật khẩu):

```http
POST /api/horses/{horseId}/health-exams
Content-Type: application/json

{"temperature_c":38.9,"heart_rate":42,"respiratory_rate":12,"notes":"Theo dõi"}
```

Ví dụ tạo lịch:

```json
{"horse_id":"<uuid>","care_type":"Vaccination","frequency_days":90,"next_due_date":"2026-10-03","start_time":"08:00","end_time":"09:00","book_event":true}
```
