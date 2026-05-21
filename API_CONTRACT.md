# API Contract

Tất cả response dùng envelope `{ success, data, message, pagination, error }`.

| Method | Endpoint | Roles |
|---|---|---|
| POST | `/api/v1/auth/register` | guest |
| POST | `/api/v1/auth/login` | guest |
| POST | `/api/v1/auth/logout` | authenticated |
| POST | `/api/v1/auth/refresh` | guest |
| POST | `/api/v1/auth/forgot-password`, `/reset-password`, `/verify-email` | guest |
| GET | `/api/v1/auth/me` | authenticated |
| GET/PATCH | `/api/v1/users/me` | authenticated |
| GET/PATCH | `/api/v1/users/me/profile` | authenticated |
| GET | `/api/v1/tutors` | guest |
| GET | `/api/v1/tutors/{tutorId}` | guest |
| GET/POST/DELETE | `/api/v1/favorites/tutors/*` | authenticated |
| GET/PATCH/POST | `/api/v1/tutor/profile`, `/api/v1/tutor/profile/submit` | tutor |
| GET/POST/DELETE | `/api/v1/tutor/documents` | tutor |
| GET/POST/PATCH/DELETE | `/api/v1/tutor/availability` | tutor |
| GET/POST/PATCH | `/api/v1/learning-requests` | student, parent, admin |
| GET/POST | `/api/v1/bookings` | student, parent |
| GET/POST | `/api/v1/tutor/bookings/*` | tutor |
| GET | `/api/v1/classes`, `/api/v1/sessions`, `/api/v1/classes/{id}/sessions` | owner/admin |
| GET/POST | `/api/v1/tutor/sessions/*` | tutor |
| GET/POST | `/api/v1/reviews` | student, parent |
| GET/POST | `/api/v1/conversations/*` | conversation member |
| GET/POST | `/api/v1/notifications/*` | authenticated |
| GET/POST | `/api/v1/payments/*` | owner/admin |
| POST | `/api/v1/payments/{paymentId}/create-checkout` | payment owner |
| GET | `/api/v1/payments/{paymentId}/status`, `/invoice`, `/receipt` | payment owner/admin |
| POST | `/api/v1/payments/webhooks/{gateway}` | gateway webhook, signature verified by backend |
| GET | `/api/v1/admin/payment-transactions`, `/api/v1/admin/payment-webhook-events`, `/api/v1/admin/refunds` | admin |
| GET/POST | `/api/v1/tutor/payments`, `/api/v1/tutor/earnings`, `/api/v1/tutor/payouts` | tutor |
| POST | `/api/v1/uploads` | authenticated |
| GET | `/api/v1/files/{fileId}` | public if file visibility is `public`; owner/admin if `private` |
| POST | `/api/v1/contact-requests` | guest |
| GET/PATCH/POST | `/api/v1/admin/**` | admin |

Admin endpoints bao phủ users, tutors, tutor documents, learning requests, matching tutors, bookings, classes, sessions, reviews, conversations, notifications, payments, payouts, reports, settings, contact requests và audit logs.

## Auth Contract

| Endpoint | Request DTO | Response DTO | Rule chính |
|---|---|---|---|
| `POST /api/v1/auth/login` | `{ email, password }` | `{ accessToken, refreshToken, user }` | Password BCrypt, user phải `active`, refresh token lưu hash trong DB |
| `POST /api/v1/auth/refresh` | `{ refreshToken }` | `{ accessToken, refreshToken }` | Token phải có `type=refresh`, chưa revoked, chưa expired, user active, rotate token |
| `POST /api/v1/auth/logout` | `{ refreshToken }` optional | `{ loggedOut: true }` | Revoke refresh token tương ứng; nếu không gửi token thì revoke refresh token của user hiện tại |
| `POST /api/v1/auth/forgot-password` | `{ email }` | `{ accepted: true }` | Tạo `password_reset_tokens` hash và queue email trong `auth_email_outbox` nếu email tồn tại |
| `POST /api/v1/auth/reset-password` | `{ token, newPassword }` | `{ accepted: true }` | Token chưa dùng/chưa hết hạn, update BCrypt password và revoke refresh token cũ |
| `POST /api/v1/auth/verify-email` | `{ token }` | `{ verified: true }` | Token chưa dùng/chưa hết hạn, set `users.email_verified = true` |

Protected API chỉ nhận Bearer access token có claim `type=access`. Refresh token dùng làm Bearer token sẽ trả `401`.

## File Contract

`POST /api/v1/uploads` nhận multipart `file` và optional `visibility=private|public`.

Response:

```json
{
  "id": "fileId",
  "fileId": "fileId",
  "fileName": "randomized-name.pdf",
  "originalFileName": "degree.pdf",
  "fileUrl": "/api/v1/files/{fileId}",
  "fileSize": 12345,
  "mimeType": "application/pdf",
  "visibility": "private"
}
```

Backend không expose storage path hoặc `/uploads/private/**`. Tutor documents phải gắn `fileId` hoặc `fileUrl` dạng `/api/v1/files/{fileId}`.

## Payment/Payout Rules

- FE không gửi amount để backend tin khi tạo checkout; amount lấy từ `payments`.
- `gateway` unknown hoặc disabled trả lỗi `UNKNOWN_PAYMENT_GATEWAY` / `PAYMENT_GATEWAY_DISABLED`.
- `paymentMode=production` chặn gateway mô phỏng cho tới khi nối gateway thật.
- Admin `mark-paid` yêu cầu `{ reason }`.
- Payout tạo `payout_earning_items`; approve/reject chỉ tác động các earning được allocate trong payout đó.

## Error Codes chính

`INVALID_REFRESH_TOKEN`, `REFRESH_TOKEN_REVOKED`, `REFRESH_TOKEN_EXPIRED`, `USER_NOT_ACTIVE`, `FORBIDDEN`, `INVALID_STATUS_TRANSITION`, `FILE_ID_REQUIRED`, `INVALID_FILE_TYPE`, `UNKNOWN_PAYMENT_GATEWAY`, `PAYMENT_GATEWAY_DISABLED`, `INVALID_PAYOUT_AMOUNT`.
