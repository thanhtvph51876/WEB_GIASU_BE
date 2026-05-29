package com.example.tutorplatform.verification;

import com.example.tutorplatform.common.NotFoundException;
import com.example.tutorplatform.db.DbService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class TutorApprovalEligibilityService {
  public static final int APPROVAL_RISK_SCORE_LIMIT = 60;

  private final JdbcTemplate jdbc;

  public TutorApprovalEligibilityService(DbService db) {
    this.jdbc = db.jdbc();
  }

  public Map<String, Object> checkTutorApprovalEligibility(UUID tutorId) {
    Map<String, Object> tutor = tutorRow(tutorId);
    UUID userId = (UUID) tutor.get("user_id");
    String rawStatus = String.valueOf(tutor.get("status"));
    Map<String, Object> identity = latestVerification(userId, "tutor_identity");
    Map<String, Object> certificate = latestVerification(userId, "tutor_certificate");
    Map<String, Object> commitment = latestTutorCommitment(tutorId);

    List<String> reasons = new ArrayList<>();
    if ("draft".equals(rawStatus)) reasons.add("PROFILE_NOT_SUBMITTED");
    if ("rejected".equals(rawStatus)) reasons.add("PROFILE_REJECTED");
    if ("suspended".equals(rawStatus)) reasons.add("PROFILE_SUSPENDED");
    if (List.of("need_update", "needs_more_documents").contains(rawStatus)) reasons.add("NEEDS_MORE_DOCUMENTS");

    applyDocumentReasons(reasons, identity, "IDENTITY");
    applyDocumentReasons(reasons, certificate, "CERTIFICATE");

    boolean commitmentSigned = commitment != null;
    boolean commitmentVersionValid = commitmentSigned
        && VerificationTerms.VERSION.equals(commitment.get("commitment_version"))
        && VerificationTerms.CONTENT_HASH.equals(commitment.get("accepted_terms_hash"));
    if (!commitmentSigned) {
      reasons.add("COMMITMENT_NOT_SIGNED");
    } else if (!commitmentVersionValid) {
      reasons.add("COMMITMENT_VERSION_INVALID");
    }

    boolean duplicateDetected = duplicate(identity) || duplicate(certificate);
    if (duplicateDetected) reasons.add("DUPLICATE_DOCUMENT_DETECTED");

    int riskScore = riskScore(tutor, identity, certificate);
    if (riskScore > APPROVAL_RISK_SCORE_LIMIT) reasons.add("RISK_SCORE_TOO_HIGH");

    boolean identityApproved = approved(identity);
    boolean certificateApproved = approved(certificate);
    boolean eligible = reasons.isEmpty()
        && identityApproved
        && certificateApproved
        && commitmentVersionValid
        && !duplicateDetected
        && riskScore <= APPROVAL_RISK_SCORE_LIMIT;

    Map<String, Object> checklist = new LinkedHashMap<>();
    checklist.put("profileSubmitted", !"draft".equals(rawStatus));
    checklist.put("identityApproved", identityApproved);
    checklist.put("certificateApproved", certificateApproved);
    checklist.put("commitmentSigned", commitmentSigned);
    checklist.put("commitmentVersionValid", commitmentVersionValid);
    checklist.put("duplicateDocumentDetected", duplicateDetected);
    checklist.put("riskScoreAcceptable", riskScore <= APPROVAL_RISK_SCORE_LIMIT);

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("tutorId", tutorId.toString());
    response.put("eligibleForApproval", eligible);
    response.put("profileStatus", profileStatus(rawStatus, eligible, reasons));
    response.put("rawProfileStatus", rawStatus);
    response.put("riskScore", riskScore);
    response.put("riskLevel", riskLevel(riskScore));
    response.put("riskBreakdown", riskBreakdown(tutor, identity, certificate));
    response.put("reasons", reasons.stream().distinct().toList());
    response.put("checklist", checklist);
    response.put("documents", List.of(documentResponse("IDENTITY", identity), documentResponse("CERTIFICATE", certificate)));
    response.put("commitment", commitmentResponse(commitment, commitmentVersionValid));
    return response;
  }

  private Map<String, Object> tutorRow(UUID tutorId) {
    return jdbc.query("select * from tutor_profiles where id = ?", rs -> {
      if (!rs.next()) throw new NotFoundException("Không tìm thấy hồ sơ gia sư.");
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", rs.getObject("id", UUID.class));
      row.put("user_id", rs.getObject("user_id", UUID.class));
      row.put("status", rs.getString("status"));
      row.put("headline", rs.getString("headline"));
      row.put("bio", rs.getString("bio"));
      row.put("university", rs.getString("university"));
      row.put("major", rs.getString("major"));
      row.put("student_code", safeString(rs, "student_code"));
      row.put("experience_years", rs.getInt("experience_years"));
      return row;
    }, tutorId);
  }

  private Map<String, Object> latestVerification(UUID userId, String type) {
    List<Map<String, Object>> rows = jdbc.query("""
        select uv.*, uf.sha256_hash, uf.file_size, uf.mime_type, uf.created_at file_uploaded_at,
               va.agreement_version, va.agreement_content_hash, va.signed_at agreement_signed_at
        from user_verifications uv
        left join uploaded_files uf on uf.id = coalesce(uv.document_file_id, uv.card_file_id, uv.selfie_file_id)
        left join verification_agreements va on va.verification_id = uv.id
        where uv.user_id = ? and uv.verification_type = ?
        order by uv.created_at desc
        limit 1
        """, this::verificationRow, userId, type);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private Map<String, Object> latestTutorCommitment(UUID tutorId) {
    List<Map<String, Object>> rows = jdbc.query("""
        select *
        from tutor_commitments
        where tutor_id = ? and status = 'signed'
        order by signed_at desc
        limit 1
        """, (rs, row) -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("commitment_version", rs.getString("commitment_version"));
      m.put("accepted_terms_hash", rs.getString("accepted_terms_hash"));
      m.put("full_name_at_signing", rs.getString("full_name_at_signing"));
      m.put("signed_at", ts(rs, "signed_at"));
      return m;
    }, tutorId);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private Map<String, Object> verificationRow(ResultSet rs, int row) throws SQLException {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", rs.getObject("id", UUID.class));
    m.put("verification_type", rs.getString("verification_type"));
    m.put("status", rs.getString("status"));
    m.put("school_name", rs.getString("school_name"));
    m.put("student_code", rs.getString("student_code"));
    m.put("full_name_input", rs.getString("full_name_input"));
    m.put("school_email", rs.getString("school_email"));
    m.put("duplicate_file", rs.getBoolean("duplicate_file"));
    m.put("risk_score", rs.getInt("risk_score"));
    m.put("reject_reason", rs.getString("reject_reason"));
    m.put("sha256_hash", rs.getString("sha256_hash"));
    m.put("file_size", rs.getObject("file_size"));
    m.put("mime_type", rs.getString("mime_type"));
    m.put("uploaded_at", ts(rs, "created_at"));
    m.put("file_uploaded_at", ts(rs, "file_uploaded_at"));
    m.put("reviewed_at", ts(rs, "reviewed_at"));
    m.put("agreement_version", rs.getString("agreement_version"));
    m.put("agreement_content_hash", rs.getString("agreement_content_hash"));
    m.put("agreement_signed_at", ts(rs, "agreement_signed_at"));
    return m;
  }

  private void applyDocumentReasons(List<String> reasons, Map<String, Object> doc, String prefix) {
    if (doc == null) {
      reasons.add(prefix + "_DOCUMENT_MISSING");
      return;
    }
    String status = String.valueOf(doc.get("status"));
    if ("pending_review".equals(status) || "draft".equals(status) || "uploaded".equals(status)) {
      reasons.add("DOCUMENT_PENDING_REVIEW");
      reasons.add(prefix + "_NOT_APPROVED");
    } else if ("rejected".equals(status) || "need_more_info".equals(status)) {
      reasons.add("DOCUMENT_REJECTED");
      reasons.add(prefix + "_NOT_APPROVED");
    } else if ("expired".equals(status)) {
      reasons.add("DOCUMENT_EXPIRED");
      reasons.add(prefix + "_NOT_APPROVED");
    } else if (!"approved".equals(status)) {
      reasons.add(prefix + "_NOT_APPROVED");
    }
  }

  private boolean approved(Map<String, Object> doc) {
    return doc != null && "approved".equals(doc.get("status"));
  }

  private boolean duplicate(Map<String, Object> doc) {
    return doc != null && Boolean.TRUE.equals(doc.get("duplicate_file"));
  }

  private int riskScore(Map<String, Object> tutor, Map<String, Object> identity, Map<String, Object> certificate) {
    int max = Math.max(risk(identity), risk(certificate));
    int extra = 0;
    if (profileCompleteness(tutor) < 60) extra += 15;
    extra += Math.min(20, rejectedHistory((UUID) tutor.get("user_id")) * 10);
    return Math.min(100, max + extra);
  }

  private int risk(Map<String, Object> doc) {
    if (doc == null || doc.get("risk_score") == null) return 0;
    return ((Number) doc.get("risk_score")).intValue();
  }

  private List<Map<String, Object>> riskBreakdown(Map<String, Object> tutor, Map<String, Object> identity, Map<String, Object> certificate) {
    List<Map<String, Object>> items = new ArrayList<>();
    if (duplicate(identity) || duplicate(certificate)) items.add(riskItem("DUPLICATE_DOCUMENT", 70));
    if (certificate != null && (blank(certificate.get("school_name")) || blank(certificate.get("student_code")))) {
      items.add(riskItem("CERTIFICATE_METADATA_MISSING", 15));
    }
    int rejectedHistory = rejectedHistory((UUID) tutor.get("user_id"));
    if (rejectedHistory > 0) items.add(riskItem("REJECTED_DOCUMENT_HISTORY", Math.min(20, rejectedHistory * 10)));
    if (profileCompleteness(tutor) < 60) items.add(riskItem("LOW_PROFILE_COMPLETENESS", 15));
    return items;
  }

  private Map<String, Object> riskItem(String reason, int score) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("reason", reason);
    item.put("score", score);
    return item;
  }

  private int rejectedHistory(UUID userId) {
    Integer count = jdbc.queryForObject("""
        select count(*)
        from user_verifications
        where user_id = ? and verification_type in ('tutor_identity','tutor_certificate') and status = 'rejected'
        """, Integer.class, userId);
    return count == null ? 0 : count;
  }

  private int profileCompleteness(Map<String, Object> tutor) {
    int total = 6;
    int filled = 0;
    if (!blank(tutor.get("headline"))) filled++;
    if (!blank(tutor.get("bio"))) filled++;
    if (!blank(tutor.get("university"))) filled++;
    if (!blank(tutor.get("major"))) filled++;
    if (!blank(tutor.get("student_code"))) filled++;
    if (((Number) tutor.get("experience_years")).intValue() > 0) filled++;
    return (int) Math.round((filled * 100.0) / total);
  }

  private Map<String, Object> documentResponse(String type, Map<String, Object> doc) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("type", type);
    response.put("status", documentStatus(doc));
    if (doc != null) {
      response.put("verificationId", doc.get("id").toString());
      response.put("uploadedAt", doc.get("uploaded_at"));
      response.put("reviewedAt", doc.get("reviewed_at"));
      response.put("duplicateDocumentDetected", duplicate(doc));
      response.put("riskScore", risk(doc));
      response.put("mimeType", doc.get("mime_type"));
      response.put("fileSize", doc.get("file_size"));
      response.put("rejectReason", doc.get("reject_reason"));
    }
    return response;
  }

  private String documentStatus(Map<String, Object> doc) {
    if (doc == null) return "MISSING";
    return switch (String.valueOf(doc.get("status"))) {
      case "draft", "uploaded" -> "UPLOADED";
      case "pending_review" -> "PENDING_REVIEW";
      case "approved" -> "APPROVED";
      case "expired" -> "EXPIRED";
      default -> "REJECTED";
    };
  }

  private Map<String, Object> commitmentResponse(Map<String, Object> commitment, boolean versionValid) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("signed", commitment != null);
    response.put("versionValid", versionValid);
    response.put("requiredVersion", VerificationTerms.VERSION);
    if (commitment != null) {
      response.put("version", commitment.get("commitment_version"));
      response.put("signedAt", commitment.get("signed_at"));
      response.put("fullNameAtSigning", commitment.get("full_name_at_signing"));
    }
    return response;
  }

  private String profileStatus(String rawStatus, boolean eligible, List<String> reasons) {
    if ("draft".equals(rawStatus)) return "DRAFT";
    if ("rejected".equals(rawStatus)) return "REJECTED";
    if ("suspended".equals(rawStatus)) return "SUSPENDED";
    if ("approved".equals(rawStatus) && eligible) return "APPROVED";
    if ("approved".equals(rawStatus)) return "PENDING_VERIFICATION";
    if (reasons.contains("NEEDS_MORE_DOCUMENTS") || reasons.contains("DOCUMENT_REJECTED")) return "NEEDS_MORE_DOCUMENTS";
    if (eligible) return "VERIFIED";
    return "PENDING_VERIFICATION";
  }

  private String riskLevel(int score) {
    if (score <= 30) return "LOW";
    if (score <= 60) return "MEDIUM";
    return "HIGH";
  }

  private boolean blank(Object value) {
    return value == null || value.toString().isBlank();
  }

  private String safeString(ResultSet rs, String column) {
    try {
      return rs.getString(column);
    } catch (Exception ex) {
      return null;
    }
  }

  private String ts(ResultSet rs, String column) throws SQLException {
    Object value = rs.getObject(column);
    return value == null ? null : rs.getObject(column, OffsetDateTime.class).toString();
  }
}
