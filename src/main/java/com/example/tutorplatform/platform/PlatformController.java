package com.example.tutorplatform.platform;

import com.example.tutorplatform.common.ApiResponse;
import com.example.tutorplatform.common.BusinessException;
import com.example.tutorplatform.common.ForbiddenException;
import com.example.tutorplatform.common.NotFoundException;
import com.example.tutorplatform.common.PageMetadata;
import com.example.tutorplatform.admin.AdminReportService;
import com.example.tutorplatform.admin.AdminSettingsService;
import com.example.tutorplatform.booking.BookingWorkflowService;
import com.example.tutorplatform.catalog.CatalogQueryService;
import com.example.tutorplatform.contact.ContactRequestService;
import com.example.tutorplatform.db.DbService;
import com.example.tutorplatform.auth.RefreshTokenService;
import com.example.tutorplatform.file.UploadApplicationService;
import com.example.tutorplatform.finance.FinanceService;
import com.example.tutorplatform.learningrequest.LearningRequestService;
import com.example.tutorplatform.message.ConversationService;
import com.example.tutorplatform.notification.NotificationService;
import com.example.tutorplatform.policy.StatusTransitionPolicy;
import com.example.tutorplatform.tutoringclass.ClassSessionService;
import com.example.tutorplatform.verification.TutorApprovalEligibilityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
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
  private final RefreshTokenService refreshTokenService;
  private final StatusTransitionPolicy statusPolicy;
  private final CatalogQueryService catalogService;
  private final ConversationService conversationService;
  private final NotificationService notificationService;
  private final AdminReportService adminReportService;
  private final AdminSettingsService adminSettingsService;
  private final ContactRequestService contactRequestService;
  private final UploadApplicationService uploadService;
  private final LearningRequestService learningRequestService;
  private final FinanceService financeService;
  private final BookingWorkflowService bookingWorkflowService;
  private final ClassSessionService classSessionService;
  private final TutorApprovalEligibilityService tutorApprovalEligibilityService;

  public PlatformController(DbService db, RefreshTokenService refreshTokenService,
                            StatusTransitionPolicy statusPolicy,
                            CatalogQueryService catalogService, ConversationService conversationService,
                            NotificationService notificationService, AdminReportService adminReportService,
                            AdminSettingsService adminSettingsService, ContactRequestService contactRequestService,
                            UploadApplicationService uploadService, LearningRequestService learningRequestService,
                            FinanceService financeService, BookingWorkflowService bookingWorkflowService,
                            ClassSessionService classSessionService,
                            TutorApprovalEligibilityService tutorApprovalEligibilityService) {
    this.db = db;
    this.jdbc = db.jdbc();
    this.refreshTokenService = refreshTokenService;
    this.statusPolicy = statusPolicy;
    this.catalogService = catalogService;
    this.conversationService = conversationService;
    this.notificationService = notificationService;
    this.adminReportService = adminReportService;
    this.adminSettingsService = adminSettingsService;
    this.contactRequestService = contactRequestService;
    this.uploadService = uploadService;
    this.learningRequestService = learningRequestService;
    this.financeService = financeService;
    this.bookingWorkflowService = bookingWorkflowService;
    this.classSessionService = classSessionService;
    this.tutorApprovalEligibilityService = tutorApprovalEligibilityService;
  }

  @GetMapping("/catalog/subjects")
  public ApiResponse<List<Map<String, Object>>> subjects() {
    return ApiResponse.ok(catalogService.subjects());
  }

  @GetMapping("/catalog/grade-levels")
  public ApiResponse<List<Map<String, Object>>> gradeLevels() {
    return ApiResponse.ok(catalogService.gradeLevels());
  }

  @GetMapping("/public/stats")
  public ApiResponse<Map<String, Object>> publicStats() {
    return ApiResponse.ok(catalogService.publicStats());
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
      case "parent" -> ApiResponse.ok(parentProfileByUser(userId));
      default -> ApiResponse.ok(studentProfileByUser(userId));
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
      return ApiResponse.ok(parentProfileByUser(userId));
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
    return ApiResponse.ok(studentProfileByUser(userId));
  }

  @GetMapping("/admin/users")
  public ApiResponse<List<Map<String, Object>>> adminUsers(
      @RequestParam(required = false) String role,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String search,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "100") int pageSize
  ) {
    int safePage = safePage(page);
    int safePageSize = safePageSize(pageSize);
    List<Object> args = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where 1 = 1 ");
    if (role != null && !role.isBlank() && !"all".equals(role)) {
      where.append(" and role = ? ");
      args.add(role);
    }
    if (status != null && !status.isBlank() && !"all".equals(status)) {
      where.append(" and status = ? ");
      args.add(status);
    }
    if (search != null && !search.isBlank()) {
      where.append(" and (lower(full_name) like lower(?) or lower(email) like lower(?) or phone like ?) ");
      String pattern = "%" + search.trim() + "%";
      args.add(pattern);
      args.add(pattern);
      args.add(pattern);
    }
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(safePageSize);
    pageArgs.add(offset(safePage, safePageSize));
    return ApiResponse.page(
        jdbc.query("select * from users" + where + " order by created_at desc limit ? offset ?", db.userMapper(), pageArgs.toArray()),
        PageMetadata.of(safePage, safePageSize, countSql("select count(*) from users" + where, args.toArray()))
    );
  }

  @GetMapping("/admin/student-profiles")
  public ApiResponse<List<Map<String, Object>>> adminStudentProfiles(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "100") int pageSize
  ) {
    int safePage = safePage(page);
    int safePageSize = safePageSize(pageSize);
    return ApiResponse.page(
        jdbc.query("select * from student_profiles order by created_at desc limit ? offset ?", studentProfileMapper(), safePageSize, offset(safePage, safePageSize)),
        PageMetadata.of(safePage, safePageSize, countSql("select count(*) from student_profiles"))
    );
  }

  @GetMapping("/admin/parent-profiles")
  public ApiResponse<List<Map<String, Object>>> adminParentProfiles(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "100") int pageSize
  ) {
    int safePage = safePage(page);
    int safePageSize = safePageSize(pageSize);
    return ApiResponse.page(
        jdbc.query("select * from parent_profiles order by created_at desc limit ? offset ?", parentProfileMapper(), safePageSize, offset(safePage, safePageSize)),
        PageMetadata.of(safePage, safePageSize, countSql("select count(*) from parent_profiles"))
    );
  }

  @GetMapping("/admin/users/{userId}")
  public ApiResponse<Map<String, Object>> adminUser(@PathVariable UUID userId) {
    return ApiResponse.ok(db.userById(userId).orElseThrow(() -> new NotFoundException("Không tìm thấy người dùng.")));
  }

  @GetMapping("/admin/users/{userId}/crm")
  public ApiResponse<Map<String, Object>> adminUserCrm(@PathVariable UUID userId) {
    return ApiResponse.ok(userCrm(userId));
  }

  @PostMapping("/admin/users/{userId}/notes")
  public ApiResponse<Map<String, Object>> addUserCrmNote(@PathVariable UUID userId, @RequestBody Map<String, Object> body) {
    db.userById(userId).orElseThrow(() -> new NotFoundException("Không tìm thấy người dùng."));
    return ApiResponse.ok(addCrmNote("user", userId, body), "Đã thêm ghi chú nội bộ.");
  }

  @PostMapping("/admin/users/{userId}/risk-flags")
  public ApiResponse<Map<String, Object>> addUserRiskFlag(@PathVariable UUID userId, @RequestBody Map<String, Object> body) {
    db.userById(userId).orElseThrow(() -> new NotFoundException("Không tìm thấy người dùng."));
    return ApiResponse.ok(addRiskFlag("USER", userId, body), "Đã gắn cờ rủi ro.");
  }

  @DeleteMapping("/admin/users/{userId}/risk-flags/{flagId}")
  public ApiResponse<Map<String, Object>> resolveUserRiskFlag(@PathVariable UUID userId, @PathVariable UUID flagId) {
    db.userById(userId).orElseThrow(() -> new NotFoundException("Không tìm thấy người dùng."));
    resolveRiskFlag("USER", userId, flagId);
    return ApiResponse.ok(Map.of("resolved", true), "Đã xử lý cờ rủi ro.");
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
    int safePage = safePage(page);
    int safePageSize = safePublicPageSize(pageSize);
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
    return ApiResponse.page(db.tutorList(where.toString(), args, safePage, safePageSize, false, sort).stream()
        .map(tutor -> publicTutorDto(tutor, false))
        .toList(), PageMetadata.of(safePage, safePageSize, total));
  }

  @GetMapping("/tutors/{tutorId}")
  public ApiResponse<Map<String, Object>> tutor(@PathVariable UUID tutorId) {
    return ApiResponse.ok(publicTutorDto(db.tutorById(tutorId, false), true));
  }

  @GetMapping("/favorites/tutors")
  public ApiResponse<List<Map<String, Object>>> favoriteTutors() {
    UUID userId = db.currentUserIdOrThrow();
    return ApiResponse.ok(jdbc.query("""
        select tp.*, u.full_name, u.avatar_url
        from tutor_favorites tf
        join tutor_profiles tp on tp.id = tf.tutor_id
        join users u on u.id = tp.user_id
        where tf.user_id = ? and tp.status = 'approved'
        order by tf.created_at desc
        """, db.tutorMapper(), userId).stream()
        .map(tutor -> publicTutorDto(tutor, false))
        .toList());
  }

  @GetMapping("/favorites/tutors/ids")
  public ApiResponse<Map<String, Object>> favoriteTutorIds() {
    UUID userId = db.currentUserIdOrThrow();
    return ApiResponse.ok(Map.of("ids", favoriteTutorIdsForUser(userId)));
  }

  @PostMapping("/favorites/tutors/{tutorId}")
  public ApiResponse<Map<String, Object>> addFavoriteTutor(@PathVariable UUID tutorId) {
    UUID userId = db.currentUserIdOrThrow();
    db.tutorById(tutorId, false);
    jdbc.update("""
        insert into tutor_favorites(user_id, tutor_id)
        values (?, ?)
        on conflict (user_id, tutor_id) do nothing
        """, userId, tutorId);
    return ApiResponse.ok(Map.of("isFavorite", true, "ids", favoriteTutorIdsForUser(userId)), "Đã lưu gia sư yêu thích.");
  }

  @DeleteMapping("/favorites/tutors/{tutorId}")
  public ApiResponse<Map<String, Object>> removeFavoriteTutor(@PathVariable UUID tutorId) {
    UUID userId = db.currentUserIdOrThrow();
    jdbc.update("delete from tutor_favorites where user_id = ? and tutor_id = ?", userId, tutorId);
    return ApiResponse.ok(Map.of("isFavorite", false, "ids", favoriteTutorIdsForUser(userId)), "Đã bỏ lưu gia sư yêu thích.");
  }

  @GetMapping("/tutor/profile")
  public ApiResponse<Map<String, Object>> tutorProfile() {
    return ApiResponse.ok(db.tutorById(db.tutorIdByUserOrThrow(db.currentUserIdOrThrow()), true));
  }

  @GetMapping("/tutor/approval-eligibility")
  public ApiResponse<Map<String, Object>> myTutorApprovalEligibility() {
    UUID tutorId = db.tutorIdByUserOrThrow(db.currentUserIdOrThrow());
    return ApiResponse.ok(tutorApprovalEligibilityService.checkTutorApprovalEligibility(tutorId));
  }

  @PatchMapping("/tutor/profile")
  public ApiResponse<Map<String, Object>> updateTutorProfile(@RequestBody Map<String, Object> body) {
    UUID tutorId = db.tutorIdByUserOrThrow(db.currentUserIdOrThrow());
    jdbc.update("""
        update tutor_profiles set headline = coalesce(?, headline), bio = coalesce(?, bio),
          gender = coalesce(?, gender), education = coalesce(?, education), university = coalesce(?, university),
          major = coalesce(?, major), experience_years = coalesce(?, experience_years),
          teaching_method = coalesce(?, teaching_method), hourly_rate_min = coalesce(?, hourly_rate_min),
          hourly_rate_max = coalesce(?, hourly_rate_max), student_code = coalesce(?, student_code), updated_at = now()
        where id = ?
        """, firstString(body, "headline"), firstString(body, "bio"), firstString(body, "gender"),
        firstString(body, "faculty", "education"), firstString(body, "university"), firstString(body, "major"),
        integer(body, "experienceYears"), firstString(body, "teachingMethod"), firstInteger(body, "pricePerHour", "hourlyRateMin"),
        firstInteger(body, "hourlyRateMax", "pricePerHour"), firstString(body, "studentCode", "student_code"), tutorId);
    replaceTutorSubjectsAndLocations(tutorId, body);
    return ApiResponse.ok(db.tutorById(tutorId, true), "Cập nhật hồ sơ gia sư thành công");
  }

  @PostMapping("/tutor/profile/submit")
  public ApiResponse<Map<String, Object>> submitTutorProfile() {
    UUID tutorId = db.tutorIdByUserOrThrow(db.currentUserIdOrThrow());
    jdbc.update("update tutor_profiles set status = 'submitted', status_reason = null, updated_at = now() where id = ?", tutorId);
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
  public ApiResponse<List<Map<String, Object>>> adminTutors(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String search,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "100") int pageSize
  ) {
    int safePage = safePage(page);
    int safePageSize = safePageSize(pageSize);
    List<Object> args = new ArrayList<>();
    String where = "";
    if (status != null && !status.isBlank() && !"all".equals(status)) {
      where += " and tp.status = ? ";
      args.add(status);
    }
    if (search != null && !search.isBlank()) {
      where += """
           and (
             lower(coalesce(u.full_name, '')) like ?
             or lower(coalesce(u.email, '')) like ?
             or lower(coalesce(tp.university, '')) like ?
             or lower(coalesce(tp.major, '')) like ?
             or lower(coalesce(tp.student_code, '')) like ?
             or exists (
               select 1
               from tutor_subjects ts
               join subjects s on s.id = ts.subject_id
               where ts.tutor_id = tp.id and lower(coalesce(s.name, '')) like ?
             )
           )
          """;
      String pattern = "%" + search.trim().toLowerCase() + "%";
      for (int i = 0; i < 6; i++) args.add(pattern);
    }
    long total = db.tutorCount(where, new ArrayList<>(args), true);
    return ApiResponse.page(
        db.tutorList(where, args, safePage, safePageSize, true, "newest"),
        PageMetadata.of(safePage, safePageSize, total)
    );
  }

  @GetMapping("/admin/tutors/{tutorId}")
  public ApiResponse<Map<String, Object>> adminTutor(@PathVariable UUID tutorId) {
    return ApiResponse.ok(db.tutorById(tutorId, true));
  }

  @GetMapping("/admin/tutors/{tutorId}/crm")
  public ApiResponse<Map<String, Object>> adminTutorCrm(@PathVariable UUID tutorId) {
    return ApiResponse.ok(tutorCrm(tutorId));
  }

  @PostMapping("/admin/tutors/{tutorId}/notes")
  public ApiResponse<Map<String, Object>> addTutorCrmNote(@PathVariable UUID tutorId, @RequestBody Map<String, Object> body) {
    db.tutorById(tutorId, true);
    return ApiResponse.ok(addCrmNote("tutor", tutorId, body), "Đã thêm ghi chú nội bộ.");
  }

  @PostMapping("/admin/tutors/{tutorId}/risk-flags")
  public ApiResponse<Map<String, Object>> addTutorRiskFlag(@PathVariable UUID tutorId, @RequestBody Map<String, Object> body) {
    db.tutorById(tutorId, true);
    return ApiResponse.ok(addRiskFlag("TUTOR", tutorId, body), "Đã gắn cờ rủi ro.");
  }

  @DeleteMapping("/admin/tutors/{tutorId}/risk-flags/{flagId}")
  public ApiResponse<Map<String, Object>> resolveTutorRiskFlag(@PathVariable UUID tutorId, @PathVariable UUID flagId) {
    db.tutorById(tutorId, true);
    resolveRiskFlag("TUTOR", tutorId, flagId);
    return ApiResponse.ok(Map.of("resolved", true), "Đã xử lý cờ rủi ro.");
  }

  @GetMapping("/admin/tutors/{tutorId}/approval-eligibility")
  public ApiResponse<Map<String, Object>> tutorApprovalEligibility(@PathVariable UUID tutorId) {
    return ApiResponse.ok(tutorApprovalEligibilityService.checkTutorApprovalEligibility(tutorId));
  }

  @PostMapping("/admin/tutors/{tutorId}/approve")
  @Transactional
  public ApiResponse<Map<String, Object>> approveTutor(@PathVariable UUID tutorId) {
    Map<String, Object> eligibility = tutorApprovalEligibilityService.checkTutorApprovalEligibility(tutorId);
    if (!Boolean.TRUE.equals(eligibility.get("eligibleForApproval"))) {
      throw new BusinessException(
          "TUTOR_NOT_ELIGIBLE_FOR_APPROVAL",
          "Hồ sơ gia sư chưa đủ điều kiện duyệt.",
          HttpStatus.UNPROCESSABLE_ENTITY,
          Map.of("reasons", eligibility.get("reasons"), "eligibility", eligibility)
      );
    }
    String currentStatus = jdbc.queryForObject("select status from tutor_profiles where id = ?", String.class, tutorId);
    statusPolicy.requireTutor(currentStatus, "approved");
    jdbc.update("update tutor_profiles set status = 'approved', status_reason = null, approved_at = now(), approved_by = ?, updated_at = now() where id = ?",
        db.currentUserIdOrThrow(), tutorId);
    UUID userId = jdbc.queryForObject("select user_id from tutor_profiles where id = ?", UUID.class, tutorId);
    db.notify(userId, "success", "Hồ sơ đã được duyệt", "Hồ sơ gia sư của bạn đã được phê duyệt.", "/dashboard/tutor", "tutor", tutorId);
    db.auditCurrent("admin.approve_tutor", "tutor", tutorId, "Admin duyệt hồ sơ gia sư.", Map.of("eligibility", eligibility));
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
    return ApiResponse.ok(learningRequestService.learningRequests());
  }

  @GetMapping("/public/learning-requests")
  public ApiResponse<List<Map<String, Object>>> publicLearningRequests(
      @RequestParam(required = false) String search,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String subject,
      @RequestParam(required = false) String grade,
      @RequestParam(required = false) String location,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "24") int pageSize
  ) {
    int safePage = safePage(page);
    int safePageSize = safePageSize(pageSize);
    String query = search != null ? search : q;
    return ApiResponse.page(
        learningRequestService.publicLearningRequests(query, subject, grade, location, safePageSize, offset(safePage, safePageSize)),
        PageMetadata.of(safePage, safePageSize, learningRequestService.publicLearningRequestsCount(query, subject, grade, location))
    );
  }

  @PostMapping("/public/learning-requests")
  @Transactional
  public ApiResponse<Map<String, Object>> createPublicLearningRequest(@RequestBody Map<String, Object> body) {
    return ApiResponse.ok(learningRequestService.createPublic(body), "Yêu cầu đã được gửi. Admin sẽ kiểm tra trước khi xử lý.");
  }

  @PostMapping("/public/trial-booking-requests")
  @Transactional
  public ApiResponse<Map<String, Object>> createPublicTrialBookingRequest(@RequestBody Map<String, Object> body, HttpServletRequest request) {
    return ApiResponse.ok(
        learningRequestService.createPublicTrialBookingRequest(body, clientIp(request), request.getHeader("User-Agent")),
        "Yêu cầu học thử đã được gửi. Tư vấn viên sẽ liên hệ để xác nhận lịch."
    );
  }

  @GetMapping("/student/learning-requests/me")
  public ApiResponse<List<Map<String, Object>>> myStudentLearningRequests() {
    return ApiResponse.ok(learningRequestService.myStudentLearningRequests());
  }

  @PostMapping("/student/learning-requests")
  @Transactional
  public ApiResponse<Map<String, Object>> createStudentLearningRequest(@RequestBody Map<String, Object> body) {
    return ApiResponse.ok(learningRequestService.createStudent(body), "Yêu cầu đã được tạo");
  }

  @PostMapping("/learning-requests")
  @Transactional
  public ApiResponse<Map<String, Object>> createLearningRequest(@RequestBody Map<String, Object> body) {
    return ApiResponse.ok(learningRequestService.create(body), "Yêu cầu đã được tạo");
  }

  @GetMapping("/learning-requests/{requestId}")
  public ApiResponse<Map<String, Object>> learningRequest(@PathVariable UUID requestId) {
    return ApiResponse.ok(learningRequestService.get(requestId));
  }

  @PatchMapping("/learning-requests/{requestId}")
  public ApiResponse<Map<String, Object>> updateLearningRequest(@PathVariable UUID requestId, @RequestBody Map<String, Object> body) {
    return ApiResponse.ok(learningRequestService.update(requestId, body));
  }

  @PostMapping("/learning-requests/{requestId}/cancel")
  public ApiResponse<Map<String, Object>> cancelLearningRequest(@PathVariable UUID requestId) {
    return ApiResponse.ok(learningRequestService.cancel(requestId));
  }

  @GetMapping("/admin/learning-requests")
  public ApiResponse<List<Map<String, Object>>> adminLearningRequests(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String search,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "100") int pageSize
  ) {
    int safePage = safePage(page);
    int safePageSize = safePageSize(pageSize);
    return ApiResponse.page(
        learningRequestService.adminLearningRequests(status, search, safePageSize, offset(safePage, safePageSize)),
        PageMetadata.of(safePage, safePageSize, learningRequestService.adminLearningRequestsCount(status, search))
    );
  }

  @GetMapping("/admin/learning-requests/{requestId}")
  public ApiResponse<Map<String, Object>> adminLearningRequest(@PathVariable UUID requestId) {
    return ApiResponse.ok(learningRequestService.adminLearningRequest(requestId));
  }

  @PatchMapping("/admin/learning-requests/{requestId}/status")
  public ApiResponse<Map<String, Object>> updateLearningRequestStatus(@PathVariable UUID requestId, @RequestBody Map<String, Object> body) {
    return ApiResponse.ok(learningRequestService.updateStatus(requestId, body));
  }

  @PostMapping("/admin/learning-requests/{requestId}/assign-tutor")
  @Transactional
  public ApiResponse<Map<String, Object>> assignTutor(@PathVariable UUID requestId, @RequestBody Map<String, Object> body) {
    return ApiResponse.ok(learningRequestService.assignTutor(requestId, body));
  }

  @PostMapping("/admin/learning-requests/{requestId}/assign-tutor-with-booking")
  @Transactional
  public ApiResponse<Map<String, Object>> assignTutorWithBooking(@PathVariable UUID requestId, @RequestBody Map<String, Object> body) {
    return ApiResponse.ok(learningRequestService.assignTutorWithBooking(requestId, body));
  }

  @GetMapping("/admin/learning-requests/{requestId}/matching-tutors")
  public ApiResponse<List<Map<String, Object>>> matchingTutors(@PathVariable UUID requestId) {
    return ApiResponse.ok(learningRequestService.matchingTutors(requestId));
  }

  @PostMapping("/admin/learning-requests/{requestId}/rematch")
  public ApiResponse<Map<String, Object>> rematch(@PathVariable UUID requestId) {
    return ApiResponse.ok(learningRequestService.rematch(requestId));
  }

  @PostMapping("/admin/learning-requests/{requestId}/cancel")
  public ApiResponse<Map<String, Object>> adminCancelRequest(@PathVariable UUID requestId) {
    return ApiResponse.ok(learningRequestService.adminCancel(requestId));
  }

  @GetMapping("/bookings")
  public ApiResponse<List<Map<String, Object>>> bookings() {
    return ApiResponse.ok(bookingWorkflowService.bookings());
  }

  @PostMapping("/bookings")
  @Transactional
  public ApiResponse<Map<String, Object>> createBooking(@RequestBody Map<String, Object> body) {
    return ApiResponse.ok(bookingWorkflowService.create(body), "Booking học thử đã được tạo");
  }

  @GetMapping("/bookings/{bookingId}")
  public ApiResponse<Map<String, Object>> booking(@PathVariable UUID bookingId) {
    return ApiResponse.ok(bookingWorkflowService.get(bookingId));
  }

  @PostMapping("/bookings/{bookingId}/cancel")
  public ApiResponse<Map<String, Object>> cancelBooking(@PathVariable UUID bookingId) {
    return ApiResponse.ok(bookingWorkflowService.cancel(bookingId));
  }

  @GetMapping("/tutor/bookings")
  public ApiResponse<List<Map<String, Object>>> tutorBookings() {
    return ApiResponse.ok(bookingWorkflowService.tutorBookings());
  }

  @PostMapping("/tutor/bookings/{bookingId}/accept")
  public ApiResponse<Map<String, Object>> acceptBooking(@PathVariable UUID bookingId, @RequestBody(required = false) Map<String, Object> body) {
    return ApiResponse.ok(bookingWorkflowService.accept(bookingId, body));
  }

  @PostMapping("/tutor/bookings/{bookingId}/reject")
  public ApiResponse<Map<String, Object>> rejectBooking(@PathVariable UUID bookingId, @RequestBody Map<String, Object> body) {
    return ApiResponse.ok(bookingWorkflowService.reject(bookingId, body));
  }

  @GetMapping("/admin/bookings")
  public ApiResponse<List<Map<String, Object>>> adminBookings(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String search,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "100") int pageSize
  ) {
    int safePage = safePage(page);
    int safePageSize = safePageSize(pageSize);
    return ApiResponse.page(
        bookingWorkflowService.adminBookings(status, search, safePageSize, offset(safePage, safePageSize)),
        PageMetadata.of(safePage, safePageSize, bookingWorkflowService.adminBookingsCount(status, search))
    );
  }

  @GetMapping("/admin/bookings/{bookingId}")
  public ApiResponse<Map<String, Object>> adminBooking(@PathVariable UUID bookingId) {
    return ApiResponse.ok(bookingWorkflowService.adminBooking(bookingId));
  }

  @PostMapping("/admin/bookings/{bookingId}/assign-tutor")
  public ApiResponse<Map<String, Object>> adminAssignBookingTutor(@PathVariable UUID bookingId, @RequestBody Map<String, Object> body) {
    return ApiResponse.ok(bookingWorkflowService.assignTutor(bookingId, body));
  }

  @PostMapping("/admin/bookings/{bookingId}/schedule")
  public ApiResponse<Map<String, Object>> scheduleBooking(@PathVariable UUID bookingId, @RequestBody Map<String, Object> body) {
    return ApiResponse.ok(bookingWorkflowService.schedule(bookingId, body));
  }

  @PostMapping("/admin/bookings/{bookingId}/complete")
  public ApiResponse<Map<String, Object>> completeBooking(@PathVariable UUID bookingId, @RequestBody(required = false) Map<String, Object> body) {
    return ApiResponse.ok(bookingWorkflowService.complete(bookingId, body));
  }

  @PostMapping("/admin/bookings/{bookingId}/mark-no-show-student")
  public ApiResponse<Map<String, Object>> noShowStudent(@PathVariable UUID bookingId) {
    return ApiResponse.ok(bookingWorkflowService.noShowStudent(bookingId));
  }

  @PostMapping("/admin/bookings/{bookingId}/mark-no-show-tutor")
  public ApiResponse<Map<String, Object>> noShowTutor(@PathVariable UUID bookingId) {
    return ApiResponse.ok(bookingWorkflowService.noShowTutor(bookingId));
  }

  @PostMapping("/admin/bookings/{bookingId}/convert-to-class")
  @Transactional
  public ApiResponse<Map<String, Object>> convertBooking(@PathVariable UUID bookingId, @RequestBody(required = false) Map<String, Object> body) {
    return ApiResponse.ok(bookingWorkflowService.convertToClass(bookingId, body));
  }

  @PostMapping("/admin/bookings/{bookingId}/cancel")
  public ApiResponse<Map<String, Object>> adminCancelBooking(@PathVariable UUID bookingId) {
    return ApiResponse.ok(bookingWorkflowService.adminCancel(bookingId));
  }

  @GetMapping("/classes")
  public ApiResponse<List<Map<String, Object>>> classes() {
    return ApiResponse.ok(classSessionService.classes());
  }

  @GetMapping("/classes/{classId}")
  public ApiResponse<Map<String, Object>> classById(@PathVariable UUID classId) {
    return ApiResponse.ok(classSessionService.classById(classId));
  }

  @GetMapping("/classes/{classId}/sessions")
  public ApiResponse<List<Map<String, Object>>> classSessions(@PathVariable UUID classId) {
    return ApiResponse.ok(classSessionService.classSessions(classId));
  }

  @GetMapping("/tutor/classes")
  public ApiResponse<List<Map<String, Object>>> tutorClasses() {
    return ApiResponse.ok(classSessionService.tutorClasses());
  }

  @GetMapping("/tutor/classes/{classId}")
  public ApiResponse<Map<String, Object>> tutorClass(@PathVariable UUID classId) {
    return ApiResponse.ok(classSessionService.tutorClass(classId));
  }

  @GetMapping("/tutor/sessions")
  public ApiResponse<List<Map<String, Object>>> tutorSessions() {
    return ApiResponse.ok(classSessionService.tutorSessions());
  }

  @GetMapping("/sessions")
  public ApiResponse<List<Map<String, Object>>> sessions() {
    return ApiResponse.ok(classSessionService.sessions());
  }

  @GetMapping("/sessions/{sessionId}")
  public ApiResponse<Map<String, Object>> session(@PathVariable UUID sessionId) {
    return ApiResponse.ok(classSessionService.session(sessionId));
  }

  @PostMapping("/tutor/sessions/{sessionId}/complete")
  public ApiResponse<Map<String, Object>> tutorCompleteSession(@PathVariable UUID sessionId, @RequestBody(required = false) Map<String, Object> body) {
    return ApiResponse.ok(classSessionService.tutorCompleteSession(sessionId, body));
  }

  @PostMapping("/tutor/sessions/{sessionId}/cancel")
  public ApiResponse<Map<String, Object>> tutorCancelSession(@PathVariable UUID sessionId, @RequestBody(required = false) Map<String, Object> body) {
    return ApiResponse.ok(classSessionService.tutorCancelSession(sessionId, body));
  }

  @GetMapping("/admin/classes")
  public ApiResponse<List<Map<String, Object>>> adminClasses(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String search,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "100") int pageSize
  ) {
    int safePage = safePage(page);
    int safePageSize = safePageSize(pageSize);
    return ApiResponse.page(
        classSessionService.adminClasses(status, search, safePageSize, offset(safePage, safePageSize)),
        PageMetadata.of(safePage, safePageSize, classSessionService.adminClassesCount(status, search))
    );
  }

  @GetMapping("/admin/sessions")
  public ApiResponse<List<Map<String, Object>>> adminSessions(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String search,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "100") int pageSize
  ) {
    int safePage = safePage(page);
    int safePageSize = safePageSize(pageSize);
    return ApiResponse.page(
        classSessionService.adminSessions(status, search, safePageSize, offset(safePage, safePageSize)),
        PageMetadata.of(safePage, safePageSize, classSessionService.adminSessionsCount(status, search))
    );
  }

  @PostMapping("/admin/classes")
  public ApiResponse<Map<String, Object>> createClass(@RequestBody Map<String, Object> body) {
    return ApiResponse.ok(classSessionService.createClass(body));
  }

  @GetMapping("/admin/classes/{classId}")
  public ApiResponse<Map<String, Object>> adminClass(@PathVariable UUID classId) {
    return ApiResponse.ok(classSessionService.adminClass(classId));
  }

  @PatchMapping("/admin/classes/{classId}")
  public ApiResponse<Map<String, Object>> updateClass(@PathVariable UUID classId, @RequestBody Map<String, Object> body) {
    return ApiResponse.ok(classSessionService.updateClass(classId, body));
  }

  @PostMapping("/admin/classes/{classId}/pause")
  public ApiResponse<Map<String, Object>> pauseClass(@PathVariable UUID classId) {
    return ApiResponse.ok(classSessionService.pauseClass(classId));
  }

  @PostMapping("/admin/classes/{classId}/complete")
  public ApiResponse<Map<String, Object>> completeClass(@PathVariable UUID classId) {
    return ApiResponse.ok(classSessionService.completeClass(classId));
  }

  @PostMapping("/admin/classes/{classId}/cancel")
  public ApiResponse<Map<String, Object>> cancelClass(@PathVariable UUID classId) {
    return ApiResponse.ok(classSessionService.cancelClass(classId));
  }

  @GetMapping("/admin/classes/{classId}/sessions")
  public ApiResponse<List<Map<String, Object>>> adminClassSessions(@PathVariable UUID classId) {
    return ApiResponse.ok(classSessionService.adminClassSessions(classId));
  }

  @PostMapping("/admin/classes/{classId}/sessions")
  public ApiResponse<Map<String, Object>> createSession(@PathVariable UUID classId, @RequestBody Map<String, Object> body) {
    return ApiResponse.ok(classSessionService.createSession(classId, body));
  }

  @PatchMapping("/admin/sessions/{sessionId}")
  public ApiResponse<Map<String, Object>> updateSession(@PathVariable UUID sessionId, @RequestBody Map<String, Object> body) {
    return ApiResponse.ok(classSessionService.updateSession(sessionId, body));
  }

  @PostMapping("/admin/sessions/{sessionId}/complete")
  public ApiResponse<Map<String, Object>> adminCompleteSession(@PathVariable UUID sessionId, @RequestBody(required = false) Map<String, Object> body) {
    return ApiResponse.ok(classSessionService.adminCompleteSession(sessionId, body));
  }

  @PostMapping("/admin/sessions/{sessionId}/cancel")
  public ApiResponse<Map<String, Object>> adminCancelSession(@PathVariable UUID sessionId) {
    return ApiResponse.ok(classSessionService.adminCancelSession(sessionId));
  }

  @PostMapping("/admin/sessions/{sessionId}/mark-student-absent")
  public ApiResponse<Map<String, Object>> markStudentAbsent(@PathVariable UUID sessionId) {
    return ApiResponse.ok(classSessionService.markStudentAbsent(sessionId));
  }

  @PostMapping("/admin/sessions/{sessionId}/mark-tutor-absent")
  public ApiResponse<Map<String, Object>> markTutorAbsent(@PathVariable UUID sessionId) {
    return ApiResponse.ok(classSessionService.markTutorAbsent(sessionId));
  }

  @GetMapping("/reviews")
  public ApiResponse<List<Map<String, Object>>> reviews() {
    UUID userId = db.currentUserIdOrThrow();
    if (db.isAdmin()) return ApiResponse.ok(db.reviews(""));
    return ApiResponse.ok(db.reviews(" where r.reviewer_id = ?", userId));
  }

  @PostMapping("/reviews")
  @Transactional
  public ApiResponse<Map<String, Object>> createReview(@RequestBody Map<String, Object> body) {
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
    return ApiResponse.ok(db.reviews(" where r.tutor_id = ? and r.status = 'visible'", tutorId).stream()
        .map(this::publicReviewDto)
        .toList());
  }

  @GetMapping("/admin/reviews")
  public ApiResponse<List<Map<String, Object>>> adminReviews(
      @RequestParam(required = false) String tutorId,
      @RequestParam(required = false) String classId,
      @RequestParam(required = false) Integer rating,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "100") int pageSize
  ) {
    int safePage = safePage(page);
    int safePageSize = safePageSize(pageSize);
    List<Object> args = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where 1 = 1 ");
    if (tutorId != null && !tutorId.isBlank() && !"all".equals(tutorId)) {
      where.append(" and r.tutor_id = ? ");
      args.add(UUID.fromString(tutorId));
    }
    if (classId != null && !classId.isBlank() && !"all".equals(classId)) {
      where.append(" and r.class_id = ? ");
      args.add(UUID.fromString(classId));
    }
    if (rating != null) {
      where.append(" and r.rating = ? ");
      args.add(rating);
    }
    if (status != null && !status.isBlank() && !"all".equals(status)) {
      where.append(" and r.status = ? ");
      args.add(status);
    }
    return ApiResponse.page(
        db.reviewsPage(where.toString(), safePageSize, offset(safePage, safePageSize), args.toArray()),
        PageMetadata.of(safePage, safePageSize, countSql("""
            select count(*)
            from reviews r
            join users u on u.id = r.reviewer_id
            """ + where, args.toArray()))
    );
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
  public ApiResponse<List<Map<String, Object>>> conversations(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "50") int pageSize
  ) {
    int safePage = safePage(page);
    int safePageSize = safePageSize(pageSize);
    return ApiResponse.page(
        conversationService.conversations(safePageSize, offset(safePage, safePageSize)),
        PageMetadata.of(safePage, safePageSize, conversationService.conversationsCount())
    );
  }

  @PostMapping("/conversations")
  @Transactional
  public ApiResponse<Map<String, Object>> createConversation(@RequestBody Map<String, Object> body) {
    return ApiResponse.ok(conversationService.createConversation(body));
  }

  @GetMapping("/conversations/{conversationId}")
  public ApiResponse<Map<String, Object>> conversation(@PathVariable UUID conversationId) {
    return ApiResponse.ok(conversationService.conversation(conversationId));
  }

  @GetMapping("/conversations/{conversationId}/messages")
  public ApiResponse<List<Map<String, Object>>> messages(
      @PathVariable UUID conversationId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "50") int pageSize
  ) {
    int safePage = safePage(page);
    int safePageSize = safePageSize(pageSize);
    return ApiResponse.page(
        conversationService.messages(conversationId, safePageSize, offset(safePage, safePageSize)),
        PageMetadata.of(safePage, safePageSize, conversationService.messagesCount(conversationId))
    );
  }

  @PostMapping("/conversations/{conversationId}/messages")
  public ApiResponse<Map<String, Object>> sendMessage(@PathVariable UUID conversationId, @Valid @RequestBody MessageRequest request) {
    return ApiResponse.ok(conversationService.sendMessage(conversationId, request.content()));
  }

  @PostMapping("/conversations/{conversationId}/mark-read")
  public ApiResponse<Map<String, Object>> markConversationRead(@PathVariable UUID conversationId) {
    return ApiResponse.ok(conversationService.markConversationRead(conversationId));
  }

  @GetMapping("/admin/conversations")
  public ApiResponse<List<Map<String, Object>>> adminConversations(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "100") int pageSize
  ) {
    int safePage = safePage(page);
    int safePageSize = safePageSize(pageSize);
    return ApiResponse.page(
        conversationService.adminConversations(safePageSize, offset(safePage, safePageSize)),
        PageMetadata.of(safePage, safePageSize, countSql("select count(*) from conversations"))
    );
  }

  @GetMapping("/admin/conversations/{conversationId}")
  public ApiResponse<Map<String, Object>> adminConversation(@PathVariable UUID conversationId) {
    return ApiResponse.ok(conversationService.adminConversation(conversationId));
  }

  @GetMapping("/notifications")
  public ApiResponse<List<Map<String, Object>>> notifications(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "50") int pageSize
  ) {
    int safePage = safePage(page);
    int safePageSize = safePageSize(pageSize);
    return ApiResponse.page(
        notificationService.notifications(safePageSize, offset(safePage, safePageSize)),
        PageMetadata.of(safePage, safePageSize, notificationService.notificationsCount())
    );
  }

  @GetMapping("/notifications/unread-count")
  public ApiResponse<Map<String, Object>> unreadCount() {
    return ApiResponse.ok(notificationService.unreadCount());
  }

  @PatchMapping("/notifications/{notificationId}/read")
  @PostMapping("/notifications/{notificationId}/read")
  public ApiResponse<Map<String, Object>> markNotificationRead(@PathVariable UUID notificationId) {
    return ApiResponse.ok(notificationService.markRead(notificationId));
  }

  @PatchMapping("/notifications/read-all")
  @PostMapping("/notifications/read-all")
  public ApiResponse<Map<String, Object>> readAllNotifications() {
    return ApiResponse.ok(notificationService.readAll());
  }

  @DeleteMapping("/notifications/{notificationId}")
  public ApiResponse<Map<String, Object>> deleteNotification(@PathVariable UUID notificationId) {
    return ApiResponse.ok(notificationService.delete(notificationId));
  }

  @DeleteMapping("/notifications")
  public ApiResponse<Map<String, Object>> deleteAllNotifications() {
    return ApiResponse.ok(notificationService.deleteAll());
  }

  @GetMapping("/admin/notifications")
  public ApiResponse<List<Map<String, Object>>> adminNotifications(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String search,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "100") int pageSize
  ) {
    int safePage = safePage(page);
    int safePageSize = safePageSize(pageSize);
    return ApiResponse.page(
        notificationService.adminNotifications(status, type, search, safePageSize, offset(safePage, safePageSize)),
        PageMetadata.of(safePage, safePageSize, notificationService.adminNotificationsCount(status, type, search))
    );
  }

  @PostMapping("/admin/notifications/send")
  public ApiResponse<Map<String, Object>> adminSendNotification(@RequestBody Map<String, Object> body) {
    return ApiResponse.ok(notificationService.adminSend(body));
  }

  @PostMapping("/admin/notifications/send-bulk")
  public ApiResponse<Map<String, Object>> adminSendBulkNotification(@RequestBody Map<String, Object> body) {
    return ApiResponse.ok(notificationService.adminSendBulk(body));
  }

  @GetMapping("/payments")
  public ApiResponse<List<Map<String, Object>>> payments() {
    return ApiResponse.ok(financeService.payments());
  }

  @GetMapping("/payments/{paymentId}")
  public ApiResponse<Map<String, Object>> payment(@PathVariable UUID paymentId) {
    return ApiResponse.ok(financeService.payment(paymentId));
  }

  @GetMapping("/tutor/earnings")
  public ApiResponse<List<Map<String, Object>>> tutorEarnings() {
    return ApiResponse.ok(financeService.tutorEarnings());
  }

  @GetMapping("/tutor/payments")
  public ApiResponse<List<Map<String, Object>>> tutorPayments() {
    return ApiResponse.ok(financeService.tutorPayments());
  }

  @GetMapping("/tutor/payouts")
  public ApiResponse<List<Map<String, Object>>> tutorPayouts() {
    return ApiResponse.ok(financeService.tutorPayouts());
  }

  @PostMapping("/tutor/payouts")
  @Transactional
  public ApiResponse<Map<String, Object>> createPayout(@RequestBody Map<String, Object> body) {
    return ApiResponse.ok(financeService.createPayout(body));
  }

  @GetMapping("/admin/payments")
  public ApiResponse<List<Map<String, Object>>> adminPayments(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String gateway,
      @RequestParam(required = false) String search,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "100") int pageSize
  ) {
    int safePage = safePage(page);
    int safePageSize = safePageSize(pageSize);
    return ApiResponse.page(
        financeService.adminPayments(status, gateway, search, safePageSize, offset(safePage, safePageSize)),
        PageMetadata.of(safePage, safePageSize, financeService.adminPaymentsCount(status, gateway, search))
    );
  }

  @GetMapping("/admin/payments/{paymentId}")
  public ApiResponse<Map<String, Object>> adminPayment(@PathVariable UUID paymentId) {
    return ApiResponse.ok(financeService.adminPayment(paymentId));
  }

  @PostMapping("/admin/payments/{paymentId}/mark-paid")
  public ApiResponse<Map<String, Object>> markPaid(@PathVariable UUID paymentId, @RequestBody(required = false) Map<String, Object> body) {
    return ApiResponse.ok(financeService.markPaid(paymentId, body), "Đã ghi nhận thanh toán thành công.");
  }

  @PostMapping("/admin/payments/{paymentId}/mark-failed")
  public ApiResponse<Map<String, Object>> markFailed(@PathVariable UUID paymentId, @RequestBody(required = false) Map<String, Object> body) {
    return ApiResponse.ok(financeService.markFailed(paymentId, body), "Đã ghi nhận thanh toán thất bại.");
  }

  @PostMapping("/admin/payments/{paymentId}/refund")
  public ApiResponse<Map<String, Object>> refund(@PathVariable UUID paymentId, @RequestBody(required = false) Map<String, Object> body) {
    return ApiResponse.ok(financeService.refund(paymentId, body), "Đã xử lý hoàn tiền.");
  }

  @GetMapping("/admin/payouts")
  public ApiResponse<List<Map<String, Object>>> adminPayouts(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String search,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "100") int pageSize
  ) {
    int safePage = safePage(page);
    int safePageSize = safePageSize(pageSize);
    return ApiResponse.page(
        financeService.adminPayouts(status, search, safePageSize, offset(safePage, safePageSize)),
        PageMetadata.of(safePage, safePageSize, financeService.adminPayoutsCount(status, search))
    );
  }

  @GetMapping("/admin/payouts/{payoutId}")
  public ApiResponse<Map<String, Object>> adminPayout(@PathVariable UUID payoutId) {
    return ApiResponse.ok(financeService.adminPayout(payoutId));
  }

  @PostMapping("/admin/payouts/{payoutId}/approve")
  @Transactional
  public ApiResponse<Map<String, Object>> approvePayout(@PathVariable UUID payoutId, @RequestBody(required = false) Map<String, Object> body) {
    return ApiResponse.ok(financeService.approvePayout(payoutId, body));
  }

  @PostMapping("/admin/payouts/{payoutId}/reject")
  @Transactional
  public ApiResponse<Map<String, Object>> rejectPayout(@PathVariable UUID payoutId, @RequestBody Map<String, Object> body) {
    return ApiResponse.ok(financeService.rejectPayout(payoutId, body));
  }

  @GetMapping("/admin/reports/overview")
  public ApiResponse<Map<String, Object>> reportOverview() {
    return ApiResponse.ok(adminReportService.overview());
  }

  @GetMapping("/admin/reports/request-trends")
  public ApiResponse<List<Map<String, Object>>> requestTrends() {
    return ApiResponse.ok(adminReportService.requestTrends());
  }

  @GetMapping("/admin/reports/conversion-funnel")
  public ApiResponse<List<Map<String, Object>>> conversionFunnel() {
    return ApiResponse.ok(adminReportService.conversionFunnel());
  }

  @GetMapping("/admin/reports/tutor-status-distribution")
  public ApiResponse<List<Map<String, Object>>> tutorStatusDistribution() {
    return ApiResponse.ok(adminReportService.tutorStatusDistribution());
  }

  @GetMapping("/admin/reports/subject-distribution")
  public ApiResponse<List<Map<String, Object>>> subjectDistribution() {
    return ApiResponse.ok(adminReportService.subjectDistribution());
  }

  @GetMapping("/admin/reports/teaching-mode-distribution")
  public ApiResponse<List<Map<String, Object>>> teachingModeDistribution() {
    return ApiResponse.ok(adminReportService.teachingModeDistribution());
  }
  @GetMapping("/admin/reports/revenue")
  public ApiResponse<List<Map<String, Object>>> revenueReport() {
    return ApiResponse.ok(adminReportService.revenue());
  }

  @GetMapping("/admin/reports/payment-status-distribution")
  public ApiResponse<List<Map<String, Object>>> paymentStatusDistribution() {
    return ApiResponse.ok(adminReportService.paymentStatusDistribution());
  }

  @GetMapping("/admin/reports/low-rating-alerts")
  public ApiResponse<List<Map<String, Object>>> lowRatingAlerts() {
    return ApiResponse.ok(adminReportService.lowRatingAlerts());
  }

  @GetMapping("/admin/settings")
  public ApiResponse<Map<String, Object>> settings() {
    return ApiResponse.ok(adminSettingsService.settings());
  }

  @PatchMapping("/admin/settings")
  public ApiResponse<Map<String, Object>> updateSettings(@RequestBody Map<String, Object> body) {
    return ApiResponse.ok(adminSettingsService.update(body));
  }

  @PostMapping("/contact-requests")
  public ApiResponse<Map<String, Object>> createContact(@RequestBody Map<String, Object> body) {
    return ApiResponse.ok(contactRequestService.create(body));
  }

  @GetMapping("/admin/contact-requests")
  public ApiResponse<List<Map<String, Object>>> adminContacts(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String search,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "100") int pageSize
  ) {
    int safePage = safePage(page);
    int safePageSize = safePageSize(pageSize);
    return ApiResponse.page(
        contactRequestService.adminContacts(status, search, safePageSize, offset(safePage, safePageSize)),
        PageMetadata.of(safePage, safePageSize, contactRequestService.adminContactsCount(status, search))
    );
  }

  @PatchMapping("/admin/contact-requests/{contactId}/status")
  public ApiResponse<Map<String, Object>> updateContactStatus(@PathVariable UUID contactId, @RequestBody Map<String, Object> body) {
    return ApiResponse.ok(contactRequestService.updateStatus(contactId, body));
  }

  @GetMapping("/admin/audit-logs")
  public ApiResponse<List<Map<String, Object>>> auditLogs(
      @RequestParam(required = false) String search,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String entityType,
      @RequestParam(required = false) String actorRole,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "100") int pageSize
  ) {
    int safePage = safePage(page);
    int safePageSize = safePageSize(pageSize);
    return ApiResponse.page(
        adminReportService.auditLogs(search, action, entityType, actorRole, safePageSize, offset(safePage, safePageSize)),
        PageMetadata.of(safePage, safePageSize, adminReportService.auditLogsCount(search, action, entityType, actorRole))
    );
  }

  @PostMapping(value = "/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<Map<String, Object>> upload(
      @RequestParam("file") MultipartFile file,
      @RequestParam(defaultValue = "private") String visibility,
      @RequestParam(defaultValue = "general") String purpose
  ) throws IOException {
    return ApiResponse.ok(uploadService.upload(file, visibility, purpose));
  }

  private Map<String, Object> userCrm(UUID userId) {
    Map<String, Object> user = db.userById(userId).orElseThrow(() -> new NotFoundException("Không tìm thấy người dùng."));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("user", user);
    data.put("profile", userProfileOrNull(userId, user.get("role").toString()));
    data.put("summary", userCrmSummary(userId));
    data.put("learningRequests", db.learningRequests(" where lr.requester_id = ? ", userId));
    data.put("bookings", db.bookings(" where tb.student_id = ? ", userId));
    data.put("classes", db.classes(" where tc.student_id = ? ", userId));
    data.put("sessions", db.sessions(" where cs.student_id = ? ", userId));
    data.put("payments", jdbc.query("select * from payments where user_id = ? order by created_at desc limit 100", db.paymentMapper(), userId));
    data.put("refunds", crmRefunds(" where p.user_id = ? ", userId));
    data.put("complaints", crmComplaintsForUser(userId));
    data.put("reviews", db.reviews(" where r.reviewer_id = ? ", userId));
    data.put("conversations", crmConversations(userId));
    data.put("notes", crmNotes("user", userId));
    data.put("riskFlags", riskFlagsForUser(userId, user));
    data.put("auditLogs", crmAudit("user", userId));
    return data;
  }

  private Map<String, Object> tutorCrm(UUID tutorId) {
    Map<String, Object> tutor = db.tutorById(tutorId, true);
    UUID tutorUserId = uuid(tutor.get("userId"));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("user", db.userById(tutorUserId).orElseThrow(() -> new NotFoundException("Không tìm thấy user của gia sư.")));
    data.put("tutor", tutor);
    data.put("summary", tutorCrmSummary(tutorId));
    data.put("approvalEligibility", tutorApprovalEligibilityService.checkTutorApprovalEligibility(tutorId));
    data.put("verifications", crmVerifications(tutorUserId));
    data.put("learningRequests", db.learningRequests(" where lr.assigned_tutor_id = ? ", tutorId));
    data.put("bookings", db.bookings(" where tb.tutor_id = ? ", tutorId));
    data.put("classes", db.classes(" where tc.tutor_id = ? ", tutorId));
    data.put("sessions", db.sessions(" where cs.tutor_id = ? ", tutorId));
    data.put("payments", jdbc.query("select * from payments where tutor_id = ? order by created_at desc limit 100", db.paymentMapper(), tutorId));
    data.put("refunds", crmRefunds(" where p.tutor_id = ? ", tutorId));
    data.put("earnings", jdbc.query("select * from tutor_earnings where tutor_id = ? order by created_at desc limit 100", db.earningMapper(), tutorId));
    data.put("payouts", jdbc.query("""
        select p.*, u.full_name tutor_name
        from payouts p
        join tutor_profiles tp on tp.id = p.tutor_id
        join users u on u.id = tp.user_id
        where p.tutor_id = ?
        order by p.created_at desc
        limit 100
        """, db.payoutMapper(), tutorId));
    data.put("reviews", db.reviews(" where r.tutor_id = ? ", tutorId));
    data.put("complaints", crmComplaintsForTutor(tutorId));
    data.put("conversations", crmConversations(tutorUserId));
    data.put("notes", crmNotes("tutor", tutorId));
    data.put("riskFlags", riskFlagsForTutor(tutorId, tutor));
    data.put("auditLogs", crmAudit("tutor", tutorId));
    return data;
  }

  private Map<String, Object> userProfileOrNull(UUID userId, String role) {
    if ("parent".equals(role)) {
      return db.optional("select * from parent_profiles where user_id = ?", parentProfileMapper(), userId).orElse(null);
    }
    if ("student".equals(role)) {
      return db.optional("select * from student_profiles where user_id = ?", studentProfileMapper(), userId).orElse(null);
    }
    return null;
  }

  private Map<String, Object> userCrmSummary(UUID userId) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("learningRequests", countSql("select count(*) from learning_requests where requester_id = ?", userId));
    summary.put("bookings", countSql("select count(*) from trial_bookings where student_id = ?", userId));
    summary.put("activeClasses", countSql("select count(*) from tutoring_classes where student_id = ? and status in ('trial','active','paused')", userId));
    summary.put("completedSessions", countSql("select count(*) from class_sessions where student_id = ? and status = 'completed'", userId));
    summary.put("paidAmount", moneySql("select coalesce(sum(amount),0) from payments where user_id = ? and status in ('paid','completed','partially_refunded')", userId));
    summary.put("openComplaints", countSql("""
        select count(*)
        from booking_disputes bd
        left join trial_bookings tb on tb.id = bd.booking_id
        where (bd.opened_by = ? or bd.reporter_id = ? or bd.target_user_id = ? or tb.student_id = ?)
          and bd.status not in ('RESOLVED','CLOSED','REJECTED')
        """, userId, userId, userId, userId));
    return summary;
  }

  private Map<String, Object> tutorCrmSummary(UUID tutorId) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("assignedRequests", countSql("select count(*) from learning_requests where assigned_tutor_id = ?", tutorId));
    summary.put("bookings", countSql("select count(*) from trial_bookings where tutor_id = ?", tutorId));
    summary.put("activeClasses", countSql("select count(*) from tutoring_classes where tutor_id = ? and status in ('trial','active','paused')", tutorId));
    summary.put("completedSessions", countSql("select count(*) from class_sessions where tutor_id = ? and status = 'completed'", tutorId));
    summary.put("paidAmount", moneySql("select coalesce(sum(amount),0) from payments where tutor_id = ? and status in ('paid','completed','partially_refunded')", tutorId));
    summary.put("pendingPayouts", countSql("select count(*) from payouts where tutor_id = ? and status in ('pending','processing','approved')", tutorId));
    summary.put("openComplaints", countSql("""
        select count(*)
        from booking_disputes bd
        join trial_bookings tb on tb.id = bd.booking_id
        where tb.tutor_id = ? and bd.status not in ('RESOLVED','CLOSED','REJECTED')
        """, tutorId));
    return summary;
  }

  private List<Map<String, Object>> crmRefunds(String where, Object... args) {
    return jdbc.query("""
        select pr.*, p.user_id, p.tutor_id, p.status payment_status, p.amount payment_amount
        from payment_refunds pr
        join payments p on p.id = pr.payment_id
        """ + where + " order by pr.created_at desc limit 100", refundCrmMapper(), args);
  }

  private List<Map<String, Object>> crmComplaintsForUser(UUID userId) {
    return jdbc.query("""
        select bd.*, assignee.full_name assigned_admin_name
        from booking_disputes bd
        left join trial_bookings tb on tb.id = bd.booking_id
        left join users assignee on assignee.id = bd.assigned_admin_id
        where bd.opened_by = ? or bd.reporter_id = ? or bd.target_user_id = ? or tb.student_id = ?
        order by bd.created_at desc
        limit 100
        """, complaintCrmMapper(), userId, userId, userId, userId);
  }

  private List<Map<String, Object>> crmComplaintsForTutor(UUID tutorId) {
    return jdbc.query("""
        select bd.*, assignee.full_name assigned_admin_name
        from booking_disputes bd
        join trial_bookings tb on tb.id = bd.booking_id
        left join users assignee on assignee.id = bd.assigned_admin_id
        where tb.tutor_id = ?
        order by bd.created_at desc
        limit 100
        """, complaintCrmMapper(), tutorId);
  }

  private List<Map<String, Object>> crmConversations(UUID userId) {
    return jdbc.query("""
        select c.id, c.title, c.type, c.created_at, c.updated_at,
          (select m.content from messages m where m.conversation_id = c.id order by m.created_at desc limit 1) last_message,
          (select max(m.created_at) from messages m where m.conversation_id = c.id) last_message_at,
          (select string_agg(u.full_name, '||' order by u.full_name)
             from conversation_members cm2 join users u on u.id = cm2.user_id
            where cm2.conversation_id = c.id) participant_names
        from conversations c
        join conversation_members cm on cm.conversation_id = c.id
        where cm.user_id = ?
        order by coalesce((select max(m.created_at) from messages m where m.conversation_id = c.id), c.updated_at, c.created_at) desc
        limit 50
        """, conversationCrmMapper(), userId);
  }

  private List<Map<String, Object>> crmVerifications(UUID userId) {
    return jdbc.query("""
        select uv.*, reviewer.full_name reviewer_name
        from user_verifications uv
        left join users reviewer on reviewer.id = uv.reviewed_by
        where uv.user_id = ?
        order by uv.created_at desc
        limit 100
        """, verificationCrmMapper(), userId);
  }

  private List<Map<String, Object>> crmNotes(String entityType, UUID entityId) {
    return jdbc.query("""
        select n.*, u.full_name created_by_name
        from admin_internal_notes n
        left join users u on u.id = n.created_by
        where lower(n.entity_type) = lower(?) and n.entity_id = ?
        order by n.created_at desc
        limit 100
        """, noteCrmMapper(), entityType, entityId);
  }

  private List<Map<String, Object>> crmAudit(String entityType, UUID entityId) {
    return jdbc.query("""
        select *
        from audit_logs
        where lower(entity_type) = lower(?) and entity_id = ?
        order by created_at desc
        limit 50
        """, db.auditMapper(), entityType, entityId);
  }

  private Map<String, Object> addCrmNote(String entityType, UUID entityId, Map<String, Object> body) {
    String content = firstString(body, "content", "note", "reason");
    if (content == null || content.isBlank()) throw new BusinessException("NOTE_REQUIRED", "Cần nhập nội dung ghi chú.");
    UUID noteId = jdbc.queryForObject("""
        insert into admin_internal_notes(entity_type, entity_id, content, visibility, created_by)
        values (?, ?, ?, 'INTERNAL_ONLY', ?)
        returning id
        """, UUID.class, entityType, entityId, content, db.currentUserIdOrThrow());
    db.auditCurrent("admin.crm_note_create", entityType, entityId, "Admin thêm ghi chú nội bộ CRM.");
    return crmNotes(entityType, entityId).stream()
        .filter(note -> noteId.toString().equals(note.get("id")))
        .findFirst()
        .orElseGet(() -> Map.of("id", noteId.toString(), "content", content));
  }

  private Map<String, Object> addRiskFlag(String entityType, UUID entityId, Map<String, Object> body) {
    String level = valueOr(firstString(body, "level"), "MEDIUM").toUpperCase();
    if (!List.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(level)) {
      throw new BusinessException("INVALID_RISK_LEVEL", "Mức rủi ro không hợp lệ.");
    }
    String reason = valueOr(firstString(body, "reason"), "MANUAL_REVIEW").toUpperCase();
    String note = firstString(body, "note", "content");
    UUID flagId = jdbc.queryForObject("""
        insert into admin_risk_flags(entity_type, entity_id, level, reason, note, created_by)
        values (?, ?, ?, ?, ?, ?)
        returning id
        """, UUID.class, entityType.toUpperCase(), entityId, level, reason, note, db.currentUserIdOrThrow());
    db.auditCurrent("admin.risk_flag_create", entityType.toLowerCase(), entityId, "Admin gắn cờ rủi ro CRM: " + reason + ".");
    return persistedRiskFlags(entityType, entityId).stream()
        .filter(flag -> flagId.toString().equals(flag.get("id")))
        .findFirst()
        .orElseGet(() -> riskFlag(flagId.toString(), entityType, entityId, level, reason, note, true, "manual"));
  }

  private void resolveRiskFlag(String entityType, UUID entityId, UUID flagId) {
    int updated = jdbc.update("""
        update admin_risk_flags
        set active = false, resolved_by = ?, resolved_at = now(), updated_at = now()
        where id = ? and entity_type = ? and entity_id = ? and active = true
        """, db.currentUserIdOrThrow(), flagId, entityType.toUpperCase(), entityId);
    if (updated == 0) throw new NotFoundException("Không tìm thấy cờ rủi ro đang hoạt động.");
    db.auditCurrent("admin.risk_flag_resolve", entityType.toLowerCase(), entityId, "Admin xử lý cờ rủi ro CRM.");
  }

  private List<Map<String, Object>> riskFlagsForUser(UUID userId, Map<String, Object> user) {
    List<Map<String, Object>> flags = new ArrayList<>(persistedRiskFlags("USER", userId));
    if (!"active".equals(user.get("status"))) {
      flags.add(riskFlag("derived-user-status", "USER", userId, "HIGH", "USER_INACTIVE", "Tài khoản không active, cần kiểm tra trước khi mở luồng mới.", true, "derived"));
    }
    long openComplaints = countSql("""
        select count(*)
        from booking_disputes bd
        left join trial_bookings tb on tb.id = bd.booking_id
        where (bd.opened_by = ? or bd.reporter_id = ? or bd.target_user_id = ? or tb.student_id = ?)
          and bd.status not in ('RESOLVED','CLOSED','REJECTED')
        """, userId, userId, userId, userId);
    if (openComplaints > 0) {
      flags.add(riskFlag("derived-open-complaints", "USER", userId, openComplaints > 2 ? "HIGH" : "MEDIUM", "OPEN_COMPLAINTS", "Có " + openComplaints + " complaint chưa đóng.", true, "derived"));
    }
    long pendingRefunds = countSql("""
        select count(*)
        from payment_refunds pr
        join payments p on p.id = pr.payment_id
        where p.user_id = ? and pr.status in ('pending','processing')
        """, userId);
    if (pendingRefunds > 0) {
      flags.add(riskFlag("derived-pending-refunds", "USER", userId, "MEDIUM", "PENDING_REFUNDS", "Có " + pendingRefunds + " refund đang chờ xử lý.", true, "derived"));
    }
    return flags;
  }

  private List<Map<String, Object>> riskFlagsForTutor(UUID tutorId, Map<String, Object> tutor) {
    List<Map<String, Object>> flags = new ArrayList<>(persistedRiskFlags("TUTOR", tutorId));
    String status = tutor.get("status") == null ? "" : tutor.get("status").toString();
    if ("suspended".equals(status) || "rejected".equals(status)) {
      flags.add(riskFlag("derived-tutor-status", "TUTOR", tutorId, "CRITICAL", "TUTOR_STATUS_" + status.toUpperCase(), "Hồ sơ tutor đang ở trạng thái cần kiểm soát.", true, "derived"));
    }
    long openComplaints = countSql("""
        select count(*)
        from booking_disputes bd
        join trial_bookings tb on tb.id = bd.booking_id
        where tb.tutor_id = ? and bd.status not in ('RESOLVED','CLOSED','REJECTED')
        """, tutorId);
    if (openComplaints > 0) {
      flags.add(riskFlag("derived-tutor-complaints", "TUTOR", tutorId, openComplaints > 2 ? "HIGH" : "MEDIUM", "OPEN_COMPLAINTS", "Tutor có " + openComplaints + " complaint chưa đóng.", true, "derived"));
    }
    Number rating = tutor.get("rating") instanceof Number n ? n : 0;
    if (rating.doubleValue() > 0 && rating.doubleValue() < 4) {
      flags.add(riskFlag("derived-low-rating", "TUTOR", tutorId, "MEDIUM", "LOW_RATING", "Rating trung bình dưới 4.0.", true, "derived"));
    }
    return flags;
  }

  private List<Map<String, Object>> persistedRiskFlags(String entityType, UUID entityId) {
    return jdbc.query("""
        select f.*, u.full_name created_by_name, resolver.full_name resolved_by_name
        from admin_risk_flags f
        left join users u on u.id = f.created_by
        left join users resolver on resolver.id = f.resolved_by
        where f.entity_type = ? and f.entity_id = ?
        order by f.active desc, f.created_at desc
        limit 100
        """, riskFlagMapper(), entityType.toUpperCase(), entityId);
  }

  private Map<String, Object> riskFlag(String id, String entityType, UUID entityId, String level, String reason, String note, boolean active, String source) {
    Map<String, Object> flag = new LinkedHashMap<>();
    flag.put("id", id);
    flag.put("entityType", entityType);
    flag.put("entityId", entityId.toString());
    flag.put("level", level);
    flag.put("reason", reason);
    flag.put("note", note);
    flag.put("active", active);
    flag.put("source", source);
    return flag;
  }

  private RowMapper<Map<String, Object>> refundCrmMapper() {
    return (rs, row) -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", rs.getObject("id").toString());
      m.put("paymentId", rs.getObject("payment_id").toString());
      m.put("userId", rs.getObject("user_id") == null ? null : rs.getObject("user_id").toString());
      m.put("tutorId", rs.getObject("tutor_id") == null ? null : rs.getObject("tutor_id").toString());
      m.put("amount", rs.getInt("amount"));
      m.put("paymentAmount", rs.getInt("payment_amount"));
      m.put("reason", rs.getString("reason"));
      m.put("status", rs.getString("status"));
      m.put("paymentStatus", rs.getString("payment_status"));
      m.put("gatewayRefundId", rs.getString("gateway_refund_id"));
      m.put("requestedBy", idOrNull(rs, "requested_by"));
      m.put("processedBy", idOrNull(rs, "processed_by"));
      m.put("createdAt", ts(rs, "created_at"));
      m.put("updatedAt", ts(rs, "updated_at"));
      return m;
    };
  }

  private RowMapper<Map<String, Object>> complaintCrmMapper() {
    return (rs, row) -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", rs.getObject("id").toString());
      m.put("bookingId", idOrNull(rs, "booking_id"));
      m.put("status", rs.getString("status"));
      m.put("title", valueOr(rs.getString("title"), rs.getString("reason")));
      m.put("description", valueOr(rs.getString("description"), rs.getString("reason")));
      m.put("reason", rs.getString("reason"));
      m.put("priority", rs.getString("priority"));
      m.put("riskLevel", rs.getString("risk_level"));
      m.put("slaDueAt", ts(rs, "sla_due_at"));
      m.put("assignedAdminId", idOrNull(rs, "assigned_admin_id"));
      m.put("assignedAdminName", rs.getString("assigned_admin_name"));
      m.put("resolutionType", rs.getString("resolution_type"));
      m.put("resolutionNote", valueOr(rs.getString("resolution_note"), rs.getString("resolution")));
      m.put("closedAt", ts(rs, "closed_at"));
      m.put("createdAt", ts(rs, "created_at"));
      m.put("updatedAt", ts(rs, "updated_at"));
      return m;
    };
  }

  private RowMapper<Map<String, Object>> conversationCrmMapper() {
    return (rs, row) -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", rs.getObject("id").toString());
      m.put("title", rs.getString("title"));
      m.put("type", rs.getString("type"));
      m.put("participantNames", splitPipe(rs.getString("participant_names")));
      m.put("lastMessage", rs.getString("last_message"));
      m.put("lastMessageAt", ts(rs, "last_message_at"));
      m.put("createdAt", ts(rs, "created_at"));
      m.put("updatedAt", ts(rs, "updated_at"));
      return m;
    };
  }

  private RowMapper<Map<String, Object>> verificationCrmMapper() {
    return (rs, row) -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", rs.getObject("id").toString());
      m.put("userId", rs.getObject("user_id").toString());
      m.put("verificationType", rs.getString("verification_type"));
      m.put("schoolName", rs.getString("school_name"));
      m.put("studentCode", rs.getString("student_code"));
      m.put("fullNameInput", rs.getString("full_name_input"));
      m.put("emailVerified", rs.getBoolean("email_verified"));
      m.put("duplicateFile", rs.getBoolean("duplicate_file"));
      m.put("riskScore", rs.getInt("risk_score"));
      m.put("status", rs.getString("status"));
      m.put("rejectReason", rs.getString("reject_reason"));
      m.put("reviewedBy", idOrNull(rs, "reviewed_by"));
      m.put("reviewerName", rs.getString("reviewer_name"));
      m.put("reviewedAt", ts(rs, "reviewed_at"));
      m.put("createdAt", ts(rs, "created_at"));
      m.put("updatedAt", ts(rs, "updated_at"));
      return m;
    };
  }

  private RowMapper<Map<String, Object>> noteCrmMapper() {
    return (rs, row) -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", rs.getObject("id").toString());
      m.put("entityType", rs.getString("entity_type"));
      m.put("entityId", rs.getObject("entity_id").toString());
      m.put("content", rs.getString("content"));
      m.put("visibility", rs.getString("visibility"));
      m.put("createdBy", idOrNull(rs, "created_by"));
      m.put("createdByName", rs.getString("created_by_name"));
      m.put("createdAt", ts(rs, "created_at"));
      m.put("updatedAt", ts(rs, "updated_at"));
      return m;
    };
  }

  private RowMapper<Map<String, Object>> riskFlagMapper() {
    return (rs, row) -> {
      Map<String, Object> m = riskFlag(rs.getObject("id").toString(), rs.getString("entity_type"),
          rs.getObject("entity_id", UUID.class), rs.getString("level"), rs.getString("reason"), rs.getString("note"),
          rs.getBoolean("active"), "manual");
      m.put("createdBy", idOrNull(rs, "created_by"));
      m.put("createdByName", rs.getString("created_by_name"));
      m.put("resolvedBy", idOrNull(rs, "resolved_by"));
      m.put("resolvedByName", rs.getString("resolved_by_name"));
      m.put("resolvedAt", ts(rs, "resolved_at"));
      m.put("createdAt", ts(rs, "created_at"));
      m.put("updatedAt", ts(rs, "updated_at"));
      return m;
    };
  }

  private String idOrNull(ResultSet rs, String column) throws SQLException {
    Object value = rs.getObject(column);
    return value == null ? null : value.toString();
  }

  private String ts(ResultSet rs, String column) throws SQLException {
    Object value = rs.getObject(column);
    return value == null ? null : rs.getObject(column, OffsetDateTime.class).toString();
  }

  private int moneySql(String sql, Object... args) {
    Number value = jdbc.queryForObject(sql, Number.class, args);
    return value == null ? 0 : value.intValue();
  }

  private List<String> splitPipe(String value) {
    if (value == null || value.isBlank()) return List.of();
    return java.util.Arrays.stream(value.split("\\|\\|"))
        .map(String::trim)
        .filter(item -> !item.isBlank())
        .toList();
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

  private Map<String, Object> publicTutorDto(Map<String, Object> raw, boolean detail) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", raw.get("id"));
    m.put("fullName", raw.get("fullName"));
    m.put("avatar", raw.get("avatar"));
    m.put("gender", raw.get("gender"));
    m.put("university", raw.get("university"));
    m.put("faculty", raw.get("faculty"));
    m.put("major", raw.get("major"));
    m.put("subjects", raw.getOrDefault("subjects", List.of()));
    m.put("grades", raw.getOrDefault("grades", List.of()));
    m.put("experienceYears", raw.get("experienceYears"));
    m.put("teachingModes", raw.get("teachingModes"));
    m.put("locations", raw.getOrDefault("locations", List.of()));
    m.put("pricePerHour", raw.get("pricePerHour"));
    m.put("rating", raw.get("rating"));
    m.put("reviewCount", raw.get("reviewCount"));
    m.put("verified", raw.get("verified"));
    m.put("status", "approved");
    m.put("approvalStatus", "approved");
    m.put("availableSlots", raw.getOrDefault("availableSlots", List.of()));
    m.put("verifiedBadges", List.of("identity_verified", "certificate_verified", "agreement_signed", "platform_approved"));
    if (detail) {
      m.put("bio", raw.get("bio"));
      m.put("teachingMethod", raw.get("teachingMethod"));
      m.put("achievements", raw.getOrDefault("achievements", List.of()));
      m.put("certificates", raw.getOrDefault("certificates", List.of()));
      m.put("totalStudents", raw.get("totalStudents"));
      m.put("totalClasses", raw.get("totalClasses"));
      m.put("responseRate", raw.get("responseRate"));
      m.put("createdAt", raw.get("createdAt"));
    }
    return m;
  }

  private Map<String, Object> publicReviewDto(Map<String, Object> raw) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", raw.get("id"));
    m.put("tutorId", raw.get("tutorId"));
    m.put("studentName", maskReviewerName(raw.get("studentName")));
    m.put("rating", raw.get("rating"));
    m.put("content", raw.get("content"));
    m.put("createdAt", raw.get("createdAt"));
    return m;
  }

  private String maskReviewerName(Object value) {
    String name = value == null ? "" : value.toString().trim();
    if (name.isBlank()) return "Phụ huynh đã xác thực";
    StringBuilder initials = new StringBuilder();
    for (String part : name.split("\\s+")) {
      if (!part.isBlank()) {
        if (initials.length() > 0) initials.append('.');
        initials.append(Character.toUpperCase(part.charAt(0)));
      }
    }
    return "Phụ huynh " + initials;
  }

  private String clientIp(HttpServletRequest request) {
    String forwardedFor = request.getHeader("X-Forwarded-For");
    if (forwardedFor != null && !forwardedFor.isBlank()) return forwardedFor.split(",")[0].trim();
    return request.getRemoteAddr();
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
      for (Object subject : subjects) {
        UUID subjectId = db.requiredSubjectId(subject);
        if (grades.isEmpty()) {
          jdbc.update("insert into tutor_subjects(tutor_id, subject_id, grade_level_id) values (?, ?, ?) on conflict do nothing",
              tutorId, subjectId, null);
        } else {
          for (Object grade : grades) {
            jdbc.update("insert into tutor_subjects(tutor_id, subject_id, grade_level_id) values (?, ?, ?) on conflict do nothing",
                tutorId, subjectId, db.gradeLevelId(grade));
          }
        }
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

  private Map<String, Object> studentProfileByUser(UUID userId) {
    return db.required("select * from student_profiles where user_id = ?", studentProfileMapper(), userId);
  }

  private Map<String, Object> parentProfileByUser(UUID userId) {
    return db.required("select * from parent_profiles where user_id = ?", parentProfileMapper(), userId);
  }

  private List<String> favoriteTutorIdsForUser(UUID userId) {
    return jdbc.query("select tutor_id::text from tutor_favorites where user_id = ? order by created_at desc", (rs, row) -> rs.getString(1), userId);
  }

  private org.springframework.jdbc.core.RowMapper<Map<String, Object>> studentProfileMapper() {
    return (rs, row) -> {
      UUID userId = rs.getObject("user_id", UUID.class);
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", rs.getObject("id").toString());
      m.put("userId", userId.toString());
      m.put("studentName", db.userById(userId).map(user -> user.get("fullName").toString()).orElse(""));
      m.put("grade", rs.getString("grade_level"));
      m.put("gradeLevel", rs.getString("grade_level"));
      m.put("school", rs.getString("school"));
      m.put("learningGoals", splitGoals(rs.getString("learning_goals")));
      m.put("favoriteTutorIds", favoriteTutorIdsForUser(userId));
      m.put("preferredLearningMode", rs.getString("preferred_learning_mode"));
      m.put("address", rs.getString("address"));
      m.put("province", rs.getString("province"));
      m.put("district", rs.getString("district"));
      m.put("createdAt", rs.getObject("created_at", OffsetDateTime.class).toString());
      m.put("updatedAt", rs.getObject("updated_at", OffsetDateTime.class).toString());
      return m;
    };
  }

  private org.springframework.jdbc.core.RowMapper<Map<String, Object>> parentProfileMapper() {
    return (rs, row) -> {
      UUID userId = rs.getObject("user_id", UUID.class);
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", rs.getObject("id").toString());
      m.put("userId", userId.toString());
      m.put("parentName", db.userById(userId).map(user -> user.get("fullName").toString()).orElse(""));
      m.put("studentIds", List.of());
      m.put("relationship", rs.getString("relationship_to_student"));
      m.put("studentName", rs.getString("student_name"));
      m.put("studentGrade", rs.getString("student_grade"));
      m.put("address", rs.getString("address"));
      m.put("province", rs.getString("province"));
      m.put("district", rs.getString("district"));
      m.put("createdAt", rs.getObject("created_at", OffsetDateTime.class).toString());
      m.put("updatedAt", rs.getObject("updated_at", OffsetDateTime.class).toString());
      return m;
    };
  }

  private List<String> splitGoals(String goals) {
    if (goals == null || goals.isBlank()) return List.of();
    return java.util.Arrays.stream(goals.split(","))
        .map(String::trim)
        .filter(item -> !item.isBlank())
        .toList();
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

  private String nestedString(Map<String, Object> body, String objectKey, String... keys) {
    if (body == null || !(body.get(objectKey) instanceof Map<?, ?> nested)) return null;
    for (String key : keys) {
      Object value = nested.get(key);
      if (value != null && !value.toString().isBlank()) return value.toString();
    }
    return null;
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

  private int safePage(int page) {
    return Math.max(1, page);
  }

  private int safePageSize(int pageSize) {
    if (pageSize <= 0) return 100;
    return Math.min(pageSize, 200);
  }

  private int safePublicPageSize(int pageSize) {
    if (pageSize <= 0) return 20;
    return Math.min(pageSize, 50);
  }

  private int offset(int page, int pageSize) {
    return Math.max(0, (page - 1) * pageSize);
  }

  private long countSql(String sql) {
    Long total = jdbc.queryForObject(sql, Long.class);
    return total == null ? 0 : total;
  }

  private long countSql(String sql, Object... args) {
    Long total = jdbc.queryForObject(sql, Long.class, args);
    return total == null ? 0 : total;
  }

  private String normalizeOnlineOffline(String mode) {
    return "offline".equals(mode) ? "offline" : "online";
  }

  private String normalizeDateTime(String value) {
    if (value.endsWith("Z") || value.contains("+")) return value;
    return OffsetDateTime.parse(value + ZoneOffset.UTC).toString();
  }

  public record MessageRequest(@NotBlank String content) {}
}
