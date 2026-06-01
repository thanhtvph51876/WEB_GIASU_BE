package com.example.tutorplatform.admin;

import com.example.tutorplatform.db.DbService;
import com.example.tutorplatform.common.BusinessException;
import com.example.tutorplatform.common.NotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AdminOperationService {
  private final JdbcTemplate jdbc;
  private final DbService db;

  public AdminOperationService(DbService db) {
    this.db = db;
    this.jdbc = db.jdbc();
  }

  public Map<String, Object> overview() {
    int trialUpcoming = count("select count(*) from trial_bookings where status = 'scheduled' and scheduled_start between now() and now() + interval '48 hours'");
    int noShow = count("select count(*) from booking_no_show_records where created_at > now() - interval '30 days'");
    int paymentPending = count("select count(*) from payments where status in ('pending','processing','failed')");
    int payoutPending = count("select count(*) from payouts where status in ('pending','processing','approved')");
    int verificationPending = count("select count(*) from user_verifications where status in ('pending_review','need_more_info')");
    int disputePending = count("select count(*) from booking_disputes where status in ('OPEN','IN_REVIEW')");
    return Map.ofEntries(
        Map.entry("newRequests", count("select count(*) from learning_requests where status in ('new','submitted')")),
        Map.entry("unmatchedRequests", count("select count(*) from learning_requests where assigned_tutor_id is null and status in ('new','submitted','matching','waiting_tutor_proposal')")),
        Map.entry("overdueRequests", count("select count(*) from learning_requests where status not in ('cancelled','completed','closed','converted_to_class') and created_at < now() - interval '24 hours'")),
        Map.entry("riskyRequests", count("select count(*) from learning_requests lr where status in ('matching','waiting_tutor_proposal') and not exists (select 1 from tutor_proposals tp where tp.learning_request_id = lr.id) and lr.created_at < now() - interval '12 hours'")),
        Map.entry("trialUpcoming", trialUpcoming),
        Map.entry("upcomingTrialBookings", trialUpcoming),
        Map.entry("trialCancelled", count("select count(*) from trial_bookings where status in ('cancelled','cancelled_by_parent','cancelled_by_tutor') and updated_at > now() - interval '7 days'")),
        Map.entry("noShow", noShow),
        Map.entry("noShowBookings", noShow),
        Map.entry("activeClasses", count("select count(*) from tutoring_classes where status = 'active'")),
        Map.entry("paymentPending", paymentPending),
        Map.entry("pendingPayments", paymentPending),
        Map.entry("payoutPending", payoutPending),
        Map.entry("pendingPayouts", payoutPending),
        Map.entry("verificationPending", verificationPending),
        Map.entry("pendingVerifications", verificationPending),
        Map.entry("disputePending", disputePending),
        Map.entry("pendingDisputes", disputePending)
    );
  }

  public List<Map<String, Object>> matchingQueue() {
    return query("""
        select lr.id, lr.request_code, lr.student_name, lr.parent_name, lr.status, lr.created_at, s.name subject, gl.name grade,
          lr.province, lr.district,
          (select count(*) from tutor_proposals tp where tp.learning_request_id = lr.id) proposal_count
        from learning_requests lr
        join subjects s on s.id = lr.subject_id
        left join grade_levels gl on gl.id = lr.grade_level_id
        where lr.status in ('new','submitted','matching','waiting_tutor_proposal','proposal_received','rematch')
        order by lr.created_at asc limit 200
        """);
  }

  public List<Map<String, Object>> bookingRisk() {
    return query("""
        select tb.id, tb.status, tb.learning_mode, tb.scheduled_start, tb.scheduled_start scheduled_start_time,
          tb.location, tb.parent_confirmed_at, tb.tutor_confirmed_at,
          tb.student_name, s.name subject, tu.full_name tutor_name
        from trial_bookings tb
        join subjects s on s.id = tb.subject_id
        join tutor_profiles tp on tp.id = tb.tutor_id
        join users tu on tu.id = tp.user_id
        where tb.status in ('requested','parent_confirmed','tutor_confirmed','reschedule_requested','cancelled_by_parent','cancelled_by_tutor','no_show_parent','no_show_tutor')
           or (tb.status = 'scheduled' and tb.scheduled_start < now() + interval '24 hours')
        order by tb.updated_at desc limit 200
        """);
  }

  public List<Map<String, Object>> verificationRisk() {
    return query("""
        select uv.id, uv.verification_type, uv.status, uv.duplicate_file, uv.risk_score, uv.created_at, u.email, u.full_name
        from user_verifications uv join users u on u.id = uv.user_id
        where uv.status in ('pending_review','need_more_info') or uv.duplicate_file = true or uv.risk_score >= 70
        order by uv.risk_score desc, uv.created_at asc limit 200
        """);
  }

  public List<Map<String, Object>> paymentReconciliation() {
    return query("""
        select p.id, p.amount, p.currency, p.status, p.gateway, p.created_at, p.updated_at,
          u.full_name user_name, u.full_name full_name,
          (select count(*) from payment_transactions pt where pt.payment_id = p.id) transaction_count
        from payments p join users u on u.id = p.user_id
        where p.status in ('pending','processing','failed','expired')
        order by p.created_at asc limit 200
        """);
  }

  public List<Map<String, Object>> payoutQueue() {
    return query("""
        select po.id, po.amount, po.status, po.created_at, po.bank_name, po.bank_account, u.full_name tutor_name
        from payouts po join tutor_profiles tp on tp.id = po.tutor_id join users u on u.id = tp.user_id
        where po.status in ('pending','processing','approved')
        order by po.created_at asc limit 200
        """);
  }

  public List<Map<String, Object>> tutorQuality() {
    return query("""
        select tp.id, u.full_name tutor_name, tp.rating_avg, tp.rating_count, tp.response_rate, tp.total_sessions,
          (select count(*) from trial_bookings tb where tb.tutor_id = tp.id and tb.status in ('cancelled_by_tutor','no_show_tutor')) quality_incidents
        from tutor_profiles tp join users u on u.id = tp.user_id
        where tp.status = 'approved' and (tp.rating_avg < 3.5 or tp.response_rate < 50 or exists (select 1 from trial_bookings tb where tb.tutor_id = tp.id and tb.status in ('cancelled_by_tutor','no_show_tutor')))
        order by quality_incidents desc, tp.rating_avg asc limit 200
        """);
  }

  public List<Map<String, Object>> disputes() {
    return query("""
        select bd.*, tb.student_name, s.name subject
        from booking_disputes bd join trial_bookings tb on tb.id = bd.booking_id join subjects s on s.id = tb.subject_id
        order by bd.created_at desc limit 200
        """);
  }

  public Map<String, Object> dispute(UUID id) {
    List<Map<String, Object>> rows = jdbc.query("""
        select bd.*, tb.student_name, s.name subject
        from booking_disputes bd join trial_bookings tb on tb.id = bd.booking_id join subjects s on s.id = tb.subject_id
        where bd.id = ?
        """, this::mapAny, id);
    if (rows.isEmpty()) throw new NotFoundException("Không tìm thấy khiếu nại.");
    return rows.get(0);
  }

  public Map<String, Object> updateDispute(UUID id, Map<String, Object> body) {
    String status = string(body.get("status"));
    String resolution = string(body.get("resolution"));
    if (status == null || !List.of("OPEN", "IN_REVIEW", "RESOLVED", "REJECTED").contains(status)) {
      throw new BusinessException("INVALID_DISPUTE_STATUS", "Trạng thái khiếu nại không hợp lệ.");
    }
    boolean terminal = List.of("RESOLVED", "REJECTED").contains(status);
    jdbc.update("""
        update booking_disputes
        set status = ?, resolution = coalesce(?, resolution),
          resolved_by = case when ? then ? else resolved_by end,
          resolved_at = case when ? then now() else resolved_at end,
          updated_at = now()
        where id = ?
        """, status, resolution, terminal, terminal ? db.currentUserIdOrThrow() : null, terminal, id);
    db.auditCurrent("admin.dispute.update", "bookingDispute", id, "Admin cập nhật khiếu nại sang " + status + ".");
    return dispute(id);
  }

  private List<Map<String, Object>> query(String sql) {
    return jdbc.query(sql, this::mapAny);
  }

  private int count(String sql) {
    Integer value = jdbc.queryForObject(sql, Integer.class);
    return value == null ? 0 : value;
  }

  private Map<String, Object> mapAny(ResultSet rs, int row) throws SQLException {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
      String key = camel(rs.getMetaData().getColumnLabel(i));
      Object value = rs.getObject(i);
      if (value instanceof UUID uuid) value = uuid.toString();
      if (value instanceof OffsetDateTime time) value = time.toString();
      m.put(key, value);
    }
    return m;
  }

  private String camel(String label) {
    StringBuilder sb = new StringBuilder();
    boolean upper = false;
    for (char c : label.toCharArray()) {
      if (c == '_') upper = true;
      else if (upper) { sb.append(Character.toUpperCase(c)); upper = false; }
      else sb.append(c);
    }
    return sb.toString();
  }

  private String string(Object value) {
    return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
  }
}
