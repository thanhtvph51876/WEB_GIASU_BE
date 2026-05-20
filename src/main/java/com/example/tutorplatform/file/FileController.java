package com.example.tutorplatform.file;

import com.example.tutorplatform.common.BusinessException;
import com.example.tutorplatform.common.ForbiddenException;
import com.example.tutorplatform.db.DbService;
import com.example.tutorplatform.policy.FilePolicy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/files")
public class FileController {
  private final DbService db;
  private final FilePolicy filePolicy;
  private final Path uploadRoot;

  public FileController(DbService db, FilePolicy filePolicy, com.example.tutorplatform.config.AppProperties properties) {
    this.db = db;
    this.filePolicy = filePolicy;
    this.uploadRoot = Path.of(properties.upload().dir()).toAbsolutePath().normalize();
  }

  @GetMapping("/{fileId}")
  public ResponseEntity<InputStreamResource> download(@PathVariable UUID fileId) throws Exception {
    Map<String, Object> file = db.required("select * from uploaded_files where id = ?", mapper(), fileId);
    if (!filePolicy.canView(file)) {
      if (com.example.tutorplatform.security.SecurityUtils.currentUserId().isEmpty()) {
        throw new BusinessException("UNAUTHORIZED", "Bạn cần đăng nhập để xem file này.", HttpStatus.UNAUTHORIZED);
      }
      throw new ForbiddenException("Bạn không có quyền xem file này.");
    }

    Path path = uploadRoot.resolve(file.get("storagePath").toString()).normalize();
    if (!path.startsWith(uploadRoot) || !Files.exists(path) || !Files.isRegularFile(path)) {
      throw new BusinessException("FILE_NOT_FOUND", "Không tìm thấy file.", HttpStatus.NOT_FOUND);
    }

    String original = file.get("originalFileName").toString();
    String encoded = URLEncoder.encode(original, StandardCharsets.UTF_8).replace("+", "%20");
    String mimeType = file.get("mimeType") == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : file.get("mimeType").toString();
    MediaType mediaType = MediaType.parseMediaType(mimeType);
    return ResponseEntity.ok()
        .contentType(mediaType)
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded)
        .body(new InputStreamResource(Files.newInputStream(path)));
  }

  private RowMapper<Map<String, Object>> mapper() {
    return (rs, row) -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", rs.getObject("id", UUID.class).toString());
      Object ownerId = rs.getObject("owner_id");
      m.put("ownerId", ownerId == null ? null : ownerId.toString());
      m.put("fileName", rs.getString("file_name"));
      m.put("originalFileName", rs.getString("original_file_name"));
      m.put("mimeType", rs.getString("mime_type"));
      m.put("fileSize", rs.getLong("file_size"));
      m.put("storagePath", rs.getString("storage_path"));
      m.put("visibility", rs.getString("visibility"));
      m.put("entityType", rs.getString("entity_type"));
      Object entityId = rs.getObject("entity_id");
      m.put("entityId", entityId == null ? null : entityId.toString());
      m.put("createdAt", rs.getObject("created_at", OffsetDateTime.class).toString());
      return m;
    };
  }
}
