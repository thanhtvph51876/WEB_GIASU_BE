package com.example.tutorplatform.file;

import com.example.tutorplatform.common.BusinessException;
import com.example.tutorplatform.config.AppProperties;
import com.example.tutorplatform.db.DbService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FileStorageService {
  private static final long MAX_UPLOAD_BYTES = 10L * 1024L * 1024L;
  private static final List<String> ALLOWED_MIME_TYPES = List.of(
      "image/jpeg", "image/png", "image/webp", "application/pdf"
  );
  private static final List<String> SENSITIVE_PURPOSES = List.of(
      "student_card", "student_selfie", "tutor_identity", "tutor_certificate", "tutor_document",
      "contract", "invoice", "receipt", "chat_private"
  );

  private final JdbcTemplate jdbc;
  private final Path uploadRoot;

  public FileStorageService(DbService db, AppProperties properties) {
    this.jdbc = db.jdbc();
    this.uploadRoot = Path.of(properties.upload().dir()).toAbsolutePath().normalize();
  }

  public StoredFile store(MultipartFile file, UUID ownerId, String visibility, String purpose) throws IOException {
    if (file == null || file.isEmpty()) {
      throw new BusinessException("EMPTY_FILE", "File rỗng.");
    }
    if (file.getSize() > MAX_UPLOAD_BYTES) {
      throw new BusinessException("FILE_TOO_LARGE", "File tối đa 10MB.");
    }

    byte[] bytes = file.getBytes();
    String originalName = sanitizeOriginalName(file.getOriginalFilename());
    String extension = extensionFor(originalName);
    String mimeType = normalizeMime(file.getContentType(), extension);
    validateFileSignature(bytes, mimeType, extension);

    String normalizedVisibility = "public".equalsIgnoreCase(visibility) ? "public" : "private";
    String normalizedPurpose = normalizePurpose(purpose, normalizedVisibility);
    if (SENSITIVE_PURPOSES.contains(normalizedPurpose)) {
      normalizedVisibility = "private";
    }
    String sha256 = sha256(bytes);
    boolean duplicate = duplicateSensitiveFile(sha256, normalizedPurpose, ownerId);
    int riskScore = duplicate ? 70 : 0;

    String storedName = UUID.randomUUID() + extension;
    Path dir = uploadRoot.resolve(normalizedVisibility).normalize();
    if (!dir.startsWith(uploadRoot)) {
      throw new BusinessException("INVALID_UPLOAD_PATH", "Đường dẫn upload không hợp lệ.");
    }
    Files.createDirectories(dir);
    Files.write(dir.resolve(storedName), bytes, StandardOpenOption.CREATE_NEW);

    String storagePath = normalizedVisibility + "/" + storedName;
    UUID id = jdbc.queryForObject("""
        insert into uploaded_files(owner_id, file_name, original_file_name, file_url, file_size, mime_type,
          storage_path, visibility, sha256_hash, purpose, risk_score)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        returning id
        """, UUID.class, ownerId, storedName, originalName, "/api/v1/files/pending", bytes.length,
        mimeType, storagePath, normalizedVisibility, sha256, normalizedPurpose, riskScore);
    String url = "/api/v1/files/" + id;
    jdbc.update("update uploaded_files set file_url = ?, updated_at = now() where id = ?", url, id);

    return new StoredFile(id, storedName, originalName, url, bytes.length, mimeType, normalizedVisibility,
        normalizedPurpose, sha256, duplicate, riskScore);
  }

  public void attachEntity(UUID fileId, String entityType, UUID entityId, String visibility) {
    String normalizedVisibility = "public".equalsIgnoreCase(visibility) ? "public" : "private";
    jdbc.update("""
        update uploaded_files
        set entity_type = ?, entity_id = ?, visibility = ?, updated_at = now()
        where id = ?
        """, entityType, entityId, normalizedVisibility, fileId);
  }

  public Map<String, Object> response(StoredFile file) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", file.id().toString());
    result.put("fileId", file.id().toString());
    result.put("fileName", file.fileName());
    result.put("originalFileName", file.originalFileName());
    result.put("fileUrl", file.fileUrl());
    result.put("fileSize", file.fileSize());
    result.put("mimeType", file.mimeType());
    result.put("visibility", file.visibility());
    result.put("purpose", file.purpose());
    result.put("sha256Hash", file.sha256Hash());
    result.put("duplicateFile", file.duplicateFile());
    result.put("riskScore", file.riskScore());
    return result;
  }

  private boolean duplicateSensitiveFile(String sha256, String purpose, UUID ownerId) {
    if (sha256 == null || sha256.isBlank()) return false;
    boolean sensitive = SENSITIVE_PURPOSES.contains(purpose);
    if (!sensitive) return false;
    Integer count = jdbc.queryForObject("""
        select count(*) from uploaded_files
        where sha256_hash = ?
          and owner_id <> ?
          and purpose in ('student_card','student_selfie','tutor_identity','tutor_certificate','tutor_document')
        """, Integer.class, sha256, ownerId);
    return count != null && count > 0;
  }

  private void validateFileSignature(byte[] bytes, String mimeType, String extension) {
    if (!ALLOWED_MIME_TYPES.contains(mimeType)) {
      throw new BusinessException("INVALID_FILE_TYPE", "Chỉ hỗ trợ JPG, PNG, WEBP hoặc PDF.");
    }
    if (!List.of(".jpg", ".jpeg", ".png", ".webp", ".pdf").contains(extension)) {
      throw new BusinessException("INVALID_FILE_EXTENSION", "Đuôi file không được hỗ trợ.");
    }
    boolean valid = switch (mimeType) {
      case "application/pdf" -> startsWith(bytes, "%PDF".getBytes(StandardCharsets.US_ASCII));
      case "image/jpeg" -> bytes.length >= 3
          && (bytes[0] & 0xff) == 0xff
          && (bytes[1] & 0xff) == 0xd8
          && (bytes[2] & 0xff) == 0xff;
      case "image/png" -> bytes.length >= 8
          && (bytes[0] & 0xff) == 0x89
          && bytes[1] == 0x50
          && bytes[2] == 0x4e
          && bytes[3] == 0x47
          && bytes[4] == 0x0d
          && bytes[5] == 0x0a
          && bytes[6] == 0x1a
          && bytes[7] == 0x0a;
      case "image/webp" -> bytes.length >= 12
          && startsWith(bytes, "RIFF".getBytes(StandardCharsets.US_ASCII))
          && bytes[8] == 0x57
          && bytes[9] == 0x45
          && bytes[10] == 0x42
          && bytes[11] == 0x50;
      default -> false;
    };
    if (!valid) {
      throw new BusinessException("INVALID_FILE_SIGNATURE", "Nội dung file không khớp loại file khai báo.");
    }
  }

  private boolean startsWith(byte[] bytes, byte[] prefix) {
    if (bytes.length < prefix.length) return false;
    for (int i = 0; i < prefix.length; i++) {
      if (bytes[i] != prefix[i]) return false;
    }
    return true;
  }

  private String sanitizeOriginalName(String name) {
    String original = name == null || name.isBlank() ? "upload.bin" : name;
    return original.replaceAll("[^a-zA-Z0-9._-]", "_");
  }

  private String extensionFor(String originalName) {
    String lower = originalName.toLowerCase();
    int dot = lower.lastIndexOf('.');
    return dot >= 0 && dot < lower.length() - 1 ? lower.substring(dot) : "";
  }

  private String normalizeMime(String contentType, String extension) {
    String declared = contentType == null ? "" : contentType.toLowerCase();
    if (ALLOWED_MIME_TYPES.contains(declared)) return declared;
    return switch (extension) {
      case ".jpg", ".jpeg" -> "image/jpeg";
      case ".png" -> "image/png";
      case ".webp" -> "image/webp";
      case ".pdf" -> "application/pdf";
      default -> declared;
    };
  }

  private String normalizePurpose(String purpose, String visibility) {
    if (purpose == null || purpose.isBlank()) {
      return "public".equals(visibility) ? "general_public" : "general_private";
    }
    return purpose.trim().toLowerCase().replace("-", "_");
  }

  private String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception ex) {
      throw new IllegalStateException("Cannot hash upload", ex);
    }
  }

  public record StoredFile(
      UUID id,
      String fileName,
      String originalFileName,
      String fileUrl,
      long fileSize,
      String mimeType,
      String visibility,
      String purpose,
      String sha256Hash,
      boolean duplicateFile,
      int riskScore
  ) {}
}
