# Giáo án, lịch tập và giả lập cảm biến

## Luồng đã triển khai

1. Tạo giai đoạn riêng cho ngựa: ngày bắt đầu/kết thúc, cự ly, phút tập mỗi tuần, mặt sân và cường độ. Giai đoạn của cùng ngựa không được chồng ngày.
2. Tạo lịch từng ngày, chọn Training/TrialRun/Rest, cự ly và cường độ riêng cho buổi. Buổi vận động cần giáo án. Khung giờ giới hạn thời điểm bắt đầu và thời gian còn lại tối đa; thời lượng mô phỏng được tính theo cự ly và khả năng tốc độ của cường độ.
3. Phân công Groom; có thể lưu lịch chưa phân công, nhưng chỉ bắt đầu sau khi giao Groom đủ điều kiện. Kiểm tra trùng lịch ngựa/Groom vẫn áp dụng.
4. Mở giả lập đúng khung giờ Asia/Ho_Chi_Minh. Một phiên thuộc một ngựa và một buổi; mở lại cùng buổi trả phiên đang chạy. Tập nhóm mở từ 1 đến 5 phiên riêng trong cùng giao dịch, mỗi ngựa gắn đúng lịch và giáo án. Nếu một buổi không đủ điều kiện, cả nhóm không được mở.
5. Thu dữ liệu theo pha khởi động, vận động chính, giảm cường độ và hồi phục. Backend tạo và lưu mẫu; client không quyết định kết quả tổng kết.
6. Hoàn thành thời lượng mô phỏng thì lưu kết quả và chuyển lịch/sự kiện sang Completed, có thể sớm hơn giờ kết thúc đã đặt nếu đạt cự ly trong thời gian ngắn hơn. Groom không thể đóng buổi đang thu dữ liệu. Tải lại trang khôi phục được phiên.
7. Hủy lịch/khóa ngựa sẽ dừng phiên, lưu thời điểm và lý do, bổ sung mẫu đúng thời điểm dừng và lưu tổng kết ngay trong cùng giao dịch. Cự ly, tốc độ trung bình và nhịp tim tối đa của phiên gián đoạn được đưa vào phân tích; điểm hồi phục để trống vì chưa hoàn tất pha hồi phục. Các lần giả lập trước vẫn xem được trong báo cáo nếu tập lại.
8. Tab Phân tích tập luyện có khối lượng tuần, mức đạt cự ly, lịch sử chạy thử, chuỗi cảm biến và nhận xét Head Trainer.

## Snapshot và sửa giáo án

- Lưu mục tiêu, giai đoạn, khoảng ngày, khối lượng tuần, mặt sân, cự ly và cường độ cùng lịch lúc tạo. Sửa giáo án chỉ ảnh hưởng lịch tạo tiếp theo; lịch đã tạo giữ mục tiêu riêng.
- Khoảng ngày giáo án mới phải bao gồm mọi lịch chưa hủy đã gắn. Hạn sửa và quy tắc giữ giáo án đã có lịch vẫn áp dụng.
- V23 backfill lịch cũ từ giáo án hiện tại. Không thể khôi phục phiên bản lịch sử chưa từng được lưu.

## Công thức và nguồn dữ liệu

- Phiên mới dùng model_version=5. Tốc độ đỉnh theo cường độ là Low 22, Medium 40, High 65 km/h, điều chỉnh theo stamina và mặt sân; tốc độ thực thay đổi theo pha khởi động, vận động chính, giảm cường độ và hồi phục. Mốc High khoảng 40 mph, phù hợp với tốc độ đua ngựa Thoroughbred được Royal Veterinary College mô tả là vượt 40 mph. Thời lượng ngắn nhất để đạt cự ly được tính theo tốc độ này; nếu khung lịch còn lại không đủ, phiên chạy đến giờ kết thúc lịch và kết quả ghi nhận cự ly thiếu. Phiên model cũ đang chạy có thể được chạy lại theo model mới; lần cũ được lưu thành một lần gián đoạn trước khi mở phiên mới.
- Nhịp tim tăng theo tải và giảm có độ trễ khi hồi phục. Normal/Fatigue minh họa phản ứng tải bình thường hoặc tải cao/hồi phục chậm.
- Chu kỳ lưu mẫu: max(5, ceil(duration_seconds/600)) giây, cộng mẫu kết thúc. Seed và thông số nền được lưu để tái tạo nhất quán.
- Cự ly dùng tích phân hình thang tốc độ; tốc độ trung bình dùng cùng thời lượng. Nhịp tim/huyết áp tối đa lấy từ mẫu đã lưu.
- Cân nặng lấy từ hồ sơ lúc mở phiên. Stamina mới là điểm hồi phục ước tính, không phải phép đo thể lực đã xác nhận. Hệ số và công thức là giả định đồ án, không dùng cho quyết định y tế.
- Vượt ngưỡng cấu hình thì thông báo Vet ngay khi backend ghi nhận mẫu, gồm mẫu bổ sung lúc gián đoạn. Thông báo liên kết Race_Simulations và chỉ gửi một lần cho mỗi Vet/phiên. Kết quả tổng kết có has_injury_alert; gợi ý trên báo cáo cần Head Trainer duyệt, không tự sửa giáo án.

## Xử lý nền và phân tích

- Worker mỗi 15 giây (app.head-trainer.session-maintenance-ms) cập nhật mẫu, hoàn tất phiên khi hết thời lượng mô phỏng, đánh dấu Missed cho buổi vận động Scheduled đã qua giờ kết thúc. Profile test không chạy worker.
- Worker duy trì vòng đời phiên/lịch đã được tạo hợp lệ, kể cả khi người tạo không còn hoạt động. Các API của người dùng vẫn kiểm tra quyền Head Trainer. Worker cũng bổ sung tổng kết còn thiếu cho các phiên gián đoạn cũ.
- Khối lượng thực hiện cộng thời gian mô phỏng mọi lần, gồm phiên bị dừng; thời lượng này có thể ngắn hơn khung lịch. Nghỉ không tính tải vận động; buổi không có cảm biến không tự được coi là đã tập đủ thời lượng.
- Mục tiêu tuần phân bổ theo số ngày giai đoạn trong khoảng báo cáo. Nếu cùng giáo án có nhiều phiên bản mục tiêu trong tuần, dùng snapshot buổi sớm nhất trong tuần.
- Chạy thử so với lần hoàn thành trước trong khoảng báo cáo có cùng cự ly, cường độ và mặt sân. Thời lượng/tốc độ gồm khởi động và hồi phục, không phải thành tích thi đấu.
- Nhận xét lưu trên buổi tập; gợi ý dựa trên mức đạt mục tiêu và cảnh báo, Head Trainer quyết định điều chỉnh.

## API và migration

Giữ tên API race-simulations để tương thích; phiên mới là luyện tập, không tạo races hoặc horse_race_entries.

- POST /api/head-trainer/race-simulations: horse_ids một UUID, training_schedule_id và scenario (Normal/Fatigue).
- GET /api/head-trainer/race-simulations/active: phiên đang chạy gần nhất hoặc {}.
- GET /api/head-trainer/race-simulations/active-sessions: {data: [...]} chứa tất cả phiên đang chạy để khôi phục việc theo dõi nhiều ngựa.
- POST /api/head-trainer/race-simulations/group: {sessions: [{horse_ids: [UUID], training_schedule_id: UUID, scenario: "Normal"}]} tạo/mở lại tối đa 5 phiên, không lặp ngựa hoặc lịch.
- GET /api/head-trainer/race-simulations/{id}: trạng thái, mẫu và kết quả.
- POST /api/head-trainer/race-simulations/{id}/finish: body {}; phiên chưa đủ thời gian trả SESSION_NOT_FINISHED. Phiên hoàn tất/gián đoạn trả trạng thái đã lưu, không tạo lại tổng kết. GET chi tiết và worker cũng hoàn tất phiên khi đủ thời gian, nên đóng trình duyệt không làm mất kết quả.
- GET /api/horses/{horseId}/training-analysis?from=YYYY-MM-DD&to=YYYY-MM-DD: chênh lệch tối đa 366 ngày.
- PATCH /api/training-schedules/{id}: trainer_review để lưu nhận xét buổi đã kết thúc/dừng.

Migration mới: V21 (chuỗi cảm biến), PostgreSQL V22 (RLS), V23 (snapshot, mục tiêu buổi, kết quả hồi phục). Không sửa migration đã chạy. Chưa áp dụng migration lên database trong phiên sửa mã; Flyway cần chạy chúng khi khởi động backend.

V25__training_simulation_speed_precision.sql nâng độ chính xác base_max_speed_kmh lên DECIMAL(8,4) để buổi tập chậm vẫn bám cự ly.

## Kiểm tra

- FE: 8 test đăng nhập đạt, gồm refresh đồng thời, phiên không ghi nhớ, xác thực role từ server và tách phiên giữa các tab. `npm.cmd run typecheck`, `npm.cmd run lint` và production build đạt.
- Fixture MockMvc Vet dùng đúng quyền VETERINARIAN; token của tài khoản đã xóa phải trả 401. Test JWT kiểm tra lại role và trạng thái hiện tại trong database.
- Test migration kiểm tra đủ bảng và độ chính xác tốc độ. Test Groom phân biệt sự cố thường/khẩn cấp và từ chối ảnh ngoài hoặc ảnh do Groom khác upload; test Storage kiểm tra thời điểm upload UTC.
- Backend mặc định: `mvn test` đạt 106 test; 41 test PostgreSQL opt-in được bỏ qua.
- PostgreSQL: 15 test Head Trainer và 27 test Vet đều đạt. Bài kiểm tra mở/kết thúc đồng thời dùng lịch một phút để giới hạn số mẫu khi chạy qua pooler từ xa. Test đọc kết nối từ `application-local.properties`, tạo schema ngẫu nhiên riêng, xác minh search path rồi mới migrate/chạy test, và xóa schema sau kiểm tra; không migrate schema dữ liệu ứng dụng. Chạy lại bằng `mvn -Dtest=PostgresHeadTrainerTests,PostgresVeterinarianTests -Dtraining.postgres=true -Dvet.postgres=true test`. Bộ test mặc định bỏ qua hai lớp PostgreSQL nếu không bật các cờ này.

## Bổ sung sau rà soát

- Hàm đọc giáo án lấy đầy đủ trường để snapshot có tên giai đoạn, mục tiêu và khối lượng tuần.
- V24__repair_missing_training_plan_snapshots.sql chỉ bù snapshot bị thiếu tên giai đoạn; dùng giáo án hiện tại làm dữ liệu thay thế và không ghi đè snapshot đã có. Phiên bản giáo án chưa từng lưu không thể khôi phục chính xác.
- Buổi Scheduled chưa qua giờ kết thúc có thể sửa mục tiêu, mặt sân hoặc đổi giáo án qua PATCH training-schedules. Đổi giáo án phải đúng ngựa và khoảng ngày; mục tiêu bị khóa sau khi bắt đầu. Lưu mục tiêu và thay trạng thái là hai thao tác riêng.
- Lịch sử ghi recorded_at/stopped_at theo thời điểm kết thúc phiên đã tính, dù worker xử lý muộn. Cảnh báo tốc độ xét mẫu cao nhất thay vì chỉ tốc độ trung bình.
- Khi lưu nhận xét cho buổi vận động đã hết giờ nhưng worker chưa đánh dấu Missed, backend chuyển lịch và sự kiện thành Missed trong cùng giao dịch.
- Giao diện chặn phản hồi tải lịch/cảm biến cũ ghi đè dữ liệu sau khi đổi lựa chọn.
