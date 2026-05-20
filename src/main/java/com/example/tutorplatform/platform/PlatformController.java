package com.example.tutorplatform.platform;

import com.example.tutorplatform.common.ApiResponse;
import com.example.tutorplatform.common.BusinessException;
import com.example.tutorplatform.common.ForbiddenException;
import com.example.tutorplatform.common.NotFoundException;
import com.example.tutorplatform.common.PageMetadata;
import com.example.tutorplatform.config.AppProperties;
import com.example.tutorplatform.db.DbService;
import com.example.tutorplatform.dto.booking.CompleteTrialRequest;
import com.example.tutorplatform.dto.booking.CreateBookingRequest;
import com.example.tutorplatform.dto.booking.RejectBookingRequest;
import com.example.tutorplatform.dto.booking.ScheduleBookingRequest;
import com.example.tutorplatform.dto.file.UploadedFileResponse;
import com.example.tutorplatform.dto.learningrequest.AssignTutorRequest;
import com.example.tutorplatform.dto.learningrequest.CreateLearningRequestRequest;
import com.example.tutorplatform.dto.learningrequest.UpdateLearningRequestRequest;
import com.example.tutorplatform.dto.learningrequest.UpdateLearningRequestStatusRequest;
import com.example.tutorplatform.dto.message.CreateConversationRequest;
import com.example.tutorplatform.dto.payment.AdminMarkPaymentRequest;
import com.example.tutorplatform.dto.payment.RefundRequest;
import com.example.tutorplatform.dto.payout.CreatePayoutRequest;
import com.example.tutorplatform.dto.payout.RejectPayoutRequest;
import com.example.tutorplatform.dto.review.CreateReviewRequest;
import com.example.tutorplatform.dto.session.CompleteSessionRequest;
import com.example.tutorplatform.dto.session.CreateSessionRequest;
import com.example.tutorplatform.dto.tutoringclass.CreateClassRequest;
import com.example.tutorplatform.dto.tutoringclass.UpdateClassRequest;
import com.example.tutorplatform.auth.RefreshTokenService;
import com.example.tutorplatform.payment.PaymentService;
import com.example.tutorplatform.policy.StatusTransitionPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class PlatformController {
  private final DbService db;
  private final JdbcTemplate jdbc;
  private final AppProperties properties;
  private final PaymentService paymentService;
  private final RefreshTokenService refreshTokenService;
  private final StatusTransitionPolicy statusPolicy;

  public PlatformController(DbService db, AppProperties properties, PaymentService paymentService, RefreshTokenService refreshTokenService,
                            StatusTransitionPolicy statusPolicy) {
    this.db = db;
    this.jdbc = db.jdbc();
    this.properties = properties;
    this.paymentService = paymentService;
    this.refreshTokenService = refreshTokenService;
    this.statusPolicy = statusPolicy;
  }

  @GetMapping("/catalog/subjects")
  public ApiResponse<List<Map<String, Object>>> subjects() {
    return ApiResponse.ok(jdbc.query("""
        select s.*, (select count(distinct tutor_id) from tutor_subjects ts where ts.subject_id = s.id) tutor_count
        from subjects s order by name
        """, (rs, row) -> Map.of(
        "id", rs.getObject("id").toString(),
        "name", rs.getString("name"),
        "slug", rs.getString("slug"),
        "description", rs.getString("description"),
        "icon", "",
        "category", "school",
        "tutorCount", rs.getInt("tutor_count")
    )));
  }

  @GetMapping("/catalog/grade-levels")
  public ApiResponse<List<Map<String, Object>>> gradeLevels() {
    return ApiResponse.ok(jdbc.query("select * from grade_levels order by sort_order", (rs, row) -> Map.of(
        "id", rs.getObject("id").toString(),
        "name", rs.getString("name"),
        "group", groupForGrade(rs.getInt("sort_order")),
        "sortOrder", rs.getInt("sort_order")
    )));
  }

  @GetMapping("/users/me")
  public ApiResponse<Map<String, Object>> me() {
    return ApiResponse.ok(db.currentUserOrThrow());
  }

  @PatchMapping("/users/me")
  public ApiResponse<Map<String, Object>> updateMe(@RequestBody Map<String, Object> body) {
    UUID userId = db.currentUserIdOrThrow();
    jdbc.update("""
        update users set
          full_name = coalesce(?, full_name),
          phone = coalesce(?, phone),
          avatar_url = coalesce(?, avatar_url),
          updated_at = now()
        where id = ?
        """, string(body, "fullName"), string(body, "phone"), firstString(body, "avatarUrl", "avatar"), userId);
    return ApiResponse.ok(db.userById(userId).orElseThrow(), "Cập nhật thông tin thành công");
  }

  @GetMapping("/users/me/profile")
  public ApiResponse<Map<String, Object>> myProfile() {
    Map<String, Object> user = db.currentUserOrThrow();
    UUID userId = uuid(user.get("id"));
    return switch (user.get("role").toString()) {
      case "tutor" -> ApiResponse.ok(db.tutorById(db.tutorIdByUserOrThrow(userId), true));
      case "parent" -> ApiResponse.ok(profile("parent_profiles", userId));
      default -> ApiResponse.ok(profile("student_profiles", userId));
    };
  }

  @PatchMapping("/users/me/profile")
  public ApiResponse<Map<String, Object>> updateMyProfile(@RequestBody Map<String, Object> body) {
    Map<String, Object> user = db.currentUserOrThrow();
    UUID userId = uuid(user.get("id"));
    if ("tutor".equals(user.get("role"))) {
      return updateTutorProfile(body);
    }
    if ("parent".equals(user.get("role"))) {
      jdbc.update("""
          update parent_profiles set relationship_to_student = coalesce(?, relationship_to_student),
            student_name = coalesce(?, student_name), student_grade = coalesce(?, student_grade),
            address = coalesce(?, address), province = coalesce(?, province), district = coalesce(?, district),
            updated_at = now()
          where user_id = ?
          """, string(body, "relationship"), string(body, "studentName"), firstString(body, "studentGrade", "grade"),
          string(body, "address"), string(body, "province"), string(body, "district"), userId);
      return ApiResponse.ok(profile("parent_profiles", userId));
    }
    jdbc.update("""
        update student_profiles set grade_level = coalesce(?, grade_level),
          school = coalesce(?, school), learning_goals = coalesce(?, learning_goals),
          preferred_learning_mode = coalesce(?, preferred_learning_mode),
          address = coalesce(?, address), province = coalesce(?, province), district = coalesce(?, district),
          updated_at = now()
        where user_id = ?
        """, firstString(body, "grade", "gradeLevel"), string(body, "school"), string(body, "learningGoals"),
        firstString(body, "teachingMode", "preferredLearningMode"), string(body, "address"),
        string(body, "province"), string(body, "district"), userId);
    return ApiResponse.ok(profile("student_profiles", userId));
  }

  @GetMapping("/admin/users")
  public ApiResponse<List<Map<String, Object>>> adminUsers() {
    return ApiResponse.ok(jdbc.query("select * from users order by created_at desc", db.userMapper()));
  }

  @GetMapping("/admin/users/{userId}")
  public ApiResponse<Map<String, Object>> adminUser(@PathVariable UUID userId) {
    return ApiResponse.ok(db.userById(userId).orElseThrow(() -> new NotFoundException("Không tìm thấy người dùng.")));
  }

  @PatchMapping("/admin/users/{userId}/status")
  public ApiResponse<Map<String, Object>> updateUserStatus(@PathVariable UUID userId, @RequestBody Map<String, Object> body) {
    String status = firstString(body, "status");
    if (!List.of("active", "inactive", "suspended").contains(status)) {
      throw new BusinessException("INVALID_STATUS", "Trạng thái người dùng không hợp lệ.");
    }
    jdbc.update("update users set status = ?, updated_at = now() where id = ?", status, userId);
    if (!"active".equals(status)) {
      refreshTokenService.revokeAllForUser(userId, "Admin cập nhật trạng thái tài khoản thành " + status + ", thu hồi toàn bộ refresh token.");
    }
    db.auditCurrent("admin.update_user_status", "user", userId, "Admin cập nhật trạng thái người dùng thành " + status + ".");
    return ApiResponse.ok(db.userById(userId).orElseThrow());
  }

  @GetMapping("/tutors")
  public ApiResponse<List<Map<String, Object>>> tutors(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String subjectId,
      @RequestParam(required = false) String subject,
      @RequestParam(required = false) String gradeLevelId,
      @RequestParam(required = false) String province,
      @RequestParam(required = false) String district,
      @RequestParam(required = false) String learningMode,
      @RequestParam(required = false) Integer minRate,
      @RequestParam(required = false) Integer maxRate,
      @RequestParam(required = false) Double minRating,
      @RequestParam(required = false) String gender,
      @RequestParam(required = false) Boolean verified,
      @RequestParam(defaultValue = "best_match") String sort,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize
  ) {
    List<Object> args = new ArrayList<>();
    StringBuilder where = new StringBuilder();
    if (q != null && !q.isBlank()) {
      where.append(" and (lower(u.full_name) like lower(?) or lower(tp.university) like lower(?) or lower(tp.major) like lower(?)) ");
      String pattern = "%" + q + "%";
      args.add(pattern);
      args.add(pattern);
      args.add(pattern);
    }
    String subjectFilter = subjectId != null ? subjectId : subject;
    if (subjectFilter != null && !subjectFilter.isBlank()) {
      UUID sid = db.requiredSubjectId(subjectFilter);
      where.append(" and exists (select 1 from tutor_subjects ts where ts.tutor_id = tp.id and ts.subject_id = ?) ");
      args.add(sid);
    }
    if (gradeLevelId != null && !gradeLevelId.isBlank()) {
      UUID gid = db.gradeLevelId(gradeLevelId);
      if (gid != null) {
        where.append(" and exists (select 1 from tutor_subjects ts where ts.tutor_id = tp.id and ts.grade_level_id = ?) ");
        args.add(gid);
      }
    }
    if (province != null && !province.isBlank()) {
      where.append(" and exists (select 1 from tutor_locations tl where tl.tutor_id = tp.id and lower(tl.province) like lower(?)) ");
      args.add("%" + province + "%");
    }
    if (district != null && !district.isBlank()) {
      where.append(" and exists (select 1 from tutor_locations tl where tl.tutor_id = tp.id and lower(tl.district) like lower(?)) ");
      args.add("%" + district + "%");
    }
    if (learningMode != null && !learningMode.isBlank() && !"both".equals(learningMode)) {
      where.append(" and exists (select 1 from tutor_locations tl where tl.tutor_id = tp.id and tl.teaching_mode in (?, 'both')) ");
      args.add(learningMode);
    }
    if (minRate != null) {
      where.append(" and coalesce(tp.hourly_rate_min, tp.hourly_rate_max, 0) >= ? ");
      args.add(minRate);
    }
    if (maxRate != null) {
      where.append(" and coalesce(tp.hourly_rate_min, tp.hourly_rate_max, 999999999) <= ? ");
      args.add(maxRate);
    }
    if (minRating != null) {
      where.append(" and tp.rating_avg >= ? ");
      args.add(minRating);
    }
    if (gender != null && !gender.isBlank()) {
      where.append(" and tp.gender = ? ");
      args.add(gender);
    }
    if (Boolean.TRUE.equals(verified)) {
      where.append(" and tp.status = 'approved' ");
    }
    long total = db.tutorCount(where.toString(), new ArrayList<>(args), false);
    return ApiResponse.page(db.tutorList(where.toString(), args, page, pageSize, false), PageMetadata.of(page, pageSize, total));
  }

  @GetMapping("/tutors/{tutorId}")
  public ApiResponse<Map<String, Object>> tutor(@PathVariable UUID tutorId) {
    return ApiResponse.ok(db.tutorById(tutorId, db.isAdmin()));
  }

  @GetMapping("/tutor/profile")
  public ApiResponse<Map<String, Object>> tutorProfile() {
    return ApiResponse.ok(db.tutorById(db.tutorIdByUserOrThrow(db.currentUserIdOrThrow()), true));
  }

  @PatchMapping("/tutor/profile")
  public ApiResponse<Map<String, Object>> updateTutorProfile(@RequestBody Map<String, Object> body) {
    UUID tutorId = db.tutorIdByUserOrThrow(db.currentUserIdOrThrow());
    jdbc.update("""
        update tutor_profiles set headline = coalesce(?, headline), bio = coalesce(?, bio),
          gender = coalesce(?, gender), education = coalesce(?, education), university = coalesce(?, university),
          major = coalesce(?, major), experience_years = coalesce(?, experience_years),
          teaching_method = coalesce(?, teaching_method), hourly_rate_min = coalesce(?, hourly_rate_min),
          hourly_rate_max = coalesce(?, hourly_rate_max), updated_at = now()
        where id = ?
        """, firstString(body, "headline"), firstString(body, "bio"), firstString(body, "gender"),
        firstString(body, "faculty", "education"), firstString(body, "university"), firstString(body, "major"),
        integer(body, "experienceYears"), firstString(body, "teachingMethod"), firstInteger(body, "pricePerHour", "hourlyRateMin"),
        firstInteger(body, "hourlyRateMax", "pricePerHour"), tutorId);
    replaceTutorSubjectsAndLocations(tutorId, body);
    return ApiResponse.ok(db.tutorById(tutorId, true), "Cập nhật hồ sơ gia sư thành công");
  }

  @PostMapping("/tutor/profile/submit")
  public ApiResponse<Map<String, Object>> submitTutorProfile() {
    UUID tutorId = db.tutorIdByUserOrThrow(db.currentUserIdOrThrow());
    jdbc.update("update tutor_profiles set status = 'pending', status_reason = null, updated_at = now() where id = ?", tutorId);
    db.notifyAdmins("info", "Hồ sơ gia sư chờ duyệt", "Có hồ sơ gia sư mới cần duyệt.", "/admin/tutors", "tutor", tutorId);
    db.auditCurrent("tutor.submit_profile", "tutor", tutorId, "Gia sư gửi hồ sơ để admin xét duyệt.");
    return ApiResponse.ok(db.tutorById(tutorId, true), "Hồ sơ đã được gửi duyệt");
  }

  @GetMapping("/tutor/documents")
  public ApiResponse<List<Map<String, Object>>> tutorDocuments() {
    UUID tutorId = db.tutorIdByUserOrThrow(db.currentUserIdOrThrow());
    return ApiResponse.ok(jdbc.query("select * from tutor_documents where tutor_id = ? order by created_at desc", documentMapper(), tutorId));
  }

  @PostMapping("/tutor/documents")
  public ApiResponse<Map<String, Object>> createTutorDocument(@RequestBody Map<String, Object> body) {
    UUID tutorId = db.tutorIdByUserOrThrow(db.currentUserIdOrThrow());
    UUID fileId = uploadedFileIdFromBody(body);
    if (fileId == null) {
      throw new BusinessException("FILE_ID_REQUIRED", "Giấy tờ gia sư phải gắn với file đã upload qua /api/v1/uploads.");
    }
    UUID ownerId = jdbc.queryForObject("select owner_id from uploaded_files where id = ?", UUID.class, fileId);
    if (!db.currentUserIdOrThrow().equals(ownerId)) {
      throw new ForbiddenException("Bạn không có quyền dùng file này.");
    }
    UUID id = jdbc.queryForObject("""
        insert into tutor_documents(tutor_id, document_type, file_id, file_name, file_url, file_size, mime_type)
        select ?, ?, id, original_file_name, '/api/v1/files/' || id, file_size, mime_type
        from uploaded_files
        where id = ?
        returning id
        """, UUID.class, tutorId, valueOr(firstString(body, "type", "documentType"), "other"),
        fileId);
    jdbc.update("""
        update uploaded_files
        set visibility = 'private', entity_type = 'tutor_document', entity_id = ?, updated_at = now()
        where id = ?
        """, id, fileId);
    db.auditCurrent("tutor.upload_private_document", "tutorDocument", id, "Gia sư tải lên giấy tờ riêng tư.");
    return ApiResponse.ok(jdbc.queryForObject("select * from tutor_documents where id = ?", documentMapper(), id));
  }

  @DeleteMapping("/tutor/documents/{documentId}")
  public ApiResponse<Map<String, Object>> deleteTutorDocument(@PathVariable UUID documentId) {
    UUID tutorId = db.tutorIdByUserOrThrow(db.currentUserIdOrThrow());
    int count = jdbc.update("delete from tutor_documents where id = ? and tutor_id = ?", documentId, tutorId);
    if (count == 0) throw new NotFoundException("Không tìm thấy giấy tờ.");
    return ApiResponse.ok(Map.of("deleted", true));
  }

  @GetMapping("/tutor/availability")
  public ApiResponse<List<Map<String, Object>>> availability() {
    UUID tutorId = db.tutorIdByUserOrThrow(db.currentUserIdOrThrow());
    return ApiResponse.ok(jdbc.query("""
        select id, tutor_id, day_of_week, start_time, end_time, is_active, created_at, updated_at
        from tutor_availability where tutor_id = ? order by day_of_week, start_time
        """, availabilityMapper(), tutorId));
  }

  @PostMapping("/tutor/availability")
  public ApiResponse<Map<String, Object>> createAvailability(@RequestBody Map<String, Object> body) {
    UUID tutorId = db.tutorIdByUserOrThrow(db.currentUserIdOrThrow());
    UUID id = jdbc.queryForObject("""
        insert into tutor_availability(tutor_id, day_of_week, start_time, end_time, is_active)
        values (?, ?, ?::time, ?::time, true) returning id
        """, UUID.class, tutorId, integer(body, "dayOfWeek"), firstString(body, "startTime"), firstString(body, "endTime"));
    return ApiResponse.ok(jdbc.queryForObject("select * from tutor_availability where id = ?", availabilityMapper(), id));
  }

  @PatchMapping("/tutor/availability/{availabilityId}")
  public ApiResponse<Map<String, Object>> updateAvailability(@PathVariable UUID availabilityId, @RequestBody Map<String, Object> body) {
    UUID tutorId = db.tutorIdByUserOrThrow(db.currentUserIdOrThrow());
    jdbc.update("""
        update tutor_availability set day_of_week = coalesce(?, day_of_week),
          start_time = coalesce(?::time, start_time), end_time = coalesce(?::time, end_time),
          is_active = coalesce(?, is_active), updated_at = now()
        where id = ? and tutor_id = ?
        """, integer(body, "dayOfWeek"), firstString(body, "startTime"), firstString(body, "endTime"),
        bool(body, "isActive"), availabilityId, tutorId);
    return ApiResponse.ok(jdbc.queryForObject("select * from tutor_availability where id = ?", availabilityMapper(), availabilityId));
  }

  @DeleteMapping("/tutor/availability/{availabilityId}")
  public ApiResponse<Map<String, Object>> deleteAvailability(@PathVariable UUID availabilityId) {
    UUID tutorId = db.tutorIdByUserOrThrow(db.currentUserIdOrThrow());
    jdbc.update("delete from tutor_availability where id = ? and tutor_id = ?", availabilityId, tutorId);
    return ApiResponse.ok(Map.of("deleted", true));
  }

  @GetMapping("/admin/tutors")
  public ApiResponse<List<Map<String, Object>>> adminTutors(@RequestParam(required = false) String status) {
    List<Object> args = new ArrayList<>();
    String where = "";
    if (status != null && !status.isBlank()) {
      where = " and tp.status = ? ";
      args.add(status);
    }
    return ApiResponse.ok(db.tutorList(where, args, 1, 500, true));
  }

  @GetMapping("/admin/tutors/{tutorId}")
  public ApiResponse<Map<String, Object>> adminTutor(@PathVariable UUID tutorId) {
    return ApiResponse.ok(db.tutorById(tutorId, true));
  }

  @PostMapping("/admin/tutors/{tutorId}/approve")
  public ApiResponse<Map<String, Object>> approveTutor(@PathVariable UUID tutorId) {
    String currentStatus = jdbc.queryForObject("select status from tutor_profiles where id = ?", String.class, tutorId);
    statusPolicy.requireTutor(currentStatus, "approved");
    jdbc.update("update tutor_profiles set status = 'approved', status_reason = null, approved_at = now(), approved_by = ?, updated_at = now() where id = ?",
        db.currentUserIdOrThrow(), tutorId);
    UUID userId = jdbc.queryForObject("select user_id from tutor_profiles where id = ?", UUID.class, tutorId);
    db.notify(userId, "success", "Hồ sơ đã được duyệt", "Hồ sơ gia sư của bạn đã được phê duyệt.", "/dashboard/tutor", "tutor", tutorId);
    db.auditCurrent("admin.approve_tutor", "tutor", tutorId, "Admin duyệt hồ sơ gia sư.");
    return ApiResponse.ok(db.tutorById(tutorId, true));
  }

  @PostMapping("/admin/tutors/{tutorId}/reject")
  public ApiResponse<Map<String, Object>> rejectTutor(@PathVariable UUID tutorId, @RequestBody Map<String, Object> body) {
    return updateTutorStatus(tutorId, "rejected", requiredReason(body));
  }

  @PostMapping("/admin/tutors/{tutorId}/request-update")
  public ApiResponse<Map<String, Object>> requestTutorUpdate(@PathVariable UUID tutorId, @RequestBody Map<String, Object> body) {
    return updateTutorStatus(tutorId, "need_update", requiredReason(body));
  }

  @PostMapping("/admin/tutors/{tutorId}/suspend")
  public ApiResponse<Map<String, Object>> suspendTutor(@PathVariable UUID tutorId, @RequestBody Map<String, Object> body) {
    return updateTutorStatus(tutorId, "suspended", requiredReason(body));
  }

  @PostMapping("/admin/tutors/{tutorId}/reactivate")
  public ApiResponse<Map<String, Object>> reactivateTutor(@PathVariable UUID tutorId) {
    return updateTutorStatus(tutorId, "approved", null);
  }

  @PostMapping("/admin/tutor-documents/{documentId}/approve")
  public ApiResponse<Map<String, Object>> approveDocument(@PathVariable UUID documentId) {
    return reviewDocument(documentId, "approved", null);
  }

  @PostMapping("/admin/tutor-documents/{documentId}/reject")
  public ApiResponse<Map<String, Object>> rejectDocument(@PathVariable UUID documentId, @RequestBody Map<String, Object> body) {
    return reviewDocument(documentId, "rejected", firstString(body, "note", "reason"));
  }

  @GetMapping("/learning-requests")
  public ApiResponse<List<Map<String, Object>>> learningRequests() {
    UUID userId = db.currentUserIdOrThrow();
    if (db.isAdmin()) return ApiResponse.ok(db.learningRequests(""));
    if (db.isTutor()) {
      UUID tutorId = db.tutorIdByUserOrThrow(userId);
      return ApiResponse.ok(db.learningRequests(" where lr.assigned_tutor_id = ?", tutorId));
    }
    return ApiResponse.ok(db.learningRequests(" where lr.requester_id = ?", userId));
  }

  @PostMapping("/learning-requests")
  @Transactional
  public ApiResponse<Map<String, Object>> createLearningRequest(@Valid @RequestBody CreateLearningRequestRequest request) {
    Map<String, Object> body = request.toMap();
    UUID userId = db.currentUserIdOrThrow();
    UUID subjectId = db.requiredSubjectId(firstPresent(body, "subjectId", "subject"));
    UUID gradeId = db.gradeLevelId(firstPresent(body, "gradeLevelId", "grade"));
    String code = "REQ-" + java.time.Year.now() + "-" + String.format("%03d",
        jdbc.queryForObject("select count(*) + 1 from learning_requests where date_part('year', created_at) = date_part('year', now())", Integer.class));
    UUID id = jdbc.queryForObject("""
        insert into learning_requests(request_code, requester_id, student_name, parent_name, phone, email, student_grade,
          subject_id, grade_level_id, goal, learning_mode, province, district, budget_min, budget_max,
          preferred_schedule, learning_goal, note, status)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'new') returning id
        """, UUID.class, code, userId, firstString(body, "studentName"), firstString(body, "parentName"),
        firstString(body, "phone"), firstString(body, "email"), firstString(body, "grade"),
        subjectId, gradeId, valueOr(firstString(body, "goal"), "improve_grades"),
        valueOr(firstString(body, "teachingMode", "learningMode"), "both"), firstString(body, "province", "location"),
        firstString(body, "district"), firstInteger(body, "budgetMin", "expectedFee"),
        firstInteger(body, "budgetMax", "expectedFee"), firstString(body, "preferredSchedule"),
        firstString(body, "learningGoal"), firstString(body, "note"));
    db.notifyAdmins("info", "Yêu cầu học mới", "Có yêu cầu tìm gia sư mới cần xử lý.", "/admin/learning-requests", "learningRequest", id);
    db.auditCurrent("student.create_learning_request", "learningRequest", id, "Tạo yêu cầu tìm gia sư mới.");
    return ApiResponse.ok(db.learningRequestById(id), "Yêu cầu đã được tạo");
  }

  @GetMapping("/learning-requests/{requestId}")
  public ApiResponse<Map<String, Object>> learningRequest(@PathVariable UUID requestId) {
    Map<String, Object> request = db.learningRequestById(requestId);
    UUID requesterId = uuidOrNull(request.get("userId"));
    UUID assignedTutorId = uuidOrNull(request.get("assignedTutorId"));
    UUID current = db.currentUserIdOrThrow();
    if (!db.isAdmin() && (requesterId == null || !requesterId.equals(current))) {
      if (!db.isTutor() || assignedTutorId == null || !assignedTutorId.equals(db.tutorIdByUserOrThrow(current))) {
        throw new ForbiddenException("Bạn không có quyền xem yêu cầu này.");
      }
    }
    return ApiResponse.ok(request);
  }

  @PatchMapping("/learning-requests/{requestId}")
  public ApiResponse<Map<String, Object>> updateLearningRequest(@PathVariable UUID requestId, @Valid @RequestBody UpdateLearningRequestRequest requestBody) {
    Map<String, Object> body = requestBody.toMap();
    Map<String, Object> request = db.learningRequestById(requestId);
    if (!db.isAdmin()) db.requireUserOwned(uuidOrNull(request.get("userId")));
    jdbc.update("""
        update learning_requests set student_name = coalesce(?, student_name), parent_name = coalesce(?, parent_name),
          phone = coalesce(?, phone), email = coalesce(?, email), note = coalesce(?, note),
          preferred_schedule = coalesce(?, preferred_schedule), updated_at = now()
        where id = ?
        """, firstString(body, "studentName"), firstString(body, "parentName"), firstString(body, "phone"),
        firstString(body, "email"), firstString(body, "note"), firstString(body, "preferredSchedule"), requestId);
    return ApiResponse.ok(db.learningRequestById(requestId));
  }

  @PostMapping("/learning-requests/{requestId}/cancel")
  public ApiResponse<Map<String, Object>> cancelLearningRequest(@PathVariable UUID requestId) {
    Map<String, Object> request = db.learningRequestById(requestId);
    if (!db.isAdmin()) db.requireUserOwned(uuidOrNull(request.get("userId")));
    statusPolicy.requireLearningRequest(request.get("status").toString(), "cancelled");
    jdbc.update("update learning_requests set status = 'cancelled', updated_at = now() where id = ?", requestId);
    db.auditCurrent("learning_request.cancel", "learningRequest", requestId, "Người dùng hủy yêu cầu tìm gia sư.");
    return ApiResponse.ok(db.learningRequestById(requestId));
  }

  @GetMapping("/admin/learning-requests")
  public ApiResponse<List<Map<String, Object>>> adminLearningRequests() {
    return ApiResponse.ok(db.learningRequests(""));
  }

  @GetMapping("/admin/learning-requests/{requestId}")
  public ApiResponse<Map<String, Object>> adminLearningRequest(@PathVariable UUID requestId) {
    return ApiResponse.ok(db.learningRequestById(requestId));
  }

  @PatchMapping("/admin/learning-requests/{requestId}/status")
  public ApiResponse<Map<String, Object>> updateLearningRequestStatus(@PathVariable UUID requestId, @Valid @RequestBody UpdateLearningRequestStatusRequest body) {
    return updateLearningRequestStatusInternal(requestId, body.status());
  }

  private ApiResponse<Map<String, Object>> updateLearningRequestStatusInternal(UUID requestId, String status) {
    if (!List.of("new", "consulting", "matched", "trial_scheduled", "trial_completed", "active", "rematch", "cancelled", "completed").contains(status)) {
      throw new BusinessException("INVALID_STATUS", "Trạng thái yêu cầu không hợp lệ.");
    }
    Map<String, Object> current = db.learningRequestById(requestId);
    statusPolicy.requireLearningRequest(current.get("status").toString(), status);
    jdbc.update("update learning_requests set status = ?, updated_at = now() where id = ?", status, requestId);
    db.auditCurrent("admin.update_learning_request_status", "learningRequest", requestId, "Admin cập nhật trạng thái yêu cầu thành " + status + ".");
    return ApiResponse.ok(db.learningRequestById(requestId));
  }

  @PostMapping("/admin/learning-requests/{requestId}/assign-tutor")
  @Transactional
  public ApiResponse<Map<String, Object>> assignTutor(@PathVariable UUID requestId, @Valid @RequestBody AssignTutorRequest requestBody) {
    Map<String, Object> body = requestBody.toMap();
    UUID tutorId = uuid(firstPresent(body, "tutorId", "assignedTutorId"));
    Map<String, Object> requestBefore = db.learningRequestById(requestId);
    String requestStatus = requestBefore.get("status").toString();
    if (List.of("cancelled", "completed", "active").contains(requestStatus)) {
      throw new BusinessException("LEARNING_REQUEST_NOT_ASSIGNABLE", "Yêu cầu học không còn có thể gán gia sư.");
    }
    if (!"matched".equals(requestStatus)) {
      statusPolicy.requireLearningRequest(requestStatus, "matched");
    }
    String tutorStatus = jdbc.queryForObject("select status from tutor_profiles where id = ?", String.class, tutorId);
    if (!"approved".equals(tutorStatus)) throw new BusinessException("TUTOR_NOT_APPROVED", "Chỉ có thể gán gia sư đã được duyệt.");
    jdbc.update("""
        update learning_requests set assigned_tutor_id = ?, assigned_by = ?, assigned_at = now(), status = 'matched', updated_at = now()
        where id = ?
        """, tutorId, db.currentUserIdOrThrow(), requestId);
    UUID tutorUserId = jdbc.queryForObject("select user_id from tutor_profiles where id = ?", UUID.class, tutorId);
    Map<String, Object> request = db.learningRequestById(requestId);
    Map<String, Object> booking = bookingForAssignedRequest(requestId, request, tutorId, body);
    db.notify(tutorUserId, "info", "Booking học thử mới", "Bạn được gán một yêu cầu học mới. Vui lòng xác nhận booking học thử.", "/dashboard/tutor/requests", "booking", uuid(booking.get("id")));
    UUID requesterId = uuidOrNull(request.get("userId"));
    if (requesterId != null) db.notify(requesterId, "success", "Đã có gia sư phù hợp", "Hệ thống đã tìm được gia sư phù hợp cho yêu cầu của bạn.", "/dashboard/requests", "learningRequest", requestId);
    db.auditCurrent("admin.assign_tutor", "learningRequest", requestId, "Admin gán gia sư cho yêu cầu học.");
    return ApiResponse.ok(Map.of("learningRequest", request, "booking", booking), "Đã gán gia sư và tạo booking học thử");
  }

  @GetMapping("/admin/learning-requests/{requestId}/matching-tutors")
  public ApiResponse<List<Map<String, Object>>> matchingTutors(@PathVariable UUID requestId) {
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
    return ApiResponse.ok(results);
  }

  @PostMapping("/admin/learning-requests/{requestId}/rematch")
  public ApiResponse<Map<String, Object>> rematch(@PathVariable UUID requestId) {
    jdbc.update("update learning_requests set assigned_tutor_id = null, status = 'rematch', updated_at = now() where id = ?", requestId);
    db.auditCurrent("admin.rematch_learning_request", "learningRequest", requestId, "Admin chuyển yêu cầu sang trạng thái cần ghép lại.");
    return ApiResponse.ok(db.learningRequestById(requestId));
  }

  @PostMapping("/admin/learning-requests/{requestId}/cancel")
  public ApiResponse<Map<String, Object>> adminCancelRequest(@PathVariable UUID requestId) {
    return updateLearningRequestStatusInternal(requestId, "cancelled");
  }

  @GetMapping("/bookings")
  public ApiResponse<List<Map<String, Object>>> bookings() {
    UUID userId = db.currentUserIdOrThrow();
    return db.isAdmin() ? ApiResponse.ok(db.bookings("")) : ApiResponse.ok(db.bookings(" where tb.student_id = ?", userId));
  }

  @PostMapping("/bookings")
  @Transactional
  public ApiResponse<Map<String, Object>> createBooking(@Valid @RequestBody CreateBookingRequest request) {
    Map<String, Object> body = request.toMap();
    UUID studentId = db.currentUserIdOrThrow();
    UUID tutorId = uuid(firstPresent(body, "tutorId"));
    String tutorStatus = jdbc.queryForObject("select status from tutor_profiles where id = ?", String.class, tutorId);
    if (!"approved".equals(tutorStatus)) throw new BusinessException("TUTOR_NOT_APPROVED", "Gia sư chưa được duyệt.");
    UUID subjectId = db.requiredSubjectId(firstPresent(body, "subjectId", "subject"));
    UUID gradeId = db.gradeLevelId(firstPresent(body, "gradeLevelId", "grade"));
    UUID id = jdbc.queryForObject("""
        insert into trial_bookings(learning_request_id, student_id, tutor_id, subject_id, grade_level_id,
          student_name, parent_name, phone, email, preferred_time, learning_mode, goal, status)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending') returning id
        """, UUID.class, uuidOrNull(firstPresent(body, "learningRequestId")), studentId, tutorId, subjectId, gradeId,
        firstString(body, "studentName"), firstString(body, "parentName"), firstString(body, "phone"),
        firstString(body, "email"), firstString(body, "preferredTime"),
        normalizeOnlineOffline(valueOr(firstString(body, "teachingMode", "learningMode"), "online")),
        firstString(body, "message", "goal"));
    UUID tutorUserId = jdbc.queryForObject("select user_id from tutor_profiles where id = ?", UUID.class, tutorId);
    db.notify(tutorUserId, "info", "Yêu cầu học thử mới", "Bạn có yêu cầu học thử mới.", "/dashboard/tutor/requests", "booking", id);
    db.notifyAdmins("info", "Booking học thử mới", "Có booking học thử mới cần theo dõi.", "/admin/bookings", "booking", id);
    db.auditCurrent("student.create_trial_booking", "booking", id, "Học viên đặt lịch học thử.");
    return ApiResponse.ok(db.bookingById(id), "Booking học thử đã được tạo");
  }

  @GetMapping("/bookings/{bookingId}")
  public ApiResponse<Map<String, Object>> booking(@PathVariable UUID bookingId) {
    Map<String, Object> booking = db.bookingById(bookingId);
    ensureBookingAccess(booking);
    return ApiResponse.ok(booking);
  }

  @PostMapping("/bookings/{bookingId}/cancel")
  public ApiResponse<Map<String, Object>> cancelBooking(@PathVariable UUID bookingId) {
    Map<String, Object> booking = db.bookingById(bookingId);
    if (!db.isAdmin() && !db.currentUserIdOrThrow().equals(uuid(booking.get("studentId")))) {
      throw new ForbiddenException("Bạn chỉ được hủy booking của mình.");
    }
    statusPolicy.requireBooking(booking.get("status").toString(), "cancelled");
    jdbc.update("update trial_bookings set status = 'cancelled', updated_at = now() where id = ?", bookingId);
    db.auditCurrent("booking.cancel", "booking", bookingId, "Hủy booking học thử.");
    return ApiResponse.ok(db.bookingById(bookingId));
  }

  @GetMapping("/tutor/bookings")
  public ApiResponse<List<Map<String, Object>>> tutorBookings() {
    UUID tutorId = db.tutorIdByUserOrThrow(db.currentUserIdOrThrow());
    return ApiResponse.ok(db.bookings(" where tb.tutor_id = ?", tutorId));
  }

  @PostMapping("/tutor/bookings/{bookingId}/accept")
  public ApiResponse<Map<String, Object>> acceptBooking(@PathVariable UUID bookingId, @Valid @RequestBody(required = false) ScheduleBookingRequest requestBody) {
    Map<String, Object> body = requestBody == null ? Map.of() : requestBody.toMap();
    UUID tutorId = db.tutorIdByUserOrThrow(db.currentUserIdOrThrow());
    String status = jdbc.queryForObject("select status from tutor_profiles where id = ?", String.class, tutorId);
    if (!"approved".equals(status)) throw new BusinessException("TUTOR_NOT_APPROVED", "Gia sư phải được duyệt mới được nhận booking.");
    Map<String, Object> booking = db.bookingById(bookingId);
    if (!tutorId.equals(uuid(booking.get("tutorId")))) throw new ForbiddenException("Bạn không có quyền nhận booking này.");
    statusPolicy.requireBooking(booking.get("status").toString(), "accepted");
    jdbc.update("update trial_bookings set status = 'accepted', updated_at = now() where id = ? and tutor_id = ?", bookingId, tutorId);
    if (body.containsKey("date") || body.containsKey("schedule") || body.containsKey("scheduledStart")) {
      scheduleBookingInternal(bookingId, body);
    }
    notifyBookingParties(bookingId, "success", "Gia sư đã chấp nhận booking", "Booking học thử đã được gia sư chấp nhận.");
    db.auditCurrent("tutor.accept_booking", "booking", bookingId, "Gia sư chấp nhận booking học thử.");
    return ApiResponse.ok(db.bookingById(bookingId));
  }

  @PostMapping("/tutor/bookings/{bookingId}/reject")
  public ApiResponse<Map<String, Object>> rejectBooking(@PathVariable UUID bookingId, @Valid @RequestBody RejectBookingRequest requestBody) {
    Map<String, Object> body = requestBody.toMap();
    UUID tutorId = db.tutorIdByUserOrThrow(db.currentUserIdOrThrow());
    Map<String, Object> booking = db.bookingById(bookingId);
    if (!tutorId.equals(uuid(booking.get("tutorId")))) throw new ForbiddenException("Bạn không có quyền từ chối booking này.");
    statusPolicy.requireBooking(booking.get("status").toString(), "rejected");
    int count = jdbc.update("update trial_bookings set status = 'rejected', tutor_response_note = ?, updated_at = now() where id = ? and tutor_id = ?",
        firstString(body, "reason", "rejectReason", "note"), bookingId, tutorId);
    if (count == 0) throw new ForbiddenException("Bạn không có quyền từ chối booking này.");
    notifyBookingParties(bookingId, "warning", "Gia sư từ chối booking", "Booking học thử đã bị từ chối.");
    db.auditCurrent("tutor.reject_booking", "booking", bookingId, "Gia sư từ chối booking học thử.");
    return ApiResponse.ok(db.bookingById(bookingId));
  }

  @GetMapping("/admin/bookings")
  public ApiResponse<List<Map<String, Object>>> adminBookings() {
    return ApiResponse.ok(db.bookings(""));
  }

  @GetMapping("/admin/bookings/{bookingId}")
  public ApiResponse<Map<String, Object>> adminBooking(@PathVariable UUID bookingId) {
    return ApiResponse.ok(db.bookingById(bookingId));
  }

  @PostMapping("/admin/bookings/{bookingId}/assign-tutor")
  public ApiResponse<Map<String, Object>> adminAssignBookingTutor(@PathVariable UUID bookingId, @Valid @RequestBody AssignTutorRequest requestBody) {
    Map<String, Object> body = requestBody.toMap();
    UUID tutorId = uuid(firstPresent(body, "tutorId"));
    Map<String, Object> booking = db.bookingById(bookingId);
    statusPolicy.requireBooking(booking.get("status").toString(), "assigned");
    jdbc.update("update trial_bookings set tutor_id = ?, status = 'assigned', updated_at = now() where id = ?", tutorId, bookingId);
    UUID tutorUserId = jdbc.queryForObject("select user_id from tutor_profiles where id = ?", UUID.class, tutorId);
    db.notify(tutorUserId, "info", "Bạn được gán booking", "Admin đã gán bạn vào một booking học thử.", "/dashboard/tutor/requests", "booking", bookingId);
    db.auditCurrent("admin.assign_booking_tutor", "booking", bookingId, "Admin gán gia sư cho booking học thử.");
    return ApiResponse.ok(db.bookingById(bookingId));
  }

  @PostMapping("/admin/bookings/{bookingId}/schedule")
  public ApiResponse<Map<String, Object>> scheduleBooking(@PathVariable UUID bookingId, @Valid @RequestBody ScheduleBookingRequest requestBody) {
    Map<String, Object> body = requestBody.toMap();
    scheduleBookingInternal(bookingId, body);
    db.auditCurrent("admin.schedule_booking", "booking", bookingId, "Admin xếp lịch học thử.");
    return ApiResponse.ok(db.bookingById(bookingId));
  }

  @PostMapping("/admin/bookings/{bookingId}/complete")
  public ApiResponse<Map<String, Object>> completeBooking(@PathVariable UUID bookingId, @Valid @RequestBody(required = false) CompleteTrialRequest requestBody) {
    Map<String, Object> body = requestBody == null ? Map.of() : requestBody.toMap();
    Map<String, Object> booking = db.bookingById(bookingId);
    statusPolicy.requireBooking(booking.get("status").toString(), "completed");
    jdbc.update("update trial_bookings set status = 'completed', result_note = coalesce(?, result_note), updated_at = now() where id = ?",
        firstString(body, "resultNote", "note"), bookingId);
    UUID requestId = jdbc.queryForObject("select learning_request_id from trial_bookings where id = ?", UUID.class, bookingId);
    if (requestId != null) jdbc.update("update learning_requests set status = 'trial_completed', updated_at = now() where id = ?", requestId);
    db.notifyAdmins("info", "Học thử đã hoàn tất", "Một booking học thử đã hoàn tất.", "/admin/bookings", "booking", bookingId);
    db.auditCurrent("admin.complete_booking", "booking", bookingId, "Admin đánh dấu hoàn tất học thử.");
    return ApiResponse.ok(db.bookingById(bookingId));
  }

  @PostMapping("/admin/bookings/{bookingId}/mark-no-show-student")
  public ApiResponse<Map<String, Object>> noShowStudent(@PathVariable UUID bookingId) {
    Map<String, Object> booking = db.bookingById(bookingId);
    statusPolicy.requireBooking(booking.get("status").toString(), "no_show_student");
    jdbc.update("update trial_bookings set status = 'no_show_student', updated_at = now() where id = ?", bookingId);
    return ApiResponse.ok(db.bookingById(bookingId));
  }

  @PostMapping("/admin/bookings/{bookingId}/mark-no-show-tutor")
  public ApiResponse<Map<String, Object>> noShowTutor(@PathVariable UUID bookingId) {
    Map<String, Object> booking = db.bookingById(bookingId);
    statusPolicy.requireBooking(booking.get("status").toString(), "no_show_tutor");
    jdbc.update("update trial_bookings set status = 'no_show_tutor', updated_at = now() where id = ?", bookingId);
    return ApiResponse.ok(db.bookingById(bookingId));
  }

  @PostMapping("/admin/bookings/{bookingId}/convert-to-class")
  @Transactional
  public ApiResponse<Map<String, Object>> convertBooking(@PathVariable UUID bookingId, @RequestBody(required = false) Map<String, Object> body) {
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
    return ApiResponse.ok(db.classById(classId));
  }

  @PostMapping("/admin/bookings/{bookingId}/cancel")
  public ApiResponse<Map<String, Object>> adminCancelBooking(@PathVariable UUID bookingId) {
    Map<String, Object> booking = db.bookingById(bookingId);
    statusPolicy.requireBooking(booking.get("status").toString(), "cancelled");
    jdbc.update("update trial_bookings set status = 'cancelled', updated_at = now() where id = ?", bookingId);
    db.auditCurrent("admin.cancel_booking", "booking", bookingId, "Admin hủy booking học thử.");
    return ApiResponse.ok(db.bookingById(bookingId));
  }

  @GetMapping("/classes")
  public ApiResponse<List<Map<String, Object>>> classes() {
    UUID userId = db.currentUserIdOrThrow();
    if (db.isAdmin()) return ApiResponse.ok(db.classes(""));
    if (db.isTutor()) return ApiResponse.ok(db.classes(" where tc.tutor_id = ?", db.tutorIdByUserOrThrow(userId)));
    return ApiResponse.ok(db.classes(" where tc.student_id = ?", userId));
  }

  @GetMapping("/classes/{classId}")
  public ApiResponse<Map<String, Object>> classById(@PathVariable UUID classId) {
    Map<String, Object> c = db.classById(classId);
    ensureClassAccess(c);
    return ApiResponse.ok(c);
  }

  @GetMapping("/classes/{classId}/sessions")
  public ApiResponse<List<Map<String, Object>>> classSessions(@PathVariable UUID classId) {
    Map<String, Object> c = db.classById(classId);
    ensureClassAccess(c);
    return ApiResponse.ok(db.sessions(" where cs.class_id = ?", classId));
  }

  @GetMapping("/tutor/classes")
  public ApiResponse<List<Map<String, Object>>> tutorClasses() {
    return ApiResponse.ok(db.classes(" where tc.tutor_id = ?", db.tutorIdByUserOrThrow(db.currentUserIdOrThrow())));
  }

  @GetMapping("/tutor/classes/{classId}")
  public ApiResponse<Map<String, Object>> tutorClass(@PathVariable UUID classId) {
    Map<String, Object> c = db.classById(classId);
    if (!uuid(c.get("tutorId")).equals(db.tutorIdByUserOrThrow(db.currentUserIdOrThrow()))) throw new ForbiddenException("Bạn không có quyền xem lớp này.");
    return ApiResponse.ok(c);
  }

  @GetMapping("/tutor/sessions")
  public ApiResponse<List<Map<String, Object>>> tutorSessions() {
    return ApiResponse.ok(db.sessions(" where cs.tutor_id = ?", db.tutorIdByUserOrThrow(db.currentUserIdOrThrow())));
  }

  @GetMapping("/sessions/{sessionId}")
  public ApiResponse<Map<String, Object>> session(@PathVariable UUID sessionId) {
    Map<String, Object> session = db.sessionById(sessionId);
    Map<String, Object> c = db.classById(uuid(session.get("classId")));
    ensureClassAccess(c);
    return ApiResponse.ok(session);
  }

  @PostMapping("/tutor/sessions/{sessionId}/complete")
  public ApiResponse<Map<String, Object>> tutorCompleteSession(@PathVariable UUID sessionId, @Valid @RequestBody(required = false) CompleteSessionRequest requestBody) {
    Map<String, Object> body = requestBody == null ? Map.of() : requestBody.toMap();
    UUID tutorId = db.tutorIdByUserOrThrow(db.currentUserIdOrThrow());
    UUID sessionTutor = jdbc.queryForObject("select tutor_id from class_sessions where id = ?", UUID.class, sessionId);
    if (!tutorId.equals(sessionTutor)) throw new ForbiddenException("Bạn không có quyền hoàn tất buổi học này.");
    completeSessionInternal(sessionId, firstString(body, "note", "tutorNote"));
    return ApiResponse.ok(db.sessionById(sessionId));
  }

  @PostMapping("/tutor/sessions/{sessionId}/cancel")
  public ApiResponse<Map<String, Object>> tutorCancelSession(@PathVariable UUID sessionId, @RequestBody(required = false) Map<String, Object> body) {
    UUID tutorId = db.tutorIdByUserOrThrow(db.currentUserIdOrThrow());
    Map<String, Object> session = db.sessionById(sessionId);
    statusPolicy.requireSession(session.get("status").toString(), "cancelled");
    int count = jdbc.update("update class_sessions set status = 'cancelled', tutor_note = coalesce(?, tutor_note), updated_at = now() where id = ? and tutor_id = ?",
        body == null ? null : firstString(body, "note"), sessionId, tutorId);
    if (count == 0) throw new ForbiddenException("Bạn không có quyền hủy buổi học này.");
    db.auditCurrent("tutor.cancel_session", "session", sessionId, "Gia sư hủy buổi học.");
    return ApiResponse.ok(db.sessionById(sessionId));
  }

  @GetMapping("/admin/classes")
  public ApiResponse<List<Map<String, Object>>> adminClasses() {
    return ApiResponse.ok(db.classes(""));
  }

  @GetMapping("/admin/sessions")
  public ApiResponse<List<Map<String, Object>>> adminSessions() {
    return ApiResponse.ok(db.sessions(""));
  }

  @PostMapping("/admin/classes")
  public ApiResponse<Map<String, Object>> createClass(@Valid @RequestBody CreateClassRequest requestBody) {
    Map<String, Object> body = requestBody.toMap();
    UUID studentId = uuid(firstPresent(body, "studentId"));
    UUID tutorId = uuid(firstPresent(body, "tutorId"));
    UUID subjectId = db.requiredSubjectId(firstPresent(body, "subjectId", "subject"));
    UUID gradeId = db.gradeLevelId(firstPresent(body, "gradeLevelId", "grade"));
    UUID id = jdbc.queryForObject("""
        insert into tutoring_classes(learning_request_id, student_id, tutor_id, subject_id, grade_level_id, title,
          learning_mode, location, meeting_url, hourly_rate, sessions_per_week, start_date, end_date, status)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, coalesce(?::date, current_date), ?::date, coalesce(?, 'active')) returning id
        """, UUID.class, uuidOrNull(firstPresent(body, "learningRequestId")), studentId, tutorId, subjectId, gradeId,
        firstString(body, "title"), normalizeOnlineOffline(valueOr(firstString(body, "mode", "learningMode"), "online")),
        firstString(body, "location"), firstString(body, "meetingUrl"), firstInteger(body, "feePerSession", "hourlyRate"),
        integer(body, "sessionsPerWeek"), firstString(body, "startDate"), firstString(body, "endDate"),
        firstString(body, "status"));
    db.auditCurrent("admin.create_class", "class", id, "Admin tạo lớp học mới.");
    return ApiResponse.ok(db.classById(id));
  }

  @GetMapping("/admin/classes/{classId}")
  public ApiResponse<Map<String, Object>> adminClass(@PathVariable UUID classId) {
    return ApiResponse.ok(db.classById(classId));
  }

  @PatchMapping("/admin/classes/{classId}")
  public ApiResponse<Map<String, Object>> updateClass(@PathVariable UUID classId, @Valid @RequestBody UpdateClassRequest requestBody) {
    Map<String, Object> body = requestBody.toMap();
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
    return ApiResponse.ok(db.classById(classId));
  }

  private ApiResponse<Map<String, Object>> updateClassStatus(UUID classId, String status, String description) {
    Map<String, Object> c = db.classById(classId);
    statusPolicy.requireClass(c.get("status").toString(), status);
    jdbc.update("update tutoring_classes set status = ?, updated_at = now() where id = ?", status, classId);
    db.auditCurrent("admin.update_class_status", "class", classId, description);
    return ApiResponse.ok(db.classById(classId));
  }

  @PostMapping("/admin/classes/{classId}/pause")
  public ApiResponse<Map<String, Object>> pauseClass(@PathVariable UUID classId) {
    return updateClassStatus(classId, "paused", "Admin tạm dừng lớp học.");
  }

  @PostMapping("/admin/classes/{classId}/complete")
  public ApiResponse<Map<String, Object>> completeClass(@PathVariable UUID classId) {
    return updateClassStatus(classId, "completed", "Admin hoàn tất lớp học.");
  }

  @PostMapping("/admin/classes/{classId}/cancel")
  public ApiResponse<Map<String, Object>> cancelClass(@PathVariable UUID classId) {
    return updateClassStatus(classId, "cancelled", "Admin hủy lớp học.");
  }

  @GetMapping("/admin/classes/{classId}/sessions")
  public ApiResponse<List<Map<String, Object>>> adminClassSessions(@PathVariable UUID classId) {
    return ApiResponse.ok(db.sessions(" where cs.class_id = ?", classId));
  }

  @PostMapping("/admin/classes/{classId}/sessions")
  public ApiResponse<Map<String, Object>> createSession(@PathVariable UUID classId, @Valid @RequestBody CreateSessionRequest requestBody) {
    Map<String, Object> body = requestBody.toMap();
    Map<String, Object> c = db.classById(classId);
    UUID id = jdbc.queryForObject("""
        insert into class_sessions(class_id, student_id, tutor_id, scheduled_start, scheduled_end, status)
        values (?, ?, ?, ?::timestamptz, ?::timestamptz, 'scheduled') returning id
        """, UUID.class, classId, uuid(c.get("studentId")), uuid(c.get("tutorId")),
        firstString(body, "scheduledStart", "startTime"), firstString(body, "scheduledEnd", "endTime"));
    db.auditCurrent("admin.create_session", "session", id, "Admin tạo buổi học.");
    return ApiResponse.ok(db.sessionById(id));
  }

  @PatchMapping("/admin/sessions/{sessionId}")
  public ApiResponse<Map<String, Object>> updateSession(@PathVariable UUID sessionId, @RequestBody Map<String, Object> body) {
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
    return ApiResponse.ok(db.sessionById(sessionId));
  }

  @PostMapping("/admin/sessions/{sessionId}/complete")
  public ApiResponse<Map<String, Object>> adminCompleteSession(@PathVariable UUID sessionId, @Valid @RequestBody(required = false) CompleteSessionRequest requestBody) {
    Map<String, Object> body = requestBody == null ? Map.of() : requestBody.toMap();
    completeSessionInternal(sessionId, firstString(body, "note", "tutorNote"));
    return ApiResponse.ok(db.sessionById(sessionId));
  }

  @PostMapping("/admin/sessions/{sessionId}/cancel")
  public ApiResponse<Map<String, Object>> adminCancelSession(@PathVariable UUID sessionId) {
    Map<String, Object> session = db.sessionById(sessionId);
    statusPolicy.requireSession(session.get("status").toString(), "cancelled");
    jdbc.update("update class_sessions set status = 'cancelled', updated_at = now() where id = ?", sessionId);
    db.auditCurrent("admin.cancel_session", "session", sessionId, "Admin hủy buổi học.");
    return ApiResponse.ok(db.sessionById(sessionId));
  }

  @PostMapping("/admin/sessions/{sessionId}/mark-student-absent")
  public ApiResponse<Map<String, Object>> markStudentAbsent(@PathVariable UUID sessionId) {
    Map<String, Object> session = db.sessionById(sessionId);
    statusPolicy.requireSession(session.get("status").toString(), "student_absent");
    jdbc.update("update class_sessions set status = 'student_absent', updated_at = now() where id = ?", sessionId);
    return ApiResponse.ok(db.sessionById(sessionId));
  }

  @PostMapping("/admin/sessions/{sessionId}/mark-tutor-absent")
  public ApiResponse<Map<String, Object>> markTutorAbsent(@PathVariable UUID sessionId) {
    Map<String, Object> session = db.sessionById(sessionId);
    statusPolicy.requireSession(session.get("status").toString(), "tutor_absent");
    jdbc.update("update class_sessions set status = 'tutor_absent', updated_at = now() where id = ?", sessionId);
    return ApiResponse.ok(db.sessionById(sessionId));
  }

  @GetMapping("/reviews")
  public ApiResponse<List<Map<String, Object>>> reviews() {
    UUID userId = db.currentUserIdOrThrow();
    if (db.isAdmin()) return ApiResponse.ok(db.reviews(""));
    return ApiResponse.ok(db.reviews(" where r.reviewer_id = ?", userId));
  }

  @PostMapping("/reviews")
  @Transactional
  public ApiResponse<Map<String, Object>> createReview(@Valid @RequestBody CreateReviewRequest requestBody) {
    Map<String, Object> body = requestBody.toMap();
    UUID reviewerId = db.currentUserIdOrThrow();
    UUID sessionId = uuid(firstPresent(body, "sessionId"));
    Map<String, Object> session = db.sessionById(sessionId);
    if (!"completed".equals(session.get("status"))) throw new BusinessException("SESSION_NOT_COMPLETED", "Chỉ được đánh giá buổi học đã hoàn thành.");
    if (!reviewerId.equals(uuid(session.get("studentId")))) throw new ForbiddenException("Bạn chỉ được đánh giá buổi học của mình.");
    if (exists("select 1 from reviews where session_id = ?", sessionId)) throw new BusinessException("REVIEW_EXISTS", "Buổi học này đã được đánh giá.");
    int rating = integer(body, "rating");
    if (rating < 1 || rating > 5) throw new BusinessException("INVALID_RATING", "Rating phải từ 1 đến 5.");
    UUID id = jdbc.queryForObject("""
        insert into reviews(session_id, class_id, tutor_id, reviewer_id, rating, comment, status)
        values (?, ?, ?, ?, ?, ?, 'visible') returning id
        """, UUID.class, sessionId, uuid(session.get("classId")), uuid(session.get("tutorId")), reviewerId, rating,
        firstString(body, "content", "comment"));
    db.refreshTutorRating(uuid(session.get("tutorId")));
    if (rating < 3) db.notifyAdmins("warning", "Đánh giá thấp", "Có đánh giá dưới 3 sao cần xem xét.", "/admin/reviews", "review", id);
    db.auditCurrent("student.create_review", "review", id, "Học viên tạo đánh giá sau buổi học.");
    return ApiResponse.ok(db.reviews(" where r.id = ?", id).getFirst());
  }

  @GetMapping("/tutors/{tutorId}/reviews")
  public ApiResponse<List<Map<String, Object>>> tutorReviews(@PathVariable UUID tutorId) {
    return ApiResponse.ok(db.reviews(" where r.tutor_id = ? and r.status = 'visible'", tutorId));
  }

  @GetMapping("/admin/reviews")
  public ApiResponse<List<Map<String, Object>>> adminReviews() {
    return ApiResponse.ok(db.reviews(""));
  }

  @PostMapping("/admin/reviews/{reviewId}/hide")
  public ApiResponse<Map<String, Object>> hideReview(@PathVariable UUID reviewId) {
    return updateReviewStatus(reviewId, "hidden");
  }

  @PostMapping("/admin/reviews/{reviewId}/show")
  public ApiResponse<Map<String, Object>> showReview(@PathVariable UUID reviewId) {
    return updateReviewStatus(reviewId, "visible");
  }

  @PostMapping("/admin/reviews/{reviewId}/flag")
  public ApiResponse<Map<String, Object>> flagReview(@PathVariable UUID reviewId) {
    return updateReviewStatus(reviewId, "flagged");
  }

  private ApiResponse<Map<String, Object>> updateReviewStatus(UUID reviewId, String status) {
    if (!List.of("visible", "hidden", "flagged").contains(status)) {
      throw new BusinessException("INVALID_STATUS", "Trạng thái đánh giá không hợp lệ.");
    }
    UUID tutorId = jdbc.queryForObject("select tutor_id from reviews where id = ?", UUID.class, reviewId);
    jdbc.update("update reviews set status = ?, updated_at = now() where id = ?", status, reviewId);
    db.refreshTutorRating(tutorId);
    db.auditCurrent("admin.update_review_status", "review", reviewId, "Admin cập nhật trạng thái đánh giá thành " + status + ".");
    return ApiResponse.ok(db.reviews(" where r.id = ?", reviewId).getFirst());
  }

  @GetMapping("/conversations")
  public ApiResponse<List<Map<String, Object>>> conversations() {
    UUID userId = db.currentUserIdOrThrow();
    return ApiResponse.ok(jdbc.query("""
        select c.* from conversations c
        join conversation_members cm on cm.conversation_id = c.id
        where cm.user_id = ?
        order by c.updated_at desc
        """, db.conversationMapper(userId), userId));
  }

  @PostMapping("/conversations")
  @Transactional
  public ApiResponse<Map<String, Object>> createConversation(@Valid @RequestBody CreateConversationRequest body) {
    UUID userId = db.currentUserIdOrThrow();
    UUID bookingId = body.bookingId();
    UUID classId = body.classId();
    String type = valueOr(body.type(), bookingId != null ? "booking" : classId != null ? "class" : "support");
    if (!List.of("booking", "class", "support", "direct").contains(type)) {
      throw new BusinessException("INVALID_CONVERSATION_TYPE", "Loại hội thoại không hợp lệ.");
    }
    if (!db.isAdmin() && body.participantIds() != null && !body.participantIds().isEmpty() && bookingId == null && classId == null) {
      throw new ForbiddenException("Bạn không được tự thêm người tham gia hội thoại.");
    }
    if (bookingId != null) {
      requireBookingConversationAccess(bookingId, userId);
    } else if (classId != null) {
      requireClassConversationAccess(classId, userId);
    }
    UUID existingId = existingConversationId(type, bookingId, classId, userId);
    if (existingId != null) {
      return ApiResponse.ok(jdbc.queryForObject("select * from conversations where id = ?", db.conversationMapper(userId), existingId));
    }

    UUID id = jdbc.queryForObject("""
        insert into conversations(title, type, booking_id, class_id)
        values (?, ?, ?, ?)
        returning id
        """, UUID.class, body.title(), type, bookingId, classId);
    jdbc.update("insert into conversation_members(conversation_id, user_id) values (?, ?) on conflict do nothing", id, userId);

    if (bookingId != null) {
      addBookingConversationMembers(id, bookingId, userId);
    } else if (classId != null) {
      addClassConversationMembers(id, classId, userId);
    } else if ("support".equals(type)) {
      addSupportConversationMembers(id);
    } else if (db.isAdmin()) {
      for (UUID member : body.participantIds() == null ? List.<UUID>of() : body.participantIds()) {
        jdbc.update("insert into conversation_members(conversation_id, user_id) values (?, ?) on conflict do nothing", id, member);
      }
    } else {
      throw new ForbiddenException("Hội thoại cần gắn với booking, lớp học hoặc kênh hỗ trợ.");
    }
    if (body.initialMessage() != null && !body.initialMessage().isBlank()) {
      jdbc.update("insert into messages(conversation_id, sender_id, content, message_type) values (?, ?, ?, 'text')", id, userId, body.initialMessage().trim());
      jdbc.update("update conversations set updated_at = now() where id = ?", id);
    }
    db.auditCurrent("conversation.create", "conversation", id, "Tạo hội thoại theo ngữ cảnh nghiệp vụ.");
    return ApiResponse.ok(jdbc.queryForObject("select * from conversations where id = ?", db.conversationMapper(userId), id));
  }

  @GetMapping("/conversations/{conversationId}")
  public ApiResponse<Map<String, Object>> conversation(@PathVariable UUID conversationId) {
    UUID userId = db.currentUserIdOrThrow();
    requireConversationMember(conversationId, userId);
    return ApiResponse.ok(jdbc.queryForObject("select * from conversations where id = ?", db.conversationMapper(userId), conversationId));
  }

  @GetMapping("/conversations/{conversationId}/messages")
  public ApiResponse<List<Map<String, Object>>> messages(@PathVariable UUID conversationId) {
    UUID userId = db.currentUserIdOrThrow();
    requireConversationMember(conversationId, userId);
    return ApiResponse.ok(jdbc.query("select * from messages where conversation_id = ? order by created_at", db.messageMapper(userId), conversationId));
  }

  @PostMapping("/conversations/{conversationId}/messages")
  public ApiResponse<Map<String, Object>> sendMessage(@PathVariable UUID conversationId, @Valid @RequestBody MessageRequest request) {
    UUID userId = db.currentUserIdOrThrow();
    requireConversationMember(conversationId, userId);
    UUID id = jdbc.queryForObject("insert into messages(conversation_id, sender_id, content, message_type) values (?, ?, ?, 'text') returning id",
        UUID.class, conversationId, userId, request.content());
    jdbc.update("update conversations set updated_at = now() where id = ?", conversationId);
    return ApiResponse.ok(jdbc.queryForObject("select * from messages where id = ?", db.messageMapper(userId), id));
  }

  @PostMapping("/conversations/{conversationId}/mark-read")
  public ApiResponse<Map<String, Object>> markConversationRead(@PathVariable UUID conversationId) {
    UUID userId = db.currentUserIdOrThrow();
    requireConversationMember(conversationId, userId);
    jdbc.update("update conversation_members set last_read_at = now() where conversation_id = ? and user_id = ?", conversationId, userId);
    return ApiResponse.ok(Map.of("success", true));
  }

  @GetMapping("/admin/conversations")
  public ApiResponse<List<Map<String, Object>>> adminConversations() {
    return ApiResponse.ok(jdbc.query("select * from conversations order by updated_at desc", db.conversationMapper(db.currentUserIdOrThrow())));
  }

  @GetMapping("/admin/conversations/{conversationId}")
  public ApiResponse<Map<String, Object>> adminConversation(@PathVariable UUID conversationId) {
    return ApiResponse.ok(jdbc.queryForObject("select * from conversations where id = ?", db.conversationMapper(db.currentUserIdOrThrow()), conversationId));
  }

  @GetMapping("/notifications")
  public ApiResponse<List<Map<String, Object>>> notifications() {
    UUID userId = db.currentUserIdOrThrow();
    return ApiResponse.ok(jdbc.query("select * from notifications where user_id = ? order by created_at desc", db.notificationMapper(), userId));
  }

  @GetMapping("/notifications/unread-count")
  public ApiResponse<Map<String, Object>> unreadCount() {
    UUID userId = db.currentUserIdOrThrow();
    Integer count = jdbc.queryForObject("select count(*) from notifications where user_id = ? and status = 'unread'", Integer.class, userId);
    return ApiResponse.ok(Map.of("count", count));
  }

  @PostMapping("/notifications/{notificationId}/read")
  public ApiResponse<Map<String, Object>> markNotificationRead(@PathVariable UUID notificationId) {
    UUID userId = db.currentUserIdOrThrow();
    jdbc.update("update notifications set status = 'read', read_at = now() where id = ? and user_id = ?", notificationId, userId);
    return ApiResponse.ok(Map.of("success", true));
  }

  @PostMapping("/notifications/read-all")
  public ApiResponse<Map<String, Object>> readAllNotifications() {
    jdbc.update("update notifications set status = 'read', read_at = now() where user_id = ?", db.currentUserIdOrThrow());
    return ApiResponse.ok(Map.of("success", true));
  }

  @GetMapping("/admin/notifications")
  public ApiResponse<List<Map<String, Object>>> adminNotifications() {
    return ApiResponse.ok(jdbc.query("select * from notifications order by created_at desc limit 500", db.notificationMapper()));
  }

  @PostMapping("/admin/notifications/send")
  public ApiResponse<Map<String, Object>> adminSendNotification(@RequestBody Map<String, Object> body) {
    UUID userId = uuid(firstPresent(body, "userId"));
    db.notify(userId, valueOr(firstString(body, "type"), "info"), firstString(body, "title"), firstString(body, "message", "content"), firstString(body, "actionUrl", "link"), null, null);
    return ApiResponse.ok(Map.of("sent", true));
  }

  @GetMapping("/payments")
  public ApiResponse<List<Map<String, Object>>> payments() {
    UUID userId = db.currentUserIdOrThrow();
    return ApiResponse.ok(jdbc.query("select * from payments where user_id = ? order by created_at desc", db.paymentMapper(), userId));
  }

  @GetMapping("/payments/{paymentId}")
  public ApiResponse<Map<String, Object>> payment(@PathVariable UUID paymentId) {
    Map<String, Object> payment = jdbc.queryForObject("select * from payments where id = ?", db.paymentMapper(), paymentId);
    if (!db.isAdmin() && !db.currentUserIdOrThrow().equals(uuid(payment.get("userId")))) throw new ForbiddenException("Bạn không có quyền xem thanh toán này.");
    return ApiResponse.ok(payment);
  }

  @PostMapping("/payments/{paymentId}/mock-pay")
  public ApiResponse<Map<String, Object>> mockPay(@PathVariable UUID paymentId) {
    return ApiResponse.ok(paymentService.mockPay(paymentId), "Đã ghi nhận thanh toán demo.");
  }

  @GetMapping("/tutor/earnings")
  public ApiResponse<List<Map<String, Object>>> tutorEarnings() {
    UUID tutorId = db.tutorIdByUserOrThrow(db.currentUserIdOrThrow());
    return ApiResponse.ok(jdbc.query("select * from tutor_earnings where tutor_id = ? order by created_at desc", db.earningMapper(), tutorId));
  }

  @GetMapping("/tutor/payouts")
  public ApiResponse<List<Map<String, Object>>> tutorPayouts() {
    UUID tutorId = db.tutorIdByUserOrThrow(db.currentUserIdOrThrow());
    return ApiResponse.ok(jdbc.query("""
        select p.*, u.full_name tutor_name from payouts p
        join tutor_profiles tp on tp.id = p.tutor_id join users u on u.id = tp.user_id
        where p.tutor_id = ? order by p.created_at desc
        """, db.payoutMapper(), tutorId));
  }

  @PostMapping("/tutor/payouts")
  @Transactional
  public ApiResponse<Map<String, Object>> createPayout(@Valid @RequestBody CreatePayoutRequest body) {
    UUID tutorId = db.tutorIdByUserOrThrow(db.currentUserIdOrThrow());
    int amount = body.amount();
    List<Map<String, Object>> availableEarnings = availableEarningsForUpdate(tutorId);
    int balance = availableEarnings.stream().mapToInt(row -> ((Number) row.get("netAmount")).intValue()).sum();
    if (amount <= 0 || amount > balance) throw new BusinessException("INVALID_PAYOUT_AMOUNT", "Số tiền rút vượt quá số dư khả dụng.");
    UUID id = jdbc.queryForObject("""
        insert into payouts(tutor_id, amount, bank_name, bank_account, account_holder, status)
        values (?, ?, ?, ?, ?, 'pending') returning id
        """, UUID.class, tutorId, amount, body.bankName(), body.bankAccount(), body.accountHolder());
    allocatePayoutEarnings(id, availableEarnings, amount);
    db.auditCurrent("tutor.create_payout", "payout", id, "Gia sư đã yêu cầu rút " + amount + " VND.");
    return ApiResponse.ok(payoutById(id));
  }

  @GetMapping("/admin/payments")
  public ApiResponse<List<Map<String, Object>>> adminPayments() {
    return ApiResponse.ok(jdbc.query("select * from payments order by created_at desc", db.paymentMapper()));
  }

  @GetMapping("/admin/payments/{paymentId}")
  public ApiResponse<Map<String, Object>> adminPayment(@PathVariable UUID paymentId) {
    return ApiResponse.ok(jdbc.queryForObject("select * from payments where id = ?", db.paymentMapper(), paymentId));
  }

  @PostMapping("/admin/payments/{paymentId}/mark-paid")
  public ApiResponse<Map<String, Object>> markPaid(@PathVariable UUID paymentId, @Valid @RequestBody AdminMarkPaymentRequest body) {
    return ApiResponse.ok(paymentService.adminMarkPaid(paymentId, body.reason()), "Đã ghi nhận thanh toán thành công.");
  }

  @PostMapping("/admin/payments/{paymentId}/mark-failed")
  public ApiResponse<Map<String, Object>> markFailed(@PathVariable UUID paymentId) {
    return ApiResponse.ok(paymentService.adminMarkFailed(paymentId), "Đã ghi nhận thanh toán thất bại.");
  }

  @PostMapping("/admin/payments/{paymentId}/refund")
  public ApiResponse<Map<String, Object>> refund(@PathVariable UUID paymentId, @Valid @RequestBody RefundRequest body) {
    return ApiResponse.ok(paymentService.refund(paymentId, body.toMap()), "Đã xử lý hoàn tiền.");
  }

  @GetMapping("/admin/payouts")
  public ApiResponse<List<Map<String, Object>>> adminPayouts() {
    return ApiResponse.ok(jdbc.query("""
        select p.*, u.full_name tutor_name from payouts p
        join tutor_profiles tp on tp.id = p.tutor_id join users u on u.id = tp.user_id
        order by p.created_at desc
        """, db.payoutMapper()));
  }

  @GetMapping("/admin/payouts/{payoutId}")
  public ApiResponse<Map<String, Object>> adminPayout(@PathVariable UUID payoutId) {
    return ApiResponse.ok(payoutById(payoutId));
  }

  @PostMapping("/admin/payouts/{payoutId}/approve")
  @Transactional
  public ApiResponse<Map<String, Object>> approvePayout(@PathVariable UUID payoutId) {
    Map<String, Object> payout = payoutById(payoutId);
    statusPolicy.requirePayout(payout.get("status").toString(), "completed");
    jdbc.update("update payouts set status = 'completed', processed_by = ?, processed_at = now(), updated_at = now() where id = ?", db.currentUserIdOrThrow(), payoutId);
    UUID tutorId = jdbc.queryForObject("select tutor_id from payouts where id = ?", UUID.class, payoutId);
    jdbc.update("""
        update tutor_earnings te
        set status = 'paid', updated_at = now()
        where te.status = 'payout_pending'
          and exists (
            select 1 from payout_earning_items pei
            where pei.payout_id = ? and pei.earning_id = te.id
          )
        """, payoutId);
    UUID tutorUser = jdbc.queryForObject("select user_id from tutor_profiles where id = ?", UUID.class, tutorId);
    db.notify(tutorUser, "success", "Rút tiền đã được duyệt", "Yêu cầu rút tiền của bạn đã được duyệt.", "/dashboard/tutor/earnings", "payout", payoutId);
    db.auditCurrent("admin.approve_payout", "payout", payoutId, "Admin duyệt yêu cầu rút tiền.");
    return ApiResponse.ok(payoutById(payoutId));
  }

  @PostMapping("/admin/payouts/{payoutId}/reject")
  @Transactional
  public ApiResponse<Map<String, Object>> rejectPayout(@PathVariable UUID payoutId, @Valid @RequestBody RejectPayoutRequest body) {
    Map<String, Object> payout = payoutById(payoutId);
    statusPolicy.requirePayout(payout.get("status").toString(), "rejected");
    String reason = body.reason();
    jdbc.update("update payouts set status = 'rejected', admin_note = ?, processed_by = ?, processed_at = now(), updated_at = now() where id = ?",
        reason, db.currentUserIdOrThrow(), payoutId);
    UUID tutorId = jdbc.queryForObject("select tutor_id from payouts where id = ?", UUID.class, payoutId);
    jdbc.update("""
        update tutor_earnings te
        set status = 'available', updated_at = now()
        where te.status = 'payout_pending'
          and exists (
            select 1 from payout_earning_items pei
            where pei.payout_id = ? and pei.earning_id = te.id
          )
        """, payoutId);
    UUID tutorUser = jdbc.queryForObject("select user_id from tutor_profiles where id = ?", UUID.class, tutorId);
    db.notify(tutorUser, "warning", "Rút tiền bị từ chối", "Yêu cầu rút tiền của bạn bị từ chối.", "/dashboard/tutor/earnings", "payout", payoutId);
    db.auditCurrent("admin.reject_payout", "payout", payoutId, "Admin đã từ chối payout với lý do " + reason + ".");
    return ApiResponse.ok(payoutById(payoutId));
  }

  @GetMapping("/admin/reports/overview")
  public ApiResponse<Map<String, Object>> reportOverview() {
    return ApiResponse.ok(Map.of(
        "totalUsers", count("users"),
        "totalTutors", count("tutor_profiles"),
        "pendingTutors", countWhere("tutor_profiles", "status = 'pending'"),
        "totalStudents", jdbc.queryForObject("select count(*) from users where role in ('student','parent')", Integer.class),
        "newRequests", countWhere("learning_requests", "status = 'new'"),
        "activeClasses", countWhere("tutoring_classes", "status = 'active'"),
        "pendingBookings", countWhere("trial_bookings", "status in ('pending','assigned','accepted')"),
        "totalRevenue", jdbc.queryForObject("select coalesce(sum(amount),0) from payments where status in ('paid','completed')", Long.class)
    ));
  }

  @GetMapping("/admin/reports/request-trends")
  public ApiResponse<List<Map<String, Object>>> requestTrends() {
    return ApiResponse.ok(jdbc.query("""
        select to_char(date_trunc('month', created_at), 'YYYY-MM') month, count(*) count
        from learning_requests group by 1 order by 1
        """, (rs, row) -> Map.of("month", rs.getString("month"), "count", rs.getInt("count"))));
  }

  @GetMapping("/admin/reports/conversion-funnel")
  public ApiResponse<List<Map<String, Object>>> conversionFunnel() {
    return ApiResponse.ok(jdbc.query("""
        select status stage, count(*) count from learning_requests group by status order by status
        """, (rs, row) -> Map.of("stage", rs.getString("stage"), "count", rs.getInt("count"))));
  }

  @GetMapping("/admin/reports/tutor-status-distribution")
  public ApiResponse<List<Map<String, Object>>> tutorStatusDistribution() {
    return ApiResponse.ok(distribution("tutor_profiles", "status", "status"));
  }

  @GetMapping("/admin/reports/subject-distribution")
  public ApiResponse<List<Map<String, Object>>> subjectDistribution() {
    return ApiResponse.ok(jdbc.query("""
        select s.name subject, count(lr.id) count from subjects s
        left join learning_requests lr on lr.subject_id = s.id
        group by s.name order by count desc
        """, (rs, row) -> Map.of("subject", rs.getString("subject"), "count", rs.getInt("count"))));
  }

  @GetMapping("/admin/reports/revenue")
  public ApiResponse<List<Map<String, Object>>> revenueReport() {
    return ApiResponse.ok(jdbc.query("""
        select to_char(date_trunc('month', created_at), 'YYYY-MM') month, coalesce(sum(amount),0) revenue
        from payments where status in ('paid','completed') group by 1 order by 1
        """, (rs, row) -> Map.of("month", rs.getString("month"), "revenue", rs.getLong("revenue"))));
  }

  @GetMapping("/admin/reports/payment-status-distribution")
  public ApiResponse<List<Map<String, Object>>> paymentStatusDistribution() {
    return ApiResponse.ok(distribution("payments", "status", "status"));
  }

  @GetMapping("/admin/reports/low-rating-alerts")
  public ApiResponse<List<Map<String, Object>>> lowRatingAlerts() {
    return ApiResponse.ok(db.reviews(" where r.rating < 3"));
  }

  @GetMapping("/admin/settings")
  public ApiResponse<Map<String, Object>> settings() {
    Map<String, Object> result = new LinkedHashMap<>();
    jdbc.query("select key, value from system_settings order by key", rs -> {
      result.put(rs.getString("key"), parseJson(rs.getString("value")));
    });
    return ApiResponse.ok(result);
  }

  @PatchMapping("/admin/settings")
  public ApiResponse<Map<String, Object>> updateSettings(@RequestBody Map<String, Object> body) {
    UUID actor = db.currentUserIdOrThrow();
    body.forEach((key, value) -> jdbc.update("""
        insert into system_settings(key, value, updated_by)
        values (?, ?::jsonb, ?)
        on conflict(key) do update set value = excluded.value, updated_by = excluded.updated_by, updated_at = now()
        """, key, jsonValue(value), actor));
    db.auditCurrent("admin.update_settings", "settings", null, "Admin cập nhật cấu hình hệ thống.");
    return settings();
  }

  @PostMapping("/contact-requests")
  public ApiResponse<Map<String, Object>> createContact(@RequestBody Map<String, Object> body) {
    UUID id = jdbc.queryForObject("""
        insert into contact_requests(full_name, email, phone, message, status)
        values (?, ?, ?, ?, 'new') returning id
        """, UUID.class, firstString(body, "fullName"), firstString(body, "email"), firstString(body, "phone"), firstString(body, "message"));
    db.notifyAdmins("info", "Liên hệ mới", "Có yêu cầu liên hệ mới từ khách.", "/admin/contact-requests", "contactRequest", id);
    return ApiResponse.ok(contactById(id));
  }

  @GetMapping("/admin/contact-requests")
  public ApiResponse<List<Map<String, Object>>> adminContacts() {
    return ApiResponse.ok(jdbc.query("select * from contact_requests order by created_at desc", contactMapper()));
  }

  @PatchMapping("/admin/contact-requests/{contactId}/status")
  public ApiResponse<Map<String, Object>> updateContactStatus(@PathVariable UUID contactId, @RequestBody Map<String, Object> body) {
    jdbc.update("update contact_requests set status = ?, updated_at = now() where id = ?", firstString(body, "status"), contactId);
    return ApiResponse.ok(contactById(contactId));
  }

  @GetMapping("/admin/audit-logs")
  public ApiResponse<List<Map<String, Object>>> auditLogs() {
    return ApiResponse.ok(jdbc.query("""
        select al.*, u.full_name actor_name from audit_logs al
        left join users u on u.id = al.actor_id
        order by al.created_at desc limit 500
        """, db.auditMapper()));
  }

  @PostMapping(value = "/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<UploadedFileResponse> upload(
      @RequestParam("file") MultipartFile file,
      @RequestParam(defaultValue = "private") String visibility
  ) throws IOException {
    if (file.isEmpty()) throw new BusinessException("EMPTY_FILE", "File rỗng.");
    if (file.getSize() > 10 * 1024 * 1024) throw new BusinessException("FILE_TOO_LARGE", "File tối đa 10MB.");
    String mime = valueOr(file.getContentType(), "application/octet-stream");
    if (!List.of("image/jpeg", "image/png", "image/webp", "application/pdf").contains(mime)) {
      throw new BusinessException("INVALID_FILE_TYPE", "Chỉ hỗ trợ JPG, PNG, WEBP hoặc PDF.");
    }
    String normalizedVisibility = "public".equalsIgnoreCase(visibility) ? "public" : "private";
    UUID owner = db.currentUserIdOrThrow();
    String originalName = file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename().replaceAll("[^a-zA-Z0-9._-]", "_");
    String storedName = UUID.randomUUID() + extensionFor(originalName, mime);
    Path root = Path.of(properties.upload().dir()).toAbsolutePath().normalize();
    Path dir = root.resolve(normalizedVisibility).normalize();
    if (!dir.startsWith(root)) throw new BusinessException("INVALID_UPLOAD_PATH", "Đường dẫn upload không hợp lệ.");
    Files.createDirectories(dir);
    Files.copy(file.getInputStream(), dir.resolve(storedName), StandardCopyOption.REPLACE_EXISTING);
    String storagePath = normalizedVisibility + "/" + storedName;
    UUID id = jdbc.queryForObject("""
        insert into uploaded_files(owner_id, file_name, original_file_name, file_url, file_size, mime_type, storage_path, visibility)
        values (?, ?, ?, ?, ?, ?, ?, ?) returning id
        """, UUID.class, owner, storedName, originalName, "/api/v1/files/pending", file.getSize(), mime, storagePath, normalizedVisibility);
    String url = "/api/v1/files/" + id;
    jdbc.update("update uploaded_files set file_url = ?, updated_at = now() where id = ?", url, id);
    if ("private".equals(normalizedVisibility)) {
      db.auditCurrent("file.upload_private", "uploadedFile", id, "Người dùng tải lên file riêng tư.");
    }
    return ApiResponse.ok(new UploadedFileResponse(
        id.toString(),
        id.toString(),
        storedName,
        originalName,
        url,
        file.getSize(),
        mime,
        normalizedVisibility
    ));
  }

  private ApiResponse<Map<String, Object>> updateTutorStatus(UUID tutorId, String status, String reason) {
    String currentStatus = jdbc.queryForObject("select status from tutor_profiles where id = ?", String.class, tutorId);
    statusPolicy.requireTutor(currentStatus, status);
    jdbc.update("update tutor_profiles set status = ?, status_reason = ?, updated_at = now() where id = ?", status, reason, tutorId);
    UUID userId = jdbc.queryForObject("select user_id from tutor_profiles where id = ?", UUID.class, tutorId);
    if ("suspended".equals(status) || "inactive".equals(status)) {
      refreshTokenService.revokeAllForUser(userId, "Hồ sơ gia sư bị chuyển sang trạng thái " + status + ", thu hồi refresh token.");
    }
    String title = switch (status) {
      case "rejected" -> "Hồ sơ gia sư bị từ chối";
      case "need_update" -> "Hồ sơ gia sư cần bổ sung";
      case "suspended" -> "Hồ sơ gia sư bị tạm khóa";
      default -> "Hồ sơ gia sư đã được kích hoạt";
    };
    db.notify(userId, "approved".equals(status) ? "success" : "warning", title, valueOr(reason, title), "/dashboard/tutor/profile", "tutor", tutorId);
    db.auditCurrent("admin." + status + "_tutor", "tutor", tutorId, "Admin cập nhật trạng thái gia sư thành " + status + ".");
    return ApiResponse.ok(db.tutorById(tutorId, true));
  }

  private ApiResponse<Map<String, Object>> reviewDocument(UUID documentId, String status, String note) {
    jdbc.update("update tutor_documents set status = ?, review_note = ?, reviewed_by = ?, reviewed_at = now(), updated_at = now() where id = ?",
        status, note, db.currentUserIdOrThrow(), documentId);
    db.auditCurrent("admin.review_tutor_document", "tutorDocument", documentId, "Admin xét duyệt giấy tờ gia sư.");
    return ApiResponse.ok(jdbc.queryForObject("select * from tutor_documents where id = ?", documentMapper(), documentId));
  }

  private UUID uploadedFileIdFromBody(Map<String, Object> body) {
    Object direct = firstPresent(body, "fileId", "uploadedFileId", "id");
    if (direct != null) {
      try {
        return UUID.fromString(direct.toString());
      } catch (IllegalArgumentException ignored) {
      }
    }
    String fileUrl = firstString(body, "fileUrl", "url");
    if (fileUrl == null) return null;
    int index = fileUrl.lastIndexOf('/');
    if (index < 0 || index == fileUrl.length() - 1) return null;
    try {
      return UUID.fromString(fileUrl.substring(index + 1));
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private String extensionFor(String originalName, String mimeType) {
    String lower = originalName == null ? "" : originalName.toLowerCase();
    String ext = "";
    int dot = lower.lastIndexOf('.');
    if (dot >= 0 && dot < lower.length() - 1) ext = lower.substring(dot);
    if (List.of(".jpg", ".jpeg", ".png", ".webp", ".pdf").contains(ext)) return ext;
    return switch (mimeType) {
      case "image/jpeg" -> ".jpg";
      case "image/png" -> ".png";
      case "image/webp" -> ".webp";
      case "application/pdf" -> ".pdf";
      default -> ".bin";
    };
  }

  private void replaceTutorSubjectsAndLocations(UUID tutorId, Map<String, Object> body) {
    if (body.containsKey("subjects")) {
      jdbc.update("delete from tutor_subjects where tutor_id = ?", tutorId);
      List<Object> subjects = list(body.get("subjects"));
      List<Object> grades = list(body.get("grades"));
      UUID gradeId = grades.isEmpty() ? null : db.gradeLevelId(grades.getFirst());
      for (Object subject : subjects) {
        jdbc.update("insert into tutor_subjects(tutor_id, subject_id, grade_level_id) values (?, ?, ?) on conflict do nothing",
            tutorId, db.requiredSubjectId(subject), gradeId);
      }
    }
    if (body.containsKey("locations") || body.containsKey("teachingModes")) {
      jdbc.update("delete from tutor_locations where tutor_id = ?", tutorId);
      List<Object> locations = list(body.getOrDefault("locations", List.of("Online")));
      String mode = valueOr(firstString(body, "teachingModes", "teachingMode"), "both");
      for (Object location : locations) {
        jdbc.update("insert into tutor_locations(tutor_id, province, teaching_mode) values (?, ?, ?)",
            tutorId, location.toString(), mode);
      }
    }
  }

  private Map<String, Object> bookingForAssignedRequest(UUID requestId, Map<String, Object> request, UUID tutorId, Map<String, Object> body) {
    Optional<Map<String, Object>> existing = db.optional("""
        select tb.*, s.name subject_name, gl.name grade_name
        from trial_bookings tb
        join subjects s on s.id = tb.subject_id
        left join grade_levels gl on gl.id = tb.grade_level_id
        where tb.learning_request_id = ?
          and tb.status not in ('converted','cancelled','expired')
        order by tb.created_at desc
        limit 1
        """, db.bookingMapper(), requestId);
    if (existing.isPresent()) {
      Map<String, Object> booking = existing.get();
      if (!tutorId.equals(uuid(booking.get("tutorId")))) {
        throw new BusinessException("ACTIVE_BOOKING_EXISTS", "Yêu cầu này đã có booking chưa kết thúc với gia sư khác.");
      }
      return booking;
    }
    return createAssignedBooking(requestId, request, tutorId, body);
  }

  private Map<String, Object> createAssignedBooking(UUID requestId, Map<String, Object> request, UUID tutorId, Map<String, Object> body) {
    UUID studentId = uuid(request.get("userId"));
    String learningMode = normalizeOnlineOffline(valueOr(firstString(body, "learningMode", "mode"), valueOr(string(request, "learningMode"), "online")));
    if ("both".equals(learningMode)) learningMode = "online";
    UUID bookingId = jdbc.queryForObject("""
        insert into trial_bookings(learning_request_id, student_id, tutor_id, subject_id, grade_level_id,
          student_name, parent_name, phone, email, preferred_time, learning_mode, goal, status)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'assigned')
        returning id
        """, UUID.class, requestId, studentId, tutorId, uuid(request.get("subjectId")), uuidOrNull(request.get("gradeLevelId")),
        string(request, "studentName"), string(request, "parentName"), string(request, "phone"), string(request, "email"),
        string(request, "preferredSchedule"), learningMode, valueOr(string(request, "learningGoal"), string(request, "goal")));
    db.auditCurrent("admin.create_assigned_booking", "booking", bookingId, "Admin tạo booking học thử khi gán gia sư cho yêu cầu học.");
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
        """, startAt, endAt, normalizeOnlineOffline(valueOr(firstString(body, "mode", "learningMode"), "online")),
        firstString(body, "location"), firstString(body, "meetingUrl"), bookingId);
    UUID requestId = jdbc.queryForObject("select learning_request_id from trial_bookings where id = ?", UUID.class, bookingId);
    if (requestId != null) jdbc.update("update learning_requests set status = 'trial_scheduled', updated_at = now() where id = ?", requestId);
    notifyBookingParties(bookingId, "info", "Lịch học thử đã được xếp", "Booking học thử đã có lịch cụ thể.");
  }

  @Transactional
  private void completeSessionInternal(UUID sessionId, String tutorNote) {
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
      jdbc.update("""
          insert into tutor_earnings(tutor_id, session_id, payment_id, gross_amount, platform_fee, net_amount, status)
          values (?, ?, ?, ?, ?, ?, 'pending')
          """, tutorId, sessionId, paymentId, rate, fee, Math.max(0, rate - fee));
    }
    db.notify(uuid(session.get("studentId")), "success", "Buổi học đã hoàn thành", "Gia sư đã đánh dấu hoàn thành buổi học.", "/dashboard/classes", "session", sessionId);
    db.notifyAdmins("info", "Buổi học hoàn thành", "Một buổi học đã được hoàn thành.", "/admin/classes", "session", sessionId);
    db.auditCurrent("session.complete", "session", sessionId, "Đánh dấu buổi học đã hoàn thành.");
  }

  private ApiResponse<Map<String, Object>> markPayment(UUID paymentId, String status) {
    Map<String, Object> before = jdbc.queryForObject("select * from payments where id = ?", db.paymentMapper(), paymentId);
    if (!db.isAdmin() && !db.currentUserIdOrThrow().equals(uuid(before.get("userId")))) {
      throw new ForbiddenException("Bạn không có quyền cập nhật thanh toán này.");
    }
    jdbc.update("update payments set status = ?, paid_at = case when ? in ('paid','completed') then now() else paid_at end, updated_at = now() where id = ?",
        status, status, paymentId);
    if ("paid".equals(status) || "completed".equals(status)) {
      jdbc.update("update tutor_earnings set status = 'available', updated_at = now() where payment_id = ?", paymentId);
    } else if ("failed".equals(status) || "refunded".equals(status)) {
      jdbc.update("update tutor_earnings set status = 'cancelled', updated_at = now() where payment_id = ?", paymentId);
    }
    Map<String, Object> payment = jdbc.queryForObject("select * from payments where id = ?", db.paymentMapper(), paymentId);
    db.notify(uuid(payment.get("userId")), "failed".equals(status) ? "error" : "success", "Thanh toán đã cập nhật", "Thanh toán chuyển sang trạng thái " + status + ".", "/dashboard/payments", "payment", paymentId);
    db.auditCurrent("payment." + status, "payment", paymentId, "Cập nhật thanh toán sang trạng thái " + status + ".");
    return ApiResponse.ok(payment);
  }

  private Map<String, Object> payoutById(UUID payoutId) {
    return jdbc.queryForObject("""
        select p.*, u.full_name tutor_name from payouts p
        join tutor_profiles tp on tp.id = p.tutor_id join users u on u.id = tp.user_id
        where p.id = ?
        """, db.payoutMapper(), payoutId);
  }

  private List<Map<String, Object>> availableEarningsForUpdate(UUID tutorId) {
    return jdbc.query("""
        select id, tutor_id, session_id, payment_id, gross_amount, platform_fee, net_amount
        from tutor_earnings
        where tutor_id = ? and status = 'available'
        order by created_at, id
        for update
        """, (rs, row) -> {
      Map<String, Object> m = new LinkedHashMap<>();
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
        remaining -= netAmount;
      } else {
        UUID splitId = splitEarningForPayout(earning, remaining);
        jdbc.update("insert into payout_earning_items(payout_id, earning_id, amount) values (?, ?, ?)", payoutId, splitId, remaining);
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
    return jdbc.queryForObject("""
        insert into tutor_earnings(tutor_id, session_id, payment_id, gross_amount, platform_fee, net_amount, status)
        values (?, ?, ?, ?, ?, ?, 'payout_pending')
        returning id
        """, UUID.class, earning.get("tutorId"), earning.get("sessionId"), earning.get("paymentId"),
        allocatedGross, allocatedFee, allocatedNetAmount);
  }

  private List<Map<String, Object>> distribution(String table, String column, String label) {
    return jdbc.query("select " + column + " value, count(*) count from " + table + " group by " + column + " order by count desc",
        (rs, row) -> Map.of(label, rs.getString("value"), "count", rs.getInt("count")));
  }

  private Map<String, Object> profile(String table, UUID userId) {
    return jdbc.queryForMap("select * from " + table + " where user_id = ?", userId);
  }

  private int count(String table) {
    return jdbc.queryForObject("select count(*) from " + table, Integer.class);
  }

  private int countWhere(String table, String where) {
    return jdbc.queryForObject("select count(*) from " + table + " where " + where, Integer.class);
  }

  private Map<String, Object> contactById(UUID contactId) {
    return jdbc.queryForObject("select * from contact_requests where id = ?", contactMapper(), contactId);
  }

  private org.springframework.jdbc.core.RowMapper<Map<String, Object>> contactMapper() {
    return (rs, row) -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", rs.getObject("id").toString());
      m.put("fullName", rs.getString("full_name"));
      m.put("email", rs.getString("email"));
      m.put("phone", rs.getString("phone"));
      m.put("message", rs.getString("message"));
      m.put("status", rs.getString("status"));
      m.put("createdAt", rs.getObject("created_at", OffsetDateTime.class).toString());
      m.put("updatedAt", rs.getObject("updated_at", OffsetDateTime.class).toString());
      return m;
    };
  }

  private org.springframework.jdbc.core.RowMapper<Map<String, Object>> documentMapper() {
    return (rs, row) -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", rs.getObject("id").toString());
      m.put("tutorId", rs.getObject("tutor_id").toString());
      m.put("name", rs.getString("document_type"));
      m.put("type", rs.getString("document_type"));
      m.put("documentType", rs.getString("document_type"));
      Object fileId = rs.getObject("file_id");
      m.put("fileId", fileId == null ? null : fileId.toString());
      m.put("fileName", rs.getString("file_name"));
      m.put("fileUrl", fileId == null ? rs.getString("file_url") : "/api/v1/files/" + fileId);
      m.put("fileSize", rs.getLong("file_size"));
      m.put("mimeType", rs.getString("mime_type"));
      m.put("status", rs.getString("status"));
      m.put("note", rs.getString("review_note"));
      m.put("uploadedAt", rs.getObject("created_at", OffsetDateTime.class).toString());
      Object reviewedAt = rs.getObject("reviewed_at");
      m.put("reviewedAt", reviewedAt == null ? null : rs.getObject("reviewed_at", OffsetDateTime.class).toString());
      Object reviewedBy = rs.getObject("reviewed_by");
      m.put("reviewedBy", reviewedBy == null ? null : reviewedBy.toString());
      return m;
    };
  }

  private org.springframework.jdbc.core.RowMapper<Map<String, Object>> availabilityMapper() {
    return (rs, row) -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", rs.getObject("id").toString());
      m.put("tutorId", rs.getObject("tutor_id").toString());
      m.put("dayOfWeek", rs.getInt("day_of_week"));
      m.put("startTime", rs.getString("start_time").substring(0, 5));
      m.put("endTime", rs.getString("end_time").substring(0, 5));
      m.put("isActive", rs.getBoolean("is_active"));
      m.put("createdAt", rs.getObject("created_at", OffsetDateTime.class).toString());
      m.put("updatedAt", rs.getObject("updated_at", OffsetDateTime.class).toString());
      return m;
    };
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

  private void ensureClassAccess(Map<String, Object> c) {
    if (db.isAdmin()) return;
    UUID current = db.currentUserIdOrThrow();
    if (uuid(c.get("studentId")).equals(current)) return;
    if (db.isTutor() && uuid(c.get("tutorId")).equals(db.tutorIdByUserOrThrow(current))) return;
    throw new ForbiddenException("Bạn không có quyền xem lớp học này.");
  }

  private void requireConversationMember(UUID conversationId, UUID userId) {
    if (db.isAdmin()) return;
    if (!exists("select 1 from conversation_members where conversation_id = ? and user_id = ?", conversationId, userId)) {
      throw new ForbiddenException("Bạn không có quyền xem hội thoại này.");
    }
  }

  private UUID existingConversationId(String type, UUID bookingId, UUID classId, UUID userId) {
    if (bookingId != null) {
      return db.optional("""
          select id from conversations
          where booking_id = ? and type = 'booking'
          order by created_at desc
          limit 1
          """, (rs, row) -> rs.getObject("id", UUID.class), bookingId).orElse(null);
    }
    if (classId != null) {
      return db.optional("""
          select id from conversations
          where class_id = ? and type = 'class'
          order by created_at desc
          limit 1
          """, (rs, row) -> rs.getObject("id", UUID.class), classId).orElse(null);
    }
    if ("support".equals(type)) {
      return db.optional("""
          select c.id from conversations c
          join conversation_members cm on cm.conversation_id = c.id and cm.user_id = ?
          where c.type = 'support'
          order by c.created_at desc
          limit 1
          """, (rs, row) -> rs.getObject("id", UUID.class), userId).orElse(null);
    }
    return null;
  }

  private void addBookingConversationMembers(UUID conversationId, UUID bookingId, UUID actorId) {
    Map<String, Object> booking = db.bookingById(bookingId);
    UUID studentId = uuid(booking.get("studentId"));
    UUID tutorId = uuid(booking.get("tutorId"));
    UUID tutorUserId = jdbc.queryForObject("select user_id from tutor_profiles where id = ?", UUID.class, tutorId);
    jdbc.update("insert into conversation_members(conversation_id, user_id) values (?, ?) on conflict do nothing", conversationId, studentId);
    jdbc.update("insert into conversation_members(conversation_id, user_id) values (?, ?) on conflict do nothing", conversationId, tutorUserId);
  }

  private void addClassConversationMembers(UUID conversationId, UUID classId, UUID actorId) {
    Map<String, Object> c = db.classById(classId);
    UUID studentId = uuid(c.get("studentId"));
    UUID tutorId = uuid(c.get("tutorId"));
    UUID tutorUserId = jdbc.queryForObject("select user_id from tutor_profiles where id = ?", UUID.class, tutorId);
    jdbc.update("insert into conversation_members(conversation_id, user_id) values (?, ?) on conflict do nothing", conversationId, studentId);
    jdbc.update("insert into conversation_members(conversation_id, user_id) values (?, ?) on conflict do nothing", conversationId, tutorUserId);
  }

  private void requireBookingConversationAccess(UUID bookingId, UUID actorId) {
    Map<String, Object> booking = db.bookingById(bookingId);
    UUID studentId = uuid(booking.get("studentId"));
    UUID tutorId = uuid(booking.get("tutorId"));
    UUID tutorUserId = jdbc.queryForObject("select user_id from tutor_profiles where id = ?", UUID.class, tutorId);
    boolean allowed = db.isAdmin() || actorId.equals(studentId) || actorId.equals(tutorUserId);
    if (!allowed) throw new ForbiddenException("Bạn không có quyền tạo hội thoại cho booking này.");
  }

  private void requireClassConversationAccess(UUID classId, UUID actorId) {
    Map<String, Object> c = db.classById(classId);
    UUID studentId = uuid(c.get("studentId"));
    UUID tutorId = uuid(c.get("tutorId"));
    UUID tutorUserId = jdbc.queryForObject("select user_id from tutor_profiles where id = ?", UUID.class, tutorId);
    boolean allowed = db.isAdmin() || actorId.equals(studentId) || actorId.equals(tutorUserId);
    if (!allowed) throw new ForbiddenException("Bạn không có quyền tạo hội thoại cho lớp này.");
  }

  private void addSupportConversationMembers(UUID conversationId) {
    List<UUID> admins = jdbc.query("select id from users where role = 'admin' and status = 'active'", (rs, row) -> rs.getObject("id", UUID.class));
    for (UUID admin : admins) {
      jdbc.update("insert into conversation_members(conversation_id, user_id) values (?, ?) on conflict do nothing", conversationId, admin);
    }
  }

  private boolean exists(String sql, Object... args) {
    Integer count = jdbc.queryForObject("select count(*) from (" + sql + ") x", Integer.class, args);
    return count != null && count > 0;
  }

  private String requiredReason(Map<String, Object> body) {
    String reason = firstString(body, "reason", "note", "statusReason");
    if (reason == null || reason.isBlank()) throw new BusinessException("REASON_REQUIRED", "Cần nhập lý do.");
    return reason;
  }

  private Object firstPresent(Map<String, Object> body, String... keys) {
    if (body == null) return null;
    for (String key : keys) {
      if (body.containsKey(key) && body.get(key) != null) return body.get(key);
    }
    return null;
  }

  private String firstString(Map<String, Object> body, String... keys) {
    Object value = firstPresent(body, keys);
    return value == null || value.toString().isBlank() ? null : value.toString();
  }

  private String string(Map<String, Object> body, String key) {
    return firstString(body, key);
  }

  private Integer firstInteger(Map<String, Object> body, String... keys) {
    Object value = firstPresent(body, keys);
    if (value == null || value.toString().isBlank()) return null;
    return ((Number) (value instanceof Number ? value : Integer.parseInt(value.toString()))).intValue();
  }

  private Integer integer(Map<String, Object> body, String key) {
    return firstInteger(body, key);
  }

  private Long longValue(Map<String, Object> body, String key) {
    Object value = firstPresent(body, key);
    if (value == null || value.toString().isBlank()) return null;
    return ((Number) (value instanceof Number ? value : Long.parseLong(value.toString()))).longValue();
  }

  private Boolean bool(Map<String, Object> body, String key) {
    Object value = firstPresent(body, key);
    if (value == null) return null;
    return value instanceof Boolean b ? b : Boolean.parseBoolean(value.toString());
  }

  private UUID uuid(Object value) {
    if (value == null) throw new BusinessException("INVALID_ID", "Thiếu ID bắt buộc.");
    return UUID.fromString(value.toString());
  }

  private UUID uuidOrNull(Object value) {
    if (value == null || value.toString().isBlank()) return null;
    return UUID.fromString(value.toString());
  }

  private String valueOr(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private Integer valueOr(Integer value, Integer fallback) {
    return value == null ? fallback : value;
  }

  private List<Object> list(Object value) {
    if (value instanceof List<?> items) return new ArrayList<>(items);
    if (value == null) return new ArrayList<>();
    return new ArrayList<>(List.of(value));
  }

  private String normalizeOnlineOffline(String mode) {
    return "offline".equals(mode) ? "offline" : "online";
  }

  private String normalizeDateTime(String value) {
    if (value.endsWith("Z") || value.contains("+")) return value;
    return OffsetDateTime.parse(value + ZoneOffset.UTC).toString();
  }

  private String jsonValue(Object value) {
    if (value == null) return "null";
    if (value instanceof Number || value instanceof Boolean) return value.toString();
    if (value instanceof Map || value instanceof List) {
      try {
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
      } catch (Exception ex) {
        return "\"" + value + "\"";
      }
    }
    return "\"" + value.toString().replace("\"", "\\\"") + "\"";
  }

  private Object parseJson(String value) {
    if (value == null) return null;
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().readValue(value, Object.class);
    } catch (Exception ex) {
      return value;
    }
  }

  private String groupForGrade(int sortOrder) {
    if (sortOrder <= 5) return "primary";
    if (sortOrder <= 9) return "secondary";
    return "high_school";
  }

  public record MessageRequest(@NotBlank String content) {}
}
