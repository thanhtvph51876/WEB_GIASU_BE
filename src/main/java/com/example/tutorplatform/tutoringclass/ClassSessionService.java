package com.example.tutorplatform.tutoringclass;

import static com.example.tutorplatform.platform.PlatformRequestSupport.firstInteger;
import static com.example.tutorplatform.platform.PlatformRequestSupport.firstPresent;
import static com.example.tutorplatform.platform.PlatformRequestSupport.firstString;
import static com.example.tutorplatform.platform.PlatformRequestSupport.integer;
import static com.example.tutorplatform.platform.PlatformRequestSupport.normalizeOnlineOffline;
import static com.example.tutorplatform.platform.PlatformRequestSupport.uuid;
import static com.example.tutorplatform.platform.PlatformRequestSupport.uuidOrNull;
import static com.example.tutorplatform.platform.PlatformRequestSupport.valueOr;

import com.example.tutorplatform.common.BusinessException;
import com.example.tutorplatform.common.ForbiddenException;
import com.example.tutorplatform.db.DbService;
import com.example.tutorplatform.finance.EarningLedgerService;
import com.example.tutorplatform.policy.StatusTransitionPolicy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClassSessionService {
  private final DbService db;
  private final JdbcTemplate jdbc;
  private final StatusTransitionPolicy statusPolicy;
  private final EarningLedgerService ledgerService;

  public ClassSessionService(DbService db, StatusTransitionPolicy statusPolicy, EarningLedgerService ledgerService) {
    this.db = db;
    this.jdbc = db.jdbc();
    this.statusPolicy = statusPolicy;
    this.ledgerService = ledgerService;
  }

  public List<Map<String, Object>> classes() {
    UUID userId = db.currentUserIdOrThrow();
    if (db.isAdmin()) return db.classes("");
    if (db.isTutor()) return db.classes(" where tc.tutor_id = ?", db.tutorIdByUserOrThrow(userId));
    return db.classes(" where tc.student_id = ?", userId);
  }

  public Map<String, Object> classById(UUID classId) {
    Map<String, Object> c = db.classById(classId);
    ensureClassAccess(c);
    return c;
  }

  public List<Map<String, Object>> classSessions(UUID classId) {
    Map<String, Object> c = db.classById(classId);
    ensureClassAccess(c);
    return db.sessions(" where cs.class_id = ?", classId);
  }

  public List<Map<String, Object>> tutorClasses() {
    return db.classes(" where tc.tutor_id = ?", db.tutorIdByUserOrThrow(db.currentUserIdOrThrow()));
  }

  public Map<String, Object> tutorClass(UUID classId) {
    Map<String, Object> c = db.classById(classId);
    if (!uuid(c.get("tutorId")).equals(db.tutorIdByUserOrThrow(db.currentUserIdOrThrow()))) {
      throw new ForbiddenException("Bạn không có quyền xem lớp này.");
    }
    return c;
  }

  public List<Map<String, Object>> tutorSessions() {
    return db.sessions(" where cs.tutor_id = ?", db.tutorIdByUserOrThrow(db.currentUserIdOrThrow()));
  }

  public List<Map<String, Object>> sessions() {
    UUID userId = db.currentUserIdOrThrow();
    if (db.isAdmin()) return db.sessions("");
    if (db.isTutor()) return db.sessions(" where cs.tutor_id = ?", db.tutorIdByUserOrThrow(userId));
    return db.sessions(" where cs.student_id = ?", userId);
  }

  public Map<String, Object> session(UUID sessionId) {
    Map<String, Object> session = db.sessionById(sessionId);
    Map<String, Object> c = db.classById(uuid(session.get("classId")));
    ensureClassAccess(c);
    return session;
  }

  public Map<String, Object> tutorCompleteSession(UUID sessionId, Map<String, Object> body) {
    UUID tutorId = db.tutorIdByUserOrThrow(db.currentUserIdOrThrow());
    UUID sessionTutor = jdbc.queryForObject("select tutor_id from class_sessions where id = ?", UUID.class, sessionId);
    if (!tutorId.equals(sessionTutor)) throw new ForbiddenException("Bạn không có quyền hoàn tất buổi học này.");
    completeSessionInternal(sessionId, body == null ? null : firstString(body, "note", "tutorNote"));
    return db.sessionById(sessionId);
  }

  public Map<String, Object> tutorCancelSession(UUID sessionId, Map<String, Object> body) {
    UUID tutorId = db.tutorIdByUserOrThrow(db.currentUserIdOrThrow());
    Map<String, Object> session = db.sessionById(sessionId);
    statusPolicy.requireSession(session.get("status").toString(), "cancelled");
    int count = jdbc.update("update class_sessions set status = 'cancelled', tutor_note = coalesce(?, tutor_note), updated_at = now() where id = ? and tutor_id = ?",
        body == null ? null : firstString(body, "note"), sessionId, tutorId);
    if (count == 0) throw new ForbiddenException("Bạn không có quyền hủy buổi học này.");
    db.auditCurrent("tutor.cancel_session", "session", sessionId, "Gia sư hủy buổi học.");
    return db.sessionById(sessionId);
  }

  public List<Map<String, Object>> adminClasses() {
    return db.classes("");
  }

  public List<Map<String, Object>> adminSessions() {
    return db.sessions("");
  }

  public Map<String, Object> createClass(Map<String, Object> body) {
    UUID studentId = uuid(firstPresent(body, "studentId"));
    UUID tutorId = uuid(firstPresent(body, "tutorId"));
    UUID subjectId = db.requiredSubjectId(firstPresent(body, "subjectId", "subject"));
    UUID gradeId = db.gradeLevelId(firstPresent(body, "gradeLevelId", "grade"));
    UUID studentProfileId = uuidOrNull(firstPresent(body, "studentProfileId"));
    UUID userId = db.currentUserIdOrThrow();
    if (studentProfileId != null && userId != null && !db.isAdmin()) {
      Integer allowed = jdbc.queryForObject("""
          select count(*) from guardian_student_links
          where guardian_user_id = ? and student_profile_id = ? and can_book = true
          """, Integer.class, userId, studentProfileId);
      if (allowed == null || allowed == 0) {
        throw new ForbiddenException("Bạn không có quyền tạo yêu cầu cho học sinh này.");
      }
    }
    UUID id = jdbc.queryForObject("""
        insert into tutoring_classes(learning_request_id, student_id, tutor_id, subject_id, grade_level_id, student_profile_id, title,
          learning_mode, location, meeting_url, hourly_rate, sessions_per_week, start_date, end_date, status)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, coalesce(?::date, current_date), ?::date, coalesce(?, 'active')) returning id
        """, UUID.class, uuidOrNull(firstPresent(body, "learningRequestId")), studentId, tutorId, subjectId, gradeId,
        studentProfileId,
        firstString(body, "title"), normalizeOnlineOffline(valueOr(firstString(body, "mode", "learningMode"), "online")),
        firstString(body, "location"), firstString(body, "meetingUrl"), firstInteger(body, "feePerSession", "hourlyRate"),
        integer(body, "sessionsPerWeek"), firstString(body, "startDate"), firstString(body, "endDate"),
        firstString(body, "status"));
    db.auditCurrent("admin.create_class", "class", id, "Admin tạo lớp học mới.");
    return db.classById(id);
  }

  public Map<String, Object> adminClass(UUID classId) {
    return db.classById(classId);
  }

  public Map<String, Object> updateClass(UUID classId, Map<String, Object> body) {
    String nextStatus = firstString(body, "status");
    if (nextStatus != null) {
      Map<String, Object> c = db.classById(classId);
      statusPolicy.requireClass(c.get("status").toString(), nextStatus);
    }
    jdbc.update("""
        update tutoring_classes set title = coalesce(?, title), status = coalesce(?, status),
          location = coalesce(?, location), meeting_url = coalesce(?, meeting_url),
          hourly_rate = coalesce(?, hourly_rate), updated_at = now()
        where id = ?
        """, firstString(body, "title"), firstString(body, "status"), firstString(body, "location"),
        firstString(body, "meetingUrl"), firstInteger(body, "feePerSession", "hourlyRate"), classId);
    return db.classById(classId);
  }

  public Map<String, Object> pauseClass(UUID classId) {
    return updateClassStatus(classId, "paused", "Admin tạm dừng lớp học.");
  }

  public Map<String, Object> completeClass(UUID classId) {
    return updateClassStatus(classId, "completed", "Admin hoàn tất lớp học.");
  }

  public Map<String, Object> cancelClass(UUID classId) {
    return updateClassStatus(classId, "cancelled", "Admin hủy lớp học.");
  }

  public List<Map<String, Object>> adminClassSessions(UUID classId) {
    return db.sessions(" where cs.class_id = ?", classId);
  }

  public Map<String, Object> createSession(UUID classId, Map<String, Object> body) {
    Map<String, Object> c = db.classById(classId);
    UUID id = jdbc.queryForObject("""
        insert into class_sessions(class_id, student_id, tutor_id, scheduled_start, scheduled_end, status)
        values (?, ?, ?, ?::timestamptz, ?::timestamptz, 'scheduled') returning id
        """, UUID.class, classId, uuid(c.get("studentId")), uuid(c.get("tutorId")),
        firstString(body, "scheduledStart", "startTime"), firstString(body, "scheduledEnd", "endTime"));
    db.auditCurrent("admin.create_session", "session", id, "Admin tạo buổi học.");
    return db.sessionById(id);
  }

  public Map<String, Object> updateSession(UUID sessionId, Map<String, Object> body) {
    String nextStatus = firstString(body, "status");
    if (nextStatus != null) {
      Map<String, Object> session = db.sessionById(sessionId);
      statusPolicy.requireSession(session.get("status").toString(), nextStatus);
    }
    jdbc.update("""
        update class_sessions set scheduled_start = coalesce(?::timestamptz, scheduled_start),
          scheduled_end = coalesce(?::timestamptz, scheduled_end),
          tutor_note = coalesce(?, tutor_note), student_note = coalesce(?, student_note),
          status = coalesce(?, status), updated_at = now()
        where id = ?
        """, firstString(body, "scheduledStart", "startTime"), firstString(body, "scheduledEnd", "endTime"),
        firstString(body, "tutorNote", "note"), firstString(body, "studentNote"), firstString(body, "status"), sessionId);
    return db.sessionById(sessionId);
  }

  public Map<String, Object> adminCompleteSession(UUID sessionId, Map<String, Object> body) {
    completeSessionInternal(sessionId, body == null ? null : firstString(body, "note", "tutorNote"));
    return db.sessionById(sessionId);
  }

  public Map<String, Object> adminCancelSession(UUID sessionId) {
    Map<String, Object> session = db.sessionById(sessionId);
    statusPolicy.requireSession(session.get("status").toString(), "cancelled");
    jdbc.update("update class_sessions set status = 'cancelled', updated_at = now() where id = ?", sessionId);
    db.auditCurrent("admin.cancel_session", "session", sessionId, "Admin hủy buổi học.");
    return db.sessionById(sessionId);
  }

  public Map<String, Object> markStudentAbsent(UUID sessionId) {
    Map<String, Object> session = db.sessionById(sessionId);
    statusPolicy.requireSession(session.get("status").toString(), "student_absent");
    jdbc.update("update class_sessions set status = 'student_absent', updated_at = now() where id = ?", sessionId);
    return db.sessionById(sessionId);
  }

  public Map<String, Object> markTutorAbsent(UUID sessionId) {
    Map<String, Object> session = db.sessionById(sessionId);
    statusPolicy.requireSession(session.get("status").toString(), "tutor_absent");
    jdbc.update("update class_sessions set status = 'tutor_absent', updated_at = now() where id = ?", sessionId);
    return db.sessionById(sessionId);
  }

  private Map<String, Object> updateClassStatus(UUID classId, String status, String description) {
    Map<String, Object> c = db.classById(classId);
    statusPolicy.requireClass(c.get("status").toString(), status);
    jdbc.update("update tutoring_classes set status = ?, updated_at = now() where id = ?", status, classId);
    db.auditCurrent("admin.update_class_status", "class", classId, description);
    return db.classById(classId);
  }

  @Transactional
  private void completeSessionInternal(UUID sessionId, String tutorNote) {
    String lockedStatus = jdbc.queryForObject("select status from class_sessions where id = ? for update", String.class, sessionId);
    if ("completed".equals(lockedStatus)) {
      db.auditCurrent("session.complete_idempotent", "session", sessionId, "Bỏ qua yêu cầu hoàn thành trùng lặp vì buổi học đã hoàn thành.");
      return;
    }
    Map<String, Object> session = db.sessionById(sessionId);
    statusPolicy.requireSession(session.get("status").toString(), "completed");
    UUID classId = uuid(session.get("classId"));
    Map<String, Object> c = db.classById(classId);
    int rate = c.get("hourlyRate") == null ? 0 : ((Number) c.get("hourlyRate")).intValue();
    jdbc.update("""
        update class_sessions set status = 'completed', actual_start = coalesce(actual_start, scheduled_start),
          actual_end = coalesce(actual_end, scheduled_end), tutor_note = coalesce(?, tutor_note),
          completed_by = ?, completed_at = now(), updated_at = now()
        where id = ?
        """, tutorNote, db.currentUserIdOrThrow(), sessionId);
    UUID tutorId = uuid(session.get("tutorId"));
    jdbc.update("update tutor_profiles set total_sessions = total_sessions + 1, updated_at = now() where id = ?", tutorId);
    if (rate > 0 && !exists("select 1 from tutor_earnings where session_id = ?", sessionId)) {
      int fee = db.commissionFee(rate);
      UUID paymentId = jdbc.queryForObject("""
          insert into payments(user_id, tutor_id, class_id, session_id, amount, description, status)
          values (?, ?, ?, ?, ?, ?, 'pending') returning id
          """, UUID.class, uuid(session.get("studentId")), tutorId, classId, sessionId, rate, "Thanh toán buổi học " + session.get("subject"));
      UUID earningId = jdbc.queryForObject("""
          insert into tutor_earnings(tutor_id, session_id, payment_id, gross_amount, platform_fee, net_amount, status)
          values (?, ?, ?, ?, ?, ?, 'pending')
          returning id
          """, UUID.class, tutorId, sessionId, paymentId, rate, fee, Math.max(0, rate - fee));
      ledgerService.record(earningId, tutorId, paymentId, null, "earning_created", Math.max(0, rate - fee),
          "Earning được tạo khi hoàn thành buổi học.");
    }
    db.notify(uuid(session.get("studentId")), "success", "Buổi học đã hoàn thành", "Gia sư đã đánh dấu hoàn thành buổi học.", "/dashboard/classes", "session", sessionId);
    db.notifyAdmins("info", "Buổi học hoàn thành", "Một buổi học đã được hoàn thành.", "/admin/classes", "session", sessionId);
    db.auditCurrent("session.complete", "session", sessionId, "Đánh dấu buổi học đã hoàn thành.");
  }

  private void ensureClassAccess(Map<String, Object> c) {
    if (db.isAdmin()) return;
    UUID current = db.currentUserIdOrThrow();
    if (uuid(c.get("studentId")).equals(current)) return;
    if (db.isTutor() && uuid(c.get("tutorId")).equals(db.tutorIdByUserOrThrow(current))) return;
    throw new ForbiddenException("Bạn không có quyền xem lớp học này.");
  }

  private boolean exists(String sql, Object... args) {
    Integer count = jdbc.queryForObject("select count(*) from (" + sql + ") x", Integer.class, args);
    return count != null && count > 0;
  }
}
