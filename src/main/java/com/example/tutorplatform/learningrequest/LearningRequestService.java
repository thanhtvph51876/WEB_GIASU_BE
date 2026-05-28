package com.example.tutorplatform.learningrequest;

import static com.example.tutorplatform.platform.PlatformRequestSupport.bool;
import static com.example.tutorplatform.platform.PlatformRequestSupport.firstInteger;
import static com.example.tutorplatform.platform.PlatformRequestSupport.firstPresent;
import static com.example.tutorplatform.platform.PlatformRequestSupport.firstString;
import static com.example.tutorplatform.platform.PlatformRequestSupport.locationSummary;
import static com.example.tutorplatform.platform.PlatformRequestSupport.normalizeOnlineOffline;
import static com.example.tutorplatform.platform.PlatformRequestSupport.optionalDateTime;
import static com.example.tutorplatform.platform.PlatformRequestSupport.string;
import static com.example.tutorplatform.platform.PlatformRequestSupport.uuid;
import static com.example.tutorplatform.platform.PlatformRequestSupport.uuidOrNull;
import static com.example.tutorplatform.platform.PlatformRequestSupport.valueOr;

import com.example.tutorplatform.common.BusinessException;
import com.example.tutorplatform.common.ForbiddenException;
import com.example.tutorplatform.db.DbService;
import com.example.tutorplatform.policy.StatusTransitionPolicy;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LearningRequestService {
  private final DbService db;
  private final JdbcTemplate jdbc;
  private final StatusTransitionPolicy statusPolicy;

  public LearningRequestService(DbService db, StatusTransitionPolicy statusPolicy) {
    this.db = db;
    this.jdbc = db.jdbc();
    this.statusPolicy = statusPolicy;
  }

  public List<Map<String, Object>> learningRequests() {
    UUID userId = db.currentUserIdOrThrow();
    if (db.isAdmin()) return db.learningRequests("");
    if (db.isTutor()) {
      UUID tutorId = db.tutorIdByUserOrThrow(userId);
      return db.learningRequests(" where lr.assigned_tutor_id = ?", tutorId);
    }
    return db.learningRequests(" where lr.requester_id = ?", userId);
  }

  public List<Map<String, Object>> publicLearningRequests() {
    return jdbc.query("""
        select lr.id, lr.request_code, lr.student_grade, s.name subject_name, gl.name grade_name,
          lr.learning_mode, lr.province, lr.district, lr.budget_min, lr.budget_max,
          lr.preferred_schedule, lr.status, lr.created_at
        from learning_requests lr
        join subjects s on s.id = lr.subject_id
        left join grade_levels gl on gl.id = lr.grade_level_id
        where lr.public_visible = true
          and lr.status in ('new','consulting','matched','trial_scheduled')
        order by lr.created_at desc
        limit 100
        """, (rs, row) -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", rs.getObject("id").toString());
      m.put("requestCode", rs.getString("request_code"));
      m.put("subject", rs.getString("subject_name"));
      m.put("grade", valueOr(rs.getString("student_grade"), rs.getString("grade_name")));
      m.put("teachingMode", rs.getString("learning_mode"));
      m.put("learningMode", rs.getString("learning_mode"));
      m.put("province", rs.getString("province"));
      m.put("district", rs.getString("district"));
      m.put("location", locationSummary(rs.getString("province"), rs.getString("district")));
      m.put("budgetMin", nullableInt(rs, "budget_min"));
      m.put("budgetMax", nullableInt(rs, "budget_max"));
      m.put("expectedFee", nullableInt(rs, "budget_max"));
      m.put("preferredSchedule", rs.getString("preferred_schedule"));
      m.put("status", rs.getString("status"));
      m.put("createdAt", rs.getObject("created_at", OffsetDateTime.class).toString());
      return m;
    });
  }

  @Transactional
  public Map<String, Object> createPublic(Map<String, Object> body) {
    validatePublicLearningRequest(body);
    return publicLearningRequestResponse(createLearningRequestInternal(body, null));
  }

  public List<Map<String, Object>> myStudentLearningRequests() {
    requireStudentOrParent();
    return db.learningRequests(" where lr.requester_id = ?", db.currentUserIdOrThrow());
  }

  @Transactional
  public Map<String, Object> createStudent(Map<String, Object> body) {
    requireStudentOrParent();
    return createLearningRequestInternal(body, db.currentUserIdOrThrow());
  }

  @Transactional
  public Map<String, Object> create(Map<String, Object> body) {
    return createLearningRequestInternal(body, db.currentUserIdOrThrow());
  }

  public Map<String, Object> get(UUID requestId) {
    Map<String, Object> request = db.learningRequestById(requestId);
    UUID requesterId = uuidOrNull(request.get("userId"));
    UUID assignedTutorId = uuidOrNull(request.get("assignedTutorId"));
    UUID current = db.currentUserIdOrThrow();
    if (!db.isAdmin() && (requesterId == null || !requesterId.equals(current))) {
      if (!db.isTutor() || assignedTutorId == null || !assignedTutorId.equals(db.tutorIdByUserOrThrow(current))) {
        throw new ForbiddenException("Bạn không có quyền xem yêu cầu này.");
      }
    }
    return request;
  }

  public Map<String, Object> update(UUID requestId, Map<String, Object> body) {
    Map<String, Object> request = db.learningRequestById(requestId);
    if (!db.isAdmin()) db.requireUserOwned(uuidOrNull(request.get("userId")));
    Object subjectInput = firstPresent(body, "subjectId", "subject");
    Object gradeInput = firstPresent(body, "gradeLevelId", "grade");
    UUID subjectId = subjectInput == null ? null : db.requiredSubjectId(subjectInput);
    UUID gradeId = gradeInput == null ? null : db.gradeLevelId(gradeInput);
    String submittedGoal = firstString(body, "goal");
    String goalCode = submittedGoal == null ? null : submittedGoal.length() <= 50 ? submittedGoal : "custom";
    String learningGoal = firstString(body, "learningGoal");
    if (learningGoal == null && submittedGoal != null && submittedGoal.length() > 50) learningGoal = submittedGoal;
    jdbc.update("""
        update learning_requests set student_name = coalesce(?, student_name), parent_name = coalesce(?, parent_name),
          phone = coalesce(?, phone), email = coalesce(?, email), student_grade = coalesce(?, student_grade),
          subject_id = coalesce(?, subject_id), grade_level_id = coalesce(?, grade_level_id),
          goal = coalesce(?, goal), learning_goal = coalesce(?, learning_goal),
          learning_mode = coalesce(?, learning_mode), province = coalesce(?, province), district = coalesce(?, district),
          budget_min = coalesce(?, budget_min), budget_max = coalesce(?, budget_max),
          note = coalesce(?, note), preferred_schedule = coalesce(?, preferred_schedule), updated_at = now()
        where id = ?
        """, firstString(body, "studentName"), firstString(body, "parentName"), firstString(body, "phone"),
        firstString(body, "email"), firstString(body, "grade"), subjectId, gradeId, goalCode, learningGoal,
        firstString(body, "teachingMode", "learningMode"), firstString(body, "province", "location"), firstString(body, "district"),
        firstInteger(body, "budgetMin"), firstInteger(body, "budgetMax", "expectedFee"),
        firstString(body, "note"), firstString(body, "preferredSchedule"), requestId);
    return db.learningRequestById(requestId);
  }

  public Map<String, Object> cancel(UUID requestId) {
    Map<String, Object> request = db.learningRequestById(requestId);
    if (!db.isAdmin()) db.requireUserOwned(uuidOrNull(request.get("userId")));
    statusPolicy.requireLearningRequest(request.get("status").toString(), "cancelled");
    jdbc.update("update learning_requests set status = 'cancelled', updated_at = now() where id = ?", requestId);
    db.auditCurrent("learning_request.cancel", "learningRequest", requestId, "Người dùng hủy yêu cầu tìm gia sư.");
    return db.learningRequestById(requestId);
  }

  public List<Map<String, Object>> adminLearningRequests() {
    return db.learningRequests("");
  }

  public Map<String, Object> adminLearningRequest(UUID requestId) {
    return db.learningRequestById(requestId);
  }

  public Map<String, Object> updateStatus(UUID requestId, Map<String, Object> body) {
    String status = firstString(body, "status");
    if (!List.of("draft", "submitted", "new", "consulting", "matching", "waiting_tutor_proposal",
        "proposal_received", "waiting_parent_confirmation", "matched", "trial_scheduled",
        "trial_completed", "active", "rematch", "converted_to_class", "cancelled",
        "completed", "expired", "closed").contains(status)) {
      throw new BusinessException("INVALID_STATUS", "Trạng thái yêu cầu không hợp lệ.");
    }
    Map<String, Object> current = db.learningRequestById(requestId);
    statusPolicy.requireLearningRequest(current.get("status").toString(), status);
    jdbc.update("update learning_requests set status = ?, updated_at = now() where id = ?", status, requestId);
    db.auditCurrent("admin.update_learning_request_status", "learningRequest", requestId, "Admin cập nhật trạng thái yêu cầu thành " + status + ".");
    return db.learningRequestById(requestId);
  }

  @Transactional
  public Map<String, Object> assignTutor(UUID requestId, Map<String, Object> body) {
    UUID tutorId = uuid(firstPresent(body, "tutorId", "assignedTutorId"));
    String tutorStatus = jdbc.queryForObject("select status from tutor_profiles where id = ?", String.class, tutorId);
    if (!"approved".equals(tutorStatus)) throw new BusinessException("TUTOR_NOT_APPROVED", "Chỉ có thể gán gia sư đã được duyệt.");
    jdbc.update("""
        update learning_requests set assigned_tutor_id = ?, assigned_by = ?, assigned_at = now(), status = 'matched', updated_at = now()
        where id = ?
        """, tutorId, db.currentUserIdOrThrow(), requestId);
    UUID tutorUserId = jdbc.queryForObject("select user_id from tutor_profiles where id = ?", UUID.class, tutorId);
    db.notify(tutorUserId, "info", "Yêu cầu dạy học mới", "Bạn được gán cho một yêu cầu tìm gia sư.", "/dashboard/tutor/requests", "learningRequest", requestId);
    Map<String, Object> request = db.learningRequestById(requestId);
    UUID requesterId = uuidOrNull(request.get("userId"));
    if (requesterId != null) db.notify(requesterId, "success", "Đã có gia sư phù hợp", "Admin đã gán gia sư cho yêu cầu của bạn.", "/dashboard/requests", "learningRequest", requestId);
    db.auditCurrent("admin.assign_tutor", "learningRequest", requestId, "Admin gán gia sư cho yêu cầu học.");
    return request;
  }

  @Transactional
  public Map<String, Object> assignTutorWithBooking(UUID requestId, Map<String, Object> body) {
    UUID tutorId = uuid(firstPresent(body, "tutorId", "assignedTutorId"));
    String tutorStatus = jdbc.queryForObject("select status from tutor_profiles where id = ?", String.class, tutorId);
    if (!"approved".equals(tutorStatus)) throw new BusinessException("TUTOR_NOT_APPROVED", "Chỉ có thể gán gia sư đã được duyệt.");

    Map<String, Object> request = db.learningRequestById(requestId);
    if (!"matched".equals(request.get("status").toString())) {
      statusPolicy.requireLearningRequest(request.get("status").toString(), "matched");
    }
    boolean createBooking = !Boolean.FALSE.equals(bool(body, "createBooking"));
    OffsetDateTime trialStart = optionalDateTime(body, "trialStartTime", "scheduledStart", "startTime");
    OffsetDateTime trialEnd = optionalDateTime(body, "trialEndTime", "scheduledEnd", "endTime");
    if ((trialStart == null) != (trialEnd == null)) {
      throw new BusinessException("INVALID_TRIAL_TIME", "Cần nhập đủ thời gian bắt đầu và kết thúc học thử.");
    }
    if (trialStart != null && !trialEnd.isAfter(trialStart)) {
      throw new BusinessException("INVALID_TRIAL_TIME", "Thời gian kết thúc học thử phải sau thời gian bắt đầu.");
    }
    UUID studentId = uuidOrNull(request.get("userId"));
    UUID subjectId = uuid(request.get("subjectId"));
    UUID gradeId = uuidOrNull(request.get("gradeLevelId"));
    UUID bookingId = null;
    if (createBooking) {
      bookingId = db.optional("""
          select id from trial_bookings
          where learning_request_id = ? and tutor_id = ? and status <> 'cancelled'
          order by created_at desc
          limit 1
          """, (rs, row) -> rs.getObject("id", UUID.class), requestId, tutorId).orElse(null);
      if (bookingId != null) {
        jdbc.update("""
            update trial_bookings
            set scheduled_start = coalesce(?, scheduled_start),
                scheduled_end = coalesce(?, scheduled_end),
                tutor_response_note = coalesce(?, tutor_response_note),
                status = coalesce(?, status),
                updated_at = now()
            where id = ?
            """, trialStart, trialEnd, firstString(body, "note"), trialStart == null ? null : "scheduled", bookingId);
        db.auditCurrent("admin.assign_tutor_with_booking_idempotent", "learningRequest", requestId, "Bỏ qua tạo booking trùng khi gán gia sư.");
      } else {
        bookingId = jdbc.queryForObject("""
            insert into trial_bookings(learning_request_id, student_id, tutor_id, subject_id, grade_level_id,
              student_name, parent_name, phone, email, preferred_time, learning_mode, scheduled_start,
              scheduled_end, goal, tutor_response_note, status)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            returning id
            """, UUID.class, requestId, studentId, tutorId, subjectId, gradeId,
            string(request, "studentName"), string(request, "parentName"), string(request, "phone"),
            string(request, "email"), valueOr(firstString(body, "preferredTime"), string(request, "preferredSchedule")),
            normalizeOnlineOffline(valueOr(string(request, "learningMode"), "online")), trialStart, trialEnd,
            valueOr(string(request, "learningGoal"), string(request, "note")), firstString(body, "note"),
            trialStart == null ? "assigned" : "scheduled");
      }
    }

    jdbc.update("""
        update learning_requests set assigned_tutor_id = ?, assigned_by = ?, assigned_at = now(), status = 'matched', updated_at = now()
        where id = ?
        """, tutorId, db.currentUserIdOrThrow(), requestId);
    UUID tutorUserId = jdbc.queryForObject("select user_id from tutor_profiles where id = ?", UUID.class, tutorId);
    db.notify(tutorUserId, "info", createBooking ? "Booking học thử mới" : "Yêu cầu dạy học mới",
        createBooking ? "Bạn được gán một booking học thử từ yêu cầu học." : "Bạn được gán cho một yêu cầu tìm gia sư.",
        "/dashboard/tutor/requests", createBooking ? "booking" : "learningRequest", createBooking ? bookingId : requestId);
    if (studentId != null) {
      db.notify(studentId, "success", "Đã có gia sư phù hợp",
          createBooking ? "Admin đã gán gia sư và tạo booking học thử." : "Admin đã gán gia sư cho yêu cầu của bạn.",
          createBooking ? "/dashboard/bookings" : "/dashboard/requests",
          createBooking ? "booking" : "learningRequest",
          createBooking ? bookingId : requestId);
    }
    db.auditCurrent("admin.assign_tutor_with_booking", "learningRequest", requestId, createBooking
        ? "Admin gán gia sư và tạo booking trong cùng giao dịch."
        : "Admin gán gia sư không tạo booking theo request.");
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("learningRequest", db.learningRequestById(requestId));
    data.put("booking", bookingId == null ? null : db.bookingById(bookingId));
    return data;
  }

  public List<Map<String, Object>> matchingTutors(UUID requestId) {
    Map<String, Object> request = db.learningRequestById(requestId);
    UUID subjectId = uuid(request.get("subjectId"));
    UUID gradeId = uuidOrNull(request.get("gradeLevelId"));
    Integer budgetMax = request.get("budgetMax") == null ? null : ((Number) request.get("budgetMax")).intValue();
    List<Map<String, Object>> tutors = db.tutorList("", new ArrayList<>(), 1, 100, false);
    List<Map<String, Object>> results = new ArrayList<>();
    for (Map<String, Object> tutor : tutors) {
      UUID tutorId = uuid(tutor.get("id"));
      double subjectMatch = exists("select 1 from tutor_subjects where tutor_id = ? and subject_id = ?", tutorId, subjectId) ? 1 : 0;
      double gradeMatch = gradeId == null || exists("select 1 from tutor_subjects where tutor_id = ? and grade_level_id = ?", tutorId, gradeId) ? 1 : 0;
      double locationMatch = string(request, "province") == null || exists("select 1 from tutor_locations where tutor_id = ? and lower(province) like lower(?)", tutorId, "%" + string(request, "province") + "%") ? 1 : 0;
      int rate = ((Number) tutor.getOrDefault("pricePerHour", 0)).intValue();
      double budgetMatch = budgetMax == null || rate <= budgetMax ? 1 : 0;
      double scheduleMatch = 0.7;
      double ratingScore = Math.min(1, ((Number) tutor.getOrDefault("rating", 0)).doubleValue() / 5.0);
      double responseScore = Math.min(1, ((Number) tutor.getOrDefault("responseRate", 0)).doubleValue() / 100.0);
      double score = subjectMatch * 30 + gradeMatch * 20 + locationMatch * 15 + budgetMatch * 10 + scheduleMatch * 10 + ratingScore * 10 + responseScore * 5;
      results.add(Map.of("tutor", tutor, "matchingScore", Math.round(score), "reasons", Map.of(
          "subjectMatch", subjectMatch == 1,
          "gradeMatch", gradeMatch == 1,
          "locationMatch", locationMatch == 1,
          "budgetMatch", budgetMatch == 1,
          "scheduleMatch", scheduleMatch,
          "ratingScore", ratingScore,
          "responseScore", responseScore
      )));
    }
    results.sort((a, b) -> Double.compare(((Number) b.get("matchingScore")).doubleValue(), ((Number) a.get("matchingScore")).doubleValue()));
    return results;
  }

  public Map<String, Object> rematch(UUID requestId) {
    jdbc.update("update learning_requests set assigned_tutor_id = null, status = 'rematch', updated_at = now() where id = ?", requestId);
    db.auditCurrent("admin.rematch_learning_request", "learningRequest", requestId, "Admin chuyển yêu cầu sang trạng thái cần ghép lại.");
    return db.learningRequestById(requestId);
  }

  public Map<String, Object> adminCancel(UUID requestId) {
    return updateStatus(requestId, Map.of("status", "cancelled"));
  }

  private void validatePublicLearningRequest(Map<String, Object> body) {
    String studentName = firstString(body, "studentName");
    String parentName = firstString(body, "parentName");
    if (studentName == null && parentName == null) {
      throw new BusinessException("CONTACT_NAME_REQUIRED", "Vui lòng nhập tên học sinh hoặc phụ huynh.");
    }
    String phone = firstString(body, "phone");
    String email = firstString(body, "email");
    if (phone == null && email == null) {
      throw new BusinessException("CONTACT_REQUIRED", "Vui lòng nhập số điện thoại hoặc email.");
    }
    Object subject = firstPresent(body, "subjectId", "subject");
    if (subject == null || subject.toString().isBlank()) {
      throw new BusinessException("SUBJECT_REQUIRED", "Vui lòng chọn môn học.");
    }
    if (firstString(body, "grade") == null) {
      throw new BusinessException("GRADE_REQUIRED", "Vui lòng nhập lớp hiện tại.");
    }
    if (phone != null && !phone.matches("^[0-9+() .-]{8,20}$")) {
      throw new BusinessException("INVALID_PHONE", "Số điện thoại không hợp lệ.");
    }
    if (email != null && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
      throw new BusinessException("INVALID_EMAIL", "Email không hợp lệ.");
    }
    for (String key : List.of("studentName", "parentName", "phone", "email", "grade", "province", "district", "preferredSchedule")) {
      String value = firstString(body, key);
      if (value != null && value.length() > 255) {
        throw new BusinessException("FIELD_TOO_LONG", "Trường " + key + " vượt quá độ dài cho phép.");
      }
    }
    String note = firstString(body, "note", "learningGoal", "goal");
    if (note != null && note.length() > 2000) {
      throw new BusinessException("FIELD_TOO_LONG", "Ghi chú vượt quá độ dài cho phép.");
    }
  }

  private Map<String, Object> createLearningRequestInternal(Map<String, Object> body, UUID userId) {
    UUID subjectId = db.requiredSubjectId(firstPresent(body, "subjectId", "subject"));
    UUID gradeId = db.gradeLevelId(firstPresent(body, "gradeLevelId", "grade"));
    UUID studentProfileId = uuidOrNull(firstPresent(body, "studentProfileId"));
    if (studentProfileId != null && userId != null && !db.isAdmin()) {
      Integer allowed = jdbc.queryForObject("""
          select count(*) from guardian_student_links
          where guardian_user_id = ? and student_profile_id = ? and can_book = true
          """, Integer.class, userId, studentProfileId);
      if (allowed == null || allowed == 0) {
        throw new ForbiddenException("Bạn không có quyền tạo yêu cầu cho học sinh này.");
      }
    }
    String code = "REQ-" + java.time.Year.now() + "-" + String.format("%03d",
        jdbc.queryForObject("select count(*) + 1 from learning_requests where date_part('year', created_at) = date_part('year', now())", Integer.class));
    String submittedGoal = firstString(body, "goal");
    String goalCode = submittedGoal == null ? null : submittedGoal.length() <= 50 ? submittedGoal : "custom";
    String learningGoal = valueOr(firstString(body, "learningGoal"), submittedGoal != null && submittedGoal.length() > 50 ? submittedGoal : null);
    UUID id = jdbc.queryForObject("""
        insert into learning_requests(request_code, requester_id, student_profile_id, student_name, parent_name, phone, email, student_grade,
          subject_id, grade_level_id, goal, learning_mode, province, district, budget_min, budget_max,
          preferred_schedule, learning_goal, note, status, public_visible)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'new', ?) returning id
        """, UUID.class, code, userId, studentProfileId, firstString(body, "studentName"), firstString(body, "parentName"),
        firstString(body, "phone"), firstString(body, "email"), firstString(body, "grade"),
        subjectId, gradeId, valueOr(goalCode, "improve_grades"),
        valueOr(firstString(body, "teachingMode", "learningMode"), "both"), firstString(body, "province", "location"),
        firstString(body, "district"), firstInteger(body, "budgetMin", "expectedFee"),
        firstInteger(body, "budgetMax", "expectedFee"), firstString(body, "preferredSchedule"),
        learningGoal, firstString(body, "note"), userId != null);
    db.notifyAdmins("info", "Yêu cầu học mới", "Có yêu cầu tìm gia sư mới cần xử lý.", "/admin/learning-requests", "learningRequest", id);
    if (userId == null) {
      db.audit(null, "guest", "public.create_learning_request", "learningRequest", id, "Khách gửi nhu cầu học qua form công khai.");
    } else {
      db.auditCurrent("student.create_learning_request", "learningRequest", id, "Tạo yêu cầu tìm gia sư mới.");
    }
    return db.learningRequestById(id);
  }

  private Map<String, Object> publicLearningRequestResponse(Map<String, Object> request) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", request.get("id"));
    m.put("requestCode", request.get("requestCode"));
    m.put("subject", request.get("subject"));
    m.put("grade", request.get("grade"));
    m.put("teachingMode", request.get("teachingMode"));
    m.put("learningMode", request.get("learningMode"));
    m.put("province", request.get("province"));
    m.put("district", request.get("district"));
    m.put("location", locationSummary(string(request, "province"), string(request, "district")));
    m.put("budgetMin", request.get("budgetMin"));
    m.put("budgetMax", request.get("budgetMax"));
    m.put("expectedFee", request.get("expectedFee"));
    m.put("preferredSchedule", request.get("preferredSchedule"));
    m.put("status", request.get("status"));
    m.put("createdAt", request.get("createdAt"));
    return m;
  }

  private void requireStudentOrParent() {
    String role = db.currentUserOrThrow().get("role").toString();
    if (!List.of("student", "parent").contains(role)) {
      throw new ForbiddenException("Chức năng này dành cho học sinh hoặc phụ huynh.");
    }
  }

  private Integer nullableInt(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
    Object value = rs.getObject(column);
    return value == null ? null : ((Number) value).intValue();
  }

  private boolean exists(String sql, Object... args) {
    Integer count = jdbc.queryForObject("select count(*) from (" + sql + ") x", Integer.class, args);
    return count != null && count > 0;
  }
}
