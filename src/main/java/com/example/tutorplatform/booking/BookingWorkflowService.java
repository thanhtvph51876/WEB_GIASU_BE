package com.example.tutorplatform.booking;

import static com.example.tutorplatform.platform.PlatformRequestSupport.bool;
import static com.example.tutorplatform.platform.PlatformRequestSupport.firstInteger;
import static com.example.tutorplatform.platform.PlatformRequestSupport.firstPresent;
import static com.example.tutorplatform.platform.PlatformRequestSupport.firstString;
import static com.example.tutorplatform.platform.PlatformRequestSupport.integer;
import static com.example.tutorplatform.platform.PlatformRequestSupport.nestedString;
import static com.example.tutorplatform.platform.PlatformRequestSupport.normalizeDateTime;
import static com.example.tutorplatform.platform.PlatformRequestSupport.normalizeOnlineOffline;
import static com.example.tutorplatform.platform.PlatformRequestSupport.uuid;
import static com.example.tutorplatform.platform.PlatformRequestSupport.uuidOrNull;
import static com.example.tutorplatform.platform.PlatformRequestSupport.valueOr;

import com.example.tutorplatform.common.BusinessException;
import com.example.tutorplatform.common.ForbiddenException;
import com.example.tutorplatform.db.DbService;
import com.example.tutorplatform.policy.StatusTransitionPolicy;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingWorkflowService {
  private final DbService db;
  private final JdbcTemplate jdbc;
  private final StatusTransitionPolicy statusPolicy;

  public BookingWorkflowService(DbService db, StatusTransitionPolicy statusPolicy) {
    this.db = db;
    this.jdbc = db.jdbc();
    this.statusPolicy = statusPolicy;
  }

  public List<Map<String, Object>> bookings() {
    UUID userId = db.currentUserIdOrThrow();
    return db.isAdmin() ? db.bookings("") : db.bookings(" where tb.student_id = ?", userId);
  }

  @Transactional
  public Map<String, Object> create(Map<String, Object> body) {
    UUID studentId = db.currentUserIdOrThrow();
    UUID tutorId = uuid(firstPresent(body, "tutorId"));
    String tutorStatus = jdbc.queryForObject("select status from tutor_profiles where id = ?", String.class, tutorId);
    if (!"approved".equals(tutorStatus)) throw new BusinessException("TUTOR_NOT_APPROVED", "Gia sư chưa được duyệt.");
    UUID subjectId = db.requiredSubjectId(firstPresent(body, "subjectId", "subject"));
    UUID gradeId = db.gradeLevelId(firstPresent(body, "gradeLevelId", "grade"));
    UUID studentProfileId = uuidOrNull(firstPresent(body, "studentProfileId"));
    if (studentProfileId != null && !db.isAdmin()) {
      Integer allowed = jdbc.queryForObject("""
          select count(*) from guardian_student_links
          where guardian_user_id = ? and student_profile_id = ? and can_book = true
          """, Integer.class, studentId, studentProfileId);
      if (allowed == null || allowed == 0) {
        throw new ForbiddenException("Bạn không có quyền tạo booking cho học sinh này.");
      }
    }
    String bookingMode = normalizeOnlineOffline(valueOr(firstString(body, "teachingMode", "learningMode"), "online"));
    if ("offline".equals(bookingMode) && firstPresent(body, "proposalId") == null) {
      throw new BusinessException("OFFLINE_PROPOSAL_REQUIRED", "Không thể đặt học thử offline trực tiếp khi chưa có proposal và xác nhận từ gia sư.");
    }
    UUID id = jdbc.queryForObject("""
        insert into trial_bookings(learning_request_id, student_id, student_profile_id, tutor_id, subject_id, grade_level_id,
          student_name, parent_name, phone, email, preferred_time, learning_mode, goal, status)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending') returning id
        """, UUID.class, uuidOrNull(firstPresent(body, "learningRequestId")), studentId, studentProfileId, tutorId, subjectId, gradeId,
        firstString(body, "studentName"), firstString(body, "parentName"), firstString(body, "phone"),
        firstString(body, "email"), firstString(body, "preferredTime"),
        bookingMode,
        firstString(body, "message", "goal"));
    UUID tutorUserId = jdbc.queryForObject("select user_id from tutor_profiles where id = ?", UUID.class, tutorId);
    db.notify(tutorUserId, "info", "Yêu cầu học thử mới", "Bạn có yêu cầu học thử mới.", "/dashboard/tutor/requests", "booking", id);
    db.notifyAdmins("info", "Booking học thử mới", "Có booking học thử mới cần theo dõi.", "/admin/bookings", "booking", id);
    db.auditCurrent("student.create_trial_booking", "booking", id, "Học viên đặt lịch học thử.");
    return db.bookingById(id);
  }

  public Map<String, Object> get(UUID bookingId) {
    Map<String, Object> booking = db.bookingById(bookingId);
    ensureBookingAccess(booking);
    return booking;
  }

  public Map<String, Object> cancel(UUID bookingId) {
    Map<String, Object> booking = db.bookingById(bookingId);
    if (!db.isAdmin() && !db.currentUserIdOrThrow().equals(uuid(booking.get("studentId")))) {
      throw new ForbiddenException("Bạn chỉ được hủy booking của mình.");
    }
    statusPolicy.requireBooking(booking.get("status").toString(), "cancelled");
    jdbc.update("update trial_bookings set status = 'cancelled', updated_at = now() where id = ?", bookingId);
    db.auditCurrent("booking.cancel", "booking", bookingId, "Hủy booking học thử.");
    return db.bookingById(bookingId);
  }

  public List<Map<String, Object>> tutorBookings() {
    UUID tutorId = db.tutorIdByUserOrThrow(db.currentUserIdOrThrow());
    return db.bookings(" where tb.tutor_id = ?", tutorId);
  }

  @Transactional
  public Map<String, Object> accept(UUID bookingId, Map<String, Object> body) {
    UUID tutorId = db.tutorIdByUserOrThrow(db.currentUserIdOrThrow());
    String status = jdbc.queryForObject("select status from tutor_profiles where id = ?", String.class, tutorId);
    if (!"approved".equals(status)) throw new BusinessException("TUTOR_NOT_APPROVED", "Gia sư phải được duyệt mới được nhận booking.");
    Map<String, Object> booking = db.bookingById(bookingId);
    if (!tutorId.equals(uuid(booking.get("tutorId")))) throw new ForbiddenException("Bạn không có quyền nhận booking này.");
    statusPolicy.requireBooking(booking.get("status").toString(), "accepted");
    jdbc.update("update trial_bookings set status = 'accepted', updated_at = now() where id = ? and tutor_id = ?", bookingId, tutorId);
    if (body != null && (body.containsKey("date") || body.containsKey("schedule") || body.containsKey("scheduledStart"))) {
      scheduleBookingInternal(bookingId, body);
    }
    notifyBookingParties(bookingId, "success", "Gia sư đã chấp nhận booking", "Booking học thử đã được gia sư chấp nhận.");
    db.auditCurrent("tutor.accept_booking", "booking", bookingId, "Gia sư chấp nhận booking học thử.");
    return db.bookingById(bookingId);
  }

  public Map<String, Object> reject(UUID bookingId, Map<String, Object> body) {
    UUID tutorId = db.tutorIdByUserOrThrow(db.currentUserIdOrThrow());
    Map<String, Object> booking = db.bookingById(bookingId);
    if (!tutorId.equals(uuid(booking.get("tutorId")))) throw new ForbiddenException("Bạn không có quyền từ chối booking này.");
    statusPolicy.requireBooking(booking.get("status").toString(), "rejected");
    int count = jdbc.update("update trial_bookings set status = 'rejected', tutor_response_note = ?, updated_at = now() where id = ? and tutor_id = ?",
        firstString(body, "reason", "rejectReason", "note"), bookingId, tutorId);
    if (count == 0) throw new ForbiddenException("Bạn không có quyền từ chối booking này.");
    notifyBookingParties(bookingId, "warning", "Gia sư từ chối booking", "Booking học thử đã bị từ chối.");
    db.auditCurrent("tutor.reject_booking", "booking", bookingId, "Gia sư từ chối booking học thử.");
    return db.bookingById(bookingId);
  }

  public List<Map<String, Object>> adminBookings() {
    return db.bookings("");
  }

  public Map<String, Object> adminBooking(UUID bookingId) {
    return db.bookingById(bookingId);
  }

  public Map<String, Object> assignTutor(UUID bookingId, Map<String, Object> body) {
    UUID tutorId = uuid(firstPresent(body, "tutorId"));
    Map<String, Object> booking = db.bookingById(bookingId);
    statusPolicy.requireBooking(booking.get("status").toString(), "assigned");
    jdbc.update("update trial_bookings set tutor_id = ?, status = 'assigned', updated_at = now() where id = ?", tutorId, bookingId);
    UUID tutorUserId = jdbc.queryForObject("select user_id from tutor_profiles where id = ?", UUID.class, tutorId);
    db.notify(tutorUserId, "info", "Bạn được gán booking", "Admin đã gán bạn vào một booking học thử.", "/dashboard/tutor/requests", "booking", bookingId);
    db.auditCurrent("admin.assign_booking_tutor", "booking", bookingId, "Admin gán gia sư cho booking học thử.");
    return db.bookingById(bookingId);
  }

  @Transactional
  public Map<String, Object> schedule(UUID bookingId, Map<String, Object> body) {
    scheduleBookingInternal(bookingId, body);
    db.auditCurrent("admin.schedule_booking", "booking", bookingId, "Admin xếp lịch học thử.");
    return db.bookingById(bookingId);
  }

  public Map<String, Object> complete(UUID bookingId, Map<String, Object> body) {
    Map<String, Object> booking = db.bookingById(bookingId);
    statusPolicy.requireBooking(booking.get("status").toString(), "completed");
    jdbc.update("update trial_bookings set status = 'completed', result_note = coalesce(?, result_note), updated_at = now() where id = ?",
        body == null ? null : firstString(body, "resultNote", "note"), bookingId);
    UUID requestId = jdbc.queryForObject("select learning_request_id from trial_bookings where id = ?", UUID.class, bookingId);
    if (requestId != null) jdbc.update("update learning_requests set status = 'trial_completed', updated_at = now() where id = ?", requestId);
    db.notifyAdmins("info", "Học thử đã hoàn tất", "Một booking học thử đã hoàn tất.", "/admin/bookings", "booking", bookingId);
    db.auditCurrent("admin.complete_booking", "booking", bookingId, "Admin đánh dấu hoàn tất học thử.");
    return db.bookingById(bookingId);
  }

  public Map<String, Object> noShowStudent(UUID bookingId) {
    Map<String, Object> booking = db.bookingById(bookingId);
    statusPolicy.requireBooking(booking.get("status").toString(), "no_show_student");
    jdbc.update("update trial_bookings set status = 'no_show_student', updated_at = now() where id = ?", bookingId);
    return db.bookingById(bookingId);
  }

  public Map<String, Object> noShowTutor(UUID bookingId) {
    Map<String, Object> booking = db.bookingById(bookingId);
    statusPolicy.requireBooking(booking.get("status").toString(), "no_show_tutor");
    jdbc.update("update trial_bookings set status = 'no_show_tutor', updated_at = now() where id = ?", bookingId);
    return db.bookingById(bookingId);
  }

  @Transactional
  public Map<String, Object> convertToClass(UUID bookingId, Map<String, Object> body) {
    if (exists("select 1 from tutoring_classes where trial_booking_id = ?", bookingId)) {
      throw new BusinessException("BOOKING_ALREADY_CONVERTED", "Booking đã được chuyển thành lớp.");
    }
    Map<String, Object> booking = db.bookingById(bookingId);
    statusPolicy.requireBooking(booking.get("status").toString(), "converted");
    UUID studentId = uuid(booking.get("studentId"));
    UUID tutorId = uuid(booking.get("tutorId"));
    UUID subjectId = db.requiredSubjectId(booking.get("subject"));
    UUID gradeId = db.gradeLevelId(booking.get("grade"));
    int hourlyRate = jdbc.queryForObject("select coalesce(hourly_rate_min, hourly_rate_max, 0) from tutor_profiles where id = ?", Integer.class, tutorId);
    String title = valueOr(body == null ? null : firstString(body, "title"), "Lớp " + booking.get("subject") + " - " + booking.get("studentName"));
    UUID classId = jdbc.queryForObject("""
        insert into tutoring_classes(learning_request_id, trial_booking_id, student_id, tutor_id, subject_id, grade_level_id,
          title, learning_mode, location, meeting_url, hourly_rate, sessions_per_week, start_date, status)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_date, 'active') returning id
        """, UUID.class, uuidOrNull(booking.get("learningRequestId")), bookingId, studentId, tutorId, subjectId, gradeId,
        title, normalizeOnlineOffline(body == null ? "online" : valueOr(firstString(body, "mode", "learningMode"), "online")),
        body == null ? null : firstString(body, "location"), body == null ? null : firstString(body, "meetingUrl"),
        hourlyRate, body == null ? 2 : valueOr(integer(body, "sessionsPerWeek"), 2));
    jdbc.update("update trial_bookings set status = 'converted', converted_class_id = ?, updated_at = now() where id = ?", classId, bookingId);
    UUID requestId = uuidOrNull(booking.get("learningRequestId"));
    if (requestId != null) jdbc.update("update learning_requests set status = 'active', updated_at = now() where id = ?", requestId);
    db.notify(studentId, "success", "Học thử đã chuyển thành lớp", "Lớp học chính thức đã được tạo.", "/dashboard/classes", "class", classId);
    UUID tutorUserId = jdbc.queryForObject("select user_id from tutor_profiles where id = ?", UUID.class, tutorId);
    db.notify(tutorUserId, "success", "Bạn có lớp học mới", "Booking học thử đã chuyển thành lớp chính thức.", "/dashboard/tutor/classes", "class", classId);
    db.auditCurrent("admin.convert_booking_to_class", "booking", bookingId, "Admin chuyển booking học thử thành lớp chính thức.");
    return db.classById(classId);
  }

  public Map<String, Object> adminCancel(UUID bookingId) {
    Map<String, Object> booking = db.bookingById(bookingId);
    statusPolicy.requireBooking(booking.get("status").toString(), "cancelled");
    jdbc.update("update trial_bookings set status = 'cancelled', updated_at = now() where id = ?", bookingId);
    db.auditCurrent("admin.cancel_booking", "booking", bookingId, "Admin hủy booking học thử.");
    return db.bookingById(bookingId);
  }

  @Transactional
  private void scheduleBookingInternal(UUID bookingId, Map<String, Object> body) {
    String start = firstString(body, "scheduledStart", "startTime");
    String end = firstString(body, "scheduledEnd", "endTime");
    if (body.get("date") != null && body.get("startTime") != null && body.get("endTime") != null) {
      start = body.get("date") + "T" + body.get("startTime") + ":00Z";
      end = body.get("date") + "T" + body.get("endTime") + ":00Z";
    }
    if (start == null && body.get("schedule") instanceof Map<?, ?> schedule) {
      Object date = schedule.get("date");
      Object startTime = schedule.get("startTime");
      Object endTime = schedule.get("endTime");
      start = date + "T" + startTime + ":00Z";
      end = date + "T" + endTime + ":00Z";
    }
    if (start == null || end == null) throw new BusinessException("INVALID_SCHEDULE", "Cần có thời gian bắt đầu và kết thúc.");
    OffsetDateTime startAt = OffsetDateTime.parse(normalizeDateTime(start));
    OffsetDateTime endAt = OffsetDateTime.parse(normalizeDateTime(end));
    if (!endAt.isAfter(startAt)) throw new BusinessException("INVALID_SCHEDULE", "Thời gian kết thúc phải sau thời gian bắt đầu.");
    String bookingStatus = jdbc.queryForObject("select status from trial_bookings where id = ?", String.class, bookingId);
    statusPolicy.requireBooking(bookingStatus, "scheduled");
    UUID tutorId = jdbc.queryForObject("select tutor_id from trial_bookings where id = ?", UUID.class, bookingId);
    Integer conflicts = jdbc.queryForObject("""
        select count(*) from class_sessions where tutor_id = ? and status in ('scheduled','upcoming')
        and scheduled_start < ? and scheduled_end > ?
        """, Integer.class, tutorId, endAt, startAt);
    if (conflicts != null && conflicts > 0) throw new BusinessException("SCHEDULE_CONFLICT", "Lịch gia sư bị trùng.");
    jdbc.update("""
        update trial_bookings set status = 'scheduled', scheduled_start = ?, scheduled_end = ?,
          learning_mode = ?, location = ?, meeting_url = ?, updated_at = now()
        where id = ?
        """, startAt, endAt, normalizeOnlineOffline(valueOr(firstString(body, "mode", "learningMode", "teachingMode"), valueOr(nestedString(body, "schedule", "mode", "learningMode", "teachingMode"), "online"))),
        valueOr(firstString(body, "location"), nestedString(body, "schedule", "location")),
        valueOr(firstString(body, "meetingUrl"), nestedString(body, "schedule", "meetingUrl")), bookingId);
    UUID requestId = jdbc.queryForObject("select learning_request_id from trial_bookings where id = ?", UUID.class, bookingId);
    if (requestId != null) jdbc.update("update learning_requests set status = 'trial_scheduled', updated_at = now() where id = ?", requestId);
    notifyBookingParties(bookingId, "info", "Lịch học thử đã được xếp", "Booking học thử đã có lịch cụ thể.");
  }

  private void notifyBookingParties(UUID bookingId, String type, String title, String message) {
    Map<String, Object> booking = db.bookingById(bookingId);
    UUID studentId = uuidOrNull(booking.get("studentId"));
    if (studentId != null) db.notify(studentId, type, title, message, "/dashboard/bookings", "booking", bookingId);
    UUID tutorUser = jdbc.queryForObject("select user_id from tutor_profiles where id = ?", UUID.class, uuid(booking.get("tutorId")));
    db.notify(tutorUser, type, title, message, "/dashboard/tutor/requests", "booking", bookingId);
    db.notifyAdmins(type, title, message, "/admin/bookings", "booking", bookingId);
  }

  private void ensureBookingAccess(Map<String, Object> booking) {
    if (db.isAdmin()) return;
    UUID current = db.currentUserIdOrThrow();
    if (uuid(booking.get("studentId")).equals(current)) return;
    if (db.isTutor() && uuid(booking.get("tutorId")).equals(db.tutorIdByUserOrThrow(current))) return;
    throw new ForbiddenException("Bạn không có quyền xem booking này.");
  }

  private boolean exists(String sql, Object... args) {
    Integer count = jdbc.queryForObject("select count(*) from (" + sql + ") x", Integer.class, args);
    return count != null && count > 0;
  }
}
