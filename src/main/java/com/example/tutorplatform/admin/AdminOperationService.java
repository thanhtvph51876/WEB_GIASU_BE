package com.example.tutorplatform.admin;

import com.example.tutorplatform.db.DbService;
import com.example.tutorplatform.common.BusinessException;
import com.example.tutorplatform.common.NotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AdminOperationService {
  private static final Duration CACHE_TTL = Duration.ofSeconds(20);
  private static final List<String> DISPUTE_STATUSES = List.of(
      "NEW",
      "ASSIGNED",
      "INVESTIGATING",
      "WAITING_PARENT",
      "WAITING_TUTOR",
      "PROPOSED_RESOLUTION",
      "RESOLVED",
      "CLOSED",
      "ESCALATED",
      "REJECTED"
  );
  private static final Map<String, Set<String>> DISPUTE_TRANSITIONS = Map.ofEntries(
      Map.entry("NEW", Set.of("ASSIGNED", "INVESTIGATING", "ESCALATED", "REJECTED")),
      Map.entry("ASSIGNED", Set.of("INVESTIGATING", "WAITING_PARENT", "WAITING_TUTOR", "PROPOSED_RESOLUTION", "ESCALATED", "REJECTED")),
      Map.entry("INVESTIGATING", Set.of("WAITING_PARENT", "WAITING_TUTOR", "PROPOSED_RESOLUTION", "RESOLVED", "ESCALATED", "REJECTED")),
      Map.entry("WAITING_PARENT", Set.of("INVESTIGATING", "PROPOSED_RESOLUTION", "ESCALATED", "REJECTED")),
      Map.entry("WAITING_TUTOR", Set.of("INVESTIGATING", "PROPOSED_RESOLUTION", "ESCALATED", "REJECTED")),
      Map.entry("PROPOSED_RESOLUTION", Set.of("INVESTIGATING", "RESOLVED", "ESCALATED", "REJECTED")),
      Map.entry("RESOLVED", Set.of("CLOSED", "ESCALATED")),
      Map.entry("ESCALATED", Set.of("INVESTIGATING", "PROPOSED_RESOLUTION", "RESOLVED", "REJECTED")),
      Map.entry("REJECTED", Set.of("CLOSED"))
  );
  private static final Set<String> DISPUTE_RESOLUTION_TYPES = Set.of(
      "NO_ACTION",
      "WARNING",
      "REFUND",
      "PARTIAL_REFUND",
      "COMPENSATION",
      "TUTOR_SUSPENDED",
      "BOOKING_CANCELLED",
      "CLASS_CANCELLED",
      "OTHER"
  );
  private final JdbcTemplate jdbc;
  private final DbService db;
  private final ObjectMapper objectMapper;
  private final Map<String, CacheEntry<?>> cache = new ConcurrentHashMap<>();

  public AdminOperationService(DbService db, ObjectMapper objectMapper) {
    this.db = db;
    this.objectMapper = objectMapper;
    this.jdbc = db.jdbc();
  }

  public Map<String, Object> overview() {
    return cached("overview", () -> {
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
    });
  }

  public List<Map<String, Object>> matchingQueue() {
    return cached("matchingQueue", () -> query("""
        select lr.id, lr.request_code, lr.student_name, lr.parent_name, lr.status, lr.created_at, s.name subject, gl.name grade,
          lr.province, lr.district,
          (select count(*) from tutor_proposals tp where tp.learning_request_id = lr.id) proposal_count
        from learning_requests lr
        join subjects s on s.id = lr.subject_id
        left join grade_levels gl on gl.id = lr.grade_level_id
        where lr.status in ('new','submitted','matching','waiting_tutor_proposal','proposal_received','rematch')
        order by lr.created_at asc limit 200
        """));
  }

  public List<Map<String, Object>> bookingRisk() {
    return cached("bookingRisk", () -> query("""
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
        """));
  }

  public List<Map<String, Object>> verificationRisk() {
    return cached("verificationRisk", () -> query("""
        select uv.id, uv.verification_type, uv.status, uv.duplicate_file, uv.risk_score, uv.created_at, u.email, u.full_name
        from user_verifications uv join users u on u.id = uv.user_id
        where uv.status in ('pending_review','need_more_info') or uv.duplicate_file = true or uv.risk_score >= 70
        order by uv.risk_score desc, uv.created_at asc limit 200
        """));
  }

  public List<Map<String, Object>> paymentReconciliation() {
    return cached("paymentReconciliation", () -> query("""
        select p.id, p.amount, p.currency, p.status, p.gateway, p.created_at, p.updated_at,
          u.full_name user_name, u.full_name full_name,
          (select count(*) from payment_transactions pt where pt.payment_id = p.id) transaction_count
        from payments p join users u on u.id = p.user_id
        where p.status in ('pending','processing','failed','expired')
        order by p.created_at asc limit 200
        """));
  }

  public List<Map<String, Object>> payoutQueue() {
    return cached("payoutQueue", () -> query("""
        select po.id, po.amount, po.status, po.created_at, po.bank_name, po.bank_account, u.full_name tutor_name
        from payouts po join tutor_profiles tp on tp.id = po.tutor_id join users u on u.id = tp.user_id
        where po.status in ('pending','processing','approved')
        order by po.created_at asc limit 200
        """));
  }

  public List<Map<String, Object>> tutorQuality() {
    return cached("tutorQuality", () -> query("""
        select tp.id, u.full_name tutor_name, tp.rating_avg, tp.rating_count, tp.response_rate, tp.total_sessions,
          (select count(*) from trial_bookings tb where tb.tutor_id = tp.id and tb.status in ('cancelled_by_tutor','no_show_tutor')) quality_incidents
        from tutor_profiles tp join users u on u.id = tp.user_id
        where tp.status = 'approved' and (tp.rating_avg < 3.5 or tp.response_rate < 50 or exists (select 1 from trial_bookings tb where tb.tutor_id = tp.id and tb.status in ('cancelled_by_tutor','no_show_tutor')))
        order by quality_incidents desc, tp.rating_avg asc limit 200
        """));
  }

  public List<Map<String, Object>> workItems() {
    return cached("workItems", () -> {
      List<Map<String, Object>> items = new ArrayList<>();
      addWorkItems(items, """
          select tp.id, 'tutors' module, 'TUTOR_PENDING_APPROVAL' item_type,
            coalesce(u.full_name, u.email, 'Tutor ' || tp.id::text) title, tp.status,
            case when tp.created_at < now() - interval '48 hours' then 'CRITICAL'
                 when tp.created_at < now() - interval '24 hours' then 'HIGH'
                 else 'MEDIUM' end priority,
            case when tp.status in ('need_update','needs_more_documents') then 'HIGH' else 'MEDIUM' end risk_level,
            tp.created_at + interval '24 hours' sla_due_at,
            (tp.created_at + interval '24 hours') < now() overdue,
            'Duyệt hồ sơ gia sư hoặc yêu cầu bổ sung.' recommended_action,
            concat('/admin/tutors/', tp.id::text) detail_href,
            null::text assigned_admin,
            tp.created_at, tp.updated_at, 'TUTOR' related_type, tp.id related_id
          from tutor_profiles tp join users u on u.id = tp.user_id
          where tp.status in ('submitted','pending','pending_verification','verified','need_update','needs_more_documents')
          order by tp.created_at asc limit 100
          """);
      addWorkItems(items, """
          select uv.id, 'verifications' module, 'VERIFICATION_PENDING' item_type,
            coalesce(u.full_name, u.email, 'Verification ' || uv.id::text) title, uv.status,
            case when uv.duplicate_file = true or uv.risk_score >= 70 then 'CRITICAL'
                 when uv.created_at < now() - interval '24 hours' then 'HIGH'
                 else 'MEDIUM' end priority,
            case when uv.duplicate_file = true or uv.risk_score >= 70 then 'CRITICAL'
                 when uv.risk_score >= 40 then 'HIGH'
                 else 'MEDIUM' end risk_level,
            uv.created_at + interval '24 hours' sla_due_at,
            (uv.created_at + interval '24 hours') < now() overdue,
            'Review giấy tờ xác minh và xử lý rủi ro trùng file/risk score.' recommended_action,
            concat('/admin/verifications?id=', uv.id::text) detail_href,
            null::text assigned_admin,
            uv.created_at, uv.updated_at, 'USER' related_type, uv.user_id related_id
          from user_verifications uv join users u on u.id = uv.user_id
          where uv.status in ('pending_review','need_more_info') or uv.duplicate_file = true or uv.risk_score >= 70
          order by uv.risk_score desc, uv.created_at asc limit 100
          """);
      addWorkItems(items, """
          select lr.id, 'learningRequests' module, 'LEARNING_REQUEST_UNMATCHED' item_type,
            coalesce(lr.request_code, lr.student_name, 'Learning request ' || lr.id::text) title, lr.status,
            case when lr.created_at < now() - interval '24 hours' then 'CRITICAL'
                 when lr.created_at < now() - interval '12 hours' then 'HIGH'
                 else 'MEDIUM' end priority,
            case when lr.created_at < now() - interval '12 hours' then 'HIGH' else 'MEDIUM' end risk_level,
            lr.created_at + interval '12 hours' sla_due_at,
            (lr.created_at + interval '12 hours') < now() overdue,
            'Tìm gia sư phù hợp và assign hoặc chuyển sang rematch.' recommended_action,
            concat('/admin/requests/', lr.id::text) detail_href,
            null::text assigned_admin,
            lr.created_at, lr.updated_at, 'LEARNING_REQUEST' related_type, lr.id related_id
          from learning_requests lr
          where lr.assigned_tutor_id is null and lr.status in ('new','submitted','matching','waiting_tutor_proposal','proposal_received','rematch')
          order by lr.created_at asc limit 100
          """);
      addWorkItems(items, """
          select lr.id, 'learningRequests' module, 'REQUEST_MATCHING_FAIL' item_type,
            coalesce(lr.request_code, lr.student_name, 'Learning request ' || lr.id::text) title, lr.status,
            'HIGH' priority, 'HIGH' risk_level,
            lr.created_at + interval '12 hours' sla_due_at,
            true overdue,
            'Không có proposal sau SLA; cần rematch hoặc can thiệp thủ công.' recommended_action,
            concat('/admin/requests/', lr.id::text) detail_href,
            null::text assigned_admin,
            lr.created_at, lr.updated_at, 'LEARNING_REQUEST' related_type, lr.id related_id
          from learning_requests lr
          where lr.status in ('matching','waiting_tutor_proposal')
            and lr.created_at < now() - interval '12 hours'
            and not exists (select 1 from tutor_proposals tp where tp.learning_request_id = lr.id)
          order by lr.created_at asc limit 100
          """);
      addWorkItems(items, """
          select tb.id, 'bookings' module, 'BOOKING_UPCOMING' item_type,
            coalesce(tb.student_name, 'Booking ' || tb.id::text) title, tb.status,
            case when tb.scheduled_start < now() + interval '2 hours' then 'HIGH' else 'MEDIUM' end priority,
            case when tb.parent_confirmed_at is null or tb.tutor_confirmed_at is null then 'HIGH' else 'MEDIUM' end risk_level,
            tb.scheduled_start sla_due_at,
            false overdue,
            'Xác nhận lịch học thử và kiểm tra hai bên đã confirm.' recommended_action,
            concat('/admin/bookings/', tb.id::text) detail_href,
            null::text assigned_admin,
            tb.created_at, tb.updated_at, 'BOOKING' related_type, tb.id related_id
          from trial_bookings tb
          where tb.status = 'scheduled' and tb.scheduled_start between now() and now() + interval '24 hours'
          order by tb.scheduled_start asc limit 100
          """);
      addWorkItems(items, """
          select tb.id, 'bookings' module, 'BOOKING_OVERDUE' item_type,
            coalesce(tb.student_name, 'Booking ' || tb.id::text) title, tb.status,
            'CRITICAL' priority, 'CRITICAL' risk_level,
            tb.scheduled_start sla_due_at,
            true overdue,
            'Booking đã qua giờ nhưng chưa complete/cancel; cần xử lý kết quả học thử.' recommended_action,
            concat('/admin/bookings/', tb.id::text) detail_href,
            null::text assigned_admin,
            tb.created_at, tb.updated_at, 'BOOKING' related_type, tb.id related_id
          from trial_bookings tb
          where tb.status = 'scheduled' and tb.scheduled_start < now() - interval '2 hours'
          order by tb.scheduled_start asc limit 100
          """);
      addWorkItems(items, """
          select tb.id, 'bookings' module, 'BOOKING_NO_SHOW_RISK' item_type,
            coalesce(tb.student_name, 'Booking ' || tb.id::text) title, tb.status,
            'HIGH' priority, 'HIGH' risk_level,
            tb.updated_at + interval '4 hours' sla_due_at,
            (tb.updated_at + interval '4 hours') < now() overdue,
            'Xác minh no-show, quyết định rematch/cancel/refund nếu cần.' recommended_action,
            concat('/admin/bookings/', tb.id::text) detail_href,
            null::text assigned_admin,
            tb.created_at, tb.updated_at, 'BOOKING' related_type, tb.id related_id
          from trial_bookings tb
          where tb.status in ('no_show_parent','no_show_student','no_show_tutor')
             or exists (select 1 from booking_no_show_records bns where bns.booking_id = tb.id and bns.created_at > now() - interval '7 days')
          order by tb.updated_at desc limit 100
          """);
      addWorkItems(items, """
          select p.id, 'payments' module, 'PAYMENT_PENDING_LONG' item_type,
            coalesce(p.description, 'Payment ' || p.id::text) title, p.status,
            case when p.created_at < now() - interval '2 hours' then 'HIGH' else 'MEDIUM' end priority,
            case when p.created_at < now() - interval '2 hours' then 'HIGH' else 'MEDIUM' end risk_level,
            p.created_at + interval '30 minutes' sla_due_at,
            (p.created_at + interval '30 minutes') < now() overdue,
            'Đối soát gateway/webhook hoặc mark paid/failed theo chứng từ.' recommended_action,
            concat('/admin/payments?id=', p.id::text) detail_href,
            null::text assigned_admin,
            p.created_at, p.updated_at, 'PAYMENT' related_type, p.id related_id
          from payments p
          where p.status in ('pending','processing') and p.created_at < now() - interval '30 minutes'
          order by p.created_at asc limit 100
          """);
      addWorkItems(items, """
          select p.id, 'payments' module, 'PAYMENT_RECONCILIATION' item_type,
            coalesce(p.description, 'Payment ' || p.id::text) title, p.status,
            'HIGH' priority, 'HIGH' risk_level,
            p.updated_at + interval '4 hours' sla_due_at,
            (p.updated_at + interval '4 hours') < now() overdue,
            'Kiểm tra payment failed/expired và quyết định retry, cancel hoặc hỗ trợ khách.' recommended_action,
            concat('/admin/payments?id=', p.id::text) detail_href,
            null::text assigned_admin,
            p.created_at, p.updated_at, 'PAYMENT' related_type, p.id related_id
          from payments p
          where p.status in ('failed','expired')
          order by p.updated_at desc limit 100
          """);
      addWorkItems(items, """
          select pr.id, 'payments' module, 'REFUND_PENDING' item_type,
            coalesce(pr.reason, 'Refund ' || pr.id::text) title, pr.status,
            case when pr.created_at < now() - interval '24 hours' then 'HIGH' else 'MEDIUM' end priority,
            case when pr.created_at < now() - interval '24 hours' then 'HIGH' else 'MEDIUM' end risk_level,
            pr.created_at + interval '24 hours' sla_due_at,
            (pr.created_at + interval '24 hours') < now() overdue,
            'Theo dõi refund pending/processing và đối chiếu gateway.' recommended_action,
            concat('/admin/payments?id=', pr.payment_id::text) detail_href,
            null::text assigned_admin,
            pr.created_at, pr.updated_at, 'PAYMENT' related_type, pr.payment_id related_id
          from payment_refunds pr
          where pr.status in ('pending','processing')
          order by pr.created_at asc limit 100
          """);
      addWorkItems(items, """
          select po.id, 'payouts' module, 'PAYOUT_PENDING' item_type,
            coalesce(u.full_name, 'Payout ' || po.id::text) title, po.status,
            case when po.created_at < now() - interval '48 hours' then 'HIGH' else 'MEDIUM' end priority,
            case when po.bank_account is null or po.bank_name is null then 'HIGH' else 'MEDIUM' end risk_level,
            po.created_at + interval '48 hours' sla_due_at,
            (po.created_at + interval '48 hours') < now() overdue,
            'Kiểm tra earning/ngân hàng và approve hoặc reject payout.' recommended_action,
            concat('/admin/payouts?id=', po.id::text) detail_href,
            null::text assigned_admin,
            po.created_at, po.updated_at, 'PAYOUT' related_type, po.id related_id
          from payouts po join tutor_profiles tp on tp.id = po.tutor_id join users u on u.id = tp.user_id
          where po.status in ('pending','processing','approved')
          order by po.created_at asc limit 100
          """);
      addWorkItems(items, """
          select tp.id, 'tutors' module, 'TUTOR_QUALITY_WARNING' item_type,
            coalesce(u.full_name, u.email, 'Tutor ' || tp.id::text) title, tp.status,
            case when tp.rating_avg < 3 or exists (select 1 from trial_bookings tb where tb.tutor_id = tp.id and tb.status = 'no_show_tutor') then 'HIGH' else 'MEDIUM' end priority,
            case when tp.rating_avg < 3 or exists (select 1 from trial_bookings tb where tb.tutor_id = tp.id and tb.status = 'no_show_tutor') then 'HIGH' else 'MEDIUM' end risk_level,
            now() + interval '24 hours' sla_due_at,
            false overdue,
            'Rà chất lượng gia sư, review thấp, response rate và cân nhắc cảnh báo/suspend.' recommended_action,
            concat('/admin/tutors/', tp.id::text) detail_href,
            null::text assigned_admin,
            tp.created_at, tp.updated_at, 'TUTOR' related_type, tp.id related_id
          from tutor_profiles tp join users u on u.id = tp.user_id
          where tp.status = 'approved' and (tp.rating_avg < 3.5 or tp.response_rate < 50 or exists (select 1 from trial_bookings tb where tb.tutor_id = tp.id and tb.status in ('cancelled_by_tutor','no_show_tutor')))
          order by tp.rating_avg asc, tp.updated_at desc limit 100
          """);
      addWorkItems(items, """
          select bd.id, 'complaints' module, 'COMPLAINT_OPEN' item_type,
            coalesce(bd.title, bd.reason, 'Complaint ' || bd.id::text) title, bd.status,
            bd.priority, bd.risk_level,
            bd.sla_due_at,
            bd.sla_due_at is not null and bd.sla_due_at < now() overdue,
            'Assign owner, điều tra case và cập nhật timeline/resolution.' recommended_action,
            concat('/admin/complaints/', bd.id::text) detail_href,
            au.full_name assigned_admin,
            bd.assigned_admin_id assigned_admin_id,
            bd.created_at, bd.updated_at, coalesce(bd.related_type, 'BOOKING') related_type, coalesce(bd.related_id, bd.booking_id) related_id
          from booking_disputes bd left join users au on au.id = bd.assigned_admin_id
          where bd.status not in ('RESOLVED','CLOSED','REJECTED')
          order by bd.sla_due_at asc nulls last, bd.created_at asc limit 100
          """);
      addWorkItems(items, """
          select bd.id, 'complaints' module, 'COMPLAINT_SLA_OVERDUE' item_type,
            coalesce(bd.title, bd.reason, 'Complaint ' || bd.id::text) title, bd.status,
            'CRITICAL' priority, 'CRITICAL' risk_level,
            bd.sla_due_at,
            true overdue,
            'Case quá SLA; cần escalate hoặc chốt phương án xử lý ngay.' recommended_action,
            concat('/admin/complaints/', bd.id::text) detail_href,
            au.full_name assigned_admin,
            bd.assigned_admin_id assigned_admin_id,
            bd.created_at, bd.updated_at, coalesce(bd.related_type, 'BOOKING') related_type, coalesce(bd.related_id, bd.booking_id) related_id
          from booking_disputes bd left join users au on au.id = bd.assigned_admin_id
          where bd.status not in ('RESOLVED','CLOSED','REJECTED') and bd.sla_due_at < now()
          order by bd.sla_due_at asc limit 100
          """);
      items.sort(Comparator
          .comparingInt((Map<String, Object> item) -> priorityRank(string(item.get("priority"))))
          .thenComparing(item -> String.valueOf(item.getOrDefault("slaDueAt", ""))));
      return items;
    });
  }

  public List<Map<String, Object>> disputes() {
    return cached("disputes", () -> disputes(200, 0));
  }

  public List<Map<String, Object>> disputes(int limit, int offset) {
    return jdbc.query(disputeSelect() + """
        order by
          case bd.priority when 'CRITICAL' then 0 when 'HIGH' then 1 when 'MEDIUM' then 2 else 3 end,
          bd.sla_due_at asc nulls last,
          bd.created_at desc
        limit ? offset ?
        """, this::mapAny, limit, offset);
  }

  public List<Map<String, Object>> disputes(String search, String status, String priority, String sla, String owner, int limit, int offset) {
    List<Object> args = new ArrayList<>();
    String where = disputeWhere(search, status, priority, sla, owner, args);
    args.add(limit);
    args.add(offset);
    return jdbc.query(disputeSelect() + where + """
        order by
          case bd.priority when 'CRITICAL' then 0 when 'HIGH' then 1 when 'MEDIUM' then 2 else 3 end,
          bd.sla_due_at asc nulls last,
          bd.created_at desc
        limit ? offset ?
        """, this::mapAny, args.toArray());
  }

  public long disputeCount() {
    Long total = jdbc.queryForObject("select count(*) from booking_disputes", Long.class);
    return total == null ? 0 : total;
  }

  public long disputeCount(String search, String status, String priority, String sla, String owner) {
    List<Object> args = new ArrayList<>();
    String where = disputeWhere(search, status, priority, sla, owner, args);
    Long total = jdbc.queryForObject("""
        select count(*)
        from booking_disputes bd
        join trial_bookings tb on tb.id = bd.booking_id
        join subjects s on s.id = tb.subject_id
        left join users reporter on reporter.id = coalesce(bd.reporter_id, bd.opened_by)
        left join users target on target.id = bd.target_user_id
        left join users assignee on assignee.id = bd.assigned_admin_id
        """ + where, Long.class, args.toArray());
    return total == null ? 0 : total;
  }

  public Map<String, Object> dispute(UUID id) {
    List<Map<String, Object>> rows = jdbc.query(disputeSelect() + " where bd.id = ?", this::mapAny, id);
    if (rows.isEmpty()) throw new NotFoundException("Không tìm thấy khiếu nại.");
    Map<String, Object> item = rows.get(0);
    item.put("timeline", disputeTimeline(id));
    item.put("notes", disputeNotes(id));
    return item;
  }

  public Map<String, Object> updateDispute(UUID id, Map<String, Object> body) {
    Map<String, Object> before = dispute(id);
    String current = canonicalDisputeStatus(string(before.get("status")));
    String status = canonicalDisputeStatus(string(body.get("status")));
    if (status == null) status = current;
    requireDisputeTransition(current, status);
    String resolution = firstNonBlank(body.get("resolution"), body.get("resolutionNote"));
    String resolutionType = normalizedResolutionType(string(body.get("resolutionType")));
    String priority = normalizedEnum(string(body.get("priority")), Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL"), null);
    String riskLevel = normalizedEnum(string(body.get("riskLevel")), Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL"), null);
    String reason = firstNonBlank(body.get("reason"), body.get("note"), resolution);
    boolean terminal = List.of("RESOLVED", "REJECTED", "CLOSED").contains(status);
    jdbc.update("""
        update booking_disputes
        set status = ?,
          resolution = coalesce(?, resolution),
          resolution_note = coalesce(?, resolution_note),
          resolution_type = coalesce(?, resolution_type),
          priority = coalesce(?, priority),
          risk_level = coalesce(?, risk_level),
          resolved_by = case when ? then ? else resolved_by end,
          resolved_at = case when ? then now() else resolved_at end,
          closed_at = case when ? then now() else closed_at end,
          updated_at = now()
        where id = ?
        """, status, resolution, resolution, resolutionType, priority, riskLevel,
        terminal, terminal ? db.currentUserIdOrThrow() : null, terminal,
        "CLOSED".equals(status), id);
    Map<String, Object> statusChange = new LinkedHashMap<>();
    statusChange.put("status", status);
    if (reason != null) statusChange.put("reason", reason);
    insertDisputeTimeline(id, "STATUS_CHANGE", current, status, reason, auditMetadata(before, statusChange));
    cache.clear();
    Map<String, Object> after = dispute(id);
    db.auditCurrent("admin.dispute.update", "bookingDispute", id, "Admin cập nhật khiếu nại sang " + status + ".",
        auditMetadata(before, after, reason));
    return after;
  }

  public Map<String, Object> assignDispute(UUID id, Map<String, Object> body) {
    Map<String, Object> before = dispute(id);
    UUID assignee = uuid(body.get("assignedAdminId"));
    if (assignee == null) assignee = db.currentUserIdOrThrow();
    String current = canonicalDisputeStatus(string(before.get("status")));
    String next = "NEW".equals(current) ? "ASSIGNED" : current;
    requireDisputeTransition(current, next);
    String reason = firstNonBlank(body.get("reason"), body.get("note"), "Assign owner");
    jdbc.update("""
        update booking_disputes
        set assigned_admin_id = ?, status = ?, updated_at = now()
        where id = ?
        """, assignee, next, id);
    insertDisputeTimeline(id, "ASSIGN_OWNER", current, next, reason, Map.of("assignedAdminId", assignee.toString()));
    cache.clear();
    Map<String, Object> after = dispute(id);
    db.auditCurrent("admin.dispute.assign", "bookingDispute", id, "Admin phân công người xử lý khiếu nại.",
        auditMetadata(before, after, reason));
    return after;
  }

  public Map<String, Object> addDisputeNote(UUID id, Map<String, Object> body) {
    String content = string(body.get("content"));
    if (content == null) throw new BusinessException("DISPUTE_NOTE_REQUIRED", "Nội dung ghi chú không được trống.");
    UUID actor = db.currentUserIdOrThrow();
    jdbc.update("""
        insert into admin_internal_notes(entity_type, entity_id, content, visibility, created_by)
        values ('bookingDispute', ?, ?, 'INTERNAL_ONLY', ?)
        """, id, content, actor);
    jdbc.update("""
        update booking_disputes
        set internal_notes = internal_notes || jsonb_build_array(jsonb_build_object(
          'content', ?,
          'createdBy', ?::text,
          'createdAt', now(),
          'visibility', 'INTERNAL_ONLY'
        )),
        updated_at = now()
        where id = ?
        """, content, actor.toString(), id);
    insertDisputeTimeline(id, "INTERNAL_NOTE", null, null, content, Map.of("visibility", "INTERNAL_ONLY"));
    cache.clear();
    Map<String, Object> after = dispute(id);
    db.auditCurrent("admin.dispute.note", "bookingDispute", id, "Admin thêm ghi chú nội bộ cho khiếu nại.",
        Map.of("noteLength", content.length()));
    return after;
  }

  public Map<String, Object> addDisputeTimeline(UUID id, Map<String, Object> body) {
    String eventType = string(body.get("eventType"));
    if (eventType == null) eventType = "MANUAL_EVENT";
    String note = firstNonBlank(body.get("note"), body.get("reason"));
    insertDisputeTimeline(id, eventType, null, null, note, Map.of());
    cache.clear();
    Map<String, Object> after = dispute(id);
    db.auditCurrent("admin.dispute.timeline", "bookingDispute", id, "Admin thêm timeline event cho khiếu nại.",
        Map.of("eventType", eventType));
    return after;
  }

  public Map<String, Object> resolveDispute(UUID id, Map<String, Object> body) {
    Map<String, Object> payload = new LinkedHashMap<>(body);
    payload.put("status", "RESOLVED");
    return updateDispute(id, payload);
  }

  public Map<String, Object> closeDispute(UUID id, Map<String, Object> body) {
    Map<String, Object> payload = new LinkedHashMap<>(body);
    payload.put("status", "CLOSED");
    return updateDispute(id, payload);
  }

  public Map<String, Object> escalateDispute(UUID id, Map<String, Object> body) {
    Map<String, Object> payload = new LinkedHashMap<>(body);
    payload.put("status", "ESCALATED");
    payload.put("priority", "CRITICAL");
    payload.put("riskLevel", "CRITICAL");
    return updateDispute(id, payload);
  }

  private String disputeSelect() {
    return """
        select bd.*,
          coalesce(bd.title, bd.reason, 'Khiếu nại booking ' || bd.booking_id::text) title,
          coalesce(bd.description, bd.reason) description,
          coalesce(bd.related_type, 'BOOKING') related_type,
          coalesce(bd.related_id, bd.booking_id) related_id,
          coalesce(bd.reporter_id, bd.opened_by) reporter_id,
          coalesce(bd.sla_due_at, bd.created_at + interval '48 hours') sla_due_at,
          (coalesce(bd.sla_due_at, bd.created_at + interval '48 hours') < now()
            and bd.status not in ('RESOLVED','CLOSED','REJECTED')) overdue,
          reporter.full_name reporter_name,
          reporter.email reporter_email,
          target.full_name target_user_name,
          assignee.full_name assigned_admin,
          tb.student_name,
          tb.parent_name,
          tb.tutor_id,
          s.name subject
        from booking_disputes bd
        join trial_bookings tb on tb.id = bd.booking_id
        join subjects s on s.id = tb.subject_id
        left join users reporter on reporter.id = coalesce(bd.reporter_id, bd.opened_by)
        left join users target on target.id = bd.target_user_id
        left join users assignee on assignee.id = bd.assigned_admin_id
        """;
  }

  private String disputeWhere(String search, String status, String priority, String sla, String owner, List<Object> args) {
    StringBuilder where = new StringBuilder(" where 1 = 1 ");
    if (status != null && !status.isBlank() && !"all".equals(status)) {
      where.append(" and bd.status = ? ");
      args.add(canonicalDisputeStatus(status));
    }
    if (priority != null && !priority.isBlank() && !"all".equals(priority)) {
      where.append(" and bd.priority = ? ");
      args.add(priority);
    }
    if ("overdue".equals(sla)) {
      where.append(" and coalesce(bd.sla_due_at, bd.created_at + interval '48 hours') < now() and bd.status not in ('RESOLVED','CLOSED','REJECTED') ");
    } else if ("ok".equals(sla)) {
      where.append(" and not (coalesce(bd.sla_due_at, bd.created_at + interval '48 hours') < now() and bd.status not in ('RESOLVED','CLOSED','REJECTED')) ");
    }
    if (owner != null && !owner.isBlank() && !"all".equals(owner)) {
      if ("me".equals(owner)) {
        where.append(" and bd.assigned_admin_id = ? ");
        args.add(db.currentUserIdOrThrow());
      } else if ("unassigned".equals(owner)) {
        where.append(" and bd.assigned_admin_id is null ");
      } else {
        where.append(" and bd.assigned_admin_id = ? ");
        args.add(UUID.fromString(owner));
      }
    }
    if (search != null && !search.isBlank()) {
      String pattern = "%" + search.trim().toLowerCase() + "%";
      where.append("""
          and (
            lower(coalesce(bd.title, bd.reason, '')) like ?
            or lower(coalesce(bd.description, bd.reason, '')) like ?
            or lower(coalesce(bd.resolution, '')) like ?
            or lower(coalesce(bd.resolution_note, '')) like ?
            or lower(coalesce(reporter.full_name, '')) like ?
            or lower(coalesce(target.full_name, '')) like ?
            or lower(coalesce(tb.student_name, '')) like ?
            or lower(coalesce(tb.parent_name, '')) like ?
            or lower(coalesce(s.name, '')) like ?
            or bd.id::text like ?
            or bd.booking_id::text like ?
          )
          """);
      for (int i = 0; i < 11; i++) args.add(pattern);
    }
    return where.toString();
  }

  private List<Map<String, Object>> disputeTimeline(UUID id) {
    return jdbc.query("""
        select bde.*, u.full_name actor_name
        from booking_dispute_timeline_events bde
        left join users u on u.id = bde.actor_user_id
        where bde.dispute_id = ?
        order by bde.created_at desc
        """, this::mapAny, id);
  }

  private List<Map<String, Object>> disputeNotes(UUID id) {
    return jdbc.query("""
        select ain.*, u.full_name created_by_name
        from admin_internal_notes ain
        left join users u on u.id = ain.created_by
        where ain.entity_type = 'bookingDispute' and ain.entity_id = ?
        order by ain.created_at desc
        """, this::mapAny, id);
  }

  private void insertDisputeTimeline(UUID id, String eventType, String from, String to, String note, Object metadata) {
    Map<String, Object> actor = db.currentUserOrThrow();
    jdbc.update("""
        insert into booking_dispute_timeline_events(dispute_id, event_type, status_from, status_to, actor_user_id, actor_role, note, metadata)
        values (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
        """,
        id,
        eventType,
        from,
        to,
        UUID.fromString(actor.get("id").toString()),
        String.valueOf(actor.get("role")),
        note,
        json(metadata));
  }

  private void requireDisputeTransition(String current, String next) {
    if (current == null || next == null || current.equals(next)) return;
    if (!DISPUTE_STATUSES.contains(next) || !DISPUTE_TRANSITIONS.getOrDefault(current, Set.of()).contains(next)) {
      throw new BusinessException("INVALID_DISPUTE_STATUS_TRANSITION", "Không thể chuyển khiếu nại từ " + current + " sang " + next + ".");
    }
  }

  private String canonicalDisputeStatus(String status) {
    String normalized = normalizedEnum(status, Set.copyOf(DISPUTE_STATUSES), null);
    if ("OPEN".equals(normalized)) return "NEW";
    if ("IN_REVIEW".equals(normalized)) return "INVESTIGATING";
    return normalized;
  }

  private String normalizedResolutionType(String type) {
    return normalizedEnum(type, DISPUTE_RESOLUTION_TYPES, null);
  }

  private String normalizedEnum(String value, Set<String> allowed, String fallback) {
    if (value == null) return fallback;
    String normalized = value.trim().toUpperCase();
    if (allowed.contains(normalized)) return normalized;
    throw new BusinessException("INVALID_ENUM_VALUE", "Giá trị không hợp lệ: " + value + ".");
  }

  private String firstNonBlank(Object... values) {
    for (Object value : values) {
      String text = string(value);
      if (text != null) return text;
    }
    return null;
  }

  private UUID uuid(Object value) {
    String text = string(value);
    if (text == null) return null;
    try {
      return UUID.fromString(text);
    } catch (IllegalArgumentException ex) {
      throw new BusinessException("INVALID_UUID", "ID không hợp lệ.");
    }
  }

  private Map<String, Object> auditMetadata(Object before, Object after) {
    return auditMetadata(before, after, null);
  }

  private Map<String, Object> auditMetadata(Object before, Object after, String reason) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("before", before);
    metadata.put("after", after);
    if (reason != null) metadata.put("reason", reason);
    return metadata;
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value == null ? Map.of() : value);
    } catch (Exception ex) {
      return "{}";
    }
  }

  private List<Map<String, Object>> query(String sql) {
    return jdbc.query(sql, this::mapAny);
  }

  private void addWorkItems(List<Map<String, Object>> items, String sql) {
    items.addAll(query(sql));
  }

  private int count(String sql) {
    Integer value = jdbc.queryForObject(sql, Integer.class);
    return value == null ? 0 : value;
  }

  private int priorityRank(String priority) {
    return switch (priority == null ? "" : priority) {
      case "CRITICAL" -> 0;
      case "HIGH" -> 1;
      case "MEDIUM" -> 2;
      case "LOW" -> 3;
      default -> 4;
    };
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

  @SuppressWarnings("unchecked")
  private <T> T cached(String key, Supplier<T> loader) {
    CacheEntry<?> existing = cache.get(key);
    if (existing != null && existing.expiresAt().isAfter(Instant.now())) {
      return (T) existing.value();
    }
    T value = loader.get();
    cache.put(key, new CacheEntry<>(value, Instant.now().plus(CACHE_TTL)));
    return value;
  }

  private record CacheEntry<T>(T value, Instant expiresAt) {}
}
