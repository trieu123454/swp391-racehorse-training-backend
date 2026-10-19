# Cấu hình Cloudinary cho ảnh và giấy tờ

Backend tải tệp lên Cloudinary. Ảnh hồ sơ ngựa dùng URL ảnh Cloudinary; ảnh sự cố dùng authenticated asset và link tải có thời hạn sau khi backend kiểm tra quyền; giấy tờ xác minh được tải lên dưới dạng raw authenticated asset và chỉ trả link tải có thời hạn 5 phút. API secret chỉ được dùng ở backend.

## 1. Lấy thông tin tài khoản

1. Tạo hoặc mở tài khoản tại [Cloudinary Console](https://console.cloudinary.com/).
2. Trong Dashboard hoặc **Settings → API Keys**, lấy `Cloud name`, `API Key` và `API Secret`.
3. Không đưa API Secret vào frontend, `.env.local` của Next.js, Git hoặc ảnh chụp màn hình.

## 2. Cấu hình backend local

Thêm các dòng sau vào `swp391-racehorse-training-backend/application-local.properties` và thay giá trị mẫu bằng thông tin trong Cloudinary Dashboard:

```properties
app.storage.provider=cloudinary
app.cloudinary.cloud-name=your_cloud_name
app.cloudinary.api-key=your_api_key
app.cloudinary.api-secret=your_api_secret
```

Hoặc đặt các biến môi trường `STORAGE_PROVIDER`, `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY` và `CLOUDINARY_API_SECRET`. Không cần thay đổi FE hoặc thêm Cloudinary secret vào FE. Khởi động lại backend sau khi cấu hình.

## 3. Kiểm tra

1. Đăng nhập bằng Horse Owner và gửi yêu cầu đăng ký ngựa có ảnh cùng giấy tờ.
2. Trong Cloudinary **Media Library**, kiểm tra ảnh trong `horses` và file giấy tờ trong `horse-documents`.
3. Ảnh ngựa sẽ hiển thị qua CDN. Link giấy tờ là link tải tạm thời; backend tạo link mới khi người dùng được phép xem hồ sơ.

Ảnh và giấy tờ đã có trong Supabase vẫn được đọc từ Supabase vì database còn lưu đường dẫn cũ. Giữ cấu hình Supabase đến khi dữ liệu cũ được di chuyển và kiểm tra xong. Đổi `app.storage.provider=supabase` để tạm quay lại dùng Supabase cho các lượt tải mới.
