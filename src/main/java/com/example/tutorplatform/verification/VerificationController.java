package com.example.tutorplatform.verification;

import com.example.tutorplatform.common.ApiResponse;
import com.example.tutorplatform.common.BusinessException;
import com.example.tutorplatform.common.ForbiddenException;
import com.example.tutorplatform.common.NotFoundException;
import com.example.tutorplatform.db.DbService;
import com.example.tutorplatform.file.FileStorageService;
import com.example.tutorplatform.file.FileStorageService.StoredFile;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class VerificationController {
  private static final String AGREEMENT_VERSION = "student-tutor-verification-v1";
  private static final String AGREEMENT_TITLE = "Bản cam kết xác thực thông tin";
  private static final String AGREEMENT_CONTENT = """
      Tôi xác nhận thông tin và giấy tờ cung cấp là đúng sự thật.
      Tôi là chủ sở hữu hợp pháp của giấy tờ đã tải lên.
      Tôi đồng ý để nền tảng xử lý dữ liệu phục vụ xác thực tài khoản.
      Tôi hiểu rằng nếu giả mạo, tài khoản có thể bị từ chối, bị khóa hoặc bị hủy quyền sử dụng.
      Tôi đồng ý với điều khoản sử dụng và chính sách dữ liệu của nền tảng.
      """;

  private final DbService db;
  private final JdbcTemplate jdbc;
  private final FileStorageService fileStorage;
  private final DuplicateDocumentService duplicateDocumentService;
  private final FraudRiskService fraudRiskService;
  private final OcrService ocrService;

  public VerificationController(
      DbService db,
      FileStorageService fileStorage,
      DuplicateDocumentService duplicateDocumentService,
      FraudRiskService fraudRiskService,
      OcrService ocrService
  ) {
    this.db = db;
    this.jdbc = db.jdbc();
    this.fileStorage = fileStorage;
    this.duplicateDocumentService = duplicateDocumentService;
    this.fraudRiskService = fraudRiskService;
    this.ocrService = ocrService;
  }

  @PostMapping(value = "/student/verifications/student-card/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @Transactional
  public ApiResponse<Map<String, Object>> uploadStudentCard(
      @RequestParam("file") MultipartFile file,
      @RequestParam(required = false) String schoolName,
      @RequestParam(required = false) String studentCode,
      @RequestParam(required = false) String fullNameInput,
      @RequestParam(required = false) String schoolEmail
  ) throws Exception {
    requireStudent();
    UUID userId = db.currentUserIdOrThrow();
    StoredFile stored = fileStorage.store(file, userId, "private", "student_card");
    boolean duplicate = duplicateDocumentService.hasDuplicate(stored.sha256Hash());
    int riskScore = fraudRiskService.score(duplicate, schoolEmail, studentCode);
    UUID id = jdbc.queryForObject("""
        insert into user_verifications(user_id, verification_type, school_name, student_code, full_name_input,
          school_email, card_file_id, duplicate_file, risk_score, status)
        values (?, 'student_card', ?, ?, ?, ?, ?, ?, ?, 'draft')
        returning id
        """, UUID.class, userId, schoolName, studentCode, fullNameInput, schoolEmail, stored.id(), duplicate, riskScore);
    fileStorage.attachEntity(stored.id(), "verification", id, "private");
    ocrService.extractDocumentFields(stored.id().toString());
    db.auditCurrent("verification.student_card_upload", "verification", id, "Người dùng tải thẻ sinh viên để xác thực.");
    return ApiResponse.ok(verificationById(id), "Đã tải giấy tờ. Vui lòng ký bản cam kết để gửi xét duyệt.");
  }

  @PostMapping(value = "/student/verifications/{id}/selfie/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @Transactional
  public ApiResponse<Map<String, Object>> uploadStudentSelfie(@PathVariable UUID id, @RequestParam("file") MultipartFile file) throws Exception {
    requireStudent();
    Map<String, Object> verification = verificationById(id);
    requireOwner(verification);
    StoredFile stored = fileStorage.store(file, db.currentUserIdOrThrow(), "private", "student_selfie");
    jdbc.update("update user_verifications set selfie_file_id = ?, updated_at = now() where id = ?", stored.id(), id);
    fileStorage.attachEntity(stored.id(), "verification", id, "private");
    db.auditCurrent("verification.student_selfie_upload", "verification", id, "Người dùng tải selfie để bổ sung xác thực.");
    return ApiResponse.ok(verificationById(id));
  }

  @GetMapping("/student/verifications/me")
  public ApiResponse<List<Map<String, Object>>> myStudentVerifications() {
    requireStudent();
    return ApiResponse.ok(verificationsForUser(db.currentUserIdOrThrow()));
  }

  @PostMapping("/student/verifications/{id}/agreement/sign")
  @Transactional
  public ApiResponse<Map<String, Object>> signStudentAgreement(@PathVariable UUID id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
    requireStudent();
    return ApiResponse.ok(signAgreement(id, body, request), "Đã ký cam kết xác thực thông tin.");
  }

  @PostMapping("/student/verifications/{id}/submit")
  @Transactional
  public ApiResponse<Map<String, Object>> submitStudentVerification(@PathVariable UUID id) {
    requireStudent();
    return ApiResponse.ok(submitVerification(id), "Hồ sơ xác thực đã được gửi admin xét duyệt.");
  }

  @PostMapping(value = "/tutor/verifications/document/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @Transactional
  public ApiResponse<Map<String, Object>> uploadTutorDocument(
      @RequestParam("file") MultipartFile file,
      @RequestParam(defaultValue = "tutor_identity") String verificationType,
      @RequestParam(required = false) String schoolName,
      @RequestParam(required = false) String studentCode,
      @RequestParam(required = false) String fullNameInput,
      @RequestParam(required = false) String schoolEmail
  ) throws Exception {
    requireTutor();
    String type = normalizeTutorType(verificationType);
    UUID userId = db.currentUserIdOrThrow();
    StoredFile stored = fileStorage.store(file, userId, "private", type);
    boolean duplicate = duplicateDocumentService.hasDuplicate(stored.sha256Hash());
    int riskScore = fraudRiskService.score(duplicate, schoolEmail, studentCode);
    UUID id = jdbc.queryForObject("""
        insert into user_verifications(user_id, verification_type, school_name, student_code, full_name_input,
          school_email, document_file_id, duplicate_file, risk_score, status)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'draft')
        returning id
        """, UUID.class, userId, type, schoolName, studentCode, fullNameInput, schoolEmail, stored.id(), duplicate, riskScore);
    fileStorage.attachEntity(stored.id(), "verification", id, "private");
    ocrService.extractDocumentFields(stored.id().toString());
    db.auditCurrent("verification.tutor_document_upload", "verification", id, "Gia sư tải giấy tờ xác thực.");
    return ApiResponse.ok(verificationById(id), "Đã tải giấy tờ. Vui lòng ký bản cam kết để gửi xét duyệt.");
  }

  @GetMapping("/tutor/verifications/me")
  public ApiResponse<List<Map<String, Object>>> myTutorVerifications() {
    requireTutor();
    return ApiResponse.ok(verificationsForUser(db.currentUserIdOrThrow()));
  }

  @PostMapping("/tutor/verifications/{id}/agreement/sign")
  @Transactional
  public ApiResponse<Map<String, Object>> signTutorAgreement(@PathVariable UUID id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
    requireTutor();
    return ApiResponse.ok(signAgreement(id, body, request), "Đã ký cam kết xác thực thông tin.");
  }

  @PostMapping("/tutor/verifications/{id}/submit")
  @Transactional
  public ApiResponse<Map<String, Object>> submitTutorVerification(@PathVariable UUID id) {
    requireTutor();
    return ApiResponse.ok(submitVerification(id), "Hồ sơ xác thực đã được gửi admin xét duyệt.");
  }

  @GetMapping("/admin/verifications")
  public ApiResponse<List<Map<String, Object>>> adminVerifications(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String type
  ) {
    List<Object> args = new java.util.ArrayList<>();
    StringBuilder where = new StringBuilder(" where 1=1 ");
    if (status != null && !status.isBlank() && !"all".equals(status)) {
      where.append(" and uv.status = ? ");
      args.add(status);
    }
    if (type != null && !type.isBlank() && !"all".equals(type)) {
      where.append(" and uv.verification_type = ? ");
      args.add(type);
    }
    return ApiResponse.ok(jdbc.query("""
        select uv.*, u.email, u.full_name
        from user_verifications uv
        join users u on u.id = uv.user_id
        """ + where + " order by uv.created_at desc", verificationMapper(), args.toArray()));
  }

  @GetMapping("/admin/verifications/{id}")
  public ApiResponse<Map<String, Object>> adminVerification(@PathVariable UUID id) {
    return ApiResponse.ok(verificationById(id));
  }

  @PostMapping("/admin/verifications/{id}/approve")
  @Transactional
  public ApiResponse<Map<String, Object>> approve(@PathVariable UUID id) {
    return ApiResponse.ok(adminUpdateStatus(id, "approved", null), "Đã duyệt xác thực.");
  }

  @PostMapping("/admin/verifications/{id}/reject")
  @Transactional
  public ApiResponse<Map<String, Object>> reject(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
    return ApiResponse.ok(adminUpdateStatus(id, "rejected", requiredReason(body)), "Đã từ chối xác thực.");
  }

  @PostMapping("/admin/verifications/{id}/need-more-info")
  @Transactional
  public ApiResponse<Map<String, Object>> needMoreInfo(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
    return ApiResponse.ok(adminUpdateStatus(id, "need_more_info", requiredReason(body)), "Đã yêu cầu bổ sung thông tin.");
  }

  private Map<String, Object> signAgreement(UUID verificationId, Map<String, Object> body, HttpServletRequest request) {
    Map<String, Object> verification = verificationById(verificationId);
    requireOwner(verification);
    String signer = firstString(body, "signerFullName", "fullName", "name");
    if (signer == null || signer.isBlank()) {
      throw new BusinessException("SIGNER_REQUIRED", "Vui lòng nhập họ tên người cam kết.");
    }
    String signerEmail = firstString(body, "signerEmail", "email");
    String uploadedHash = uploadedFileHash(verification);
    jdbc.update("""
        insert into verification_agreements(user_id, verification_id, agreement_version, agreement_title,
          agreement_content, agreement_content_snapshot, agreement_content_hash, uploaded_file_hash, signer_full_name, signer_email,
          otp_verified, ip_address, user_agent)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false, ?, ?)
        on conflict(verification_id) do update
        set signer_full_name = excluded.signer_full_name,
            signer_email = excluded.signer_email,
            agreement_content_snapshot = excluded.agreement_content_snapshot,
            ip_address = excluded.ip_address,
            user_agent = excluded.user_agent,
            signed_at = now()
        """, db.currentUserIdOrThrow(), verificationId, AGREEMENT_VERSION, AGREEMENT_TITLE,
        AGREEMENT_CONTENT, AGREEMENT_CONTENT, sha256(AGREEMENT_CONTENT), uploadedHash, signer, signerEmail, ip(request), request.getHeader("User-Agent"));
    if (!List.of("approved", "pending_review").contains(verification.get("status"))) {
      jdbc.update("""
          update user_verifications
          set status = 'pending_review', reject_reason = null, reviewed_by = null, reviewed_at = null, updated_at = now()
          where id = ? and status in ('draft','pending_review','rejected','need_more_info')
          """, verificationId);
      db.notifyAdmins("info", "Xác thực chờ duyệt", "Có hồ sơ xác thực mới cần kiểm tra.", "/admin/verifications", "verification", verificationId);
    }
    db.auditCurrent("verification.agreement_sign", "verification", verificationId, "Người dùng ký bản cam kết xác thực thông tin.");
    return verificationById(verificationId);
  }

  private Map<String, Object> submitVerification(UUID verificationId) {
    Map<String, Object> verification = verificationById(verificationId);
    requireOwner(verification);
    if (!hasAgreement(verificationId)) {
      throw new BusinessException("AGREEMENT_REQUIRED", "Cần ký bản cam kết trước khi gửi xét duyệt.");
    }
    if ("approved".equals(verification.get("status"))) {
      return verification;
    }
    if ("pending_review".equals(verification.get("status"))) {
      return verification;
    }
    jdbc.update("""
        update user_verifications
        set status = 'pending_review', reject_reason = null, reviewed_by = null, reviewed_at = null, updated_at = now()
        where id = ?
        """, verificationId);
    db.notifyAdmins("info", "Xác thực chờ duyệt", "Có hồ sơ xác thực mới cần kiểm tra.", "/admin/verifications", "verification", verificationId);
    db.auditCurrent("verification.submit", "verification", verificationId, "Người dùng gửi hồ sơ xác thực để admin xét duyệt.");
    return verificationById(verificationId);
  }

  private Map<String, Object> adminUpdateStatus(UUID verificationId, String status, String reason) {
    Map<String, Object> verification = verificationById(verificationId);
    if ("approved".equals(status) && Boolean.TRUE.equals(verification.get("duplicateFile"))) {
      throw new BusinessException("DUPLICATE_REVIEW_REQUIRED", "File bị trùng, cần kiểm tra thủ công trước khi duyệt.");
    }
    jdbc.update("""
        update user_verifications
        set status = ?, reject_reason = ?, reviewed_by = ?, reviewed_at = now(), updated_at = now()
        where id = ?
        """, status, reason, db.currentUserIdOrThrow(), verificationId);
    UUID userId = UUID.fromString(verification.get("userId").toString());
    String title = switch (status) {
      case "approved" -> "Xác thực đã được duyệt";
      case "need_more_info" -> "Xác thực cần bổ sung";
      default -> "Xác thực bị từ chối";
    };
    db.notify(userId, "approved".equals(status) ? "success" : "warning", title,
        reason == null ? title : reason, "/profile", "verification", verificationId);
    db.auditCurrent("admin.verification_" + status, "verification", verificationId, "Admin cập nhật xác thực thành " + status + ".");
    return verificationById(verificationId);
  }

  private List<Map<String, Object>> verificationsForUser(UUID userId) {
    return jdbc.query("""
        select uv.*, u.email, u.full_name
        from user_verifications uv
        join users u on u.id = uv.user_id
        where uv.user_id = ?
        order by uv.created_at desc
        """, verificationMapper(), userId);
  }

  private Map<String, Object> verificationById(UUID id) {
    return db.optional("""
        select uv.*, u.email, u.full_name
        from user_verifications uv
        join users u on u.id = uv.user_id
        where uv.id = ?
        """, verificationMapper(), id).orElseThrow(() -> new NotFoundException("Không tìm thấy hồ sơ xác thực."));
  }

  private RowMapper<Map<String, Object>> verificationMapper() {
    return (rs, row) -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", str(rs, "id"));
      m.put("userId", str(rs, "user_id"));
      m.put("userEmail", rs.getString("email"));
      m.put("userFullName", rs.getString("full_name"));
      m.put("verificationType", rs.getString("verification_type"));
      m.put("schoolName", rs.getString("school_name"));
      m.put("studentCode", rs.getString("student_code"));
      m.put("fullNameInput", rs.getString("full_name_input"));
      m.put("schoolEmail", rs.getString("school_email"));
      m.put("cardFileId", str(rs, "card_file_id"));
      m.put("selfieFileId", str(rs, "selfie_file_id"));
      m.put("documentFileId", str(rs, "document_file_id"));
      m.put("cardFileUrl", fileUrl(rs, "card_file_id"));
      m.put("selfieFileUrl", fileUrl(rs, "selfie_file_id"));
      m.put("documentFileUrl", fileUrl(rs, "document_file_id"));
      m.put("ocrFullName", rs.getString("ocr_full_name"));
      m.put("ocrStudentCode", rs.getString("ocr_student_code"));
      m.put("ocrSchool", rs.getString("ocr_school"));
      m.put("ocrConfidence", rs.getObject("ocr_confidence"));
      m.put("emailVerified", rs.getBoolean("email_verified"));
      m.put("duplicateFile", rs.getBoolean("duplicate_file"));
      m.put("riskScore", rs.getInt("risk_score"));
      m.put("status", rs.getString("status"));
      m.put("rejectReason", rs.getString("reject_reason"));
      m.put("reviewedBy", str(rs, "reviewed_by"));
      m.put("reviewedAt", ts(rs, "reviewed_at"));
      m.put("createdAt", ts(rs, "created_at"));
      m.put("updatedAt", ts(rs, "updated_at"));
      m.put("agreementSigned", hasAgreement(rs.getObject("id", UUID.class)));
      return m;
    };
  }

  private String fileUrl(ResultSet rs, String column) throws SQLException {
    Object value = rs.getObject(column);
    return value == null ? null : "/api/v1/files/" + value;
  }

  private void requireStudent() {
    String role = db.currentUserOrThrow().get("role").toString();
    if (!List.of("student", "parent").contains(role)) {
      throw new ForbiddenException("Khu vực xác thực này dành cho học sinh/phụ huynh.");
    }
  }

  private void requireTutor() {
    if (!db.isTutor()) throw new ForbiddenException("Khu vực xác thực này dành cho gia sư.");
  }

  private void requireOwner(Map<String, Object> verification) {
    if (db.isAdmin()) return;
    if (!db.currentUserIdOrThrow().equals(UUID.fromString(verification.get("userId").toString()))) {
      throw new ForbiddenException("Bạn không có quyền thao tác hồ sơ xác thực này.");
    }
  }

  private boolean hasAgreement(UUID verificationId) {
    Integer count = jdbc.queryForObject("select count(*) from verification_agreements where verification_id = ?", Integer.class, verificationId);
    return count != null && count > 0;
  }

  private String uploadedFileHash(Map<String, Object> verification) {
    Object fileId = firstPresent(verification, "cardFileId", "documentFileId", "selfieFileId");
    if (fileId == null) return null;
    return db.optional("select sha256_hash from uploaded_files where id = ?", (rs, row) -> rs.getString(1), UUID.fromString(fileId.toString())).orElse(null);
  }

  private String normalizeTutorType(String value) {
    String normalized = value == null ? "tutor_identity" : value.trim().toLowerCase().replace("-", "_");
    if (!List.of("tutor_identity", "tutor_certificate").contains(normalized)) {
      throw new BusinessException("INVALID_VERIFICATION_TYPE", "Loại xác thực gia sư không hợp lệ.");
    }
    return normalized;
  }

  private String requiredReason(Map<String, Object> body) {
    String reason = firstString(body, "reason", "rejectReason", "note");
    if (reason == null || reason.isBlank()) {
      throw new BusinessException("REASON_REQUIRED", "Vui lòng nhập lý do.");
    }
    return reason;
  }

  private Object firstPresent(Map<String, Object> body, String... keys) {
    for (String key : keys) {
      Object value = body.get(key);
      if (value != null && !value.toString().isBlank()) return value;
    }
    return null;
  }

  private String firstString(Map<String, Object> body, String... keys) {
    if (body == null) return null;
    Object value = firstPresent(body, keys);
    return value == null ? null : value.toString();
  }

  private String str(ResultSet rs, String column) throws SQLException {
    Object value = rs.getObject(column);
    return value == null ? null : value.toString();
  }

  private String ts(ResultSet rs, String column) throws SQLException {
    Object value = rs.getObject(column);
    return value == null ? null : rs.getObject(column, OffsetDateTime.class).toString();
  }

  private String ip(HttpServletRequest request) {
    String forwardedFor = request.getHeader("X-Forwarded-For");
    if (forwardedFor != null && !forwardedFor.isBlank()) return forwardedFor.split(",")[0].trim();
    return request.getRemoteAddr();
  }

  private String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException("Cannot hash agreement", ex);
    }
  }
}
