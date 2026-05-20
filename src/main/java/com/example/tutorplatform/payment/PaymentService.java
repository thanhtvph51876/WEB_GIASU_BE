package com.example.tutorplatform.payment;

import com.example.tutorplatform.common.BusinessException;
import com.example.tutorplatform.common.ForbiddenException;
import com.example.tutorplatform.db.DbService;
import com.example.tutorplatform.payment.gateway.PaymentGateway;
import com.example.tutorplatform.payment.gateway.PaymentGatewayFactory;
import com.example.tutorplatform.payment.gateway.dto.CreateCheckoutRequest;
import com.example.tutorplatform.payment.gateway.dto.CreateCheckoutResponse;
import com.example.tutorplatform.payment.gateway.dto.GatewayPaymentStatus;
import com.example.tutorplatform.payment.gateway.dto.VerifyWebhookRequest;
import com.example.tutorplatform.payment.gateway.dto.VerifyWebhookResponse;
import com.example.tutorplatform.policy.StatusTransitionPolicy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {
  private final DbService db;
  private final JdbcTemplate jdbc;
  private final PaymentGatewayFactory gatewayFactory;
  private final StatusTransitionPolicy statusPolicy;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public PaymentService(DbService db, PaymentGatewayFactory gatewayFactory, StatusTransitionPolicy statusPolicy) {
    this.db = db;
    this.jdbc = db.jdbc();
    this.gatewayFactory = gatewayFactory;
    this.statusPolicy = statusPolicy;
  }

  public Map<String, Object> settings() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("paymentMode", gatewayFactory.paymentMode());
    data.put("enabledGateways", jsonListSetting("enabledGateways", List.of("mock")));
    data.put("defaultGateway", gatewayFactory.setting("defaultGateway", "mock"));
    data.put("paymentTimeoutMinutes", gatewayFactory.paymentTimeoutMinutes());
    data.put("refundPolicy", gatewayFactory.setting("refundPolicy", "Hoàn tiền theo chính sách dịch vụ."));
    data.put("invoicePrefix", gatewayFactory.setting("invoicePrefix", "INV"));
    data.put("receiptPrefix", gatewayFactory.setting("receiptPrefix", "REC"));
    return data;
  }

  @Transactional
  public Map<String, Object> createCheckout(UUID paymentId, Map<String, Object> body) {
    Map<String, Object> payment = paymentByIdForUpdate(paymentId);
    requirePaymentOwner(payment);
    String status = value(payment.get("status"));
    if ("paid".equals(status) || "completed".equals(status)) {
      throw new BusinessException("PAYMENT_ALREADY_PAID", "Thanh toán này đã được ghi nhận thành công.");
    }
    if (!List.of("pending", "processing", "failed", "expired").contains(status)) {
      throw new BusinessException("PAYMENT_NOT_CHECKOUTABLE", "Thanh toán hiện không thể tạo phiên thanh toán.");
    }

    String requestedGateway = string(body, "gateway");
    PaymentGateway gateway = gatewayFactory.resolve(requestedGateway);
    String orderCode = gateway.type().code() + "-" + paymentId.toString().substring(0, 8) + "-" + System.currentTimeMillis();
    OffsetDateTime expiredAt = OffsetDateTime.now().plusMinutes(gatewayFactory.paymentTimeoutMinutes());

    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("paymentId", paymentId.toString());
    metadata.put("userId", payment.get("userId"));
    metadata.put("classId", payment.get("classId"));
    metadata.put("sessionId", payment.get("sessionId"));

    CreateCheckoutResponse checkout = gateway.createCheckout(new CreateCheckoutRequest(
        paymentId,
        orderCode,
        number(payment.get("amount")),
        valueOr(value(payment.get("currency")), "VND"),
        value(payment.get("description")),
        string(body, "returnUrl"),
        string(body, "cancelUrl"),
        metadata
    ));

    jdbc.update("""
        insert into payment_transactions(payment_id, gateway, gateway_order_id, amount, currency, status, request_payload, raw_response)
        values (?, ?, ?, ?, ?, 'pending', ?::jsonb, ?::jsonb)
        """,
        paymentId,
        checkout.gateway(),
        checkout.gatewayOrderId(),
        number(payment.get("amount")),
        valueOr(value(payment.get("currency")), "VND"),
        json(Map.of("returnUrl", valueOr(string(body, "returnUrl"), ""), "cancelUrl", valueOr(string(body, "cancelUrl"), ""))),
        jsonOrEmpty(checkout.rawResponseJson()));

    jdbc.update("""
        update payments
        set status = 'processing', payment_method = ?, gateway = ?, checkout_url = ?,
            qr_code_url = ?, expired_at = ?, updated_at = now()
        where id = ?
        """, gateway.type().code(), checkout.gateway(), checkout.checkoutUrl(), checkout.qrCodeUrl(), expiredAt, paymentId);

    ensureInvoice(paymentId);
    db.auditCurrent("payment.create_checkout", "payment", paymentId, "Tạo phiên thanh toán qua cổng " + checkout.gateway() + ".");

    Map<String, Object> updated = paymentById(paymentId);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("payment", updated);
    data.put("gateway", checkout.gateway());
    data.put("gatewayOrderId", checkout.gatewayOrderId());
    data.put("checkoutUrl", checkout.checkoutUrl());
    data.put("qrCodeUrl", checkout.qrCodeUrl());
    data.put("expiredAt", ts(expiredAt));
    data.put("status", "processing");
    return data;
  }

  public Map<String, Object> status(UUID paymentId) {
    Map<String, Object> payment = paymentById(paymentId);
    requirePaymentOwner(payment);
    return Map.of("payment", payment, "status", payment.get("status"));
  }

  public Map<String, Object> invoice(UUID paymentId) {
    Map<String, Object> payment = paymentById(paymentId);
    requirePaymentOwner(payment);
    ensureInvoice(paymentId);
    return db.required("select * from invoices where payment_id = ?", invoiceMapper(), paymentId);
  }

  public Map<String, Object> receipt(UUID paymentId) {
    Map<String, Object> payment = paymentById(paymentId);
    requirePaymentOwner(payment);
    return db.required("select * from receipts where payment_id = ?", receiptMapper(), paymentId);
  }

  @Transactional
  public Map<String, Object> mockPay(UUID paymentId) {
    if (!gatewayFactory.isMockMode()) {
      throw new BusinessException("MOCK_PAYMENT_DISABLED", "Mock-pay chỉ được bật trong paymentMode=mock.", HttpStatus.FORBIDDEN);
    }
    Map<String, Object> payment = paymentByIdForUpdate(paymentId);
    requirePaymentOwner(payment);
    Map<String, Object> updated = applyPaid(payment, "mock", existingGatewayOrder(paymentId, "mock"), "mock-tx-" + paymentId, true);
    db.auditCurrent("payment.mock_paid", "payment", paymentId, "Người dùng thanh toán demo trong chế độ mock.");
    return updated;
  }

  @Transactional
  public Map<String, Object> adminMarkPaid(UUID paymentId, String reason) {
    requireAdmin();
    if (reason == null || reason.isBlank()) {
      throw new BusinessException("REASON_REQUIRED", "Cần nhập lý do ghi nhận thanh toán thủ công.");
    }
    Map<String, Object> payment = paymentByIdForUpdate(paymentId);
    Map<String, Object> updated = applyPaid(payment, valueOr(value(payment.get("gateway")), "manual"), existingGatewayOrder(paymentId, valueOr(value(payment.get("gateway")), "manual")), "manual-tx-" + paymentId, true);
    db.auditCurrent("admin.payment_mark_paid", "payment", paymentId, "Admin ghi nhận thanh toán thành công sau khi đối soát. Lý do: " + reason);
    return updated;
  }

  @Transactional
  public Map<String, Object> adminMarkFailed(UUID paymentId) {
    requireAdmin();
    markPaymentTerminal(paymentId, "failed", "Thanh toán thất bại", "Thanh toán của bạn chưa thành công. Vui lòng thử lại hoặc liên hệ hỗ trợ.");
    db.auditCurrent("admin.payment_mark_failed", "payment", paymentId, "Admin ghi nhận thanh toán thất bại.");
    return paymentById(paymentId);
  }

  @Transactional
  public Map<String, Object> refund(UUID paymentId, Map<String, Object> body) {
    requireAdmin();
    Map<String, Object> payment = paymentByIdForUpdate(paymentId);
    int amount = body.get("amount") == null ? number(payment.get("amount")) : number(body.get("amount"));
    String reason = string(body, "reason");
    if (reason == null || reason.isBlank()) {
      throw new BusinessException("REASON_REQUIRED", "Cần nhập lý do hoàn tiền.");
    }
    if (amount <= 0 || amount > number(payment.get("amount"))) {
      throw new BusinessException("INVALID_REFUND_AMOUNT", "Số tiền hoàn không hợp lệ.");
    }
    if (!List.of("paid", "completed", "partially_refunded").contains(value(payment.get("status")))) {
      throw new BusinessException("PAYMENT_NOT_REFUNDABLE", "Chỉ có thể hoàn tiền giao dịch đã thanh toán.");
    }
    int refunded = jdbc.queryForObject("select coalesce(sum(amount),0) from payment_refunds where payment_id = ? and status = 'succeeded'", Integer.class, paymentId);
    int remaining = Math.max(0, number(payment.get("amount")) - refunded);
    if (amount > remaining) {
      throw new BusinessException("INVALID_REFUND_AMOUNT", "Số tiền hoàn vượt quá phần còn lại của giao dịch.");
    }
    String nextStatus = amount == remaining ? "refunded" : "partially_refunded";
    statusPolicy.requirePayment(value(payment.get("status")), nextStatus);
    String gateway = valueOr(value(payment.get("gateway")), "mock");
    String gatewayRefundId = gatewayFactory.resolve(gateway).refund(latestGatewayTransactionId(paymentId), amount, reason);
    jdbc.update("""
        insert into payment_refunds(payment_id, amount, reason, status, gateway_refund_id, requested_by, processed_by)
        values (?, ?, ?, 'succeeded', ?, ?, ?)
        """, paymentId, amount, reason, gatewayRefundId, db.currentUserIdOrThrow(), db.currentUserIdOrThrow());
    jdbc.update("update payments set status = ?, updated_at = now() where id = ?", nextStatus, paymentId);
    if ("refunded".equals(nextStatus)) {
      jdbc.update("update tutor_earnings set status = 'cancelled', updated_at = now() where payment_id = ?", paymentId);
      jdbc.update("update payment_transactions set status = 'refunded', updated_at = now() where payment_id = ? and status = 'success'", paymentId);
    }
    db.notify(UUID.fromString(payment.get("userId").toString()), "warning", "Thanh toán đã được hoàn tiền", "Giao dịch đã được cập nhật trạng thái hoàn tiền.", "/dashboard/student/payments", "payment", paymentId);
    db.auditCurrent("admin.payment_refund", "payment", paymentId, "Admin hoàn tiền giao dịch với số tiền " + amount + " " + valueOr(value(payment.get("currency")), "VND") + ".");
    return paymentById(paymentId);
  }

  @Transactional
  public Map<String, Object> processWebhook(String gatewayCode, Map<String, String> headers, String rawPayload) {
    PaymentGateway gateway = gatewayFactory.resolve(gatewayCode);
    VerifyWebhookResponse verified = gateway.verifyWebhook(new VerifyWebhookRequest(headers, rawPayload));
    WebhookEventInsert eventInsert = insertWebhookEvent(gateway.type().code(), verified, rawPayload);
    UUID eventRowId = eventInsert.id();
    if (!eventInsert.inserted()) {
      return Map.of("accepted", true, "processed", true, "idempotent", true);
    }

    if (!verified.signatureValid()) {
      markWebhookEvent(eventRowId, false, "Chữ ký webhook không hợp lệ.");
      db.audit(null, "system", "security.payment_webhook_invalid", "payment_webhook_event", eventRowId, "Webhook thanh toán có chữ ký không hợp lệ.");
      return Map.of("accepted", true, "processed", false, "signatureValid", false);
    }

    if (verified.eventId() != null && alreadyProcessed(gateway.type().code(), verified.eventId(), eventRowId)) {
      markWebhookEvent(eventRowId, true, null);
      return Map.of("accepted", true, "processed", true, "idempotent", true);
    }

    Map<String, Object> payment = findPaymentForWebhook(verified)
        .orElse(null);
    if (payment == null) {
      markWebhookEvent(eventRowId, false, "Không tìm thấy payment tương ứng.");
      return Map.of("accepted", true, "processed", false);
    }

    UUID paymentId = UUID.fromString(payment.get("id").toString());
    jdbc.update("update payment_webhook_events set payment_id = ? where id = ?", paymentId, eventRowId);
    if (verified.amount() != number(payment.get("amount")) || !valueOr(value(payment.get("currency")), "VND").equalsIgnoreCase(verified.currency())) {
      markWebhookEvent(eventRowId, true, "Số tiền hoặc tiền tệ từ gateway không khớp payment.");
      db.audit(null, "system", "security.payment_webhook_amount_mismatch", "payment", paymentId, "Webhook thanh toán có số tiền hoặc tiền tệ không khớp.");
      return Map.of("accepted", true, "processed", false);
    }

    try {
      if (verified.status() == GatewayPaymentStatus.SUCCESS) {
        applyPaid(paymentByIdForUpdate(paymentId), gateway.type().code(), verified.gatewayOrderId(), verified.gatewayTransactionId(), false);
      } else if (verified.status() == GatewayPaymentStatus.FAILED) {
        markPaymentTerminal(paymentId, "failed", "Thanh toán thất bại", "Cổng thanh toán báo giao dịch thất bại.");
      } else if (verified.status() == GatewayPaymentStatus.CANCELLED) {
        markPaymentTerminal(paymentId, "cancelled", "Thanh toán đã hủy", "Giao dịch thanh toán đã được hủy.");
      } else if (verified.status() == GatewayPaymentStatus.EXPIRED) {
        markPaymentTerminal(paymentId, "expired", "Thanh toán đã hết hạn", "Phiên thanh toán đã hết hạn. Bạn có thể tạo phiên mới.");
      } else if (verified.status() == GatewayPaymentStatus.REFUNDED) {
        markGatewayRefunded(paymentId, verified.amount(), "Gateway báo giao dịch đã hoàn tiền.");
      }
      markWebhookEvent(eventRowId, true, null);
      db.audit(null, "system", "payment.webhook_processed", "payment", paymentId, "Webhook thanh toán từ " + gateway.type().code() + " đã được xử lý.");
      return Map.of("accepted", true, "processed", true, "paymentId", paymentId.toString(), "status", verified.status().name().toLowerCase(Locale.ROOT));
    } catch (RuntimeException ex) {
      markWebhookEvent(eventRowId, true, ex.getMessage());
      db.audit(null, "system", "payment.webhook_processing_error", "payment", paymentId, "Webhook thanh toán không xử lý được: " + ex.getMessage());
      return Map.of("accepted", true, "processed", false);
    }
  }

  public List<Map<String, Object>> transactions() {
    requireAdmin();
    return jdbc.query("select * from payment_transactions order by created_at desc limit 500", transactionMapper());
  }

  public List<Map<String, Object>> webhookEvents() {
    requireAdmin();
    return jdbc.query("select * from payment_webhook_events order by received_at desc limit 500", webhookEventMapper());
  }

  public List<Map<String, Object>> refunds() {
    requireAdmin();
    return jdbc.query("select * from payment_refunds order by created_at desc limit 500", refundMapper());
  }

  private Map<String, Object> applyPaid(Map<String, Object> payment, String gateway, String gatewayOrderId, String gatewayTransactionId, boolean manual) {
    UUID paymentId = UUID.fromString(payment.get("id").toString());
    if (List.of("paid", "completed").contains(value(payment.get("status")))) {
      ensureInvoice(paymentId);
      ensureReceipt(paymentId);
      return paymentById(paymentId);
    }
    if (!List.of("pending", "processing", "failed", "expired").contains(value(payment.get("status")))) {
      throw new BusinessException("INVALID_PAYMENT_TRANSITION", "Trạng thái thanh toán hiện tại không thể chuyển sang đã thanh toán.");
    }
    statusPolicy.requirePayment(value(payment.get("status")), "paid");

    upsertSuccessfulTransaction(payment, gateway, gatewayOrderId, gatewayTransactionId, manual);
    jdbc.update("""
        update payments
        set status = 'paid', gateway = coalesce(gateway, ?), payment_method = coalesce(payment_method, ?),
            paid_at = coalesce(paid_at, now()), updated_at = now()
        where id = ?
        """, gateway, gateway, paymentId);
    ensureInvoice(paymentId);
    ensureReceipt(paymentId);
    makeEarningAvailable(paymentId);

    UUID userId = UUID.fromString(payment.get("userId").toString());
    db.notify(userId, "success", "Thanh toán thành công", "Giao dịch của bạn đã được ghi nhận. Biên lai đã sẵn sàng.", "/dashboard/student/payments", "payment", paymentId);
    notifyTutorEarning(paymentId);
    return paymentById(paymentId);
  }

  private void upsertSuccessfulTransaction(Map<String, Object> payment, String gateway, String gatewayOrderId, String gatewayTransactionId, boolean manual) {
    UUID paymentId = UUID.fromString(payment.get("id").toString());
    String orderId = valueOr(gatewayOrderId, gateway + "-" + paymentId.toString().substring(0, 8));
    int updated = jdbc.update("""
        update payment_transactions
        set gateway_transaction_id = coalesce(?, gateway_transaction_id), status = 'success',
            raw_response = ?::jsonb, updated_at = now()
        where payment_id = ? and gateway = ? and gateway_order_id = ?
        """, gatewayTransactionId, json(Map.of("source", manual ? "manual" : "webhook")), paymentId, gateway, orderId);
    if (updated == 0) {
      jdbc.update("""
          insert into payment_transactions(payment_id, gateway, gateway_order_id, gateway_transaction_id, amount, currency, status, raw_response)
          values (?, ?, ?, ?, ?, ?, 'success', ?::jsonb)
          """, paymentId, gateway, orderId, gatewayTransactionId, number(payment.get("amount")), valueOr(value(payment.get("currency")), "VND"), json(Map.of("source", manual ? "manual" : "webhook")));
    }
  }

  private void makeEarningAvailable(UUID paymentId) {
    int updated = jdbc.update("update tutor_earnings set status = 'available', updated_at = now() where payment_id = ? and status in ('pending','cancelled')", paymentId);
    if (updated > 0) return;
    Map<String, Object> payment = paymentById(paymentId);
    if (payment.get("tutorId") == null) return;
    UUID tutorId = UUID.fromString(payment.get("tutorId").toString());
    int amount = number(payment.get("amount"));
    int fee = db.commissionFee(amount);
    if (!exists("select 1 from tutor_earnings where payment_id = ?", paymentId)) {
      jdbc.update("""
          insert into tutor_earnings(tutor_id, session_id, payment_id, gross_amount, platform_fee, net_amount, status)
          values (?, ?, ?, ?, ?, ?, 'available')
          """, tutorId, uuidOrNull(payment.get("sessionId")), paymentId, amount, fee, Math.max(0, amount - fee));
    }
  }

  private void notifyTutorEarning(UUID paymentId) {
    Map<String, Object> payment = paymentById(paymentId);
    if (payment.get("tutorId") == null) return;
    UUID tutorId = UUID.fromString(payment.get("tutorId").toString());
    UUID tutorUserId = db.optional("select user_id from tutor_profiles where id = ?", (rs, row) -> rs.getObject("user_id", UUID.class), tutorId).orElse(null);
    if (tutorUserId != null) {
      db.notify(tutorUserId, "success", "Thu nhập đã khả dụng", "Một khoản thanh toán đã hoàn tất và được cộng vào thu nhập của bạn.", "/dashboard/tutor/earnings", "payment", paymentId);
    }
  }

  private void markPaymentTerminal(UUID paymentId, String status, String title, String message) {
    Map<String, Object> payment = paymentByIdForUpdate(paymentId);
    if (List.of("paid", "completed", "refunded").contains(value(payment.get("status")))) return;
    statusPolicy.requirePayment(value(payment.get("status")), status);
    jdbc.update("update payments set status = ?, updated_at = now() where id = ?", status, paymentId);
    if (List.of("failed", "cancelled", "expired").contains(status)) {
      jdbc.update("update tutor_earnings set status = 'cancelled', updated_at = now() where payment_id = ? and status = 'pending'", paymentId);
    }
    db.notify(UUID.fromString(payment.get("userId").toString()), "failed".equals(status) ? "error" : "warning", title, message, "/dashboard/student/payments", "payment", paymentId);
  }

  private void markGatewayRefunded(UUID paymentId, int amount, String reason) {
    Map<String, Object> payment = paymentByIdForUpdate(paymentId);
    if ("refunded".equals(value(payment.get("status")))) return;
    statusPolicy.requirePayment(value(payment.get("status")), "refunded");
    jdbc.update("""
        insert into payment_refunds(payment_id, amount, reason, status, gateway_refund_id)
        values (?, ?, ?, 'succeeded', ?)
        """, paymentId, amount <= 0 ? number(payment.get("amount")) : amount, reason, "gateway-refund-" + paymentId);
    jdbc.update("update payments set status = 'refunded', updated_at = now() where id = ?", paymentId);
    jdbc.update("update tutor_earnings set status = 'cancelled', updated_at = now() where payment_id = ?", paymentId);
    jdbc.update("update payment_transactions set status = 'refunded', updated_at = now() where payment_id = ? and status = 'success'", paymentId);
    db.notify(UUID.fromString(payment.get("userId").toString()), "warning", "Thanh toán đã được hoàn tiền", "Gateway đã xác nhận hoàn tiền giao dịch.", "/dashboard/student/payments", "payment", paymentId);
  }

  private void ensureInvoice(UUID paymentId) {
    Map<String, Object> payment = paymentById(paymentId);
    String prefix = gatewayFactory.setting("invoicePrefix", "INV");
    String invoiceNo = prefix + "-" + OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMM")) + "-" + paymentId.toString().substring(0, 8);
    jdbc.update("""
        insert into invoices(payment_id, invoice_no, user_id, amount, currency, status, paid_at)
        values (?, ?, ?, ?, ?, case when ? in ('paid','completed') then 'paid' else 'issued' end, ?)
        on conflict(payment_id) do update
        set status = case when excluded.status = 'paid' then 'paid' else invoices.status end,
            paid_at = coalesce(invoices.paid_at, excluded.paid_at),
            updated_at = now()
        """,
        paymentId, invoiceNo, UUID.fromString(payment.get("userId").toString()), number(payment.get("amount")),
        valueOr(value(payment.get("currency")), "VND"), value(payment.get("status")), paidAt(payment));
  }

  private void ensureReceipt(UUID paymentId) {
    Map<String, Object> payment = paymentById(paymentId);
    if (!List.of("paid", "completed").contains(value(payment.get("status")))) return;
    String prefix = gatewayFactory.setting("receiptPrefix", "REC");
    String receiptNo = prefix + "-" + OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMM")) + "-" + paymentId.toString().substring(0, 8);
    jdbc.update("""
        insert into receipts(payment_id, receipt_no, user_id, amount, currency)
        values (?, ?, ?, ?, ?)
        on conflict(payment_id) do nothing
        """, paymentId, receiptNo, UUID.fromString(payment.get("userId").toString()), number(payment.get("amount")), valueOr(value(payment.get("currency")), "VND"));
  }

  private WebhookEventInsert insertWebhookEvent(String gateway, VerifyWebhookResponse verified, String rawPayload) {
    List<UUID> ids = jdbc.query("""
        insert into payment_webhook_events(gateway, event_id, gateway_order_id, gateway_transaction_id, signature_valid, payload)
        values (?, ?, ?, ?, ?, ?::jsonb)
        on conflict do nothing
        returning id
        """, (rs, row) -> rs.getObject("id", UUID.class), gateway, verified.eventId(), verified.gatewayOrderId(), verified.gatewayTransactionId(), verified.signatureValid(), payloadJson(rawPayload));
    if (!ids.isEmpty()) {
      return new WebhookEventInsert(ids.getFirst(), true);
    }
    UUID existing = jdbc.queryForObject("""
        select id from payment_webhook_events
        where gateway = ? and event_id = ?
        order by received_at desc
        limit 1
        """, UUID.class, gateway, verified.eventId());
    return new WebhookEventInsert(existing, false);
  }

  private void markWebhookEvent(UUID eventId, boolean processed, String error) {
    jdbc.update("""
        update payment_webhook_events
        set processed = ?, processing_error = ?, processed_at = case when ? then now() else processed_at end
        where id = ?
        """, processed, error, processed, eventId);
  }

  private boolean alreadyProcessed(String gateway, String eventId, UUID currentEventId) {
    Integer count = jdbc.queryForObject("""
        select count(*) from payment_webhook_events
        where gateway = ? and event_id = ? and processed = true and id <> ?
        """, Integer.class, gateway, eventId, currentEventId);
    return count != null && count > 0;
  }

  private java.util.Optional<Map<String, Object>> findPaymentForWebhook(VerifyWebhookResponse response) {
    if (response.gatewayOrderId() != null) {
      java.util.Optional<Map<String, Object>> byOrder = db.optional("""
          select p.* from payments p
          join payment_transactions tx on tx.payment_id = p.id
          where tx.gateway_order_id = ?
          order by tx.created_at desc limit 1
          """, db.paymentMapper(), response.gatewayOrderId());
      if (byOrder.isPresent()) return byOrder;
    }
    if (response.gatewayTransactionId() != null) {
      return db.optional("""
          select p.* from payments p
          join payment_transactions tx on tx.payment_id = p.id
          where tx.gateway_transaction_id = ?
          order by tx.created_at desc limit 1
          """, db.paymentMapper(), response.gatewayTransactionId());
    }
    return java.util.Optional.empty();
  }

  private Map<String, Object> paymentById(UUID paymentId) {
    return db.required("select * from payments where id = ?", db.paymentMapper(), paymentId);
  }

  private Map<String, Object> paymentByIdForUpdate(UUID paymentId) {
    return db.required("select * from payments where id = ? for update", db.paymentMapper(), paymentId);
  }

  private void requirePaymentOwner(Map<String, Object> payment) {
    if (db.isAdmin()) return;
    UUID userId = UUID.fromString(payment.get("userId").toString());
    if (!db.currentUserIdOrThrow().equals(userId)) {
      throw new ForbiddenException("Bạn không có quyền xem hoặc xử lý thanh toán này.");
    }
  }

  private void requireAdmin() {
    if (!db.isAdmin()) throw new ForbiddenException("Bạn cần quyền admin để thực hiện thao tác này.");
  }

  private boolean exists(String sql, Object... args) {
    Integer count = jdbc.queryForObject("select count(*) from (" + sql + ") x", Integer.class, args);
    return count != null && count > 0;
  }

  private String existingGatewayOrder(UUID paymentId, String gateway) {
    return db.optional("""
        select gateway_order_id from payment_transactions
        where payment_id = ? and gateway = ? and gateway_order_id is not null
        order by created_at desc limit 1
        """, (rs, row) -> rs.getString(1), paymentId, gateway).orElse(gateway + "-" + paymentId.toString().substring(0, 8));
  }

  private String latestGatewayTransactionId(UUID paymentId) {
    return db.optional("""
        select coalesce(gateway_transaction_id, gateway_order_id)
        from payment_transactions
        where payment_id = ?
        order by updated_at desc, created_at desc limit 1
        """, (rs, row) -> rs.getString(1), paymentId).orElse("manual-tx-" + paymentId);
  }

  private RowMapper<Map<String, Object>> transactionMapper() {
    return (rs, row) -> {
      Map<String, Object> m = base(rs);
      m.put("paymentId", str(rs, "payment_id"));
      m.put("gateway", rs.getString("gateway"));
      m.put("gatewayOrderId", rs.getString("gateway_order_id"));
      m.put("gatewayTransactionId", rs.getString("gateway_transaction_id"));
      m.put("amount", rs.getInt("amount"));
      m.put("currency", rs.getString("currency"));
      m.put("status", rs.getString("status"));
      m.put("requestPayload", rs.getObject("request_payload"));
      m.put("rawResponse", rs.getObject("raw_response"));
      return m;
    };
  }

  private RowMapper<Map<String, Object>> webhookEventMapper() {
    return (rs, row) -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", str(rs, "id"));
      m.put("gateway", rs.getString("gateway"));
      m.put("eventId", rs.getString("event_id"));
      m.put("paymentId", str(rs, "payment_id"));
      m.put("gatewayOrderId", rs.getString("gateway_order_id"));
      m.put("gatewayTransactionId", rs.getString("gateway_transaction_id"));
      m.put("signatureValid", rs.getBoolean("signature_valid"));
      m.put("processed", rs.getBoolean("processed"));
      m.put("processingError", rs.getString("processing_error"));
      m.put("payload", rs.getObject("payload"));
      m.put("receivedAt", ts(rs.getObject("received_at", OffsetDateTime.class)));
      m.put("processedAt", ts(rs.getObject("processed_at", OffsetDateTime.class)));
      return m;
    };
  }

  private RowMapper<Map<String, Object>> refundMapper() {
    return (rs, row) -> {
      Map<String, Object> m = base(rs);
      m.put("paymentId", str(rs, "payment_id"));
      m.put("amount", rs.getInt("amount"));
      m.put("reason", rs.getString("reason"));
      m.put("status", rs.getString("status"));
      m.put("gatewayRefundId", rs.getString("gateway_refund_id"));
      m.put("requestedBy", str(rs, "requested_by"));
      m.put("processedBy", str(rs, "processed_by"));
      return m;
    };
  }

  private RowMapper<Map<String, Object>> invoiceMapper() {
    return (rs, row) -> {
      Map<String, Object> m = base(rs);
      m.put("paymentId", str(rs, "payment_id"));
      m.put("invoiceNo", rs.getString("invoice_no"));
      m.put("userId", str(rs, "user_id"));
      m.put("amount", rs.getInt("amount"));
      m.put("currency", rs.getString("currency"));
      m.put("status", rs.getString("status"));
      m.put("issuedAt", ts(rs.getObject("issued_at", OffsetDateTime.class)));
      m.put("paidAt", ts(rs.getObject("paid_at", OffsetDateTime.class)));
      m.put("fileUrl", rs.getString("file_url"));
      return m;
    };
  }

  private RowMapper<Map<String, Object>> receiptMapper() {
    return (rs, row) -> {
      Map<String, Object> m = base(rs);
      m.put("paymentId", str(rs, "payment_id"));
      m.put("receiptNo", rs.getString("receipt_no"));
      m.put("userId", str(rs, "user_id"));
      m.put("amount", rs.getInt("amount"));
      m.put("currency", rs.getString("currency"));
      m.put("issuedAt", ts(rs.getObject("issued_at", OffsetDateTime.class)));
      m.put("fileUrl", rs.getString("file_url"));
      return m;
    };
  }

  private Map<String, Object> base(ResultSet rs) throws SQLException {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", str(rs, "id"));
    m.put("createdAt", ts(rs.getObject("created_at", OffsetDateTime.class)));
    m.put("updatedAt", hasColumn(rs, "updated_at") ? ts(rs.getObject("updated_at", OffsetDateTime.class)) : null);
    return m;
  }

  private List<String> jsonListSetting(String key, List<String> fallback) {
    String raw = db.optional("select value::text from system_settings where key = ?", (rs, row) -> rs.getString(1), key).orElse(null);
    if (raw == null) return fallback;
    try {
      return objectMapper.readValue(raw, new TypeReference<List<String>>() {});
    } catch (Exception ex) {
      return fallback;
    }
  }

  private Object paidAt(Map<String, Object> payment) {
    return payment.get("paidAt") == null ? null : OffsetDateTime.parse(payment.get("paidAt").toString());
  }

  private String payloadJson(String rawPayload) {
    try {
      JsonNode parsed = objectMapper.readTree(rawPayload);
      return objectMapper.writeValueAsString(parsed);
    } catch (Exception ex) {
      return json(Map.of("raw", valueOr(rawPayload, ""), "parseError", true));
    }
  }

  private String jsonOrEmpty(String raw) {
    if (raw == null || raw.isBlank()) return "{}";
    return payloadJson(raw);
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception ex) {
      return "{}";
    }
  }

  private String string(Map<String, Object> body, String key) {
    Object value = body == null ? null : body.get(key);
    return value == null ? null : value.toString();
  }

  private String value(Object value) {
    return value == null ? null : value.toString();
  }

  private String valueOr(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private int number(Object value) {
    if (value instanceof Number number) return number.intValue();
    return value == null ? 0 : Integer.parseInt(value.toString());
  }

  private UUID uuidOrNull(Object value) {
    return value == null ? null : UUID.fromString(value.toString());
  }

  private String str(ResultSet rs, String column) throws SQLException {
    Object value = rs.getObject(column);
    return value == null ? null : value.toString();
  }

  private String ts(OffsetDateTime value) {
    return value == null ? null : value.toString();
  }

  private boolean hasColumn(ResultSet rs, String column) throws SQLException {
    int count = rs.getMetaData().getColumnCount();
    for (int i = 1; i <= count; i++) {
      if (rs.getMetaData().getColumnLabel(i).equalsIgnoreCase(column)) return true;
    }
    return false;
  }

  private record WebhookEventInsert(UUID id, boolean inserted) {}
}
