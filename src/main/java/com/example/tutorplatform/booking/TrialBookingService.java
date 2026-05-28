package com.example.tutorplatform.booking;

import com.example.tutorplatform.common.BusinessException;
import com.example.tutorplatform.common.ForbiddenException;
import com.example.tutorplatform.common.NotFoundException;
import com.example.tutorplatform.db.DbService;
import com.example.tutorplatform.proposal.TutorProposalService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrialBookingService {
  private final DbService db;
  private final JdbcTemplate jdbc;
  private final TutorProposalService proposalService;

  public TrialBookingService(DbService db, TutorProposalService proposalService) {
    this.db = db;
    this.jdbc = db.jdbc();
    this.proposalService = proposalService;
  }

  @Transactional
  public Map<String, Object> create(Map<String, Object> body) {
    UUID proposalId = uuid(required(body, "proposalId"));
    Map<String, Object> proposal = proposalService.proposalById(proposalId);
    proposalService.requireRequestOwner(proposal);
    if (!"ACCEPTED".equals(proposal.get("status"))) {
      throw new BusinessException("PROPOSAL_NOT_ACCEPTED", "Chỉ tạo học thử từ proposal đã được chấp nhận.");
    }
    UUID existing = db.optional("""
        select id from trial_bookings where proposal_id = ? and status not in ('cancelled','cancelled_by_parent','cancelled_by_tutor','rejected_after_trial')
        order by created_at desc limit 1
        """, (rs, row) -> rs.getObject("id", UUID.class), proposalId).orElse(null);
    if (existing != null) return bookingById(existing);

    UUID requestId = UUID.fromString(proposal.get("learningRequestId").toString());
    Map<String, Object> request = db.learningRequestById(requestId);
    String mode = normalizeMode(value(body, "teachingMode", proposal.get("teachingMode").toString()));
    String location = string(body.get("location"));
    String meetingUrl = string(body.get("meetingUrl"));
    if ("offline".equals(mode) && (location == null || location.isBlank())) {
      throw new BusinessException("OFFLINE_LOCATION_REQUIRED", "Booking học thử offline cần địa điểm rõ ràng.");
    }
    OffsetDateTime start = dateTime(body.get("scheduledStartTime"), body.get("scheduledStart"), body.get("startTime"));
    OffsetDateTime end = dateTime(body.get("scheduledEndTime"), body.get("scheduledEnd"), body.get("endTime"));
    if ((start == null) != (end == null)) throw new BusinessException("INVALID_SCHEDULE", "Cần nhập đủ thời gian bắt đầu và kết thúc.");
    if (start != null && !end.isAfter(start)) throw new BusinessException("INVALID_SCHEDULE", "Thời gian kết thúc phải sau thời gian bắt đầu.");

    UUID bookingId = jdbc.queryForObject("""
        insert into trial_bookings(learning_request_id, proposal_id, student_profile_id, student_id, tutor_id, subject_id, grade_level_id,
          student_name, parent_name, phone, email, preferred_time, learning_mode, scheduled_start, scheduled_end,
          location, meeting_url, goal, status, parent_confirmed_at, cancellation_policy_id)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'parent_confirmed', now(),
          (select id from cancellation_policies where code = case when ? = 'offline' then 'TRIAL_OFFLINE_DEFAULT' else 'TRIAL_ONLINE_DEFAULT' end limit 1))
        returning id
        """, UUID.class, requestId, proposalId, uuidOrNull(request.get("studentProfileId")), uuidOrNull(request.get("userId")),
        UUID.fromString(proposal.get("tutorId").toString()), UUID.fromString(request.get("subjectId").toString()), uuidOrNull(request.get("gradeLevelId")),
        string(request.get("studentName")), string(request.get("parentName")), string(request.get("phone")), string(request.get("email")),
        value(body, "preferredTime", string(request.get("preferredSchedule"))), mode, start, end, location, meetingUrl,
        value(body, "goal", string(request.get("learningGoal"))), mode);
    bookingHistory(bookingId, null, "parent_confirmed", "created_from_proposal", null);
    jdbc.update("update learning_requests set status = 'waiting_parent_confirmation', updated_at = now() where id = ?", requestId);
    db.auditCurrent("trial_booking.create", "booking", bookingId, "Tạo học thử từ proposal đã chấp nhận.");
    return bookingById(bookingId);
  }

  public Map<String, Object> get(UUID bookingId) {
    Map<String, Object> booking = bookingById(bookingId);
    ensureAccess(booking);
    return booking;
  }

  @Transactional
  public Map<String, Object> confirm(UUID bookingId, Map<String, Object> body) {
    Map<String, Object> booking = bookingById(bookingId);
    ensureAccess(booking);
    String oldStatus = booking.get("status").toString();
    UUID current = db.currentUserIdOrThrow();
    boolean tutor = db.isTutor() && currentTutorId().equals(UUID.fromString(booking.get("tutorId").toString()));
    boolean parentSide = isParentSide(booking, current);
    if (!tutor && !parentSide && !db.isAdmin()) throw new ForbiddenException("Bạn không có quyền xác nhận booking này.");

    OffsetDateTime start = dateTime(body == null ? null : body.get("scheduledStartTime"), body == null ? null : body.get("scheduledStart"), body == null ? null : body.get("startTime"));
    OffsetDateTime end = dateTime(body == null ? null : body.get("scheduledEndTime"), body == null ? null : body.get("scheduledEnd"), body == null ? null : body.get("endTime"));
    String location = body == null ? null : string(body.get("location"));
    String meetingUrl = body == null ? null : string(body.get("meetingUrl"));
    if ((start == null) != (end == null)) throw new BusinessException("INVALID_SCHEDULE", "Cần nhập đủ thời gian bắt đầu và kết thúc.");
    if (start != null && !end.isAfter(start)) throw new BusinessException("INVALID_SCHEDULE", "Thời gian kết thúc phải sau thời gian bắt đầu.");

    if (tutor || db.isAdmin()) jdbc.update("update trial_bookings set tutor_confirmed_at = coalesce(tutor_confirmed_at, now()) where id = ?", bookingId);
    if (parentSide || db.isAdmin()) jdbc.update("update trial_bookings set parent_confirmed_at = coalesce(parent_confirmed_at, now()) where id = ?", bookingId);
    jdbc.update("""
        update trial_bookings set scheduled_start = coalesce(?, scheduled_start), scheduled_end = coalesce(?, scheduled_end),
          location = coalesce(?, location), meeting_url = coalesce(?, meeting_url), updated_at = now()
        where id = ?
        """, start, end, location, meetingUrl, bookingId);

    Map<String, Object> updated = bookingById(bookingId);
    String next = nextConfirmationStatus(updated);
    jdbc.update("update trial_bookings set status = ?, updated_at = now() where id = ?", next, bookingId);
    bookingHistory(bookingId, oldStatus, next, "confirm", body == null ? null : string(body.get("note")));
    if ("scheduled".equals(next) && updated.get("learningRequestId") != null) {
      jdbc.update("update learning_requests set status = 'trial_scheduled', updated_at = now() where id = ?", UUID.fromString(updated.get("learningRequestId").toString()));
    }
    db.auditCurrent("trial_booking.confirm", "booking", bookingId, "Xác nhận booking học thử.");
    return bookingById(bookingId);
  }

  @Transactional
  public Map<String, Object> cancel(UUID bookingId, Map<String, Object> body) {
    Map<String, Object> booking = bookingById(bookingId);
    ensureAccess(booking);
    String oldStatus = booking.get("status").toString();
    UUID current = db.currentUserIdOrThrow();
    String next = db.isTutor() ? "cancelled_by_tutor" : "cancelled_by_parent";
    if (db.isAdmin()) next = "cancelled";
    String reason = body == null ? null : string(body.get("reason"));
    jdbc.update("update trial_bookings set status = ?, cancelled_by = ?, cancellation_reason = ?, updated_at = now() where id = ?", next, current, reason, bookingId);
    jdbc.update("""
        insert into booking_cancellation_records(booking_id, actor_user_id, actor_role, old_status, new_status, reason, note, cancelled_before_hours, penalty_applied)
        values (?, ?, ?, ?, ?, ?, ?, null, false)
        """, bookingId, current, db.currentUserOrThrow().get("role"), oldStatus, next, reason, body == null ? null : string(body.get("note")));
    bookingHistory(bookingId, oldStatus, next, "cancel", reason);
    db.auditCurrent("trial_booking.cancel", "booking", bookingId, "Hủy booking học thử.");
    return bookingById(bookingId);
  }

  @Transactional
  public Map<String, Object> markNoShow(UUID bookingId, Map<String, Object> body) {
    Map<String, Object> booking = bookingById(bookingId);
    if (!db.isAdmin() && !db.isTutor()) throw new ForbiddenException("Chỉ admin/gia sư được ghi nhận no-show.");
    if (db.isTutor() && !currentTutorId().equals(UUID.fromString(booking.get("tutorId").toString()))) throw new ForbiddenException("Bạn không có quyền thao tác booking này.");
    String party = value(body, "party", "PARENT").toUpperCase();
    if ("STUDENT".equals(party)) party = "PARENT";
    if (!List.of("PARENT", "TUTOR").contains(party)) throw new BusinessException("INVALID_NO_SHOW_PARTY", "Bên no-show không hợp lệ.");
    String oldStatus = booking.get("status").toString();
    String next = "TUTOR".equals(party) ? "no_show_tutor" : "no_show_parent";
    jdbc.update("update trial_bookings set status = ?, updated_at = now() where id = ?", next, bookingId);
    jdbc.update("""
        insert into booking_no_show_records(booking_id, actor_user_id, actor_role, no_show_party, reason, note)
        values (?, ?, ?, ?, ?, ?)
        """, bookingId, db.currentUserIdOrThrow(), db.currentUserOrThrow().get("role"), party, value(body, "reason", null), value(body, "note", null));
    bookingHistory(bookingId, oldStatus, next, "no_show", party);
    db.auditCurrent("trial_booking.no_show", "booking", bookingId, "Ghi nhận no-show học thử.");
    return bookingById(bookingId);
  }

  @Transactional
  public Map<String, Object> complete(UUID bookingId, Map<String, Object> body) {
    Map<String, Object> booking = bookingById(bookingId);
    if (!db.isAdmin() && (!db.isTutor() || !currentTutorId().equals(UUID.fromString(booking.get("tutorId").toString())))) {
      throw new ForbiddenException("Bạn không có quyền hoàn tất booking này.");
    }
    if (!"scheduled".equals(booking.get("status"))) throw new BusinessException("BOOKING_NOT_SCHEDULED", "Chỉ hoàn tất booking đã được lên lịch.");
    jdbc.update("update trial_bookings set status = 'completed', result_note = coalesce(?, result_note), updated_at = now() where id = ?", body == null ? null : string(body.get("resultNote")), bookingId);
    bookingHistory(bookingId, "scheduled", "completed", "complete", body == null ? null : string(body.get("resultNote")));
    if (booking.get("learningRequestId") != null) jdbc.update("update learning_requests set status = 'trial_completed', updated_at = now() where id = ?", UUID.fromString(booking.get("learningRequestId").toString()));
    db.auditCurrent("trial_booking.complete", "booking", bookingId, "Hoàn tất học thử.");
    return bookingById(bookingId);
  }

  @Transactional
  public Map<String, Object> convertToClass(UUID bookingId, Map<String, Object> body) {
    Map<String, Object> booking = bookingById(bookingId);
    ensureAccess(booking);
    if (!"completed".equals(booking.get("status"))) throw new BusinessException("BOOKING_NOT_COMPLETED", "Chỉ chuyển lớp khi học thử đã hoàn tất.");
    UUID existing = db.optional("select id from tutoring_classes where trial_booking_id = ?", (rs, row) -> rs.getObject("id", UUID.class), bookingId).orElse(null);
    if (existing != null) return db.classById(existing);
    UUID classId = jdbc.queryForObject("""
        insert into tutoring_classes(learning_request_id, trial_booking_id, student_id, student_profile_id, tutor_id, subject_id, grade_level_id,
          title, learning_mode, location, meeting_url, hourly_rate, sessions_per_week, start_date, status)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_date, 'active') returning id
        """, UUID.class, uuidOrNull(booking.get("learningRequestId")), bookingId, uuidOrNull(booking.get("studentId")), uuidOrNull(booking.get("studentProfileId")),
        UUID.fromString(booking.get("tutorId").toString()), db.requiredSubjectId(booking.get("subject")), db.gradeLevelId(booking.get("grade")),
        value(body, "title", "Lớp " + booking.get("subject") + " - " + booking.get("studentName")), booking.get("learningMode"),
        value(body, "location", string(booking.get("location"))), value(body, "meetingUrl", string(booking.get("meetingUrl"))),
        numberOr(body, "hourlyRate", 0), numberOr(body, "sessionsPerWeek", 2));
    jdbc.update("update trial_bookings set status = 'converted_to_class', converted_class_id = ?, updated_at = now() where id = ?", classId, bookingId);
    bookingHistory(bookingId, "completed", "converted_to_class", "convert_to_class", null);
    if (booking.get("learningRequestId") != null) jdbc.update("update learning_requests set status = 'converted_to_class', updated_at = now() where id = ?", UUID.fromString(booking.get("learningRequestId").toString()));
    db.auditCurrent("trial_booking.convert_to_class", "booking", bookingId, "Chuyển học thử thành lớp chính thức.");
    return db.classById(classId);
  }

  private String nextConfirmationStatus(Map<String, Object> booking) {
    boolean parentConfirmed = booking.get("parentConfirmedAt") != null;
    boolean tutorConfirmed = booking.get("tutorConfirmedAt") != null;
    boolean hasTime = booking.get("scheduledStart") != null && booking.get("scheduledEnd") != null;
    boolean offline = "offline".equals(booking.get("learningMode"));
    if (offline && (booking.get("location") == null || booking.get("location").toString().isBlank())) {
      if (parentConfirmed && tutorConfirmed) throw new BusinessException("OFFLINE_LOCATION_REQUIRED", "Booking học thử offline cần địa điểm rõ ràng trước khi lên lịch.");
      return tutorConfirmed ? "tutor_confirmed" : "parent_confirmed";
    }
    if (parentConfirmed && tutorConfirmed && hasTime) return "scheduled";
    if (tutorConfirmed) return "tutor_confirmed";
    if (parentConfirmed) return "parent_confirmed";
    return "requested";
  }

  private Map<String, Object> bookingById(UUID bookingId) {
    return jdbc.query("""
        select tb.*, s.name subject, gl.name grade, tp.user_id tutor_user_id, tu.full_name tutor_name
        from trial_bookings tb
        join subjects s on s.id = tb.subject_id
        left join grade_levels gl on gl.id = tb.grade_level_id
        join tutor_profiles tp on tp.id = tb.tutor_id
        join users tu on tu.id = tp.user_id
        where tb.id = ?
        """, this::mapAny, bookingId).stream().findFirst().orElseThrow(() -> new NotFoundException("Không tìm thấy booking."));
  }

  private void ensureAccess(Map<String, Object> booking) {
    if (db.isAdmin()) return;
    UUID current = db.currentUserIdOrThrow();
    if (booking.get("studentId") != null && current.equals(UUID.fromString(booking.get("studentId").toString()))) return;
    if (isParentSide(booking, current)) return;
    if (db.isTutor() && currentTutorId().equals(UUID.fromString(booking.get("tutorId").toString()))) return;
    throw new ForbiddenException("Bạn không có quyền xem booking này.");
  }

  private boolean isParentSide(Map<String, Object> booking, UUID userId) {
    Object studentProfileId = booking.get("studentProfileId");
    if (studentProfileId == null) return false;
    Integer count = jdbc.queryForObject("select count(*) from guardian_student_links where guardian_user_id = ? and student_profile_id = ? and can_book = true", Integer.class, userId, UUID.fromString(studentProfileId.toString()));
    return count != null && count > 0;
  }

  private UUID currentTutorId() {
    return db.tutorIdByUserOrThrow(db.currentUserIdOrThrow());
  }

  private void bookingHistory(UUID bookingId, String oldStatus, String newStatus, String reason, String note) {
    Map<String, Object> user = db.currentUserOrThrow();
    jdbc.update("""
        insert into trial_booking_status_history(trial_booking_id, actor_user_id, actor_role, old_status, new_status, reason, note)
        values (?, ?, ?, ?, ?, ?, ?)
        """, bookingId, UUID.fromString(user.get("id").toString()), user.get("role"), oldStatus, newStatus, reason, note);
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

  private String normalizeMode(String value) {
    String normalized = value == null ? "online" : value.trim().toLowerCase();
    if (normalized.equals("offline")) return "offline";
    return "online";
  }

  private OffsetDateTime dateTime(Object... values) {
    for (Object value : values) {
      if (value != null && !value.toString().isBlank()) return OffsetDateTime.parse(value.toString());
    }
    return null;
  }

  private String required(Map<String, Object> body, String key) {
    String value = string(body.get(key));
    if (value == null) throw new BusinessException("FIELD_REQUIRED", "Thiếu trường " + key + ".");
    return value;
  }

  private String value(Map<String, Object> body, String key, String fallback) {
    if (body == null) return fallback;
    String value = string(body.get(key));
    return value == null ? fallback : value;
  }

  private String string(Object value) {
    return value == null || value.toString().isBlank() ? null : value.toString();
  }

  private UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private UUID uuidOrNull(Object value) {
    return value == null || value.toString().isBlank() ? null : UUID.fromString(value.toString());
  }

  private int numberOr(Map<String, Object> body, String key, int fallback) {
    if (body == null || body.get(key) == null || body.get(key).toString().isBlank()) return fallback;
    return Integer.parseInt(body.get(key).toString());
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
}
