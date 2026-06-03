package com.example.tutorplatform.policy;

import com.example.tutorplatform.db.DbService;
import com.example.tutorplatform.security.PermissionService;
import com.example.tutorplatform.security.SecurityUtils;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class FilePolicy {
  private final DbService db;
  private final PermissionService permissions;
  private final JdbcTemplate jdbc;

  public FilePolicy(DbService db, PermissionService permissions) {
    this.db = db;
    this.permissions = permissions;
    this.jdbc = db.jdbc();
  }

  public boolean canView(Map<String, Object> file) {
    if ("public".equals(file.get("visibility"))) {
      return true;
    }
    return SecurityUtils.currentUserId().map(userId -> {
      if (canViewAsPrivilegedAdmin(file)) return true;
      Object ownerId = file.get("ownerId");
      if (ownerId != null && userId.equals(UUID.fromString(ownerId.toString()))) return true;
      UUID fileId = UUID.fromString(file.get("id").toString());
      if (exists("""
          select count(*)
          from tutor_documents td
          join tutor_profiles tp on tp.id = td.tutor_id
          where td.file_id = ? and tp.user_id = ?
          """, fileId, userId)) {
        return true;
      }
      String entityType = file.get("entityType") == null ? null : file.get("entityType").toString();
      UUID entityId = uuidOrNull(file.get("entityId"));
      if (entityType == null || entityId == null) return false;
      return switch (entityType) {
        case "verification" -> exists("select count(*) from user_verifications where id = ? and user_id = ?", entityId, userId);
        case "booking" -> exists("""
            select count(*)
            from trial_bookings tb
            left join tutor_profiles tp on tp.id = tb.tutor_id
            where tb.id = ? and (tb.student_id = ? or tp.user_id = ?)
            """, entityId, userId, userId);
        case "class" -> exists("""
            select count(*)
            from tutoring_classes tc
            left join tutor_profiles tp on tp.id = tc.tutor_id
            where tc.id = ? and (tc.student_id = ? or tp.user_id = ?)
            """, entityId, userId, userId);
        case "session" -> exists("""
            select count(*)
            from class_sessions cs
            left join tutor_profiles tp on tp.id = cs.tutor_id
            where cs.id = ? and (cs.student_id = ? or tp.user_id = ?)
            """, entityId, userId, userId);
        case "payment" -> exists("""
            select count(*)
            from payments p
            left join tutor_profiles tp on tp.id = p.tutor_id
            where p.id = ? and (p.user_id = ? or tp.user_id = ?)
            """, entityId, userId, userId);
        default -> false;
      };
    }).orElse(false);
  }

  private boolean canViewAsPrivilegedAdmin(Map<String, Object> file) {
    if (permissions.has("files.view_private")) return true;
    String entityType = text(file.get("entityType"));
    String purpose = text(file.get("purpose"));
    if ("verification".equals(entityType) || isVerificationPurpose(purpose)) {
      return permissions.has("files.view_verification");
    }
    if ("tutor_document".equals(entityType) || "tutor_document".equals(purpose)) {
      return permissions.has("files.view_tutor_document");
    }
    if ("payment".equals(entityType) || "invoice".equals(purpose) || "receipt".equals(purpose)) {
      return permissions.has("payments.read");
    }
    return false;
  }

  private boolean isVerificationPurpose(String purpose) {
    return List.of("student_card", "student_selfie", "tutor_identity", "tutor_certificate", "contract")
        .contains(purpose);
  }

  private String text(Object value) {
    return value == null ? "" : value.toString();
  }

  private boolean exists(String sql, Object... args) {
    Integer count = jdbc.queryForObject(sql, Integer.class, args);
    return count != null && count > 0;
  }

  private UUID uuidOrNull(Object value) {
    if (value == null || value.toString().isBlank()) return null;
    try {
      return UUID.fromString(value.toString());
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }
}
