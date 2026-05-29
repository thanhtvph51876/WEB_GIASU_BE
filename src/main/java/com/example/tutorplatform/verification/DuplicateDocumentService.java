package com.example.tutorplatform.verification;

import com.example.tutorplatform.db.DbService;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DuplicateDocumentService {
  private final JdbcTemplate jdbc;

  public DuplicateDocumentService(DbService db) {
    this.jdbc = db.jdbc();
  }

  public boolean hasDuplicate(String sha256Hash) {
    if (sha256Hash == null || sha256Hash.isBlank()) return false;
    Integer count = jdbc.queryForObject("""
        select count(*) from uploaded_files
        where sha256_hash = ?
          and purpose in ('student_card','student_selfie','tutor_identity','tutor_certificate','tutor_document')
        """, Integer.class, sha256Hash);
    return count != null && count > 1;
  }

  public boolean hasDuplicateForDifferentOwner(String sha256Hash, UUID ownerId) {
    if (sha256Hash == null || sha256Hash.isBlank()) return false;
    Integer count = jdbc.queryForObject("""
        select count(*) from uploaded_files
        where sha256_hash = ?
          and owner_id <> ?
          and purpose in ('student_card','student_selfie','tutor_identity','tutor_certificate','tutor_document')
        """, Integer.class, sha256Hash, ownerId);
    return count != null && count > 0;
  }
}
