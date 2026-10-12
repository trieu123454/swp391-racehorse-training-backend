# Club Manager RBAC API

Tất cả endpoint yêu cầu Bearer access token của tài khoản `CLUB_MANAGER` đã `APPROVED`.

## Quy ước ID

Project hiện dùng hai loại ID:

| Dữ liệu | Kiểu thực tế | Ví dụ |
|---|---|---|
| User/account | `BIGINT` / Java `Long` | `17` |
| Horse | UUID string | `550e8400-e29b-41d4-a716-446655440000` |
| Stable box | UUID string | `10000000-0000-0000-0000-000000000001` |
| Role-change request | UUID string | `7b7d7f34-...` |
| Notification/audit record | UUID string | `b6b7...` |

Vì vậy các URL user dùng số, không dùng UUID:

```text
PATCH /api/club-manager/users/17/approve
```

## Xem tài khoản chờ duyệt

```http
GET /api/club-manager/users/pending?role=HEAD_TRAINER&page=1&limit=10
Authorization: Bearer <token>
```

Response:

```json
{
  "data": [
    {
      "id": 17,
      "full_name": "Nguyen Van A",
      "email": "a@example.com",
      "phone": "0901234567",
      "role_name": "HEAD_TRAINER",
      "status": "PENDING",
      "created_at": "2026-09-20T10:00:00"
    }
  ],
  "total": 1,
  "page": 1,
  "limit": 10
}
```

Chỉ các role `HEAD_TRAINER`, `VETERINARIAN`, `GROOM` được trả về. `HORSE_OWNER` không xuất hiện trong danh sách pending.

## Duyệt tài khoản

```http
PATCH /api/club-manager/users/17/approve
Authorization: Bearer <token>
```

```json
{
  "id": 17,
  "status": "APPROVED",
  "approved_by": 3,
  "approved_at": "2026-09-25T14:30:00"
}
```

Chỉ user `PENDING` mới được duyệt. User đã `APPROVED`, `REJECTED` hoặc `LOCKED` trả `409 Conflict`. Hệ thống đồng thời ghi notification và audit log.

## Từ chối tài khoản

```http
PATCH /api/club-manager/users/17/reject
Authorization: Bearer <token>
Content-Type: application/json
```

```json
{
  "reason": "Không xác minh được thông tin nhân sự"
}
```

`reason` không bắt buộc. Hệ thống chuyển trạng thái sang `REJECTED`, gửi notification và ghi audit log.

## Yêu cầu đổi vai trò

```http
GET /api/club-manager/role-change-requests?status=Pending
Authorization: Bearer <token>
```

```http
PATCH /api/club-manager/role-change-requests/7b7d7f34-0000-0000-0000-000000000001
Authorization: Bearer <token>
Content-Type: application/json
```

```json
{
  "action": "approve"
}
```

`action` nhận `approve` hoặc `reject`. Approve cập nhật role user và trạng thái request; reject chỉ cập nhật trạng thái request.

## Khóa tài khoản

```http
PATCH /api/club-manager/users/17/lock
Authorization: Bearer <token>
Content-Type: application/json
```

```json
{
  "reason": "Vi phạm quy định CLB"
}
```

Chỉ user `APPROVED` mới được khóa. Không thể khóa chính mình hoặc tài khoản `CLUB_MANAGER` khác. User `LOCKED` không thể đăng nhập và nhận lỗi `Tài khoản đã bị khóa`.
