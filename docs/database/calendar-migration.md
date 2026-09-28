# Lịch trung tâm (V8–V9)

`schema.dbml` lưu bản yêu cầu cập nhật. Database tiếp tục giữ các khác biệt
về tài khoản đã mô tả trong README: ID số, một vai trò/tài khoản, tên cột và
trạng thái đăng nhập hiện tại. Giữ `horses.image_url`, `horse_image_uploads`,
`refresh_tokens` và `users.must_change_password` phục vụ backend.

- V8 tạo `calendar_events`, index `(horse_id, event_date)`, unique
  `(source_table, source_id)` và liên kết một-một tới bốn bảng nghiệp vụ.
- Chuyển ngày/giờ buổi tập và ngày công việc sang lịch trung tâm trước khi
  bỏ `training_schedules.training_date`, `training_schedules.start_time`,
  `daily_task_logs.task_date`.
- Lịch đua lấy ngày từ `races.race_date`; lịch chăm sóc lấy `next_due_date`.
  `MedicalCheckup` ánh xạ sang `VetCheckup`; công việc là `CareTask`.
- `periodic_care_schedules.calendar_event_id` cho phép NULL; ba bảng còn lại
  bắt buộc có sự kiện. Xóa sự kiện sẽ cascade ba bảng này; lịch chăm sóc
  được giữ lại và xóa liên kết bằng SET NULL, theo DBML.
- V9 bật RLS theo cơ chế truy cập qua Spring API hiện tại.

Ứng dụng phải tạo sự kiện và bản ghi nghiệp vụ trong cùng transaction, giữ
`source_table`, `source_id`, `horse_id` khớp nhau. Khi đổi ngày cuộc đua hoặc
lịch chăm sóc, service cần cập nhật sự kiện tương ứng. Migration không tạo
trigger đồng bộ hoặc API lịch. `HorseService` đã dùng `event_date` cho cảnh
báo lịch tập khi xóa mềm ngựa.

Backfill giữ UUID bản ghi cũ làm ID sự kiện. Nếu dữ liệu cũ trùng ID giữa
các bảng, migration PostgreSQL sẽ rollback và cần xử lý ID sự kiện trước
khi chạy lại. Supabase được kiểm tra trước triển khai: cả bốn bảng đang rỗng.

Sau cập nhật có 29 bảng ứng dụng, một view và `flyway_schema_history`.
Không chỉnh sửa checksum migration cũ. Công cụ `MigrateDatabase --apply`
không tự baseline database không rõ nguồn gốc.

Kiểm tra: `mvn '-Dspring.config.import=' test`, sau đó dùng
`tools/database/MigrateDatabase.java --check` để chạy DDL PostgreSQL trong
schema tạm và rollback. `--apply` chạy Flyway và kiểm tra tài khoản,
password hash, trạng thái, vai trò hiện có được giữ nguyên.
