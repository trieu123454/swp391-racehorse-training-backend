# Đặc tả chức năng Bác sĩ Thú y (Veterinarian)

Hệ thống: Racehorse Training & Management System
Tài liệu dành cho: dev / AI code
DB tham chiếu: `racehorse_schema_v3.dbml` (tên bảng, tên cột giữ nguyên, không tự đổi)

---

## 0. Cách dùng tài liệu

- Không phụ thuộc framework. API mô tả dạng REST + JSON (snake_case). Dùng NestJS, Spring Boot hay ASP.NET đều được.
- Chức năng đánh số `#24` đến `#35` khớp bảng feature list của nhóm. `#36` đến `#41` là **bổ sung** vì đề bài yêu cầu nhưng feature list còn thiếu.
- Mỗi chức năng gồm: user story + tiêu chí chấp nhận, luồng hoạt động, API, business rules, lỗi, tác động DB.
- Chỗ nào nhóm chưa chốt, tài liệu đã chọn sẵn **giá trị mặc định** (mục 8) để code chạy được ngay. Muốn đổi thì đổi ở mục 8 rồi báo dev.
- Nếu gặp mâu thuẫn giữa tài liệu và DB, **hỏi lại**, không tự suy diễn.

## 1. Phạm vi và điều kiện tiên quyết

### 1.1 Danh sách chức năng

| # | Chức năng | Nguồn | Story | Bảng ghi chính |
|---|---|---|---|---|
| 24 | Tạo hồ sơ khám bệnh | feature list | US-V01 | `Health_Exams` |
| 25 | Xem hồ sơ khám + lịch sử sửa | feature list | US-V02 | `Health_Exams`, `Health_Exam_Logs` (đọc) |
| 26 | Sửa hồ sơ khám + ghi log | feature list | US-V03 | `Health_Exams`, `Health_Exam_Logs` |
| 38 | Chẩn đoán và phác đồ điều trị | bổ sung | US-V04 | `Medical_Records` |
| 27 | Tạo đơn thuốc | feature list | US-V05 | `Prescriptions` |
| 28 | Cập nhật / kết thúc đơn thuốc | feature list | US-V06 | `Prescriptions` |
| 29 | Tạo hồ sơ ăn uống | feature list | US-V07 | `Diet_Records` |
| 30 | Cập nhật hồ sơ ăn uống | feature list | US-V08 | `Diet_Records` |
| 31 | Đánh dấu chấn thương, theo dõi phục hồi | feature list | US-V09 | `Injury_Markers` |
| 36 | Xem sơ đồ sức khỏe toàn đàn | bổ sung | US-V10 | `Horses` (đọc) |
| 37 | Cập nhật trạng thái sức khỏe ngựa | bổ sung | US-V11 | `Horses.current_status` |
| 32 | Kích hoạt "Khóa huấn luyện" | feature list | US-V12 | `Horses` (3 field lock), `Notifications` |
| 39 | Mở khóa huấn luyện | bổ sung | US-V13 | `Horses`, `Notifications` |
| 33 | Tạo lịch kiểm tra móng / tẩy giun / tiêm phòng | feature list | US-V14 | `Periodic_Care_Schedules`, `Calendar_Events`, `Notifications` |
| 34 | Hiển thị lịch định kỳ, không trùng lịch | feature list | US-V15 | `Calendar_Events` (đọc + kiểm tra) |
| 35 | Thông báo tự động trước 1 ngày | feature list | US-V16 | `Notifications` |
| 40 | Đánh dấu hoàn thành lịch định kỳ | bổ sung | US-V17 | `Periodic_Care_Schedules`, `Calendar_Events`, `Notifications` |
| 41 | Chốt / đổi / hủy lịch định kỳ | bổ sung | US-V18 | `Periodic_Care_Schedules`, `Calendar_Events`, `Notifications` |

### 1.2 Phải có sẵn trước khi code phần Bác sĩ

1. Đăng nhập bằng JWT, token chứa `user_id` và `role_name`. Tài khoản bác sĩ do Club Manager tạo (role `VETERINARIAN`, `status = Active`).
2. Flow 1 có ít nhất: tạo ngựa, xem chi tiết ngựa (để có dữ liệu ngựa test).
3. DB v3 đã tạo, đặc biệt `Calendar_Events` và các index mới.
4. Dịch vụ thông báo dùng chung (mục 2.8) vì bác sĩ và Head Trainer đều dùng.

---

## 2. Quy ước chung (áp dụng cho mọi API của Bác sĩ)

### 2.1 Xác thực và phân quyền
- Header `Authorization: Bearer <JWT>`.
- Không có token hoặc token sai: `401 UNAUTHENTICATED`.
- Sai role (không phải `VETERINARIAN`) hoặc tài khoản `Locked`: `403 FORBIDDEN`.
- Phân quyền kiểm tra **ở BE**, không chỉ ẩn nút ở FE.

### 2.2 Định dạng lỗi

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Dữ liệu không hợp lệ",
    "details": [{ "field": "temperature_c", "message": "Phải nằm trong khoảng 30.0 đến 45.0" }]
  }
}
```

| HTTP | code | Khi nào |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Thiếu field bắt buộc, sai kiểu, ngoài khoảng cho phép |
| 401 | `UNAUTHENTICATED` | Chưa đăng nhập |
| 403 | `FORBIDDEN` | Sai role |
| 404 | `HORSE_NOT_FOUND`, `NOT_FOUND` | Ngựa hoặc bản ghi không tồn tại / đã xóa mềm |
| 409 | `SCHEDULE_CONFLICT` | Trùng lịch (mục 4F) |
| 409 | `DIET_OVERLAP` | Trùng loại thức ăn cùng khoảng thời gian |
| 409 | `PRESCRIPTION_CLOSED` | Sửa đơn thuốc đã kết thúc |
| 409 | `NOT_LOCKED` | Mở khóa ngựa chưa bị khóa |
| 409 | `EVENT_ALREADY_COMPLETED` | Hoàn thành lịch đã hoàn thành |
| 409 | `TRAINING_LOCKED` | **Dùng cho Flow 2**: tạo lịch/giáo án cho ngựa đang khóa (mục 5.1) |

### 2.3 Ghi `Audit_Logs`
Mọi thao tác ghi (tạo, sửa, khóa, mở khóa, hoàn thành...) ghi 1 dòng `Audit_Logs`:
- `user_id` lấy từ token, `ip_address` từ request.
- `action_performed` dạng `VERB_ENTITY:{id}` (kèm chi tiết ngắn nếu có), **cắt tối đa 255 ký tự**. Ví dụ: `CREATE_HEALTH_EXAM:{id}`, `LOCK_TRAINING:{horse_id}:Critical`, `UPDATE_HEALTH_STATUS:{horse_id}:Healthy->Injured`.

### 2.4 Transaction
Thao tác ghi từ 2 bảng trở lên (kể cả `Audit_Logs` và `Notifications`) chạy trong **1 transaction**. Lỗi ở bước nào thì rollback toàn bộ.

### 2.5 Thời gian và múi giờ
- Cột `TIMESTAMP` lưu UTC. Múi giờ nghiệp vụ: `Asia/Ho_Chi_Minh` (UTC+7).
- `DATE` dạng `YYYY-MM-DD`, giờ dạng `HH:mm`.

### 2.6 Phân trang
`?page=1&limit=20`, `limit` tối đa 100. Response danh sách: `{ "data": [...], "total": n, "page": 1, "limit": 20 }`.

### 2.7 Bộ giá trị (enum) dùng trong phần Bác sĩ

| Cột | Giá trị |
|---|---|
| `Horses.current_status` | `Healthy` (Đủ điều kiện), `Monitoring` (Cần theo dõi), `Injured` (Chấn thương), `Quarantine` (Cách ly) |
| `Horses.lock_level` | `Warning` (cam), `Critical` (đỏ) |
| `Prescriptions.status` | `Active`, `Completed`, `Stopped` |
| `Injury_Markers.severity` | `Mild`, `Moderate`, `Severe` |
| `Injury_Markers.recovery_status` | `Active`, `Recovering`, `Recovered` |
| `Periodic_Care_Schedules.care_type` | `HoofCheck`, `Deworming`, `Vaccination`, `MedicalCheckup` |
| `Calendar_Events.status` | `Scheduled`, `Completed`, `Cancelled` |
| `Calendar_Events.event_type` (lịch bác sĩ) | trùng với `care_type` |

### 2.8 Dịch vụ thông báo dùng chung (bác sĩ và Head Trainer cùng dùng)
Bảng `Notifications` không có cột "đã gửi", nên dùng cách đơn giản, **không cần cron**:
- Tạo thông báo = `INSERT` dòng với `scheduled_at` (thời điểm được phép hiện).
- API đọc chỉ trả dòng có `scheduled_at <= now()` (dòng `scheduled_at IS NULL` coi như bằng `created_at`).
- FE gọi định kỳ (khoảng 60 giây) để cập nhật chuông thông báo.

| Method | Endpoint | Mô tả |
|---|---|---|
| GET | `/api/notifications?unread_only=true&page=&limit=` | Thông báo đến hạn của user đang đăng nhập, mới nhất trước. Kèm `unread_count` |
| PATCH | `/api/notifications/:id/read` | Đánh dấu đã đọc (chỉ chủ thông báo) |
| POST | `/api/notifications/read-all` | Đánh dấu đã đọc tất cả |

---

## 3. Business rules chung

| Mã | Quy tắc |
|---|---|
| BR-V-01 | Ngựa phải tồn tại và `deleted_at IS NULL`, nếu không trả `404 HORSE_NOT_FOUND`. |
| BR-V-02 | `doctor_id`, `marked_by`, `edited_by` **luôn lấy từ token**. Nếu client gửi các field này thì bỏ qua. |
| BR-V-03 | Mọi API ghi chỉ cho role `VETERINARIAN`, tài khoản `Active`. |
| BR-V-04 | **Không có API xóa** cho `Health_Exams`, `Medical_Records`, `Prescriptions`, `Diet_Records`, `Injury_Markers`. Hồ sơ y tế chỉ tạo và sửa. Sai thì sửa, không xóa. |
| BR-V-05 | Mọi thao tác ghi đều ghi `Audit_Logs` (mục 2.3). |
| BR-V-06 | Bác sĩ vẫn thao tác đầy đủ trên ngựa **đang bị khóa huấn luyện**. Khóa chỉ chặn Flow 2. |
| BR-V-07 | Các field ngoài phạm vi của từng API (vd `id`, `horse_id`, `created_at`) bị bỏ qua khi có trong request, không báo lỗi. |
| BR-V-08 | Bác sĩ **không** sửa `readiness_status`, `current_weight_kg` (do Flow 2 quản lý) và không sửa hồ sơ gốc của ngựa (do Club Manager quản lý, Flow 1). |

---

## 4. Đặc tả chi tiết

## Nhóm A. Khám bệnh (#24, #25, #26)

### A1. Tạo hồ sơ khám bệnh (#24)

**US-V01.** Là bác sĩ thú y, tôi muốn ghi hồ sơ khám với đầy đủ chỉ số sinh hiệu để có dữ liệu sức khỏe theo dõi được theo thời gian.
- Given tôi chọn một ngựa còn hoạt động, When tôi nhập đủ 3 chỉ số bắt buộc (nhiệt độ, nhịp tim, nhịp thở) và lưu, Then hồ sơ được tạo với `doctor_id` là tôi.
- Given chỉ số nằm ngoài khoảng bình thường nhưng vẫn hợp lệ, When tôi lưu, Then hồ sơ vẫn được lưu và hệ thống hiện cảnh báo, không chặn.
- Given tôi nhập nhiệt độ 60, When tôi lưu, Then hệ thống từ chối và chỉ rõ field sai.

**Luồng hoạt động**
1. Bác sĩ chọn ngựa, bấm "Khám mới".
2. Nhập chỉ số, bấm Lưu.
3. BE validate (BR-A1 đến BR-A4), tạo bản ghi, ghi `Audit_Logs`.
4. Trả hồ sơ kèm `alerts`. Nếu có cảnh báo, UI gợi ý "Tạo chẩn đoán" (chức năng #38).

**API**

`POST /api/horses/:horseId/health-exams`

```json
{
  "exam_date": "2026-09-28T09:30:00+07:00",
  "temperature_c": 38.9,
  "heart_rate": 52,
  "respiratory_rate": 18,
  "mucous_membrane_color": "Hồng nhạt",
  "capillary_refill_sec": 2.0,
  "skin_turgor": "Bình thường",
  "jugular_pulse": "Bình thường",
  "digital_pulse": "Bình thường",
  "gut_sounds": "Có",
  "defecation_frequency": "4 lần/ngày",
  "urination_frequency": "5 lần/ngày",
  "body_condition_score": 5.0,
  "hoof_temperature": "Ấm",
  "gait_assessment": "Đi hơi cứng chân trước trái",
  "hematology_result": "",
  "biochemistry_result": "",
  "fecal_test_result": "",
  "notes": ""
}
```

Response `201`: toàn bộ bản ghi + `doctor` (`id`, `name`) + `alerts` + `suggest_medical_record`.

```json
{
  "id": "uuid",
  "alerts": [
    { "field": "temperature_c", "value": 38.9, "normal_range": "37.2 - 38.5", "level": "warning" }
  ],
  "suggest_medical_record": true
}
```

**Business rules**

| Mã | Quy tắc |
|---|---|
| BR-A1 | Bắt buộc: `temperature_c`, `heart_rate`, `respiratory_rate`. Các field còn lại tùy chọn. |
| BR-A2 | Khoảng **cứng** (ngoài khoảng: `400`): `temperature_c` 30.0 đến 45.0; `heart_rate` 10 đến 250 (số nguyên); `respiratory_rate` 3 đến 100 (số nguyên); `capillary_refill_sec` 0 đến 10; `body_condition_score` 1.0 đến 9.0. Field chuỗi tối đa 50 ký tự (các field TEXT không giới hạn cứng). |
| BR-A3 | Khoảng **mềm** (ngoài khoảng: không chặn, chỉ thêm vào `alerts`): `temperature_c` 37.2 đến 38.5; `heart_rate` 28 đến 44; `respiratory_rate` 8 đến 16. Đây là giá trị tham khảo cho ngựa trưởng thành nghỉ ngơi. Đặt thành hằng số cấu hình, bác sĩ hoặc giảng viên có thể yêu cầu đổi. |
| BR-A4 | `exam_date` mặc định là thời điểm hiện tại. Không cho lớn hơn hiện tại quá 5 phút (dung sai đồng hồ). |
| BR-A5 | `suggest_medical_record = true` khi `alerts` không rỗng. Chỉ là gợi ý cho UI. |

**Tác động DB:** `INSERT Health_Exams`, `INSERT Audit_Logs`.

---

### A2. Xem hồ sơ khám và lịch sử sửa (#25)

**US-V02.** Là bác sĩ thú y, tôi muốn xem lại các lần khám của một ngựa và biết ai đã sửa gì để đảm bảo hồ sơ minh bạch.
- Given ngựa có nhiều lần khám, When tôi mở tab hồ sơ khám, Then thấy danh sách mới nhất trước, có đánh dấu lần khám nào đã bị sửa.
- Given một hồ sơ đã bị sửa, When tôi mở lịch sử, Then thấy từng lần sửa với người sửa, thời gian và giá trị cũ / mới của từng field.

**API**

| Method | Endpoint | Mô tả |
|---|---|---|
| GET | `/api/horses/:horseId/health-exams?from=&to=&page=&limit=` | Danh sách, sắp `exam_date` giảm dần. Mỗi dòng: `id`, `exam_date`, `doctor {id, name}`, 3 chỉ số chính, `has_edits` (có log hay không) |
| GET | `/api/health-exams/:id` | Chi tiết đầy đủ + `last_edited_at`, `last_edited_by` (lấy từ log mới nhất, `null` nếu chưa sửa) |
| GET | `/api/health-exams/:id/logs` | Lịch sử sửa, mới nhất trước |

Response `/logs`:

```json
{
  "data": [
    {
      "id": "uuid",
      "edited_by": { "id": "uuid", "name": "BS. Nguyễn Văn A" },
      "edited_at": "2026-09-28T10:15:00Z",
      "changes": [
        { "field": "temperature_c", "old_value": 38.9, "new_value": 38.4 }
      ]
    }
  ]
}
```

**Business rules**
- BR-A6: Tài liệu này chỉ mở quyền đọc hồ sơ khám cho `VETERINARIAN`. Các actor khác đọc theo đặc tả riêng của họ.
- BR-A7: `changes` được dựng bằng cách so sánh `old_data_json` và `new_data_json` (mỗi cái chỉ chứa các field đã đổi, xem A3).

---

### A3. Sửa hồ sơ khám và ghi log (#26)

**US-V03.** Là bác sĩ thú y, tôi muốn sửa hồ sơ khám khi nhập sai mà vẫn giữ được vết sửa để không ai nghi ngờ số liệu.
- Given tôi đổi nhiệt độ từ 38.9 sang 38.4, When tôi lưu, Then hồ sơ được cập nhật và có đúng 1 dòng log ghi giá trị cũ và mới.
- Given tôi bấm lưu mà không đổi gì, When gửi request, Then không tạo dòng log nào.
- Given việc ghi log bị lỗi, When tôi lưu, Then hồ sơ **không** bị thay đổi (rollback).

**Luồng hoạt động**
1. Mở hồ sơ, bấm Sửa, đổi field, bấm Lưu.
2. BE (1 transaction): đọc bản cũ, validate như A1, so sánh từng field.
3. Nếu không có field nào đổi: trả `200 { "changed": false }`, dừng.
4. Nếu có: `UPDATE Health_Exams`, `INSERT Health_Exam_Logs`, `INSERT Audit_Logs`.

**API:** `PATCH /api/health-exams/:id` (gửi field nào đổi field đó)

Response `200`: `{ "changed": true, "exam": { ... }, "alerts": [ ... ] }`

**Business rules**

| Mã | Quy tắc |
|---|---|
| BR-A8 | Không sửa được: `id`, `horse_id`, `doctor_id`. Cho sửa `exam_date` với cùng ràng buộc như lúc tạo. |
| BR-A9 | Log lưu **chỉ các field thay đổi**: `old_data_json` = `{"temperature_c": 38.9}`, `new_data_json` = `{"temperature_c": 38.4}`. `edited_by` lấy từ token. |
| BR-A10 | Mọi `VETERINARIAN` đều sửa được hồ sơ của bác sĩ khác (mặc định D4). Vì vậy log là bắt buộc. |
| BR-A11 | So sánh giá trị theo kiểu dữ liệu, không so chuỗi thô (`38.90` và `38.9` là như nhau). |
| BR-A12 | Không có endpoint xóa hồ sơ khám (BR-V-04). |

**Tác động DB:** `UPDATE Health_Exams`, `INSERT Health_Exam_Logs`, `INSERT Audit_Logs`.

---

## Nhóm B. Chẩn đoán và đơn thuốc (#38, #27, #28)

### B1. Chẩn đoán và phác đồ điều trị (#38, bổ sung)

**US-V04.** Là bác sĩ thú y, tôi muốn ghi chẩn đoán và phác đồ cho một lần khám để đơn thuốc và điểm chấn thương có hồ sơ điều trị gốc để gắn vào.
- Given tôi vừa khám xong, When tôi tạo chẩn đoán gắn với lần khám đó, Then hệ thống lưu và cho tôi kê đơn, đánh dấu chấn thương từ hồ sơ này.
- Given tôi chọn hồ sơ khám của ngựa khác, When tôi lưu, Then hệ thống từ chối.

**API**

| Method | Endpoint | Mô tả |
|---|---|---|
| POST | `/api/horses/:horseId/medical-records` | Body: `health_exam_id?`, `diagnosis` (bắt buộc), `treatment_plan?` |
| GET | `/api/horses/:horseId/medical-records` | Danh sách, mới nhất trước |
| GET | `/api/medical-records/:id` | Chi tiết kèm danh sách `prescriptions` và `injury_markers` gắn với hồ sơ này |
| PATCH | `/api/medical-records/:id` | Sửa `diagnosis`, `treatment_plan` |

**Business rules**

| Mã | Quy tắc |
|---|---|
| BR-B1 | `diagnosis` không rỗng sau khi trim. |
| BR-B2 | Nếu có `health_exam_id` thì lần khám đó phải thuộc **cùng ngựa**, nếu không `400`. |
| BR-B3 | `Medical_Records` không có bảng log và không có `updated_at`. Sửa nội dung chỉ được ghi ở `Audit_Logs` (không kèm giá trị cũ). Hạn chế này đã được chấp nhận (D14). |
| BR-B4 | Mọi `VETERINARIAN` sửa được, không có API xóa. |

**Tác động DB:** `INSERT / UPDATE Medical_Records`, `INSERT Audit_Logs`.

---

### B2. Tạo đơn thuốc (#27)

**US-V05.** Là bác sĩ thú y, tôi muốn kê đơn thuốc gắn với chẩn đoán để việc điều trị có căn cứ và theo dõi được thuốc nào đang dùng.
- Given tôi đang xem một chẩn đoán, When tôi kê đơn với tên thuốc, liều, tần suất, đường dùng, Then đơn được tạo trạng thái `Active`.
- Given ngày kết thúc trước ngày bắt đầu, When tôi lưu, Then hệ thống từ chối.
- Given ngựa đã có đơn `Active` cùng tên thuốc, When tôi kê thêm, Then vẫn lưu được nhưng hệ thống cảnh báo trùng thuốc.

**API:** `POST /api/medical-records/:medicalRecordId/prescriptions`

```json
{
  "drug_name": "Phenylbutazone",
  "dosage": "2g",
  "frequency": "2 lần/ngày",
  "route": "Oral",
  "start_date": "2026-09-28",
  "end_date": "2026-10-05",
  "notes": "Uống sau ăn"
}
```

Response `201`: bản ghi + `warnings` (mảng, có thể rỗng).

**Business rules**

| Mã | Quy tắc |
|---|---|
| BR-B5 | Đơn thuốc **phải gắn hồ sơ chẩn đoán** (đường dẫn có `medicalRecordId`), dù DB cho phép `medical_record_id` NULL. `horse_id` lấy từ hồ sơ chẩn đoán, không nhận từ client. |
| BR-B6 | `drug_name` bắt buộc, tối đa 150 ký tự. `start_date` mặc định là hôm nay. Nếu có `end_date` thì phải `>= start_date`. |
| BR-B7 | Trạng thái ban đầu luôn là `Active`. |
| BR-B8 | `route` khuyến nghị dùng danh sách: `Oral`, `IV`, `IM`, `Topical`, `Other`. BE không ép cứng (cột là VARCHAR tự do). |
| BR-B9 | Cảnh báo (không chặn): nếu ngựa đã có đơn `Active` cùng `drug_name` (không phân biệt hoa thường) và khoảng ngày chồng nhau, thêm vào `warnings`. |

**Tác động DB:** `INSERT Prescriptions`, `INSERT Audit_Logs`.

---

### B3. Cập nhật và kết thúc đơn thuốc (#28)

**US-V06.** Là bác sĩ thú y, tôi muốn chỉnh liều, gia hạn hoặc kết thúc đơn thuốc để đơn phản ánh đúng phác đồ hiện tại.
- Given đơn đang `Active`, When tôi đổi liều hoặc gia hạn `end_date`, Then đơn được cập nhật.
- Given đơn đã `Completed` hoặc `Stopped`, When tôi cố sửa, Then hệ thống từ chối và hướng tôi tạo đơn mới.

**API**

| Method | Endpoint | Mô tả |
|---|---|---|
| PATCH | `/api/prescriptions/:id` | Body tùy chọn: `dosage`, `frequency`, `route`, `end_date`, `notes`, `status` |
| GET | `/api/horses/:horseId/prescriptions?status=Active` | Danh sách đơn của ngựa |

**Business rules**

| Mã | Quy tắc |
|---|---|
| BR-B10 | Chỉ sửa được khi `status = Active`. Đơn `Completed` / `Stopped` là **chỉ đọc hoàn toàn**: trả `409 PRESCRIPTION_CLOSED`. Cần điều chỉnh thì tạo đơn mới. |
| BR-B11 | Không sửa được `drug_name`, `start_date`, `horse_id`, `medical_record_id`. |
| BR-B12 | Chuyển trạng thái chỉ theo chiều `Active` sang `Completed` hoặc `Stopped`. Không quay lại `Active`. |
| BR-B13 | Khi chuyển sang `Completed` / `Stopped`: nếu `end_date` rỗng hoặc lớn hơn hôm nay thì đặt `end_date = hôm nay`. |
| BR-B14 | Cập nhật `updated_at`. Bảng không có log riêng, chỉ ghi `Audit_Logs` (D14). |

**Tác động DB:** `UPDATE Prescriptions`, `INSERT Audit_Logs`.

---

## Nhóm C. Ăn uống (#29, #30)

### C1. Tạo hồ sơ ăn uống (#29)

**US-V07.** Là bác sĩ thú y, tôi muốn chỉ định khẩu phần ăn cho từng ngựa để nhân viên chăm sóc biết cho ăn gì, bao nhiêu.
- Given ngựa chưa có khẩu phần "Yến mạch", When tôi tạo khẩu phần này, Then lưu thành công và ngựa có thể có nhiều dòng khẩu phần cùng lúc (cỏ, ngũ cốc, vitamin).
- Given ngựa đã có khẩu phần "Yến mạch" còn hiệu lực, When tôi tạo thêm "yến mạch" trong khoảng thời gian chồng nhau, Then hệ thống từ chối.

**API:** `POST /api/horses/:horseId/diet-records`

```json
{
  "feed_type": "Yến mạch",
  "quantity_kg": 3.5,
  "feeding_frequency": "3 bữa/ngày",
  "special_instructions": "Ngâm nước 10 phút",
  "effective_date": "2026-09-28",
  "end_date": null
}
```

| Method | Endpoint | Mô tả |
|---|---|---|
| GET | `/api/horses/:horseId/diet-records?active_on=YYYY-MM-DD&include_history=false` | Mặc định trả khẩu phần đang hiệu lực hôm nay. `include_history=true` trả cả bản đã hết hạn |

**Business rules**

| Mã | Quy tắc |
|---|---|
| BR-C1 | `feed_type` bắt buộc (tối đa 100 ký tự). `quantity_kg` bắt buộc, `> 0`. |
| BR-C2 | `effective_date` mặc định là hôm nay. `end_date` rỗng nghĩa là không có ngày kết thúc. Nếu có thì `>= effective_date`. |
| BR-C3 | Mỗi dòng là **một loại thức ăn**, nên một ngựa có nhiều dòng hiệu lực cùng lúc là bình thường. |
| BR-C4 | Không được có 2 dòng **cùng `feed_type`** (so sánh sau khi trim, không phân biệt hoa thường) của **cùng ngựa** với khoảng thời gian chồng nhau. `end_date` rỗng coi như vô hạn. Vi phạm: `409 DIET_OVERLAP`. |
| BR-C5 | Nhân viên chăm sóc (Groom) chỉ **đọc** dữ liệu này, đặc tả riêng của Groom. |

**Tác động DB:** `INSERT Diet_Records`, `INSERT Audit_Logs`.

---

### C2. Cập nhật hồ sơ ăn uống (#30)

**US-V08.** Là bác sĩ thú y, tôi muốn sửa hoặc kết thúc khẩu phần khi tình trạng ngựa thay đổi.
- Given khẩu phần đang hiệu lực, When tôi đặt `end_date`, Then khẩu phần hết hiệu lực từ ngày đó và không còn hiện trong danh sách "đang dùng".
- Given tôi sửa khiến khẩu phần trùng khoảng thời gian với dòng cùng loại khác, When tôi lưu, Then bị từ chối.

**API:** `PATCH /api/diet-records/:id` (body tùy chọn: `feed_type`, `quantity_kg`, `feeding_frequency`, `special_instructions`, `effective_date`, `end_date`)

**Business rules**

| Mã | Quy tắc |
|---|---|
| BR-C6 | Áp lại BR-C1, BR-C2, BR-C4 (kiểm tra trùng **loại trừ chính bản ghi đang sửa**). |
| BR-C7 | Quy ước dùng: `PATCH` dành cho **sửa sai**. Muốn giữ lịch sử thay đổi theo thời gian thì: đặt `end_date` bản cũ (hôm qua) rồi **tạo bản mới** với `effective_date` mới. |
| BR-C8 | Cập nhật `updated_at`. Không có bảng log, chỉ ghi `Audit_Logs`. |

**Tác động DB:** `UPDATE Diet_Records`, `INSERT Audit_Logs`.

---

## Nhóm D. Chấn thương (#31)

### D1. Đánh dấu vị trí chấn thương và theo dõi phục hồi

**US-V09.** Là bác sĩ thú y, tôi muốn đánh dấu vị trí chấn thương trên mô hình cơ thể ngựa và cập nhật diễn biến theo thời gian để theo dõi quá trình phục hồi.
- Given tôi bấm vào một vị trí trên mô hình, When tôi nhập mức độ nghiêm trọng và lưu, Then điểm đánh dấu được tạo với tọa độ và vị trí cơ thể.
- Given vị trí đó đã có điểm cũ, When tôi đánh giá lại (vd chuyển sang `Recovering`), Then hệ thống tạo **bản ghi mới**, bản cũ vẫn còn trong lịch sử.
- Given mức độ là `Severe` mà ngựa chưa bị khóa huấn luyện, When tôi lưu, Then hệ thống gợi ý tôi kích hoạt khóa huấn luyện.

**Luồng hoạt động**
1. Bác sĩ chọn ngựa, mở mô hình (3D hoặc sơ đồ 2D, do FE quyết định).
2. Bấm vị trí: FE lấy `coordinate_x/y/z` và `body_part`.
3. Nhập `severity`, `recovery_status`, mô tả, chọn hồ sơ chẩn đoán liên quan (nếu có). Lưu.
4. BE validate, `INSERT Injury_Markers`, `INSERT Audit_Logs`, trả kèm `suggest_lock`.

**API**

| Method | Endpoint | Mô tả |
|---|---|---|
| POST | `/api/horses/:horseId/injury-markers` | Tạo điểm đánh dấu |
| GET | `/api/horses/:horseId/injury-markers?latest_only=false&body_part=` | Mặc định trả toàn bộ lịch sử, mới nhất trước. `latest_only=true` chỉ trả bản mới nhất của **mỗi `body_part`** (để vẽ tình trạng hiện tại lên mô hình) |

```json
{
  "medical_record_id": "uuid-hoặc-null",
  "body_part": "Chân trước trái",
  "coordinate_x": 0.412,
  "coordinate_y": 0.733,
  "coordinate_z": null,
  "severity": "Moderate",
  "recovery_status": "Active",
  "description": "Sưng gân gấp nông"
}
```

Response `201`: bản ghi + `"suggest_lock": true / false`.

**Business rules**

| Mã | Quy tắc |
|---|---|
| BR-D1 | `body_part` bắt buộc (tối đa 50 ký tự). `severity` bắt buộc, thuộc `Mild / Moderate / Severe`. `recovery_status` mặc định `Active`. |
| BR-D2 | Tọa độ: nếu gửi `coordinate_x` hoặc `coordinate_y` thì **phải gửi cả hai**. `coordinate_z` tùy chọn (`NULL` khi dùng sơ đồ 2D). Giá trị là số, nằm trong khoảng `-999.999` đến `999.999` (giới hạn cột `DECIMAL(6,3)`). Hệ tọa độ do FE quy ước, BE không diễn giải. |
| BR-D3 | Nếu có `medical_record_id` thì hồ sơ đó phải thuộc **cùng ngựa**. |
| BR-D4 | **Chỉ thêm, không sửa, không xóa** (BR-V-04). Mỗi lần đánh giá lại là một bản ghi mới cùng `body_part`, theo dõi diễn biến bằng `marked_at`. |
| BR-D5 | Điểm có `recovery_status = Recovered` là bản mới nhất của vị trí đó thì UI coi như đã hồi phục (không tô màu chấn thương hiện tại), nhưng vẫn nằm trong lịch sử. |
| BR-D6 | `suggest_lock = true` khi `severity = Severe` **và** ngựa chưa bị khóa. Chỉ là gợi ý cho UI, BE **không tự khóa**. |
| BR-D7 | `marked_by` lấy từ token. |

**Tác động DB:** `INSERT Injury_Markers`, `INSERT Audit_Logs`.

---

## Nhóm E. Trạng thái sức khỏe và Khóa huấn luyện (#36, #37, #32, #39)

### E1. Xem sơ đồ sức khỏe toàn đàn (#36, bổ sung)

**US-V10.** Là bác sĩ thú y, tôi muốn xem nhanh toàn bộ đàn ngựa theo 4 trạng thái (Đủ điều kiện, Cần theo dõi, Chấn thương, Cách ly) trên sơ đồ chuồng để biết ngựa nào cần ưu tiên.
- Given đàn có nhiều ngựa, When tôi mở màn tổng quan, Then thấy số lượng theo từng trạng thái và danh sách ngựa kèm vị trí chuồng.
- Given ngựa đang bị khóa huấn luyện, When tôi xem, Then có dấu hiệu khóa (đỏ / cam theo `lock_level`).

**API:** `GET /api/vet/health-overview?section=`

```json
{
  "counts": { "Healthy": 12, "Monitoring": 3, "Injured": 2, "Quarantine": 1 },
  "horses": [
    {
      "id": "uuid",
      "horse_name": "Thunder Bolt",
      "image_url": "https://...",
      "current_status": "Injured",
      "is_training_locked": true,
      "lock_level": "Critical",
      "lock_reason": "Sưng gân chân trước trái",
      "stable_box": { "id": "uuid", "box_code": "A-12", "section": "A" }
    }
  ]
}
```

**Business rules**
- BR-E1: Chỉ tính ngựa `deleted_at IS NULL`.
- BR-E2: Sắp xếp `horses` theo mức ưu tiên: `Injured`, `Quarantine`, `Monitoring`, `Healthy`, cùng nhóm thì theo tên.
- BR-E3: `section` (tùy chọn) lọc theo `Stable_Boxes.section`. Ngựa chưa gán chuồng vẫn trả về với `stable_box = null`.

---

### E2. Cập nhật trạng thái sức khỏe (#37, bổ sung)

**US-V11.** Là bác sĩ thú y, tôi muốn cập nhật trạng thái sức khỏe của ngựa sau khi khám để sơ đồ tổng quan và chủ ngựa luôn thấy thông tin đúng.
- Given ngựa `Healthy`, When tôi đổi sang `Injured`, Then trạng thái cập nhật và hệ thống gợi ý khóa huấn luyện nếu chưa khóa.

**API:** `PATCH /api/horses/:id/health-status` body `{ "current_status": "Injured", "note": "..." }`

Response `200`: `{ "id", "current_status", "suggest_lock": true / false }`

**Business rules**
- BR-E4: Chỉ nhận đúng field `current_status` (thuộc 4 giá trị ở mục 2.7). Không nhận thêm field nào khác của `Horses` (BR-V-08).
- BR-E5: `suggest_lock = true` khi trạng thái mới là `Injured` hoặc `Quarantine` và ngựa chưa khóa.
- BR-E6: Không có ràng buộc cứng giữa `current_status` và `is_training_locked` (hai thao tác độc lập).
- BR-E7: `Audit_Logs` ghi giá trị cũ và mới: `UPDATE_HEALTH_STATUS:{horse_id}:Healthy->Injured`. `note` (nếu có) chỉ ghép vào phần còn chỗ trong 255 ký tự.

**Tác động DB:** `UPDATE Horses.current_status`, `INSERT Audit_Logs`.

---

### E3. Kích hoạt "Khóa huấn luyện" (#32)

**US-V12.** Là bác sĩ thú y, tôi muốn khóa huấn luyện khẩn cấp cho ngựa chấn thương để không ai xếp bài tập nặng cho nó.
- Given ngựa đang tập bình thường, When tôi khóa với mức độ và lý do, Then ngựa hiện cảnh báo (đỏ nếu `Critical`, cam nếu `Warning`) và mọi API tạo lịch tập / giáo án cho ngựa này bị chặn.
- Given tôi khóa, When hoàn tất, Then Head Trainer nhận thông báo kèm lý do.
- Given ngựa đã có lịch tập sắp tới, When tôi khóa, Then tôi thấy danh sách các lịch bị ảnh hưởng để báo Head Trainer xử lý.

**Luồng hoạt động** (1 transaction)
1. Bác sĩ vào chi tiết ngựa, bấm "Khóa huấn luyện", chọn mức độ, nhập lý do.
2. BE `UPDATE Horses SET is_training_locked = TRUE, lock_level, lock_reason`.
3. Tạo `Notifications` cho các Head Trainer.
4. Ghi `Audit_Logs`.
5. Truy vấn các lịch tập sắp tới bị ảnh hưởng và trả về cùng response.

**API:** `PUT /api/horses/:id/training-lock`

```json
{ "lock_level": "Critical", "lock_reason": "Sưng gân chân trước trái, nghi viêm" }
```

Response `200`:

```json
{
  "id": "uuid",
  "is_training_locked": true,
  "lock_level": "Critical",
  "lock_reason": "Sưng gân chân trước trái, nghi viêm",
  "was_locked": false,
  "upcoming_sessions": [
    { "training_schedule_id": "uuid", "event_date": "2026-09-30", "start_time": "06:00", "session_type": "Training" }
  ]
}
```

**Business rules**

| Mã | Quy tắc |
|---|---|
| BR-E8 | `lock_level` bắt buộc, thuộc `Warning / Critical`. `lock_reason` bắt buộc, sau trim không rỗng, tối đa 255 ký tự. |
| BR-E9 | `lock_level` chỉ quyết định **màu cảnh báo** trên UI (đúng ghi chú của DB). **Mọi mức** đều chặn cứng Flow 2 (mặc định D1). |
| BR-E10 | Gọi khi ngựa **đã khóa**: cập nhật lại `lock_level`, `lock_reason` (không lỗi), trả `was_locked = true`. Chỉ gửi thông báo mới nếu mức độ hoặc lý do thực sự thay đổi. |
| BR-E11 | Thông báo gửi cho **tất cả user role `HEAD_TRAINER` đang `Active`** (mặc định D6): `related_table = 'Horses'`, `related_id = horse_id`, `scheduled_at = now`. Nội dung: `Ngựa {horse_name} bị khóa huấn luyện ({lock_level}): {lock_reason}`. |
| BR-E12 | `upcoming_sessions` = các `Training_Schedules` của ngựa mà `Calendar_Events.status = 'Scheduled'`, `event_date >= hôm nay`, `event_type IN ('Training','TrialRun')`. **Bác sĩ không tự hủy** các lịch này. Head Trainer xử lý (mặc định D2). |
| BR-E13 | `Audit_Logs`: `LOCK_TRAINING:{horse_id}:{lock_level}`. |

**Tác động DB:** `UPDATE Horses`, `INSERT Notifications` (nhiều dòng), `INSERT Audit_Logs`.

---

### E4. Mở khóa huấn luyện (#39, bổ sung)

**US-V13.** Là bác sĩ thú y, tôi muốn mở khóa khi ngựa đã hồi phục để Head Trainer xếp lịch tập trở lại.
- Given ngựa đang khóa, When tôi mở khóa kèm lý do, Then cờ khóa được gỡ, Head Trainer nhận thông báo.
- Given ngựa không bị khóa, When tôi gọi mở khóa, Then hệ thống báo ngựa chưa bị khóa.

**API:** `POST /api/horses/:id/training-unlock` body `{ "reason": "Đã hồi phục, siêu âm gân bình thường" }`

Response `200`: `{ "id", "is_training_locked": false, "suggest_status_update": true / false }`

**Business rules**

| Mã | Quy tắc |
|---|---|
| BR-E14 | Chỉ role `VETERINARIAN` được mở khóa (mặc định D3). |
| BR-E15 | Ngựa phải đang khóa, nếu không `409 NOT_LOCKED`. |
| BR-E16 | `reason` bắt buộc. Set `is_training_locked = FALSE`, `lock_reason = NULL`, `lock_level = NULL`. |
| BR-E17 | Thông báo cho tất cả Head Trainer `Active`: `Ngựa {horse_name} đã được mở khóa huấn luyện`. |
| BR-E18 | `Audit_Logs`: `UNLOCK_TRAINING:{horse_id}:{reason}` (cắt đủ 255 ký tự). Vì `Horses` không có cột lưu lý do mở khóa nên đây là nơi duy nhất lưu. |
| BR-E19 | Không tự đổi `current_status`. `suggest_status_update = true` khi trạng thái hiện tại là `Injured` hoặc `Quarantine` để UI nhắc bác sĩ cập nhật (E2). |

**Tác động DB:** `UPDATE Horses`, `INSERT Notifications`, `INSERT Audit_Logs`.

---

## Nhóm F. Lịch chăm sóc định kỳ và thông báo (#33, #34, #35, #40, #41)

### Mô hình dữ liệu cần hiểu trước khi code

- `Periodic_Care_Schedules` = **quy tắc lặp** (vd tiêm phòng mỗi 90 ngày) cộng con trỏ `calendar_event_id` tới **lần đến hạn hiện tại**.
- `Calendar_Events` = **từng lần cụ thể** đã chốt ngày giờ. Các lần trước vẫn nằm lại trong bảng này làm lịch sử.
- `calendar_event_id = NULL` nghĩa là quy tắc đã có nhưng **chưa chốt ngày giờ cụ thể**.
- Một `Calendar_Events` của lịch định kỳ có: `source_table = 'Periodic_Care_Schedules'`, `source_id = id của quy tắc`, `event_type = care_type`.
- Unique index `(source_table, source_id, event_date)`: một quy tắc không có 2 sự kiện cùng ngày.
- **Thứ tự insert:** `Periodic_Care_Schedules.calendar_event_id` là FK tới `Calendar_Events` nên phải `INSERT Calendar_Events` **trước**. `source_id` không phải FK, vì vậy BE **sinh sẵn UUID** cho cả hai bản ghi rồi insert cùng transaction.

### Helper dùng chung: kiểm tra trùng lịch (BR-F1, BR-F2)

Hàm `checkCareConflicts(horseId, doctorId, date, startTime, endTime, excludeEventId)` trả về danh sách sự kiện trùng (rỗng nếu không trùng).

Kiểm tra **cấp ngựa** (một ngựa không thể có 2 việc cùng lúc):

```sql
SELECT ce.* FROM Calendar_Events ce
WHERE ce.horse_id = :horse_id
  AND ce.event_date = :date
  AND ce.status <> 'Cancelled'
  AND ce.event_type NOT IN ('CareTask', 'Rest')
  AND (:exclude_id IS NULL OR ce.id <> :exclude_id)
  AND (ce.start_time IS NULL OR ce.end_time IS NULL
       OR (ce.start_time < :end_time AND ce.end_time > :start_time));
```

Kiểm tra **cấp bác sĩ** (một bác sĩ không thể ở 2 lịch cùng lúc, kể cả ngựa khác):

```sql
SELECT ce.* FROM Calendar_Events ce
JOIN Periodic_Care_Schedules pcs ON pcs.calendar_event_id = ce.id
WHERE pcs.assigned_doctor_id = :doctor_id
  AND ce.event_date = :date
  AND ce.status <> 'Cancelled'
  AND (:exclude_id IS NULL OR ce.id <> :exclude_id)
  AND (ce.start_time < :end_time AND ce.end_time > :start_time);
```

Ghi chú:
- `CareTask` (việc hằng ngày của Groom) và `Rest` **không** tính là xung đột, nếu không lịch bác sĩ sẽ gần như không xếp được.
- Sự kiện khác **không có giờ** (`start_time` hoặc `end_time` NULL) coi như **cả ngày**, trùng với mọi lịch cùng ngựa cùng ngày.
- Lịch bác sĩ luôn bắt buộc có giờ nên kiểm tra cấp bác sĩ luôn so sánh được.
- Có trùng: trả `409 SCHEDULE_CONFLICT` kèm `details.conflicts`: `[{ "event_id", "event_type", "event_date", "start_time", "end_time", "horse_name" }]`.

### Quy tắc sinh thông báo nhắc lịch (dùng cho #33, #40, #41)

- Người nhận: `assigned_doctor_id` của quy tắc.
- `related_table = 'Calendar_Events'`, `related_id = id sự kiện`.
- `scheduled_at` = 08:00 (giờ Việt Nam) của **ngày trước ngày hẹn** (đổi sang UTC để lưu). Nếu thời điểm đó **đã qua**, đặt `scheduled_at = now` để nhắc ngay.
- Nội dung: `Ngày mai ({event_date}) có lịch {care_label} cho ngựa {horse_name} lúc {start_time}.`
- `care_label`: `HoofCheck` = kiểm tra móng, `Deworming` = tẩy giun, `Vaccination` = tiêm phòng, `MedicalCheckup` = khám định kỳ.
- Khi sự kiện bị **hủy / dời / hoàn thành sớm**: xóa các thông báo chưa đến hạn của sự kiện đó:
  `DELETE FROM Notifications WHERE related_table = 'Calendar_Events' AND related_id = :event_id AND scheduled_at > now()`; dời lịch thì tạo lại thông báo mới.

---

### F1. Tạo lịch định kỳ (#33)

**US-V14.** Là bác sĩ thú y, tôi muốn lập lịch kiểm tra móng, tẩy giun, tiêm phòng cho ngựa theo chu kỳ để không bỏ sót.
- Given tôi nhập loại chăm sóc, chu kỳ và ngày đến hạn, When tôi chốt ngày giờ, Then hệ thống tạo lịch trên lịch trung tâm và hẹn thông báo nhắc trước 1 ngày.
- Given khung giờ trùng buổi tập của ngựa đó hoặc lịch khác của tôi, When tôi lưu, Then bị từ chối và thấy lịch nào đang trùng.
- Given tôi chưa muốn chốt giờ, When tôi chọn "chỉ lưu quy tắc", Then quy tắc được lưu, chưa có sự kiện, chưa có thông báo.

**API:** `POST /api/periodic-care-schedules`

```json
{
  "horse_id": "uuid",
  "care_type": "Vaccination",
  "frequency_days": 90,
  "last_done_date": "2026-06-30",
  "next_due_date": "2026-09-28",
  "assigned_doctor_id": "uuid-tùy-chọn",
  "notes": "Vaccine cúm ngựa",
  "book_event": true,
  "start_time": "08:00",
  "end_time": "09:00"
}
```

Response `201`:

```json
{
  "id": "uuid",
  "horse_id": "uuid",
  "care_type": "Vaccination",
  "next_due_date": "2026-09-28",
  "calendar_event_id": "uuid-hoặc-null",
  "event": { "id": "uuid", "event_date": "2026-09-28", "start_time": "08:00", "end_time": "09:00", "status": "Scheduled" }
}
```

**Business rules**

| Mã | Quy tắc |
|---|---|
| BR-F3 | `horse_id`, `care_type` bắt buộc. `care_type` thuộc 4 giá trị (mục 2.7). |
| BR-F4 | `frequency_days` tùy chọn, nếu có thì số nguyên `>= 1`. |
| BR-F5 | `next_due_date`: nếu không gửi mà có cả `last_done_date` và `frequency_days` thì tự tính `last_done_date + frequency_days`. Nếu vẫn không xác định được thì `400`. Phải `>= hôm nay`. |
| BR-F6 | `assigned_doctor_id` mặc định là bác sĩ đang đăng nhập. Nếu truyền người khác, người đó phải là user `Active` role `VETERINARIAN`. |
| BR-F7 | `book_event` mặc định `true`. Khi `true`: bắt buộc `start_time`, `end_time`, và `end_time > start_time`. Khi `false`: lưu quy tắc với `calendar_event_id = NULL`, **không** tạo sự kiện và **không** tạo thông báo. |
| BR-F8 | Khi `book_event = true`, chạy `checkCareConflicts` (cấp ngựa và cấp bác sĩ). Có trùng thì `409 SCHEDULE_CONFLICT`, không ghi gì. |
| BR-F9 | Tạo lịch định kỳ **được phép** với ngựa đang bị khóa huấn luyện (BR-V-06). |
| BR-F10 | Sự kiện: `event_type = care_type`, `title = "{care_label} - {horse_name}"`, `event_date = next_due_date`, `status = 'Scheduled'`, `source_table = 'Periodic_Care_Schedules'`, `source_id = id quy tắc`, `created_by = bác sĩ đang đăng nhập`. |
| BR-F11 | Cho phép nhiều quy tắc cùng `care_type` trên một ngựa (vd nhiều loại vaccine), phân biệt bằng `notes`. |

**Luồng ghi (1 transaction):** sinh UUID cho quy tắc và sự kiện; nếu `book_event`: `INSERT Calendar_Events`, sau đó `INSERT Periodic_Care_Schedules` (`calendar_event_id` = id sự kiện), `INSERT Notifications` (theo quy tắc sinh thông báo), `INSERT Audit_Logs`.

---

### F2. Xem lịch định kỳ (#34)

**US-V15.** Là bác sĩ thú y, tôi muốn xem lịch định kỳ theo tuần / tháng và biết mục nào quá hạn để sắp xếp công việc.
- Given tôi mở lịch tháng, When hệ thống tải, Then thấy các lịch định kỳ của tôi trong tháng đó.
- Given một quy tắc quá ngày đến hạn mà chưa hoàn thành, When tôi xem danh sách, Then mục đó được đánh dấu quá hạn.

**API**

| Method | Endpoint | Mô tả |
|---|---|---|
| GET | `/api/vet/calendar?from=&to=&scope=mine&horse_id=&care_type=&include_context=false` | Các sự kiện lịch định kỳ. `scope=mine` (mặc định) chỉ lịch do tôi phụ trách, `scope=all` tất cả bác sĩ. `include_context=true` trả thêm sự kiện khác của các ngựa đó trong khoảng (huấn luyện, đua, chỉ đọc, đánh dấu `context: true`) để bác sĩ thấy lịch xung quanh |
| GET | `/api/periodic-care-schedules?horse_id=&care_type=&due_within_days=` | Danh sách quy tắc, kèm `is_overdue` |

Mỗi phần tử của `/api/vet/calendar`:

```json
{
  "event_id": "uuid",
  "event_date": "2026-09-28",
  "start_time": "08:00",
  "end_time": "09:00",
  "status": "Scheduled",
  "care_type": "Vaccination",
  "horse": { "id": "uuid", "horse_name": "Thunder Bolt" },
  "schedule_id": "uuid",
  "assigned_doctor": { "id": "uuid", "name": "BS. Nguyễn Văn A" },
  "notes": "Vaccine cúm ngựa"
}
```

**Business rules**
- BR-F12: Lấy sự kiện lịch định kỳ bằng cách join `Calendar_Events` với `Periodic_Care_Schedules` qua `calendar_event_id`, lọc `assigned_doctor_id` theo `scope`.
- BR-F13: `is_overdue = true` khi `next_due_date < hôm nay` **và** (`calendar_event_id IS NULL` **hoặc** sự kiện hiện tại chưa `Completed`).
- BR-F14: "Không trùng lịch" được **đảm bảo lúc tạo / dời lịch** bằng `checkCareConflicts` (F1, F6). Màn xem lịch chỉ hiển thị.
- BR-F15: Ngựa đã xóa mềm không hiện trong lịch.

---

### F3. Thông báo tự động trước 1 ngày (#35)

**US-V16.** Là bác sĩ thú y, tôi muốn được nhắc trước 1 ngày về lịch chăm sóc sắp tới để chuẩn bị dụng cụ và thuốc.
- Given tôi có lịch tiêm phòng vào ngày mai, When đến 08:00 sáng hôm nay, Then chuông thông báo của tôi hiện nhắc lịch.
- Given lịch bị dời sang ngày khác, When tôi xem thông báo, Then không còn nhắc theo ngày cũ mà có nhắc theo ngày mới.

Chức năng này **không có API riêng**. Nó gồm hai phần:
1. **Sinh thông báo**: xảy ra bên trong F1, F5, F6 theo "Quy tắc sinh thông báo nhắc lịch" ở đầu nhóm F.
2. **Đọc thông báo**: dùng dịch vụ chung ở mục 2.8.

**Business rules**
- BR-F16: Không dùng cron. Độ chính xác nhắc phụ thuộc vào FE gọi API đọc định kỳ (khoảng 60 giây).
- BR-F17: Mỗi sự kiện có tối đa 1 thông báo nhắc còn hiệu lực. Dời lịch thì xóa thông báo chưa đến hạn rồi tạo lại.
- BR-F18: Người dùng chỉ đọc và đánh dấu đã đọc thông báo của **chính mình**, thao tác lên thông báo người khác trả `404`.

---

### F4. Đánh dấu hoàn thành lịch định kỳ (#40, bổ sung)

**US-V17.** Là bác sĩ thú y, tôi muốn xác nhận đã thực hiện xong một lần chăm sóc để hệ thống tự tính lần đến hạn kế tiếp.
- Given lịch tiêm phòng 90 ngày, When tôi xác nhận hoàn thành hôm nay, Then `last_done_date = hôm nay`, `next_due_date = hôm nay + 90 ngày` và có sự kiện mới cho lần sau.
- Given ngày kế tiếp bị trùng lịch khác, When hoàn thành, Then lần này vẫn được ghi nhận hoàn thành, lần sau lưu ở dạng "chưa chốt giờ" kèm cảnh báo.
- Given lịch không có chu kỳ (làm một lần), When hoàn thành, Then không tạo lần kế tiếp.

**API:** `POST /api/periodic-care-schedules/:id/complete`

```json
{ "done_date": "2026-09-28", "next_start_time": "08:00", "next_end_time": "09:00" }
```

Tất cả field tùy chọn. `done_date` mặc định là hôm nay và không được ở tương lai. `next_start_time` / `next_end_time` mặc định lấy giờ của sự kiện vừa hoàn thành.

Response `200`:

```json
{
  "schedule": { "id": "uuid", "last_done_date": "2026-09-28", "next_due_date": "2026-12-27", "calendar_event_id": "uuid-hoặc-null" },
  "completed_event_id": "uuid",
  "next_event": { "id": "uuid", "event_date": "2026-12-27", "start_time": "08:00", "end_time": "09:00" },
  "warnings": []
}
```

**Luồng hoạt động** (1 transaction)
1. Đọc quy tắc và sự kiện hiện tại. Nếu sự kiện đã `Completed` thì `409 EVENT_ALREADY_COMPLETED`.
2. Nếu sự kiện hiện tại `Scheduled`: đặt `Calendar_Events.status = 'Completed'`, xóa thông báo nhắc chưa đến hạn của nó. Nếu chưa có sự kiện (quy tắc chưa chốt) hoặc sự kiện đã `Cancelled`: bỏ qua bước này.
3. `last_done_date = done_date`.
4. Nếu `frequency_days` có giá trị: `next_due_date = done_date + frequency_days`. Nếu không: giữ nguyên `next_due_date`, không tạo lần kế tiếp (`next_event = null`).
5. Với trường hợp có chu kỳ: thử tạo sự kiện kế tiếp tại `next_due_date` (kiểm tra trùng lịch như F1).
   - Không trùng và có đủ giờ: `INSERT Calendar_Events` mới, trỏ `calendar_event_id` sang sự kiện mới, tạo thông báo nhắc.
   - Trùng hoặc không xác định được giờ: đặt `calendar_event_id = NULL` (quy tắc chưa chốt), không tạo thông báo, thêm vào `warnings` mã `NEXT_EVENT_NOT_BOOKED` kèm lý do và danh sách `conflicts`. Bác sĩ chốt sau bằng F5.
6. `INSERT Audit_Logs`.

**Business rules**
- BR-F19: Hoàn thành **luôn thành công** (khi qua bước kiểm tra ở mục 1) dù lần kế tiếp không đặt được lịch, để không mất dữ liệu lần chăm sóc đã làm.
- BR-F20: Sự kiện cũ giữ nguyên trong `Calendar_Events` với `status = 'Completed'` làm lịch sử.
- BR-F21: Quy tắc không có chu kỳ sau khi hoàn thành vẫn giữ `calendar_event_id` trỏ tới sự kiện `Completed`, không tạo thêm.

---

### F5. Chốt, dời và hủy lịch định kỳ (#41, bổ sung)

**US-V18.** Là bác sĩ thú y, tôi muốn chốt ngày giờ cho quy tắc chưa có lịch, dời lịch khi bận và hủy khi không cần nữa.
- Given quy tắc chưa chốt giờ, When tôi chốt ngày giờ, Then sự kiện được tạo và thông báo được hẹn.
- Given lịch đã chốt, When tôi dời sang ngày khác, Then sự kiện cập nhật và thông báo cũ được thay bằng thông báo mới.
- Given tôi hủy lịch, When xác nhận, Then sự kiện chuyển `Cancelled`, không còn nhắc, quy tắc quay về trạng thái chưa chốt.

**API**

| Method | Endpoint | Body | Mô tả |
|---|---|---|---|
| POST | `/api/periodic-care-schedules/:id/book` | `event_date?`, `start_time`, `end_time` | Chốt ngày giờ khi `calendar_event_id IS NULL`. `event_date` mặc định `next_due_date` |
| PATCH | `/api/periodic-care-schedules/:id/event` | `event_date?`, `start_time?`, `end_time?` | Dời lịch đã chốt (`status = Scheduled`) |
| POST | `/api/periodic-care-schedules/:id/cancel-event` | (không) | Hủy lịch đã chốt |

**Business rules**

| Mã | Quy tắc |
|---|---|
| BR-F22 | `book`: quy tắc phải đang `calendar_event_id IS NULL`, nếu không `409`. Kiểm tra trùng lịch như F1. Tạo `Calendar_Events` (UUID sinh sẵn), cập nhật `calendar_event_id`, tạo thông báo. Nếu có `event_date` khác `next_due_date` thì cập nhật `next_due_date = event_date`. |
| BR-F23 | `event` (dời): chỉ khi sự kiện đang `Scheduled`. Ngày mới `>= hôm nay`. Kiểm tra trùng lịch với `excludeEventId = sự kiện đang dời`. Cập nhật `event_date` / giờ, đồng bộ `next_due_date = event_date` mới. Xóa thông báo chưa đến hạn cũ và tạo thông báo mới. |
| BR-F24 | `cancel-event`: sự kiện chuyển `Cancelled`, xóa thông báo chưa đến hạn, đặt `Periodic_Care_Schedules.calendar_event_id = NULL`. `next_due_date` giữ nguyên để quy tắc vẫn hiện trong danh sách (và quá hạn thì đánh dấu quá hạn). |
| BR-F25 | **Trường hợp đặc biệt của unique index** `(source_table, source_id, event_date)`: nếu `book` hoặc dời đúng vào ngày đã có một sự kiện `Cancelled` của cùng quy tắc, **cập nhật lại sự kiện cũ** (`status = 'Scheduled'`, giờ mới) thay vì insert mới, để không vi phạm unique. |

---

## 5. Hợp đồng liên flow (dev các flow khác cần biết)

### 5.1 Flow 2 (Head Trainer) phải chặn khi ngựa bị khóa

Mọi API của Flow 2 **tạo hoặc dời** `Training_Plans`, `Training_Schedules` (và `Calendar_Events` tương ứng) cho một ngựa phải gọi trước:

```
assertHorseNotLocked(horseId)
  -> nếu Horses.is_training_locked = TRUE:
       HTTP 409, code = TRAINING_LOCKED,
       details = { lock_level, lock_reason }
```

- Gọi **trước** khi tạo `Calendar_Events`, tránh để lại sự kiện mồ côi.
- Chỉ dùng cờ trong DB làm chuẩn, không dựa vào việc FE ẩn nút.
- Head Trainer nhận thông báo khi ngựa bị khóa / mở khóa (BR-E11, BR-E17) qua dịch vụ thông báo chung.

### 5.2 Flow 4 (Groom)
Groom đọc `Diet_Records` của ngựa (chỉ xem). Danh sách khẩu phần hiệu lực dùng cùng điều kiện với `active_on` ở C1. Lịch việc hằng ngày của Groom nằm ở `Calendar_Events` với `event_type = 'CareTask'` và **không** tính vào kiểm tra trùng lịch của bác sĩ.

### 5.3 Flow 1 và Horse Owner
- Hồ sơ ngựa hiển thị `current_status`, `is_training_locked`, `lock_level`, `lock_reason` do bác sĩ ghi. `PUT /api/horses/:id` của Club Manager **bỏ qua** các field này (BR-23 của Flow 1).
- Horse Owner chỉ xem, và chỉ với ngựa của mình.

### 5.4 Club Manager
Xem toàn bộ thao tác của bác sĩ qua `Audit_Logs` (mọi API ghi ở trên đều ghi log).

---

## 6. Thứ tự triển khai đề xuất

| Bước | Việc | Vì sao đứng ở đây |
|---|---|---|
| 1 | Nền tảng: guard role `VETERINARIAN`, helper `Audit_Logs`, định dạng lỗi, dịch vụ thông báo dùng chung (mục 2.8) | Mọi chức năng sau đều dùng |
| 2 | A1, A2, A3 (#24, #25, #26) | Khám bệnh là gốc của dữ liệu y tế, tự chứa, dễ demo |
| 3 | B1, B2, B3 (#38, #27, #28) | Cần hồ sơ khám để gắn chẩn đoán, đơn thuốc cần chẩn đoán |
| 4 | E3, E4 (#32, #39) cùng `assertHorseNotLocked` | **Chốt sớm** vì Flow 2 phụ thuộc trực tiếp. Phối hợp với dev Flow 2 |
| 5 | E1, E2 (#36, #37) | Trạng thái sức khỏe, gợi ý khóa |
| 6 | C1, C2 (#29, #30) | Độc lập, Groom cần đọc |
| 7 | D1 (#31) | Cần hồ sơ chẩn đoán để gắn (không bắt buộc) |
| 8 | F1, F2, F3 (#33, #34, #35) và helper `checkCareConflicts` | Cần `Calendar_Events` và dịch vụ thông báo |
| 9 | F4, F5 (#40, #41) | Phụ thuộc F1 |

---

## 7. Danh sách kiểm thử (dev tự chạy trước khi bàn giao)

| Nhóm | Ca kiểm thử | Kết quả mong đợi |
|---|---|---|
| Phân quyền | Gọi API ghi bằng token role khác (Groom, Head Trainer, Horse Owner) | `403` |
| Phân quyền | Gọi không token | `401` |
| Ngựa | `horseId` không tồn tại hoặc đã xóa mềm | `404 HORSE_NOT_FOUND` |
| Khám bệnh | Thiếu `temperature_c` | `400` chỉ rõ field |
| Khám bệnh | Nhiệt độ 60 | `400` |
| Khám bệnh | Nhiệt độ 39.2 | `201` kèm `alerts` |
| Khám bệnh | Client gửi `doctor_id` khác | Bị bỏ qua, `doctor_id` = người đăng nhập |
| Sửa khám | Sửa 1 field | 1 dòng log, chỉ chứa field đổi |
| Sửa khám | Gửi lại y nguyên giá trị cũ | `changed:false`, không có log |
| Sửa khám | Ép lỗi ghi log | Hồ sơ không đổi (rollback) |
| Chẩn đoán | `health_exam_id` của ngựa khác | `400` |
| Đơn thuốc | `end_date < start_date` | `400` |
| Đơn thuốc | Sửa đơn `Completed` | `409 PRESCRIPTION_CLOSED` |
| Đơn thuốc | Kết thúc đơn không có `end_date` | `end_date = hôm nay` |
| Ăn uống | Tạo trùng `feed_type` (khác hoa thường) chồng khoảng thời gian | `409 DIET_OVERLAP` |
| Ăn uống | Cùng ngựa, 2 loại thức ăn khác nhau | Cả hai lưu được |
| Chấn thương | Gửi `coordinate_x` mà không có `coordinate_y` | `400` |
| Chấn thương | `severity = Severe`, ngựa chưa khóa | `suggest_lock = true` |
| Chấn thương | Đánh giá lại cùng `body_part` | Có 2 bản ghi, `latest_only=true` trả bản mới |
| Khóa | Khóa hợp lệ | Cờ khóa bật, mỗi Head Trainer `Active` nhận 1 thông báo |
| Khóa | Khóa khi đã khóa, đổi lý do | `was_locked=true`, cập nhật, thông báo mới |
| Khóa | Khóa khi có lịch tập sắp tới | `upcoming_sessions` liệt kê đúng, lịch không bị hủy |
| Khóa | Flow 2 tạo lịch cho ngựa đang khóa | `409 TRAINING_LOCKED` |
| Mở khóa | Mở khi chưa khóa | `409 NOT_LOCKED` |
| Mở khóa | Mở khóa hợp lệ | Cờ tắt, `lock_reason/lock_level = NULL`, Head Trainer nhận thông báo |
| Lịch | Tạo lịch trùng giờ buổi tập cùng ngựa | `409 SCHEDULE_CONFLICT` |
| Lịch | Tạo lịch trùng giờ lịch khác của cùng bác sĩ (ngựa khác) | `409 SCHEDULE_CONFLICT` |
| Lịch | Tạo lịch trùng với việc `CareTask` của Groom | Không xung đột, tạo được |
| Lịch | `book_event=false` | Có quy tắc, `calendar_event_id=NULL`, không có thông báo |
| Thông báo | Lịch ngày mai | `scheduled_at` = ngay bây giờ (đã qua 08:00 hôm trước) |
| Thông báo | Lịch sau 5 ngày | `scheduled_at` = 08:00 (giờ VN) của ngày thứ 4, chưa hiện trước thời điểm đó |
| Thông báo | Dời lịch | Thông báo cũ chưa đến hạn bị xóa, thông báo mới được tạo |
| Hoàn thành | Hoàn thành lịch có chu kỳ | Sự kiện `Completed`, có sự kiện kế tiếp, `next_due_date` đúng |
| Hoàn thành | Ngày kế tiếp bị trùng lịch | Vẫn hoàn thành, `calendar_event_id=NULL`, `warnings` có `NEXT_EVENT_NOT_BOOKED` |
| Hoàn thành | Hoàn thành lần 2 liên tiếp | `409 EVENT_ALREADY_COMPLETED` |
| Hoàn thành | Lịch không có chu kỳ | Không tạo sự kiện kế tiếp |
| Hủy / đặt lại | Hủy rồi `book` lại đúng ngày cũ | Không vi phạm unique, sự kiện cũ được tái sử dụng |
| Audit | Mọi thao tác ghi | Có dòng `Audit_Logs` tương ứng, không vượt 255 ký tự |

---

## 8. Quyết định mặc định đã chọn (nhóm xác nhận hoặc đổi)

| Mã | Vấn đề | Mặc định đã chọn | Nếu đổi thì ảnh hưởng |
|---|---|---|---|
| D1 | `Warning` có chặn Flow 2 không | Mọi mức khóa đều chặn cứng, `lock_level` chỉ đổi màu cảnh báo | Nếu `Warning` chỉ cảnh báo: `assertHorseNotLocked` phải xét `lock_level` |
| D2 | Lịch tập đã xếp trước khi khóa | Không tự hủy, chỉ liệt kê cho Head Trainer xử lý | Nếu tự hủy: E3 phải cập nhật `Training_Schedules` và `Calendar_Events` |
| D3 | Ai được mở khóa | Chỉ `VETERINARIAN` | Thêm `CLUB_MANAGER` nếu cần ghi đè (tránh kẹt khi không còn bác sĩ) |
| D4 | Ai được sửa hồ sơ khám | Mọi `VETERINARIAN` | Nếu chỉ người tạo: thêm kiểm tra `doctor_id = user hiện tại` ở A3 |
| D5 | Log hồ sơ khám lưu gì | Chỉ các field thay đổi | Nếu lưu toàn bộ bản ghi: đổi cách dựng `changes` ở A2 |
| D6 | Thông báo khóa gửi cho ai | Tất cả Head Trainer `Active` | DB không có quan hệ ngựa với Head Trainer, muốn gửi đúng người phải thêm cột hoặc suy ra từ `Training_Plans.created_by` |
| D7 | Trùng lịch loại trừ gì | Bỏ qua `CareTask`, `Rest`. Có cả kiểm tra cấp bác sĩ | Chặt hơn thì lịch bác sĩ khó xếp |
| D8 | Giờ nhắc lịch | 08:00 (giờ VN) ngày trước ngày hẹn | Đổi hằng số cấu hình |
| D9 | Đơn thuốc có bắt buộc gắn chẩn đoán | Bắt buộc ở tầng API (DB vẫn cho NULL) | Nếu cho kê đơn không cần chẩn đoán: đổi đường dẫn API |
| D10 | Xóa hồ sơ y tế | Không cho xóa | Nếu cần xóa: dùng xóa mềm, DB hiện chưa có `deleted_at` ở các bảng này |
| D11 | Theo dõi phục hồi chấn thương | Chỉ thêm bản ghi mới, có `recovery_status` | Nếu cho sửa bản ghi: cần thêm log riêng |
| D12 | Ngưỡng chỉ số bình thường | Giá trị tham khảo (mục A1 BR-A3) | Nhờ người có chuyên môn thú y xác nhận nếu dùng cho báo cáo chính thức |
| D13 | Ai đọc dữ liệu y tế | Tài liệu này chỉ mở quyền đọc `Health_Exams`, `Medical_Records`, `Prescriptions`, `Injury_Markers` cho bác sĩ | Head Trainer, Horse Owner cần xem gì thì thêm ở đặc tả actor đó |
| D14 | Lịch sử sửa của `Medical_Records`, `Prescriptions`, `Diet_Records` | Chỉ ghi `Audit_Logs` (không kèm giá trị cũ) vì DB không có bảng log riêng | Nếu cần đầy đủ như hồ sơ khám: thêm bảng log tương tự `Health_Exam_Logs` |

---

## 9. Prompt mẫu để giao cho AI code

```
Bạn là lập trình viên backend. Hãy triển khai phần "Bác sĩ Thú y" theo đặc tả trong file veterinarian_spec.md.

Bối cảnh:
- Stack BE: [NestJS / Spring Boot / ASP.NET] ; DB: [MySQL / PostgreSQL / SQL Server]
- DB theo file racehorse_schema_v3.dbml. Tên bảng, tên cột giữ nguyên, không tự đổi.
- Đã có sẵn: đăng nhập JWT (payload có user_id, role_name), CRUD ngựa (Flow 1).

Yêu cầu:
1. Làm theo đúng thứ tự ở mục 6. Mỗi lần chỉ làm 1 nhóm chức năng (A, B, C...) rồi dừng để tôi kiểm tra.
2. Tuân thủ mục 2 (quy ước chung) và mục 3 (business rules chung) cho mọi API.
3. Với mỗi chức năng: viết endpoint, validate, xử lý lỗi đúng mã ở mục 2.2, ghi Audit_Logs, dùng transaction khi ghi nhiều bảng.
4. Các quyết định ở mục 8 đã chốt, không tự đổi. Nếu thấy mâu thuẫn giữa đặc tả và DB thì hỏi tôi trước khi làm.
5. Viết test cho các ca ở mục 7 thuộc nhóm đang làm.
6. Không thêm API xóa cho hồ sơ y tế (BR-V-04).
```