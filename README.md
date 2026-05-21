# Tutor Platform Backend

Spring Boot MVP backend cho nền tảng gia sư.

## Stack

- Java 21
- Spring Boot 3.3
- Spring Web, Spring Security, JWT
- Spring JDBC/JPA dependency, PostgreSQL
- Flyway migration
- Springdoc OpenAPI / Swagger UI
- BCrypt password hashing
- Docker Compose

## Chạy bằng Docker Compose

Từ root repo:

```bash
docker compose up --build
```

Backend: `http://localhost:8080`

Swagger UI: `http://localhost:8080/swagger-ui/index.html`

Frontend: `http://localhost:3000`

## Chạy local

Yêu cầu PostgreSQL đang chạy với database `tutor_platform`.

```bash
cd backend
mvn spring-boot:run
```

Env thường dùng:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/tutor_platform
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=postgres
JWT_SECRET=change-me-to-long-random-secret
CORS_ALLOWED_ORIGINS=http://localhost:3000
UPLOAD_DIR=uploads
FRONTEND_BASE_URL=http://localhost:3000
AUTH_EXPOSE_DEV_TOKENS=false
```

## Real Data Mode

- Business preloaded records have been removed from the backend source.
- Create real users through registration or admin APIs.
- Password reset and email verification write real token rows and email jobs to `auth_email_outbox`.
- If an old local database already contains preloaded records, recreate the Postgres volume or remove those records manually before testing real data.

## API Format

Success:

```json
{
  "success": true,
  "data": {},
  "message": "..."
}
```

Error:

```json
{
  "success": false,
  "error": {
    "code": "ERROR_CODE",
    "message": "Human readable message",
    "details": {}
  }
}
```

## Module MVC Hiện Có

- `auth`: register/login/logout/refresh/me
- `security`: JWT filter/service, RBAC config
- `auth`: refresh token persistence/rotation/revoke bằng hash trong DB
- `policy`: status transition policy và file access policy
- `file`: endpoint đọc file có object-level authorization
- `platform`: user/profile/tutor/request/booking/class/session/review/message/notification/payment/payout/report/setting/contact/upload controllers
- `payment`: production-ready payment service, gateway abstraction, checkout, webhook, refund, invoice/receipt
- `payment.gateway`: adapter contract cho VNPay/MoMo/PayOS/Stripe/QR ngân hàng
- `db`: DTO mapper và JDBC data access helper
- `common`: response envelope, exception handling
- `config`: CORS, OpenAPI, security headers

Các endpoint chính đều nằm dưới `/api/v1`.

## Security Hardening

- Bearer token cho protected API bắt buộc có JWT claim `type=access`.
- Refresh token có claim `type=refresh`, được lưu dạng SHA-256 hash trong bảng `refresh_tokens`, rotate khi refresh và revoke khi logout.
- User `inactive`/`suspended` không được login/refresh; khi admin khóa user hoặc suspend tutor, refresh token đang active bị thu hồi.
- `/uploads/**` không còn được publish static. File tải lên được đọc qua `GET /api/v1/files/{fileId}`; private file chỉ owner hoặc admin xem được.
- `/api/v1/tutor/**` yêu cầu role `TUTOR`, `/api/v1/admin/**` yêu cầu role `ADMIN`.
- Các transition nhạy cảm cho learning request, booking, class, session, payout đi qua `StatusTransitionPolicy`.

## Payment Gateway Mode

System settings trong DB có các key:

- `paymentMode`: `sandbox`, `production`
- `enabledGateways`: ví dụ `["vnpay","momo","payos","bank_qr"]`
- `defaultGateway`: gateway mặc định khi tạo checkout
- `paymentTimeoutMinutes`
- `commissionRate`
- `refundPolicy`
- `invoicePrefix`
- `receiptPrefix`

Luồng production-ready:

1. User gọi `POST /api/v1/payments/{paymentId}/create-checkout`.
2. Backend tạo `payment_transactions`, trả `checkoutUrl`/`qrCodeUrl`, chuyển payment sang `processing`.
3. Gateway gọi `POST /api/v1/payments/webhooks/{gateway}`.
4. Backend lưu raw payload vào `payment_webhook_events`, verify signature, kiểm tra idempotency, amount/currency/order id.
5. Chỉ khi webhook hợp lệ, payment mới chuyển `paid`, tạo receipt, cập nhật invoice, earning, notification và audit log.

Gateway không hợp lệ hoặc không nằm trong `enabledGateways` sẽ bị reject. Ở `production`, gateway mô phỏng bị chặn cho tới khi nối adapter gateway thật.

Các gateway `vnpay`, `momo`, `payos`, `bank_qr`, `stripe` hiện là adapter mô phỏng theo chuẩn production contract. Webhook mô phỏng dùng header `x-{gateway}-signature` hoặc `x-payment-signature`, ký HMAC-SHA256 bằng secret `{gateway}-sandbox-secret`. Khi thay bằng SDK/API thật, giữ nguyên interface `PaymentGateway`.

## Payout Correctness

Tutor payout lock earning bằng bảng `payout_earning_items`. Approve payout chỉ mark các earning đã được allocate trong payout đó sang `paid`; reject payout release earning về `available`. Không còn cơ chế mark toàn bộ earning available của tutor khi duyệt một payout.

## Env bổ sung

```bash
PAYMENT_MODE=sandbox
ENABLED_GATEWAYS=bank_qr,vnpay,momo,payos,stripe
DEFAULT_GATEWAY=bank_qr
PAYMENT_TIMEOUT_MINUTES=30
UPLOAD_PUBLIC_BASE_URL=http://localhost:8080/api/v1/files
```

## Lưu ý verification

Nếu `mvn` báo `NoClassDefFoundError: org/codehaus/plexus/logging/LoggerManager`, Maven local đang thiếu dependency nội bộ. Cài lại Maven hoặc dùng Maven wrapper trước khi chạy:

```bash
mvn test
mvn package
```
