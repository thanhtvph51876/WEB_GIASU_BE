package com.example.tutorplatform.seed;

import com.example.tutorplatform.config.AppProperties;
import com.example.tutorplatform.verification.VerificationTerms;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DataSeeder {
  private final JdbcTemplate jdbc;
  private final PasswordEncoder passwordEncoder;
  private final AppProperties properties;

  public DataSeeder(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, AppProperties properties) {
    this.jdbc = jdbc;
    this.passwordEncoder = passwordEncoder;
    this.properties = properties;
  }

  @EventListener(ApplicationReadyEvent.class)
  @Transactional
  public void seed() {
    if (!properties.seed().enabled()) return;
    Integer users = jdbc.queryForObject("select count(*) from users", Integer.class);
    if (users != null && users > 0) return;

    UUID admin = user("admin@example.com", "Admin123!", "Quản trị viên", "0900000001", "admin");
    UUID systemAdmin = user("system_admin@example.com", "Admin123!", "System Admin", "0900000011", "system_admin");
    UUID financeAdmin = user("finance_admin@example.com", "Admin123!", "Finance Admin", "0900000012", "finance_admin");
    UUID tutorAdmin = user("tutor_admin@example.com", "Admin123!", "Tutor Admin", "0900000013", "tutor_admin");
    UUID supportAdmin = user("support_admin@example.com", "Admin123!", "Support Admin", "0900000014", "support_admin");
    UUID verificationAdmin = user("verification_admin@example.com", "Admin123!", "Verification Admin", "0900000015", "verification_admin");
    UUID student = user("student@example.com", "Student123!", "Nguyễn Minh Anh", "0900000002", "student");
    UUID parent = user("parent@example.com", "Parent123!", "Trần Phụ Huynh", "0900000003", "parent");
    UUID tutorUser = user("tutor@example.com", "Tutor123!", "Lê Gia Sư", "0900000004", "tutor");
    UUID pendingTutorUser = user("tutor_pending@example.com", "Tutor123!", "Phạm Gia Sư Chờ Duyệt", "0900000005", "tutor");
    UUID needUpdateTutorUser = user("tutor_need_update@example.com", "Tutor123!", "Ngô Gia Sư Cần Bổ Sung", "0900000006", "tutor");
    UUID suspendedTutorUser = user("tutor_suspended@example.com", "Tutor123!", "Đỗ Gia Sư Tạm Khóa", "0900000007", "tutor");

    jdbc.update("insert into student_profiles(user_id, grade_level, school, learning_goals, preferred_learning_mode, province, district) values (?, 'Lop 10', 'THPT Demo', 'Củng cố nền tảng Toán', 'both', 'TP HCM', 'Quận 1')", student);
    jdbc.update("insert into parent_profiles(user_id, relationship_to_student, student_name, student_grade, province, district) values (?, 'Mẹ', 'Trần Minh Khang', 'Lop 8', 'Hà Nội', 'Cầu Giấy')", parent);
    seedTutorVerificationDocument(student, "student_card", "student-card-approved.pdf", "approved", 8, verificationAdmin, 900);
    seedTutorVerificationDocument(parent, "student_card", "student-card-need-more-info.pdf", "need_more_info", 42, verificationAdmin, 901);

    List<UUID> studentUsers = new ArrayList<>(List.of(student, parent));
    for (int i = 1; i <= 18; i++) {
      String role = i % 3 == 0 ? "parent" : "student";
      UUID id = user(role + i + "@example.com", role.equals("parent") ? "Parent123!" : "Student123!", (role.equals("parent") ? "Phụ huynh " : "Học viên ") + i, "09100000" + String.format("%02d", i), role);
      studentUsers.add(id);
      if ("student".equals(role)) {
        jdbc.update("insert into student_profiles(user_id, grade_level, school, learning_goals, preferred_learning_mode, province, district) values (?, ?, ?, ?, ?, ?, ?)",
            id, "Lop " + ((i % 12) + 1), "Trường Demo " + i, "Tăng điểm và học đều", i % 2 == 0 ? "online" : "both", i % 2 == 0 ? "Hà Nội" : "TP HCM", i % 2 == 0 ? "Cầu Giấy" : "Quận 3");
      } else {
        jdbc.update("insert into parent_profiles(user_id, relationship_to_student, student_name, student_grade, province, district) values (?, 'Bố/Mẹ', ?, ?, ?, ?)",
            id, "Học sinh " + i, "Lop " + ((i % 12) + 1), "Đà Nẵng", "Hải Châu");
      }
    }

    List<UUID> subjects = jdbc.query("select id from subjects order by name", (rs, row) -> rs.getObject("id", UUID.class));
    List<UUID> grades = jdbc.query("select id from grade_levels order by sort_order", (rs, row) -> rs.getObject("id", UUID.class));

    List<UUID> tutorProfiles = new ArrayList<>();
    tutorProfiles.add(tutor(tutorUser, "approved", "Gia sư Toán luyện thi", "Đại học Bách Khoa", "Toán ứng dụng", 4, 180000, 250000, 4.8, 18, admin, subjects, grades, 0));
    tutorProfiles.add(tutor(pendingTutorUser, "pending", "Gia sư đang chờ duyệt", "Đại học Sư phạm", "Sư phạm Toán", 2, 150000, 220000, 0, 0, null, subjects, grades, 1));
    tutorProfiles.add(tutor(needUpdateTutorUser, "need_update", "Gia sư cần bổ sung hồ sơ", "Đại học Khoa học Tự nhiên", "Vật lý", 3, 160000, 230000, 0, 0, tutorAdmin, subjects, grades, 2));
    tutorProfiles.add(tutor(suspendedTutorUser, "suspended", "Gia sư đang bị tạm khóa", "Đại học Ngoại ngữ", "Tiếng Anh", 5, 220000, 320000, 4.1, 9, admin, subjects, grades, 3));
    for (int i = 1; i <= 20; i++) {
      UUID u = user("tutor" + i + "@example.com", "Tutor123!", "Gia sư Demo " + i, "09200000" + String.format("%02d", i), "tutor");
      String status = switch (i % 6) {
        case 0 -> "pending";
        case 1 -> "approved";
        case 2 -> "approved";
        case 3 -> "need_update";
        case 4 -> "rejected";
        default -> "approved";
      };
      tutorProfiles.add(tutor(u, status, "Gia sư " + subjectName(subjects.get(i % subjects.size())), i % 2 == 0 ? "Đại học Quốc gia" : "Đại học Sư phạm", "Chuyên ngành " + i,
          1 + (i % 8), 120000 + i * 10000, 180000 + i * 12000,
          "approved".equals(status) ? 3.8 + (i % 12) / 10.0 : 0, "approved".equals(status) ? 3 + i : 0,
          "approved".equals(status) ? admin : null, subjects, grades, i));
    }

    List<UUID> approvedTutors = jdbc.query("select id from tutor_profiles where status = 'approved' order by created_at", (rs, row) -> rs.getObject("id", UUID.class));
    List<UUID> learningRequests = new ArrayList<>();
    String[] requestStatuses = {"new", "consulting", "matched", "trial_scheduled", "trial_completed", "active", "rematch", "cancelled", "completed"};
    for (int i = 1; i <= 30; i++) {
      UUID subjectId = subjects.get(i % subjects.size());
      UUID gradeId = grades.get(i % grades.size());
      UUID requester = studentUsers.get(i % studentUsers.size());
      UUID assignedTutor = i % 3 == 0 ? approvedTutors.get(i % approvedTutors.size()) : null;
      String status = assignedTutor == null ? requestStatuses[i % 2] : requestStatuses[(i % (requestStatuses.length - 2)) + 2];
      UUID requestId = jdbc.queryForObject("""
          insert into learning_requests(request_code, requester_id, student_name, parent_name, phone, email, student_grade, subject_id,
            grade_level_id, goal, learning_mode, province, district, budget_min, budget_max, preferred_schedule, learning_goal, note,
            status, assigned_tutor_id, assigned_by, assigned_at)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, case when ?::uuid is null then null else now() end)
          returning id
          """, UUID.class, "REQ-2026-" + String.format("%03d", i), requester, "Học viên " + i, i % 3 == 0 ? "Phụ huynh " + i : null,
          "09300000" + String.format("%02d", i), "student" + i + "@example.com", "Lop " + ((i % 12) + 1), subjectId, gradeId,
          "improve_grades", i % 2 == 0 ? "online" : "both", i % 2 == 0 ? "Hà Nội" : "TP HCM", i % 2 == 0 ? "Cầu Giấy" : "Quận 1",
          120000, 300000, "Tối thứ 2/4/6", "Cải thiện điểm số", "Seed request " + i, status, assignedTutor, assignedTutor == null ? null : admin, assignedTutor);
      learningRequests.add(requestId);
    }

    List<UUID> bookings = new ArrayList<>();
    String[] bookingStatuses = {"pending", "assigned", "accepted", "scheduled", "completed", "rejected", "cancelled"};
    for (int i = 1; i <= 15; i++) {
      UUID requestId = learningRequests.get(i % learningRequests.size());
      UUID studentId = studentUsers.get(i % studentUsers.size());
      UUID tutorId = approvedTutors.get(i % approvedTutors.size());
      UUID subjectId = subjects.get(i % subjects.size());
      UUID gradeId = grades.get(i % grades.size());
      String status = bookingStatuses[i % bookingStatuses.length];
      OffsetDateTime start = OffsetDateTime.now().plusDays(i).withHour(18).withMinute(0).withSecond(0).withNano(0);
      OffsetDateTime end = start.plusMinutes(90);
      UUID bookingId = jdbc.queryForObject("""
          insert into trial_bookings(learning_request_id, student_id, tutor_id, subject_id, grade_level_id, student_name, parent_name,
            phone, email, preferred_time, learning_mode, scheduled_start, scheduled_end, location, meeting_url, goal, status, result_note)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
          """, UUID.class, requestId, studentId, tutorId, subjectId, gradeId, "Học viên booking " + i, null, "09400000" + String.format("%02d", i),
          "booking" + i + "@example.com", "Tối trong tuần", i % 2 == 0 ? "online" : "offline",
          List.of("scheduled", "completed").contains(status) ? start : null,
          List.of("scheduled", "completed").contains(status) ? end : null,
          i % 2 == 0 ? null : "TP HCM", i % 2 == 0 ? "https://meet.example.com/trial-" + i : null,
          "Học thử để đánh giá phương pháp", status, "completed".equals(status) ? "Học thử tốt" : null);
      bookings.add(bookingId);
    }

    for (int i = 1; i <= Math.min(5, bookings.size()); i++) {
      UUID bookingId = bookings.get(i - 1);
      UUID openedBy = jdbc.queryForObject("select student_id from trial_bookings where id = ?", UUID.class, bookingId);
      String status = switch (i % 4) {
        case 0 -> "RESOLVED";
        case 1 -> "OPEN";
        case 2 -> "IN_REVIEW";
        default -> "REJECTED";
      };
      jdbc.update("""
          insert into booking_disputes(booking_id, opened_by, status, reason, resolution, resolved_by, resolved_at)
          values (?, ?, ?, ?, ?, ?, ?)
          """, bookingId, openedBy, status, "Khiếu nại demo #" + i + " về lịch học hoặc chất lượng buổi học.",
          "RESOLVED".equals(status) ? "Đã đối chiếu audit và thống nhất phương án hỗ trợ." : null,
          "RESOLVED".equals(status) || "REJECTED".equals(status) ? admin : null,
          "RESOLVED".equals(status) || "REJECTED".equals(status) ? OffsetDateTime.now().minusDays(i) : null);
    }
    String[] complaintStatuses = {"NEW", "ASSIGNED", "INVESTIGATING", "WAITING_PARENT", "PROPOSED_RESOLUTION", "ESCALATED", "CLOSED"};
    for (int i = 0; i < complaintStatuses.length; i++) {
      UUID bookingId = bookings.get(i % bookings.size());
      UUID openedBy = jdbc.queryForObject("select student_id from trial_bookings where id = ?", UUID.class, bookingId);
      UUID targetUser = tutorOwnerByBooking(bookingId);
      String status = complaintStatuses[i];
      boolean terminal = List.of("CLOSED").contains(status);
      UUID disputeId = jdbc.queryForObject("""
          insert into booking_disputes(booking_id, opened_by, status, reason, resolution, resolved_by, resolved_at,
            related_type, related_id, reporter_type, reporter_id, target_user_id, title, description, priority, risk_level,
            sla_due_at, assigned_admin_id, resolution_type, resolution_note, closed_at)
          values (?, ?, ?, ?, ?, ?, ?, 'BOOKING', ?, 'PARENT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          returning id
          """, UUID.class, bookingId, openedBy, status,
          "Case management demo " + (i + 1) + ": phụ huynh phản ánh lịch học, chất lượng hoặc thanh toán.",
          terminal ? "Đã thống nhất phương án hỗ trợ, không tự động refund ngoài luồng finance." : null,
          terminal ? supportAdmin : null,
          terminal ? OffsetDateTime.now().minusDays(1) : null,
          bookingId, openedBy, targetUser,
          "Case demo " + status,
          "Dữ liệu demo cho trạng thái " + status + " trong màn admin complaints.",
          i % 3 == 0 ? "HIGH" : "MEDIUM",
          "ESCALATED".equals(status) ? "CRITICAL" : i % 2 == 0 ? "HIGH" : "MEDIUM",
          OffsetDateTime.now().plusHours(12 + i * 4),
          List.of("ASSIGNED", "INVESTIGATING", "WAITING_PARENT", "PROPOSED_RESOLUTION", "ESCALATED", "CLOSED").contains(status) ? supportAdmin : null,
          terminal ? "NO_ACTION" : null,
          terminal ? "Đã xử lý theo quy trình complaint, không mutate payment/tutor trực tiếp." : null,
          terminal ? OffsetDateTime.now().minusHours(6) : null);
      jdbc.update("""
          insert into booking_dispute_timeline_events(dispute_id, event_type, status_to, actor_user_id, actor_role, note)
          values (?, 'seed_case_created', ?, ?, 'support_admin', ?)
          """, disputeId, status, supportAdmin, "Seed case trạng thái " + status);
      jdbc.update("""
          insert into admin_internal_notes(entity_type, entity_id, content, visibility, created_by)
          values ('booking_dispute', ?, ?, 'INTERNAL_ONLY', ?)
          """, disputeId, "Ghi chú nội bộ demo: đã gọi xác minh thông tin ban đầu.", supportAdmin);
    }

    List<UUID> classes = new ArrayList<>();
    for (int i = 1; i <= 10; i++) {
      UUID bookingId = bookings.get(i % bookings.size());
      UUID studentId = studentUsers.get(i % studentUsers.size());
      UUID tutorId = approvedTutors.get(i % approvedTutors.size());
      UUID subjectId = subjects.get(i % subjects.size());
      UUID gradeId = grades.get(i % grades.size());
      UUID classId = jdbc.queryForObject("""
          insert into tutoring_classes(learning_request_id, trial_booking_id, student_id, tutor_id, subject_id, grade_level_id, title,
            learning_mode, location, meeting_url, hourly_rate, sessions_per_week, start_date, status)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
          """, UUID.class, learningRequests.get(i % learningRequests.size()), bookingId, studentId, tutorId, subjectId, gradeId,
          "Lớp " + subjectName(subjectId) + " #" + i, i % 2 == 0 ? "online" : "offline", i % 2 == 0 ? null : "TP HCM",
          i % 2 == 0 ? "https://meet.example.com/class-" + i : null, 180000 + i * 10000, 2, LocalDate.now().minusDays(i * 3),
          i % 5 == 0 ? "paused" : "active");
      classes.add(classId);
      jdbc.update("update trial_bookings set converted_class_id = ?, status = 'converted' where id = ?", classId, bookingId);
    }

    List<UUID> completedSessions = new ArrayList<>();
    List<UUID> paidPayments = new ArrayList<>();
    List<UUID> pendingPayments = new ArrayList<>();
    for (int i = 1; i <= 50; i++) {
      UUID classId = classes.get(i % classes.size());
      ClassInfo info = jdbc.queryForObject("select student_id, tutor_id, hourly_rate from tutoring_classes where id = ?", (rs, row) ->
          new ClassInfo(rs.getObject("student_id", UUID.class), rs.getObject("tutor_id", UUID.class), rs.getInt("hourly_rate")), classId);
      OffsetDateTime start = OffsetDateTime.now().minusDays(30 - i).withHour(19).withMinute(0).withSecond(0).withNano(0);
      String status = i <= 30 ? "completed" : i % 4 == 0 ? "cancelled" : "scheduled";
      UUID sessionId = jdbc.queryForObject("""
          insert into class_sessions(class_id, student_id, tutor_id, scheduled_start, scheduled_end, actual_start, actual_end,
            status, tutor_note, completed_by, completed_at)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
          """, UUID.class, classId, info.studentId(), info.tutorId(), start, start.plusMinutes(90),
          "completed".equals(status) ? start : null, "completed".equals(status) ? start.plusMinutes(88) : null,
          status, "completed".equals(status) ? "Buổi học hoàn thành tốt" : null, "completed".equals(status) ? tutorUserId(info.tutorId()) : null,
          "completed".equals(status) ? start.plusMinutes(90) : null);
      if ("completed".equals(status)) {
        completedSessions.add(sessionId);
        UUID paymentId = jdbc.queryForObject("""
            insert into payments(user_id, tutor_id, class_id, session_id, amount, description, status, paid_at)
            values (?, ?, ?, ?, ?, ?, ?, ?) returning id
            """, UUID.class, info.studentId(), info.tutorId(), classId, sessionId, info.hourlyRate(), "Thanh toán buổi học #" + i,
            i % 3 == 0 ? "pending" : "paid", i % 3 == 0 ? null : start.plusDays(1));
        if (i % 3 == 0) {
          pendingPayments.add(paymentId);
        } else {
          paidPayments.add(paymentId);
        }
        int fee = (int) Math.round(info.hourlyRate() * 0.15);
        jdbc.update("""
            insert into tutor_earnings(tutor_id, session_id, payment_id, gross_amount, platform_fee, net_amount, status)
            values (?, ?, ?, ?, ?, ?, ?)
            """, info.tutorId(), sessionId, paymentId, info.hourlyRate(), fee, info.hourlyRate() - fee, i % 3 == 0 ? "pending" : "available");
      }
    }

    if (!paidPayments.isEmpty()) {
      UUID partialRefundPayment = paidPayments.get(0);
      int amount = jdbc.queryForObject("select amount from payments where id = ?", Integer.class, partialRefundPayment);
      jdbc.update("""
          insert into payment_refunds(payment_id, amount, reason, status, gateway_refund_id, requested_by, processed_by)
          values (?, ?, 'Hoàn một phần do đổi lịch demo', 'succeeded', ?, ?, ?)
          """, partialRefundPayment, Math.max(1, amount / 2), "seed-refund-partial-" + partialRefundPayment, financeAdmin, financeAdmin);
      jdbc.update("update payments set status = 'partially_refunded', updated_at = now() where id = ?", partialRefundPayment);
    }
    if (paidPayments.size() > 1) {
      UUID fullRefundPayment = paidPayments.get(1);
      int amount = jdbc.queryForObject("select amount from payments where id = ?", Integer.class, fullRefundPayment);
      jdbc.update("""
          insert into payment_refunds(payment_id, amount, reason, status, gateway_refund_id, requested_by, processed_by)
          values (?, ?, 'Hoàn toàn bộ do buổi học demo không diễn ra', 'succeeded', ?, ?, ?)
          """, fullRefundPayment, amount, "seed-refund-full-" + fullRefundPayment, financeAdmin, financeAdmin);
      jdbc.update("update payments set status = 'refunded', updated_at = now() where id = ?", fullRefundPayment);
    }
    if (paidPayments.size() > 2) {
      UUID pendingRefundPayment = paidPayments.get(2);
      int amount = jdbc.queryForObject("select amount from payments where id = ?", Integer.class, pendingRefundPayment);
      jdbc.update("""
          insert into payment_refunds(payment_id, amount, reason, status, requested_by)
          values (?, ?, 'Yêu cầu refund đang chờ đối soát demo', 'pending', ?)
          """, pendingRefundPayment, Math.max(1, amount / 3), supportAdmin);
    }

    for (int i = 0; i < Math.min(30, completedSessions.size()); i++) {
      UUID sessionId = completedSessions.get(i);
      ClassInfo info = jdbc.queryForObject("select student_id, tutor_id, 0 hourly_rate from class_sessions where id = ?", (rs, row) ->
          new ClassInfo(rs.getObject("student_id", UUID.class), rs.getObject("tutor_id", UUID.class), 0), sessionId);
      jdbc.update("""
          insert into reviews(session_id, class_id, tutor_id, reviewer_id, rating, comment, status)
          select id, class_id, tutor_id, student_id, ?, ?, 'visible' from class_sessions where id = ?
          """, 3 + (i % 3), "Đánh giá seed cho buổi học " + (i + 1), sessionId);
      refreshTutorRating(info.tutorId());
    }

    for (int i = 1; i <= 20; i++) {
      UUID tutorId = approvedTutors.get(i % approvedTutors.size());
      jdbc.update("""
          insert into payouts(tutor_id, amount, status, bank_name, bank_account, account_holder, admin_note, processed_by, processed_at)
          values (?, ?, ?, 'VCB', ?, ?, ?, ?, ?)
          """, tutorId, 300000 + i * 25000, i % 4 == 0 ? "completed" : i % 5 == 0 ? "rejected" : "pending",
          "10200000" + String.format("%02d", i), "Gia sư Demo", i % 5 == 0 ? "Thiếu thông tin tài khoản" : null,
          i % 4 == 0 || i % 5 == 0 ? admin : null, i % 4 == 0 || i % 5 == 0 ? OffsetDateTime.now().minusDays(i) : null);
    }

    for (int i = 1; i <= 50; i++) {
      UUID target = studentUsers.get(i % studentUsers.size());
      jdbc.update("""
          insert into notifications(user_id, title, message, type, status, action_url, entity_type)
          values (?, ?, ?, ?, ?, ?, ?)
          """, target, "Thông báo demo " + i, "Nội dung thông báo demo " + i,
          i % 4 == 0 ? "warning" : "info", i % 3 == 0 ? "read" : "unread", "/dashboard/notifications", "seed");
    }

    for (int i = 1; i <= 10; i++) {
      UUID conversationId = jdbc.queryForObject("insert into conversations(title, type) values (?, 'direct') returning id", UUID.class, "Hội thoại demo " + i);
      UUID u1 = studentUsers.get(i % studentUsers.size());
      UUID tutorOwner = tutorUserId(approvedTutors.get(i % approvedTutors.size()));
      jdbc.update("insert into conversation_members(conversation_id, user_id) values (?, ?), (?, ?)", conversationId, u1, conversationId, tutorOwner);
      for (int j = 1; j <= 10; j++) {
        jdbc.update("insert into messages(conversation_id, sender_id, content, message_type) values (?, ?, ?, 'text')",
            conversationId, j % 2 == 0 ? u1 : tutorOwner, "Tin nhắn demo " + j + " trong hội thoại " + i);
      }
    }

    for (int i = 1; i <= 50; i++) {
      jdbc.update("""
          insert into audit_logs(actor_id, actor_role, action, entity_type, description, metadata)
          values (?, 'admin', ?, ?, ?, '{}'::jsonb)
          """, admin, "seed.action_" + i, "seed", "Audit log demo số " + i);
    }
    jdbc.update("""
        insert into audit_logs(actor_id, actor_role, action, entity_type, description, metadata)
        values
          (?, 'system_admin', 'seed.system_admin_check', 'settings', 'System admin demo audit.', '{}'::jsonb),
          (?, 'finance_admin', 'seed.finance_admin_check', 'payment', 'Finance admin demo audit.', '{}'::jsonb),
          (?, 'tutor_admin', 'seed.tutor_admin_check', 'tutor', 'Tutor admin demo audit.', '{}'::jsonb),
          (?, 'support_admin', 'seed.support_admin_check', 'complaint', 'Support admin demo audit.', '{}'::jsonb),
          (?, 'verification_admin', 'seed.verification_admin_check', 'verification', 'Verification admin demo audit.', '{}'::jsonb)
        """, systemAdmin, financeAdmin, tutorAdmin, supportAdmin, verificationAdmin);
    jdbc.update("""
        insert into admin_internal_notes(entity_type, entity_id, content, visibility, created_by)
        values
          ('user', ?, 'CRM note demo: phụ huynh cần ưu tiên liên hệ buổi tối.', 'INTERNAL_ONLY', ?),
          ('user', ?, 'CRM note demo: học viên đang ôn gấp, cần gia sư phản hồi nhanh.', 'INTERNAL_ONLY', ?),
          ('tutor', ?, 'CRM note demo: tutor có lịch sử rating tốt nhưng cần theo dõi response rate.', 'INTERNAL_ONLY', ?),
          ('tutor', ?, 'CRM note demo: hồ sơ tạm khóa để kiểm tra chất lượng.', 'INTERNAL_ONLY', ?)
        """, parent, supportAdmin, student, supportAdmin, tutorProfiles.get(0), tutorAdmin, tutorProfiles.get(3), tutorAdmin);
    jdbc.update("""
        insert into admin_risk_flags(entity_type, entity_id, level, reason, note, created_by)
        values
          ('USER', ?, 'HIGH', 'PAYMENT_REVIEW', 'Có refund đang chờ đối soát trong seed demo.', ?),
          ('USER', ?, 'MEDIUM', 'FAST_MATCH_REQUIRED', 'Cần ưu tiên matching do mục tiêu học gấp.', ?),
          ('TUTOR', ?, 'MEDIUM', 'RESPONSE_RATE_WATCH', 'Theo dõi tỷ lệ phản hồi trong 7 ngày.', ?),
          ('TUTOR', ?, 'CRITICAL', 'QUALITY_REVIEW', 'Tutor đang bị tạm khóa, cần review trước khi mở lại.', ?)
        """, parent, financeAdmin, student, supportAdmin, tutorProfiles.get(0), tutorAdmin, tutorProfiles.get(3), tutorAdmin);
  }

  private UUID user(String email, String password, String fullName, String phone, String role) {
    return jdbc.queryForObject("""
        insert into users(email, password_hash, full_name, phone, role, status, email_verified)
        values (?, ?, ?, ?, ?, 'active', true)
        returning id
        """, UUID.class, email, passwordEncoder.encode(password), fullName, phone, role);
  }

  private UUID tutor(UUID userId, String status, String headline, String university, String major, int exp, int minRate, int maxRate,
                     double rating, int ratingCount, UUID approvedBy, List<UUID> subjects, List<UUID> grades, int index) {
    UUID tutorId = jdbc.queryForObject("""
        insert into tutor_profiles(user_id, headline, bio, gender, education, university, major, experience_years, teaching_method,
          hourly_rate_min, hourly_rate_max, rating_avg, rating_count, total_sessions, total_students, response_rate, status,
          status_reason, approved_at, approved_by)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, case when ?::uuid is null then null else now() end, ?)
        returning id
        """, UUID.class, userId, headline, "Bio demo cho " + headline, index % 2 == 0 ? "male" : "female",
        "Khoa " + (index + 1), university, major, exp, "Bám sát năng lực học viên, luyện tập theo mục tiêu.",
        minRate, maxRate, rating, ratingCount, 10 + index, 2 + index % 8, 85 + index % 15, status,
        List.of("need_update", "rejected", "suspended").contains(status) ? "Cần bổ sung thông tin hồ sơ" : null,
        approvedBy, approvedBy);
    for (int i = 0; i < 2; i++) {
      jdbc.update("insert into tutor_subjects(tutor_id, subject_id, grade_level_id) values (?, ?, ?) on conflict do nothing",
          tutorId, subjects.get((index + i) % subjects.size()), grades.get((index + i) % grades.size()));
    }
    jdbc.update("insert into tutor_locations(tutor_id, province, district, teaching_mode) values (?, ?, ?, ?)",
        tutorId, index % 2 == 0 ? "TP HCM" : "Hà Nội", index % 2 == 0 ? "Quận 1" : "Cầu Giấy", index % 3 == 0 ? "online" : "both");
    jdbc.update("insert into tutor_availability(tutor_id, day_of_week, start_time, end_time) values (?, ?, '18:00', '20:00'), (?, ?, '08:00', '10:00')",
        tutorId, index % 7, tutorId, (index + 2) % 7);
    jdbc.update("""
        insert into tutor_documents(tutor_id, document_type, file_name, file_url, file_size, mime_type, status)
        values (?, 'degree', ?, ?, 102400, 'application/pdf', ?)
        """, tutorId, "degree-" + index + ".pdf", "/uploads/degree-" + index + ".pdf", "approved".equals(status) ? "approved" : "pending");
    seedTutorVerificationBundle(tutorId, userId, status, index, approvedBy);
    return tutorId;
  }

  private void seedTutorVerificationBundle(UUID tutorId, UUID userId, String profileStatus, int index, UUID admin) {
    String documentStatus = switch (profileStatus) {
      case "approved" -> "approved";
      case "need_update" -> "need_more_info";
      case "rejected" -> "rejected";
      default -> "pending_review";
    };
    int riskScore = switch (profileStatus) {
      case "approved" -> 8 + (index % 12);
      case "rejected" -> 72;
      case "need_update" -> 45;
      default -> 25 + (index % 20);
    };
    UUID identityVerification = seedTutorVerificationDocument(userId, "tutor_identity", "identity-" + index + ".pdf", documentStatus, riskScore, admin, index);
    seedTutorVerificationDocument(userId, "tutor_certificate", "certificate-" + index + ".pdf", documentStatus, Math.max(0, riskScore - 5), admin, index);
    if (!"rejected".equals(profileStatus)) {
      seedTutorCommitment(tutorId, userId, identityVerification, "seed-tutor-identity-" + index);
    }
  }

  private UUID seedTutorVerificationDocument(UUID userId, String type, String fileName, String status, int riskScore, UUID admin, int index) {
    String hash = "seed-" + type + "-" + index + "-" + userId;
    UUID fileId = jdbc.queryForObject("""
        insert into uploaded_files(owner_id, file_name, file_url, file_size, mime_type, original_file_name, storage_path,
          visibility, sha256_hash, purpose, risk_score)
        values (?, ?, ?, 102400, 'application/pdf', ?, ?, 'private', ?, ?, ?)
        returning id
        """, UUID.class, userId, fileName, "/private/seed/" + fileName, fileName, "seed/" + fileName, hash, type, riskScore);
    UUID reviewer = List.of("approved", "rejected", "need_more_info").contains(status) ? admin : null;
    UUID verificationId = jdbc.queryForObject("""
        insert into user_verifications(user_id, verification_type, school_name, student_code, full_name_input, school_email,
          document_file_id, email_verified, duplicate_file, risk_score, status, reject_reason, reviewed_by, reviewed_at)
        values (?, ?, ?, ?, ?, ?, ?, true, false, ?, ?, ?, ?, case when ?::uuid is null then null else now() end)
        returning id
        """, UUID.class, userId, type, "Trường/Đại học Demo", "DEMO-" + String.format("%03d", index), fullNameByUser(userId),
        emailByUser(userId), fileId, riskScore, status, rejectReason(status), reviewer, reviewer);
    jdbc.update("update uploaded_files set entity_type = 'verification', entity_id = ?, updated_at = now() where id = ?", verificationId, fileId);
    if (!"rejected".equals(status)) {
      seedVerificationAgreement(userId, verificationId, hash);
    }
    return verificationId;
  }

  private void seedVerificationAgreement(UUID userId, UUID verificationId, String uploadedFileHash) {
    jdbc.update("""
        insert into verification_agreements(user_id, verification_id, agreement_version, agreement_title,
          agreement_content, agreement_content_snapshot, agreement_content_hash, uploaded_file_hash,
          signer_full_name, signer_email, otp_verified, ip_address, user_agent)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, '127.0.0.1', 'seed-data')
        on conflict(verification_id) do nothing
        """, userId, verificationId, VerificationTerms.VERSION, VerificationTerms.TITLE,
        VerificationTerms.CONTENT, VerificationTerms.CONTENT, VerificationTerms.CONTENT_HASH,
        uploadedFileHash, fullNameByUser(userId), emailByUser(userId));
  }

  private void seedTutorCommitment(UUID tutorId, UUID userId, UUID verificationId, String uploadedFileHash) {
    jdbc.update("""
        insert into tutor_commitments(tutor_id, commitment_version, accepted_terms_hash, full_name_at_signing,
          identity_number_masked, signed_ip, signed_user_agent, status)
        values (?, ?, ?, ?, 'seed-***', '127.0.0.1', 'seed-data', 'signed')
        on conflict (tutor_id, commitment_version) where status = 'signed' do nothing
        """, tutorId, VerificationTerms.VERSION, VerificationTerms.CONTENT_HASH, fullNameByUser(userId));
    seedVerificationAgreement(userId, verificationId, uploadedFileHash);
  }

  private String rejectReason(String status) {
    return switch (status) {
      case "rejected" -> "Giấy tờ demo bị từ chối để kiểm tra luồng reject.";
      case "need_more_info" -> "Cần bổ sung ảnh rõ nét hoặc thông tin trường.";
      default -> null;
    };
  }

  private String fullNameByUser(UUID userId) {
    return jdbc.queryForObject("select full_name from users where id = ?", String.class, userId);
  }

  private String emailByUser(UUID userId) {
    return jdbc.queryForObject("select email from users where id = ?", String.class, userId);
  }

  private UUID tutorUserId(UUID tutorId) {
    return jdbc.queryForObject("select user_id from tutor_profiles where id = ?", UUID.class, tutorId);
  }

  private UUID tutorOwnerByBooking(UUID bookingId) {
    return jdbc.queryForObject("""
        select tp.user_id
        from trial_bookings tb
        join tutor_profiles tp on tp.id = tb.tutor_id
        where tb.id = ?
        """, UUID.class, bookingId);
  }

  private String subjectName(UUID subjectId) {
    return jdbc.queryForObject("select name from subjects where id = ?", String.class, subjectId);
  }

  private void refreshTutorRating(UUID tutorId) {
    MapRow stats = jdbc.queryForObject("select coalesce(avg(rating),0) avg, count(*) count from reviews where tutor_id = ?", (rs, row) -> new MapRow(rs.getDouble("avg"), rs.getInt("count")), tutorId);
    jdbc.update("update tutor_profiles set rating_avg = ?, rating_count = ? where id = ?", stats.avg(), stats.count(), tutorId);
  }

  private record ClassInfo(UUID studentId, UUID tutorId, int hourlyRate) {}

  private record MapRow(double avg, int count) {}
}
