package com.example.tutorplatform.policy;

import com.example.tutorplatform.db.DbService;
import com.example.tutorplatform.security.SecurityUtils;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class FilePolicy {
  private final DbService db;
  private final JdbcTemplate jdbc;

  public FilePolicy(DbService db) {
    this.db = db;
    this.jdbc = db.jdbc();
  }

  public boolean canView(Map<String, Object> file) {
    if ("public".equals(file.get("visibility"))) {
      return true;
    }
    return SecurityUtils.currentUserId().map(userId -> {
      if (db.isAdmin()) return true;
      Object ownerId = file.get("ownerId");
      if (ownerId != null && userId.equals(UUID.fromString(ownerId.toString()))) return true;
      UUID fileId = UUID.fromString(file.get("id").toString());
      Integer tutorOwnedDocument = jdbc.queryForObject("""
          select count(*)
          from tutor_documents td
          join tutor_profiles tp on tp.id = td.tutor_id
          where td.file_id = ? and tp.user_id = ?
          """, Integer.class, fileId, userId);
      return tutorOwnedDocument != null && tutorOwnedDocument > 0;
    }).orElse(false);
  }
}
