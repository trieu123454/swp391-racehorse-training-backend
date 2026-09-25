# Phân tích và tái cấu trúc yêu cầu sản phẩm

## 1. Mục tiêu hệ thống

**Racehorse Training & Management System** là hệ thống vận hành câu lạc bộ đua ngựa. Hệ thống phải quản lý một vòng đời thống nhất:

`Ngựa -> hồ sơ sức khỏe -> giáo án -> lịch tập -> kết quả -> chăm sóc -> chi phí -> báo cáo`

Mục tiêu của bản phân tích này là làm rõ phạm vi, quyền hạn, dữ liệu và thứ tự triển khai trước khi tiếp tục viết FE/BE. Không xây các màn hình chỉ có dữ liệu giả hoặc nút không có API thật.

## 2. Hiện trạng dự án

### Đã có

- Xác thực JWT, refresh token, đăng ký, đăng nhập và phê duyệt tài khoản.
- Năm role: `HEAD_TRAINER`, `VETERINARIAN`, `GROOM`, `HORSE_OWNER`, `CLUB_MANAGER`.
- Database migration chứa các nhóm bảng cho ngựa, huấn luyện, thú y, chăm sóc, vật tư, tài chính và audit log.
- Flow 1 đã có API backend thật:
  - danh sách/chi tiết ngựa;
  - tạo, cập nhật;
  - soft delete;
  - lọc;
  - options chuồng/chủ sở hữu;
  - upload ảnh private và signed URL;
  - giới hạn dữ liệu Horse Owner ở backend.
- FE đã có dashboard và các màn hình Flow 1.

### Chưa có

- Flow 2 chưa có controller/service/API/frontend nghiệp vụ.
- Flow 3 chưa có controller/service/API/frontend nghiệp vụ.
- Flow 4 và Flow 5 chưa có triển khai thực tế.
- Các role Veterinarian và Groom hiện mới có route/auth, chưa có workspace nghiệp vụ.
- Chưa có realtime telemetry nhịp tim/vận tốc.
- Chưa có notification delivery thực tế (chỉ có bảng dữ liệu).
- Chưa có API báo cáo tài chính, audit log và dashboard KPI.
- Race history của Horse Owner còn phụ thuộc Flow 5.

### Vấn đề đang gặp

1. Tên yêu cầu nói Firebase nhưng backend hiện đã chọn Supabase Storage. Đây là khác biệt cần chốt; không nên để FE gọi Firebase trong khi BE cấp signed URL Supabase.
2. Schema gốc dùng ID chuỗi và user nhiều role, database thực tế dùng user ID số và một role chính. Tài liệu API hiện tại phải được xem là hợp đồng thực tế.
3. Bảng database đã có nhiều module nhưng bảng không đồng nghĩa với chức năng đã hoàn thành. Mỗi flow vẫn cần API, permission, validation, transaction và test.
4. Dashboard không được hiển thị card chức năng nếu chưa có API tương ứng. Card đó chỉ nên là trạng thái `chưa triển khai`, không phải nút giả.
5. Các trường vận hành như `current_status`, `is_training_locked`, readiness và cảnh báo không được cập nhật tùy ý từ form hồ sơ ngựa. Chúng phải do module sức khỏe/huấn luyện quản lý.
6. Quyền phải được kiểm tra tại backend. FE chỉ hỗ trợ trải nghiệm, không phải lớp bảo mật.

## 3. Nguyên tắc nghiệp vụ nền tảng

### BR-01: Tài khoản và phê duyệt

- Mọi API nghiệp vụ yêu cầu access token hợp lệ.
- Chỉ tài khoản `APPROVED` và chưa soft delete được sử dụng nghiệp vụ.
- Club Manager phê duyệt tài khoản nhân sự.
- Role không được tự nâng quyền từ FE.
- Mọi thay đổi role phải có lịch sử và người duyệt.

### BR-02: Phân quyền dữ liệu

- `CLUB_MANAGER`: quản lý toàn bộ dữ liệu thuộc câu lạc bộ, tùy module.
- `HEAD_TRAINER`: đọc dữ liệu ngựa và vận hành huấn luyện; không sửa hồ sơ y tế hoặc danh mục quyền.
- `VETERINARIAN`: đọc hồ sơ ngựa và quản lý khám bệnh, điều trị, thuốc, khóa huấn luyện.
- `GROOM`: chỉ xem dữ liệu cần cho chăm sóc và cập nhật task/sự cố/vật tư được giao.
- `HORSE_OWNER`: chỉ đọc ngựa có `owner_id = current_user_id` và các dữ liệu được phép công khai cho chủ ngựa.
- Mọi truy vấn phải áp dụng scope theo role tại service/query backend.

### BR-03: Trạng thái ngựa

`current_status` là trạng thái sức khỏe vận hành, không phải trường nhập tự do trong hồ sơ cơ bản. Bộ giá trị chuẩn:

- `Healthy`
- `Under Observation`
- `Injured`
- `Quarantine`
- `Sick`

`readiness_status` là trạng thái sẵn sàng thi đấu, tách khỏi tình trạng sức khỏe. Không tự suy diễn readiness chỉ từ một checkbox FE.

### BR-04: Khóa huấn luyện

- `is_training_locked = true` phải có `lock_reason` và người tạo lệnh.
- Veterinarian được tạo khóa y tế khẩn cấp.
- Head Trainer không được xếp bài tập nặng cho ngựa đang bị khóa.
- Chỉ người có quyền phù hợp mới được gỡ khóa; phải ghi audit log.
- Lịch tập đã tồn tại khi phát sinh khóa phải được đánh dấu cần rà soát, không tự ý xóa.

### BR-05: Chuồng và sức chứa

- Mỗi ngựa có tối đa một chuồng hiện hành.
- Chỉ chuồng `Available` còn sức chứa mới được chọn.
- Kiểm tra sức chứa phải nằm trong transaction và khóa bản ghi chuồng khi ghi.
- Không tin vào dropdown FE để bảo vệ sức chứa.

### BR-06: Ảnh và tệp

- Storage private; FE không giữ secret storage.
- Chỉ tài khoản đã xác thực và đúng quyền mới upload.
- Chỉ chấp nhận JPG/JPEG, PNG, WebP, tối đa 5 MiB.
- DB lưu object path lâu dài; FE xin signed URL mới khi hiển thị.
- Upload lỗi không được ghi dở hồ sơ.
- Xóa/thay ảnh không được làm mất lịch sử nếu chưa có policy lưu trữ rõ ràng.

### BR-07: Lịch sử và audit

- Không hard delete ngựa, hồ sơ y tế, lịch tập hoặc kết quả thi đấu.
- Các thao tác tạo/sửa/xóa/khóa/mở khóa/phê duyệt phải có audit trail.
- Các số liệu báo cáo phải truy nguyên được về bản ghi nguồn.

### BR-08: Thời gian và trạng thái

- Lưu timestamp ở UTC; hiển thị theo timezone câu lạc bộ.
- Lịch tập không được xếp trong quá khứ nếu không phải thao tác ghi nhận kết quả.
- Chuyển trạng thái phải kiểm tra trạng thái hiện tại và quyền của actor.

## 4. Ma trận quyền mục tiêu

| Module | Club Manager | Head Trainer | Veterinarian | Groom | Horse Owner |
|---|---|---|---|---|---|
| Hồ sơ ngựa | CRUD hồ sơ, soft delete | Đọc | Đọc | Đọc tối thiểu | Đọc ngựa sở hữu |
| Giáo án | Đọc/báo cáo | CRUD giáo án | Đọc cảnh báo | Đọc phần được giao | Đọc lịch liên quan |
| Lịch tập | Giám sát | CRUD/phân công | Chặn hoặc cảnh báo y tế | Xác nhận task được giao | Đọc |
| Metrics buổi tập | Đọc báo cáo | Ghi/đánh giá | Đọc chỉ số sức khỏe | Ghi dữ liệu được giao | Đọc tổng hợp |
| Hồ sơ y tế | Đọc báo cáo | Đọc cảnh báo | CRUD chuyên môn | Đọc hướng dẫn cần thiết | Đọc phần được phép |
| Khóa huấn luyện | Giám sát | Không tự gỡ khóa y tế | Tạo/gỡ theo quy trình | Đọc | Đọc lý do/trạng thái |
| Chăm sóc hằng ngày | Giám sát | Đọc | Đọc cảnh báo | CRUD task/sự cố được giao | Đọc tổng hợp |
| Vật tư | CRUD/duyệt | Đọc nhu cầu | Đề xuất thuốc | Đề xuất cấp phát | Không |
| Thi đấu | Báo cáo/giám sát | Đăng ký | Xác nhận đủ điều kiện | Không | Đọc thành tích |
| Báo cáo/audit | CRUD/xem | Báo cáo huấn luyện | Báo cáo y tế | Báo cáo task | Báo cáo ngựa sở hữu |

## 5. Phân chia flow và use case

## Flow 1 — Quản lý hồ sơ & lý lịch ngựa

**Mục tiêu:** tạo một nguồn dữ liệu ngựa chuẩn để mọi flow khác tham chiếu.

### Use case

- `F1-UC01` Club Manager tạo hồ sơ ngựa.
- `F1-UC02` Club Manager/Head Trainer xem danh sách và chi tiết.
- `F1-UC03` Club Manager cập nhật thông tin cơ bản.
- `F1-UC04` Club Manager soft delete.
- `F1-UC05` Club Manager/Head Trainer tìm kiếm và lọc.
- `F1-UC06` Horse Owner xem ngựa thuộc sở hữu.
- `F1-UC07` Hệ thống upload và hiển thị ảnh hồ sơ.

### Ràng buộc

Hồ sơ cơ bản chỉ sửa tên, giống, năm sinh, pedigree, ảnh, chuồng và owner. Không sửa từ màn hình này: status sức khỏe, readiness, khóa huấn luyện, metrics hoặc lịch sử.

### Tiêu chí hoàn thành

- API và FE dùng response thật, không dùng mock/localStorage.
- Có test quyền manager/trainer/owner.
- Có test chuồng đầy, owner không hợp lệ, owner truy cập ngựa người khác, soft delete.
- Form giữ nguyên dữ liệu khi lưu lỗi.

## Flow 2 — Lập & thực hiện giáo án huấn luyện

**Mục tiêu:** biến mục tiêu huấn luyện thành kế hoạch, lịch tập và kết quả có thể truy nguyên.

### Use case

- `F2-UC01` Head Trainer tạo giáo án theo giai đoạn.
- `F2-UC02` Head Trainer sửa/hủy giáo án khi chưa bắt đầu.
- `F2-UC03` Head Trainer tạo lịch tập từ giáo án.
- `F2-UC04` Head Trainer phân công Groom và track surface.
- `F2-UC05` Groom xem task được giao và xác nhận buổi tập đã thực hiện.
- `F2-UC06` Ghi metrics: cân nặng, nhịp tim, tốc độ, stamina, cảnh báo chấn thương.
- `F2-UC07` Head Trainer đánh giá và ghi nhận xét sau buổi tập.
- `F2-UC08` Hệ thống chặn hoặc cảnh báo lịch tập khi ngựa bị khóa y tế.
- `F2-UC09` Người có quyền xem dashboard tiến độ và biểu đồ.

### Business rules

- Ngựa bị khóa huấn luyện không được xếp bài tập nặng.
- Một lịch tập chỉ có một trạng thái hiện hành: `Scheduled`, `Completed`, `Cancelled`.
- Chỉ lịch `Completed` mới được chốt metrics và nhận xét cuối buổi.
- Sửa giáo án/lịch sau thời hạn phải yêu cầu lý do và ghi log.
- Không xóa metrics đã chốt; sửa phải tạo audit/version.
- Dữ liệu telemetry realtime nếu chưa có nguồn thật phải hiển thị là `Unavailable`, không tạo số giả.

## Flow 3 — Quản lý y tế & xử lý chấn thương

**Mục tiêu:** bảo vệ sức khỏe ngựa và kiểm soát an toàn trước khi huấn luyện/thi đấu.

### Use case

- `F3-UC01` Veterinarian xem sơ đồ sức khỏe toàn đàn.
- `F3-UC02` Veterinarian ghi phiếu khám.
- `F3-UC03` Veterinarian tạo chẩn đoán, treatment plan và prescription.
- `F3-UC04` Veterinarian đánh dấu vị trí injury marker.
- `F3-UC05` Veterinarian tạo lệnh khóa huấn luyện khẩn cấp.
- `F3-UC06` Veterinarian cập nhật tiến triển và gỡ khóa theo điều kiện.
- `F3-UC07` Hệ thống tạo notification cho lịch chăm sóc định kỳ.
- `F3-UC08` Head Trainer/Groom nhận cảnh báo ở mức cần thiết.
- `F3-UC09` Horse Owner xem thông tin y tế được phép chia sẻ.

### Business rules

- Mỗi chẩn đoán phải gắn với ngựa và người thực hiện.
- Prescription có khoảng thời gian và trạng thái; không hiển thị thuốc nhạy cảm cho role không được phép.
- Gỡ khóa phải có lý do, người thực hiện và điều kiện xác nhận.
- Không cho đánh dấu `Healthy` nếu còn trạng thái cách ly/chấn thương chưa được xử lý.
- Medical record không hard delete.

## Flow 4 — Chăm sóc chuồng trại & dinh dưỡng

**Phân loại:** Optional nhưng là phần cần thiết để dữ liệu Flow 2/3 có người thực thi.

### Use case

- `F4-UC01` Groom xem phân bổ chuồng và lịch công việc trong ngày.
- `F4-UC02` Groom xem diet record có hiệu lực.
- `F4-UC03` Groom xác nhận Feeding/Cleaning/Bathing/IceBath.
- `F4-UC04` Groom báo sự cố kèm ảnh.
- `F4-UC05` Manager xem, phân công và đóng sự cố.
- `F4-UC06` Groom đề xuất cấp vật tư.
- `F4-UC07` Manager duyệt supply request và cập nhật tồn kho.

### Business rules

- Chỉ task được giao mới được Groom xác nhận.
- Mỗi task trong một ngày chỉ được hoàn thành một lần, nhưng cho phép sửa có audit.
- Sự cố `Pending` phải có người xử lý; sự cố nghiêm trọng tạo notification.
- Không cho quantity tồn kho âm.
- Mọi thay đổi diet đang hiệu lực phải có ngày bắt đầu/kết thúc rõ ràng.

## Flow 5 — Đăng ký thi đấu & báo cáo thành tích

**Phân loại:** Optional, triển khai sau Flow 1-3.

### Use case

- `F5-UC01` Head Trainer xem danh sách giải phù hợp.
- `F5-UC02` Head Trainer đăng ký ngựa.
- `F5-UC03` Hệ thống kiểm tra readiness, khóa huấn luyện và điều kiện y tế.
- `F5-UC04` Manager xác nhận/giám sát đăng ký.
- `F5-UC05` Ghi kết quả, vị trí và tiền thưởng.
- `F5-UC06` Horse Owner xem lịch sử thi đấu và báo cáo thành tích.

### Business rules

- Không đăng ký ngựa đã bị khóa hoặc chưa đủ điều kiện sức khỏe.
- Không tạo trùng entry cùng ngựa/cùng giải.
- Kết quả chỉ được chốt bởi role được cấp quyền.
- Prize amount phải không âm và có currency/đơn vị rõ ràng.

## 6. User stories ưu tiên

### Epic A — Nền tảng và dữ liệu ngựa

- Là Club Manager, tôi muốn phê duyệt tài khoản để chỉ người hợp lệ truy cập dữ liệu.
- Là Club Manager, tôi muốn tạo hồ sơ ngựa đầy đủ để các module khác dùng cùng một nguồn dữ liệu.
- Là Head Trainer, tôi muốn tìm và lọc ngựa để lập kế hoạch phù hợp.
- Là Horse Owner, tôi muốn chỉ thấy ngựa của mình để bảo mật thông tin.

### Epic B — Huấn luyện

- Là Head Trainer, tôi muốn chia giáo án theo giai đoạn để theo dõi mục tiêu dài hạn.
- Là Head Trainer, tôi muốn phân công lịch tập cho Groom để công việc hàng ngày rõ ràng.
- Là Groom, tôi muốn xác nhận buổi tập và ghi nhận chỉ số đã thực hiện.
- Là Head Trainer, tôi muốn nhận cảnh báo thể lực để điều chỉnh giáo án trước khi xảy ra chấn thương.

### Epic C — Y tế

- Là Veterinarian, tôi muốn ghi hồ sơ khám và điều trị để có lịch sử y tế đáng tin cậy.
- Là Veterinarian, tôi muốn khóa huấn luyện ngay khi phát hiện nguy cơ để bảo vệ ngựa.
- Là Head Trainer, tôi muốn thấy cảnh báo khóa để không xếp bài tập nguy hiểm.
- Là Horse Owner, tôi muốn xem trạng thái sức khỏe được phép chia sẻ.

### Epic D — Chăm sóc và vận hành

- Là Groom, tôi muốn xem task theo ngày để không bỏ sót việc chăm sóc.
- Là Groom, tôi muốn báo sự cố kèm ảnh để người phụ trách xử lý nhanh.
- Là Manager, tôi muốn duyệt cấp vật tư để kiểm soát chi phí và tồn kho.

### Epic E — Thi đấu và báo cáo

- Là Head Trainer, tôi muốn chọn ngựa đủ điều kiện để đăng ký giải.
- Là Horse Owner, tôi muốn xem thành tích và chi phí của ngựa mình sở hữu.
- Là Manager, tôi muốn xem báo cáo tổng quan và audit log để quản trị câu lạc bộ.

## 7. Định hướng kiến trúc làm lại

Backend tạo module cạnh `horse` theo feature:

```text
training/
  controller/ dto/ service/ repository/ exception/
medical/
  controller/ dto/ service/ repository/ exception/
care/
  controller/ dto/ service/ repository/ exception/
race/
  controller/ dto/ service/ repository/ exception/
reporting/
  controller/ dto/ service/ repository/ exception/
notification/
  service/ repository/
```

Các nguyên tắc:

- Controller mỏng; nghiệp vụ nằm trong service transaction.
- Mỗi command có request DTO validation và response DTO ổn định, không trả `Map<String,Object>` cho toàn bộ module mới.
- Tách query read model khỏi command write khi dashboard cần tổng hợp.
- Dùng policy/service để kiểm tra actor và data scope.
- Có migration mới cho thay đổi schema; không sửa migration đã chạy.
- Mỗi endpoint mới phải có test permission, validation, happy path và conflict.

Frontend tổ chức theo feature:

```text
app/
  dashboard/[role]/
  horses/
  training/
  medical/
  care/
  races/
  reports/
components/
  dashboard/
  horses/
  training/
  medical/
  care/
lib/
  api.ts
  horses.ts
  training.ts
  medical.ts
  care.ts
  races.ts
```

Dashboard theo role phải hiển thị module thật đã có API. Module chưa triển khai chỉ hiển thị trạng thái rõ ràng, không dẫn người dùng tới màn hình rỗng.

## 8. Thứ tự triển khai đề xuất

1. **Ổn định nền tảng:** chốt Supabase Storage thay Firebase, chuẩn hóa DTO/HTTP errors, role policy, audit helper và test fixture.
2. **Hoàn thiện Flow 1:** thêm test backend/FE, owner confirmation, empty/error states, signed image, soft delete và acceptance test.
3. **Flow 3 trước Flow 2:** triển khai medical status/lock vì Flow 2 phải phụ thuộc điều kiện an toàn y tế.
4. **Flow 2:** training plan, schedule, assignment, metrics, review và rule khóa huấn luyện.
5. **Flow 4:** task hàng ngày, incident, diet và inventory.
6. **Flow 5:** race catalog, eligibility, entries, results và owner history.
7. **Reporting/audit/notifications:** dashboard tổng hợp, notification delivery và audit viewer sau khi dữ liệu nguồn ổn định.

## 9. Định nghĩa hoàn thành cho một flow

Một flow chỉ được gọi là hoàn thành khi có đủ:

- use case và actor rõ ràng;
- business rule có mã và được kiểm tra ở backend;
- migration/schema phù hợp;
- API request/response và permission;
- FE loading, empty, validation, success, conflict, forbidden, network error;
- audit/history cho thao tác thay đổi dữ liệu;
- unit/integration tests;
- dữ liệu thật hoặc trạng thái chưa triển khai được nói rõ;
- không còn nút dẫn tới chức năng không tồn tại.

## 10. Kết luận

Dự án không nên làm lại toàn bộ từ đầu. Nền tảng auth, database và Flow 1 backend là phần có thể giữ lại. Phần cần làm lại là cách chia module và thứ tự triển khai: hoàn thiện contract và test của Flow 1, xây Medical để tạo safety gate, sau đó xây Training. Dashboard chỉ là lớp hiển thị theo capability, không phải nơi che giấu các module chưa có nghiệp vụ.
