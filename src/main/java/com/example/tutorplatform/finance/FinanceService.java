package com.example.tutorplatform.finance;

import static com.example.tutorplatform.platform.PlatformRequestSupport.firstString;
import static com.example.tutorplatform.platform.PlatformRequestSupport.integer;
import static com.example.tutorplatform.platform.PlatformRequestSupport.uuid;

import com.example.tutorplatform.common.BusinessException;
import com.example.tutorplatform.common.ForbiddenException;
import com.example.tutorplatform.db.DbService;
import com.example.tutorplatform.payment.PaymentService;
import com.example.tutorplatform.policy.StatusTransitionPolicy;
import com.example.tutorplatform.security.PermissionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FinanceService {
  private final DbService db;
  private final JdbcTemplate jdbc;
  private final PaymentService paymentService;
  private final StatusTransitionPolicy statusPolicy;
  private final EarningLedgerService ledgerService;
  private final PermissionService permissions;

  public FinanceService(DbService db, PaymentService paymentService, StatusTransitionPolicy statusPolicy, EarningLedgerService ledgerService, PermissionService permissions) {
    this.db = db;
    this.jdbc = db.jdbc();
    this.paymentService = paymentService;
    this.statusPolicy = statusPolicy;
    this.ledgerService = ledgerService;
    this.permissions = permissions;
  }

  public List<Map<String, Object>> payments() {
    UUID userId = db.currentUserIdOrThrow();
    return jdbc.query("select * from payments where user_id = ? order by created_at desc limit 300", db.paymentMapper(), userId);
  }

  public Map<String, Object> payment(UUID paymentId) {
    Map<String, Object> payment = jdbc.queryForObject("select * from payments where id = ?", db.paymentMapper(), paymentId);
    if (!permissions.has("payments.read") && !db.currentUserIdOrThrow().equals(uuid(payment.get("userId")))) {
      throw new ForbiddenException("Bạn không có quyền xem thanh toán này.");
    }
    return payment;
  }

  public List<Map<String, Object>> tutorEarnings() {
    UUID tutorId = db.tutorIdByUserOrThrow(db.currentUserIdOrThrow());
    return jdbc.query("select * from tutor_earnings where tutor_id = ? order by created_at desc limit 300", db.earningMapper(), tutorId);
  }

  public List<Map<String, Object>> tutorPayments() {
    UUID tutorId = db.tutorIdByUserOrThrow(db.currentUserIdOrThrow());
    return jdbc.query("select * from payments where tutor_id = ? order by created_at desc limit 300", db.paymentMapper(), tutorId);
  }

  public List<Map<String, Object>> tutorPayouts() {
    UUID tutorId = db.tutorIdByUserOrThrow(db.currentUserIdOrThrow());
    return jdbc.query("""
        select p.*, u.full_name tutor_name from payouts p
        join tutor_profiles tp on tp.id = p.tutor_id join users u on u.id = tp.user_id
        where p.tutor_id = ? order by p.created_at desc
        limit 300
        """, db.payoutMapper(), tutorId);
  }

  @Transactional
  public Map<String, Object> createPayout(Map<String, Object> body) {
    UUID userId = db.currentUserIdOrThrow();
    if (!hasApprovedVerification(userId, "tutor_identity", "tutor_certificate")) {
      throw new BusinessException("TUTOR_VERIFICATION_REQUIRED", "Gia sư cần hoàn tất xác thực giấy tờ trước khi rút tiền.");
    }
    UUID tutorId = db.tutorIdByUserOrThrow(userId);
    int amount = integer(body, "amount");
    List<Map<String, Object>> availableEarnings = availableEarningsForUpdate(tutorId);
    int balance = availableEarnings.stream().mapToInt(row -> ((Number) row.get("netAmount")).intValue()).sum();
    if (amount <= 0 || amount > balance) throw new BusinessException("INVALID_PAYOUT_AMOUNT", "Số tiền rút vượt quá số dư khả dụng.");
    UUID id = jdbc.queryForObject("""
        insert into payouts(tutor_id, amount, bank_name, bank_account, account_holder, status)
        values (?, ?, ?, ?, ?, 'pending') returning id
        """, UUID.class, tutorId, amount, firstString(body, "bankName"), firstString(body, "bankAccount"), firstString(body, "accountHolder"));
    allocatePayoutEarnings(id, availableEarnings, amount);
    db.auditCurrent("tutor.create_payout", "payout", id, "Gia sư đã yêu cầu rút " + amount + " VND.");
    return payoutById(id);
  }

  public List<Map<String, Object>> adminPayments() {
    permissions.require("payments.read");
    return jdbc.query("select * from payments order by created_at desc limit 500", db.paymentMapper());
  }

  public List<Map<String, Object>> adminPayments(int limit, int offset) {
    permissions.require("payments.read");
    return jdbc.query("select * from payments order by created_at desc limit ? offset ?", db.paymentMapper(), limit, offset);
  }

  public Map<String, Object> adminPayment(UUID paymentId) {
    permissions.require("payments.read");
    return jdbc.queryForObject("select * from payments where id = ?", db.paymentMapper(), paymentId);
  }

  public Map<String, Object> markPaid(UUID paymentId, Map<String, Object> body) {
    return paymentService.adminMarkPaid(paymentId, body == null ? null : firstString(body, "reason", "note"));
  }

  public Map<String, Object> markFailed(UUID paymentId, Map<String, Object> body) {
    return paymentService.adminMarkFailed(paymentId, body == null ? null : firstString(body, "reason", "note"));
  }

  public Map<String, Object> refund(UUID paymentId, Map<String, Object> body) {
    return paymentService.refund(paymentId, body == null ? Map.of() : body);
  }

  public List<Map<String, Object>> adminPayouts() {
    permissions.require("payouts.read");
    return jdbc.query("""
        select p.*, u.full_name tutor_name from payouts p
        join tutor_profiles tp on tp.id = p.tutor_id join users u on u.id = tp.user_id
        order by p.created_at desc
        limit 500
        """, db.payoutMapper());
  }

  public List<Map<String, Object>> adminPayouts(int limit, int offset) {
    permissions.require("payouts.read");
    return jdbc.query("""
        select p.*, u.full_name tutor_name from payouts p
        join tutor_profiles tp on tp.id = p.tutor_id join users u on u.id = tp.user_id
        order by p.created_at desc
        limit ? offset ?
        """, db.payoutMapper(), limit, offset);
  }

  public Map<String, Object> adminPayout(UUID payoutId) {
    permissions.require("payouts.read");
    return payoutById(payoutId);
  }

  @Transactional
  public Map<String, Object> approvePayout(UUID payoutId, Map<String, Object> body) {
    permissions.require("payouts.approve");
    String reason = requiredReason(body);
    Map<String, Object> payout = payoutByIdForUpdate(payoutId);
    statusPolicy.requirePayout(payout.get("status").toString(), "paid");
    List<Map<String, Object>> items = payoutItemsForUpdate(payoutId);
    if (items.isEmpty()) {
      throw new BusinessException("PAYOUT_ITEMS_REQUIRED", "Payout không có earning item để duyệt.");
    }
    jdbc.update("update payouts set status = 'paid', processed_by = ?, processed_at = now(), updated_at = now() where id = ?", db.currentUserIdOrThrow(), payoutId);
    UUID tutorId = jdbc.queryForObject("select tutor_id from payouts where id = ?", UUID.class, payoutId);
    for (Map<String, Object> item : items) {
      UUID earningId = (UUID) item.get("earningId");
      int updated = jdbc.update("""
          update tutor_earnings
          set status = 'paid', updated_at = now()
          where id = ? and status = 'payout_pending'
          """, earningId);
      if (updated == 0) {
        throw new BusinessException("PAYOUT_ITEM_NOT_LOCKED", "Earning trong payout chưa được lock hoặc đã bị xử lý.");
      }
      ledgerService.recordForEarning(earningId, payoutId, "payout_paid", 0, "Payout đã được admin duyệt/chi trả.");
    }
    UUID tutorUser = jdbc.queryForObject("select user_id from tutor_profiles where id = ?", UUID.class, tutorId);
    db.notify(tutorUser, "success", "Rút tiền đã được duyệt", "Yêu cầu rút tiền của bạn đã được duyệt.", "/dashboard/tutor/earnings", "payout", payoutId);
    db.auditCurrent("admin.approve_payout", "payout", payoutId, "Admin duyệt yêu cầu rút tiền. Lý do: " + reason);
    return payoutById(payoutId);
  }

  @Transactional
  public Map<String, Object> rejectPayout(UUID payoutId, Map<String, Object> body) {
    permissions.require("payouts.reject");
    Map<String, Object> payout = payoutByIdForUpdate(payoutId);
    statusPolicy.requirePayout(payout.get("status").toString(), "rejected");
    List<Map<String, Object>> items = payoutItemsForUpdate(payoutId);
    String reason = requiredReason(body);
    jdbc.update("update payouts set status = 'rejected', admin_note = ?, processed_by = ?, processed_at = now(), updated_at = now() where id = ?",
        reason, db.currentUserIdOrThrow(), payoutId);
    jdbc.update("update payout_earning_items set released_at = now() where payout_id = ? and released_at is null", payoutId);
    UUID tutorId = jdbc.queryForObject("select tutor_id from payouts where id = ?", UUID.class, payoutId);
    for (Map<String, Object> item : items) {
      UUID earningId = (UUID) item.get("earningId");
      int amount = ((Number) item.get("amount")).intValue();
      jdbc.update("""
          update tutor_earnings
          set status = 'available', updated_at = now()
          where id = ? and status = 'payout_pending'
          """, earningId);
      ledgerService.recordForEarning(earningId, payoutId, "payout_released", amount, "Payout bị từ chối, earning được trả lại số dư khả dụng.");
    }
    UUID tutorUser = jdbc.queryForObject("select user_id from tutor_profiles where id = ?", UUID.class, tutorId);
    db.notify(tutorUser, "warning", "Rút tiền bị từ chối", "Yêu cầu rút tiền của bạn bị từ chối.", "/dashboard/tutor/earnings", "payout", payoutId);
    db.auditCurrent("admin.reject_payout", "payout", payoutId, "Admin đã từ chối payout với lý do " + reason + ".");
    return payoutById(payoutId);
  }

  private Map<String, Object> payoutById(UUID payoutId) {
    return jdbc.queryForObject("""
        select p.*, u.full_name tutor_name from payouts p
        join tutor_profiles tp on tp.id = p.tutor_id join users u on u.id = tp.user_id
        where p.id = ?
        """, db.payoutMapper(), payoutId);
  }

  private Map<String, Object> payoutByIdForUpdate(UUID payoutId) {
    return jdbc.queryForObject("""
        select p.*, u.full_name tutor_name from payouts p
        join tutor_profiles tp on tp.id = p.tutor_id join users u on u.id = tp.user_id
        where p.id = ?
        for update of p
        """, db.payoutMapper(), payoutId);
  }

  private List<Map<String, Object>> payoutItemsForUpdate(UUID payoutId) {
    return jdbc.query("""
        select pei.earning_id, pei.amount, te.tutor_id, te.payment_id, te.status
        from payout_earning_items pei
        join tutor_earnings te on te.id = pei.earning_id
        where pei.payout_id = ?
        order by pei.created_at, pei.id
        for update of te
        """, (rs, row) -> {
      Map<String, Object> m = new java.util.LinkedHashMap<>();
      m.put("earningId", rs.getObject("earning_id", UUID.class));
      m.put("amount", rs.getInt("amount"));
      m.put("tutorId", rs.getObject("tutor_id", UUID.class));
      m.put("paymentId", rs.getObject("payment_id", UUID.class));
      m.put("status", rs.getString("status"));
      return m;
    }, payoutId);
  }

  private List<Map<String, Object>> availableEarningsForUpdate(UUID tutorId) {
    return jdbc.query("""
        select id, tutor_id, session_id, payment_id, gross_amount, platform_fee, net_amount
        from tutor_earnings
        where tutor_id = ? and status = 'available'
        order by created_at, id
        for update
        """, (rs, row) -> {
      Map<String, Object> m = new java.util.LinkedHashMap<>();
      m.put("id", rs.getObject("id", UUID.class));
      m.put("tutorId", rs.getObject("tutor_id", UUID.class));
      m.put("sessionId", rs.getObject("session_id"));
      m.put("paymentId", rs.getObject("payment_id"));
      m.put("grossAmount", rs.getInt("gross_amount"));
      m.put("platformFee", rs.getInt("platform_fee"));
      m.put("netAmount", rs.getInt("net_amount"));
      return m;
    }, tutorId);
  }

  private void allocatePayoutEarnings(UUID payoutId, List<Map<String, Object>> availableEarnings, int requestedAmount) {
    int remaining = requestedAmount;
    for (Map<String, Object> earning : availableEarnings) {
      if (remaining <= 0) break;
      UUID earningId = (UUID) earning.get("id");
      int netAmount = ((Number) earning.get("netAmount")).intValue();
      if (netAmount <= remaining) {
        jdbc.update("update tutor_earnings set status = 'payout_pending', updated_at = now() where id = ?", earningId);
        jdbc.update("insert into payout_earning_items(payout_id, earning_id, amount) values (?, ?, ?)", payoutId, earningId, netAmount);
        ledgerService.recordForEarning(earningId, payoutId, "payout_locked", -netAmount, "Earning được lock vào payout.");
        remaining -= netAmount;
      } else {
        UUID splitId = splitEarningForPayout(earning, remaining);
        jdbc.update("insert into payout_earning_items(payout_id, earning_id, amount) values (?, ?, ?)", payoutId, splitId, remaining);
        ledgerService.recordForEarning(splitId, payoutId, "payout_locked", -remaining, "Một phần earning được lock vào payout.");
        remaining = 0;
      }
    }
    if (remaining != 0) {
      throw new BusinessException("PAYOUT_ALLOCATION_FAILED", "Không thể lock đủ earning cho yêu cầu rút tiền.");
    }
  }

  private UUID splitEarningForPayout(Map<String, Object> earning, int allocatedNetAmount) {
    UUID originalId = (UUID) earning.get("id");
    int originalNet = ((Number) earning.get("netAmount")).intValue();
    int originalGross = ((Number) earning.get("grossAmount")).intValue();
    int originalFee = ((Number) earning.get("platformFee")).intValue();
    int allocatedGross = Math.max(allocatedNetAmount, (int) Math.round(originalGross * (allocatedNetAmount / (double) originalNet)));
    int allocatedFee = Math.max(0, allocatedGross - allocatedNetAmount);
    int remainingGross = Math.max(0, originalGross - allocatedGross);
    int remainingFee = Math.max(0, originalFee - allocatedFee);
    int remainingNet = originalNet - allocatedNetAmount;

    jdbc.update("""
        update tutor_earnings
        set gross_amount = ?, platform_fee = ?, net_amount = ?, updated_at = now()
        where id = ?
        """, remainingGross, remainingFee, remainingNet, originalId);
    UUID splitId = jdbc.queryForObject("""
        insert into tutor_earnings(tutor_id, session_id, payment_id, gross_amount, platform_fee, net_amount, status)
        values (?, null, ?, ?, ?, ?, 'payout_pending')
        returning id
        """, UUID.class, earning.get("tutorId"), earning.get("paymentId"),
        allocatedGross, allocatedFee, allocatedNetAmount);
    ledgerService.record(splitId, (UUID) earning.get("tutorId"), (UUID) earning.get("paymentId"), null,
        "earning_created", allocatedNetAmount, "Tách một phần earning để xử lý payout.");
    return splitId;
  }

  private boolean hasApprovedVerification(UUID userId, String... types) {
    if (types == null || types.length == 0) return false;
    String placeholders = String.join(",", java.util.Collections.nCopies(types.length, "?"));
    List<Object> args = new ArrayList<>();
    args.add(userId);
    args.addAll(List.of(types));
    Integer count = jdbc.queryForObject("""
        select count(distinct uv.verification_type)
        from user_verifications uv
        join verification_agreements va on va.verification_id = uv.id
        where uv.user_id = ?
          and uv.status = 'approved'
          and uv.duplicate_file = false
          and uv.risk_score <= 60
          and uv.verification_type in (
        """ + placeholders + ")", Integer.class, args.toArray());
    return count != null && count == types.length;
  }

  private String requiredReason(Map<String, Object> body) {
    String reason = firstString(body, "reason", "note", "statusReason");
    if (reason == null || reason.isBlank()) throw new BusinessException("REASON_REQUIRED", "Cần nhập lý do.");
    return reason;
  }
}
