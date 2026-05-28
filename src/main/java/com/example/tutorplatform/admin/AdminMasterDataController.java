package com.example.tutorplatform.admin;

import com.example.tutorplatform.common.ApiResponse;
import com.example.tutorplatform.common.BusinessException;
import com.example.tutorplatform.db.DbService;
import com.example.tutorplatform.masterdata.MasterDataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminMasterDataController {
  private final DbService db;
  private final JdbcTemplate jdbc;
  private final MasterDataService masterData;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public AdminMasterDataController(DbService db, MasterDataService masterData) {
    this.db = db;
    this.jdbc = db.jdbc();
    this.masterData = masterData;
  }

  @PostMapping("/master-data/subjects")
  @Transactional
  public ApiResponse<Map<String, Object>> createSubject(@RequestBody Map<String, Object> body) {
    String name = required(body, "name");
    String code = value(body, "code", slug(name).toUpperCase(Locale.ROOT).replace('-', '_'));
    UUID categoryId = uuid(body.get("categoryId"));
    UUID id = jdbc.queryForObject("""
        insert into subjects(category_id, code, name, slug, normalized_name, description, is_academic_subject, is_language, is_test_prep, is_skill, is_active)
        values (?, ?, ?, ?, lower(unaccent(?)), ?, ?, ?, ?, ?, true)
        returning id
        """, UUID.class, categoryId, code, name, slug(name), name, value(body, "description", ""),
        bool(body, "isAcademicSubject", true), bool(body, "isLanguage", false), bool(body, "isTestPrep", false), bool(body, "isSkill", false));
    db.auditCurrent("admin.master_data.subject.create", "subject", id, "Admin tạo môn học.");
    return ApiResponse.ok(masterData.subjects(null, code, false).stream().filter(item -> code.equals(item.get("code"))).findFirst().orElse(Map.of("id", id.toString())));
  }

  @PatchMapping("/master-data/subjects/{id}")
  @Transactional
  public ApiResponse<Map<String, Object>> updateSubject(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
    jdbc.update("""
        update subjects set
          category_id = coalesce(?, category_id),
          code = coalesce(?, code),
          name = coalesce(?, name),
          normalized_name = coalesce(lower(unaccent(?)), normalized_name),
          description = coalesce(?, description),
          is_academic_subject = coalesce(?, is_academic_subject),
          is_language = coalesce(?, is_language),
          is_test_prep = coalesce(?, is_test_prep),
          is_skill = coalesce(?, is_skill),
          is_active = coalesce(?, is_active),
          updated_at = now()
        where id = ?
        """, uuid(body.get("categoryId")), stringOrNull(body.get("code")), stringOrNull(body.get("name")), stringOrNull(body.get("name")),
        stringOrNull(body.get("description")), nullableBool(body.get("isAcademicSubject")), nullableBool(body.get("isLanguage")),
        nullableBool(body.get("isTestPrep")), nullableBool(body.get("isSkill")), nullableBool(body.get("isActive")), id);
    db.auditCurrent("admin.master_data.subject.update", "subject", id, "Admin cập nhật môn học.");
    return ApiResponse.ok(masterData.subjects(null, null, false).stream().filter(item -> id.toString().equals(item.get("id"))).findFirst().orElseThrow());
  }

  @DeleteMapping("/master-data/subjects/{id}")
  @Transactional
  public ApiResponse<Map<String, Object>> deleteSubject(@PathVariable UUID id) {
    jdbc.update("update subjects set is_active = false, updated_at = now() where id = ?", id);
    db.auditCurrent("admin.master_data.subject.disable", "subject", id, "Admin ẩn môn học.");
    return ApiResponse.ok(Map.of("id", id.toString(), "isActive", false));
  }

  @PostMapping("/master-data/locations")
  @Transactional
  public ApiResponse<Map<String, Object>> createLocation(@RequestBody Map<String, Object> body) {
    String name = required(body, "name");
    String code = value(body, "code", slug(name).toUpperCase(Locale.ROOT));
    String type = value(body, "type", "PROVINCE").toUpperCase(Locale.ROOT);
    UUID parentId = uuid(body.get("parentId"));
    UUID id = jdbc.queryForObject("""
        insert into locations(code, name, type, parent_id, full_path, source, version, is_active)
        values (?, ?, ?, ?, ?, 'admin', 'manual', true)
        returning id
        """, UUID.class, code, name, type, parentId, value(body, "fullPath", name));
    db.auditCurrent("admin.master_data.location.create", "location", id, "Admin tạo địa điểm.");
    return ApiResponse.ok(masterData.locations(null, null, false).stream().filter(item -> id.toString().equals(item.get("id"))).findFirst().orElse(Map.of("id", id.toString())));
  }

  @PatchMapping("/master-data/locations/{id}")
  @Transactional
  public ApiResponse<Map<String, Object>> updateLocation(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
    jdbc.update("""
        update locations set name = coalesce(?, name), type = coalesce(?, type), parent_id = coalesce(?, parent_id),
          full_path = coalesce(?, full_path), is_active = coalesce(?, is_active), updated_at = now()
        where id = ?
        """, stringOrNull(body.get("name")), stringOrNull(body.get("type")), uuid(body.get("parentId")),
        stringOrNull(body.get("fullPath")), nullableBool(body.get("isActive")), id);
    db.auditCurrent("admin.master_data.location.update", "location", id, "Admin cập nhật địa điểm.");
    return ApiResponse.ok(masterData.locations(null, null, false).stream().filter(item -> id.toString().equals(item.get("id"))).findFirst().orElseThrow());
  }

  @DeleteMapping("/master-data/locations/{id}")
  @Transactional
  public ApiResponse<Map<String, Object>> deleteLocation(@PathVariable UUID id) {
    jdbc.update("update locations set is_active = false, updated_at = now() where id = ?", id);
    db.auditCurrent("admin.master_data.location.disable", "location", id, "Admin ẩn địa điểm.");
    return ApiResponse.ok(Map.of("id", id.toString(), "isActive", false));
  }

  @PostMapping("/master-data/certificates")
  @Transactional
  public ApiResponse<Map<String, Object>> createCertificate(@RequestBody Map<String, Object> body) {
    String name = required(body, "name");
    String code = value(body, "code", slug(name).toUpperCase(Locale.ROOT));
    UUID id = jdbc.queryForObject("""
        insert into certificates(code, name, language_id, description, is_active)
        values (?, ?, ?, ?, true) returning id
        """, UUID.class, code, name, uuid(body.get("languageId")), value(body, "description", ""));
    db.auditCurrent("admin.master_data.certificate.create", "certificate", id, "Admin tạo chứng chỉ.");
    return ApiResponse.ok(masterData.certificates(false).stream().filter(item -> id.toString().equals(item.get("id"))).findFirst().orElse(Map.of("id", id.toString())));
  }

  @PatchMapping("/master-data/certificates/{id}")
  @Transactional
  public ApiResponse<Map<String, Object>> updateCertificate(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
    jdbc.update("""
        update certificates set name = coalesce(?, name), language_id = coalesce(?, language_id), description = coalesce(?, description),
          is_active = coalesce(?, is_active), updated_at = now()
        where id = ?
        """, stringOrNull(body.get("name")), uuid(body.get("languageId")), stringOrNull(body.get("description")), nullableBool(body.get("isActive")), id);
    db.auditCurrent("admin.master_data.certificate.update", "certificate", id, "Admin cập nhật chứng chỉ.");
    return ApiResponse.ok(masterData.certificates(false).stream().filter(item -> id.toString().equals(item.get("id"))).findFirst().orElseThrow());
  }

  @DeleteMapping("/master-data/certificates/{id}")
  @Transactional
  public ApiResponse<Map<String, Object>> deleteCertificate(@PathVariable UUID id) {
    jdbc.update("update certificates set is_active = false, updated_at = now() where id = ?", id);
    db.auditCurrent("admin.master_data.certificate.disable", "certificate", id, "Admin ẩn chứng chỉ.");
    return ApiResponse.ok(Map.of("id", id.toString(), "isActive", false));
  }

  @GetMapping("/system-settings")
  public ApiResponse<List<Map<String, Object>>> settings() {
    return ApiResponse.ok(jdbc.query("select id, key, case when is_sensitive then null else value end value, value_type, description, is_sensitive, updated_by, created_at, updated_at from system_settings order by key", (rs, row) -> {
      Map<String, Object> m = new java.util.LinkedHashMap<>();
      m.put("id", rs.getObject("id").toString());
      m.put("key", rs.getString("key"));
      m.put("value", rs.getObject("value"));
      m.put("valueType", rs.getString("value_type"));
      m.put("description", rs.getString("description"));
      m.put("isSensitive", rs.getBoolean("is_sensitive"));
      m.put("updatedBy", rs.getObject("updated_by") == null ? null : rs.getObject("updated_by").toString());
      m.put("createdAt", rs.getObject("created_at").toString());
      m.put("updatedAt", rs.getObject("updated_at").toString());
      return m;
    }));
  }

  @PostMapping("/system-settings")
  @Transactional
  public ApiResponse<Map<String, Object>> createSetting(@RequestBody Map<String, Object> body) throws Exception {
    String key = required(body, "key");
    jdbc.update("""
        insert into system_settings(key, value, value_type, description, is_sensitive, updated_by)
        values (?, ?::jsonb, ?, ?, ?, ?)
        on conflict(key) do update set value = excluded.value, value_type = excluded.value_type,
          description = excluded.description, is_sensitive = excluded.is_sensitive, updated_by = excluded.updated_by, updated_at = now()
        """, key, json(body.get("value")), value(body, "valueType", "json"), stringOrNull(body.get("description")), bool(body, "isSensitive", false), db.currentUserIdOrThrow());
    db.auditCurrent("admin.system_setting.upsert", "systemSetting", null, "Admin cập nhật system setting " + key + ".");
    return ApiResponse.ok(Map.of("key", key));
  }

  @PatchMapping("/system-settings/{key}")
  @Transactional
  public ApiResponse<Map<String, Object>> updateSetting(@PathVariable String key, @RequestBody Map<String, Object> body) throws Exception {
    jdbc.update("""
        update system_settings set value = coalesce(?::jsonb, value), value_type = coalesce(?, value_type),
          description = coalesce(?, description), is_sensitive = coalesce(?, is_sensitive), updated_by = ?, updated_at = now()
        where key = ?
        """, body.containsKey("value") ? json(body.get("value")) : null, stringOrNull(body.get("valueType")), stringOrNull(body.get("description")),
        nullableBool(body.get("isSensitive")), db.currentUserIdOrThrow(), key);
    db.auditCurrent("admin.system_setting.update", "systemSetting", null, "Admin cập nhật system setting " + key + ".");
    return ApiResponse.ok(Map.of("key", key));
  }

  private String required(Map<String, Object> body, String key) {
    String value = stringOrNull(body.get(key));
    if (value == null || value.isBlank()) throw new BusinessException("FIELD_REQUIRED", "Thiếu trường " + key + ".");
    return value;
  }

  private String value(Map<String, Object> body, String key, String fallback) {
    String value = stringOrNull(body.get(key));
    return value == null || value.isBlank() ? fallback : value;
  }

  private String stringOrNull(Object value) {
    return value == null || value.toString().isBlank() ? null : value.toString();
  }

  private UUID uuid(Object value) {
    if (value == null || value.toString().isBlank()) return null;
    return UUID.fromString(value.toString());
  }

  private boolean bool(Map<String, Object> body, String key, boolean fallback) {
    Object value = body.get(key);
    return value == null ? fallback : Boolean.parseBoolean(value.toString());
  }

  private Boolean nullableBool(Object value) {
    return value == null ? null : Boolean.parseBoolean(value.toString());
  }

  private String slug(String value) {
    String normalized = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    return normalized.toLowerCase(Locale.ROOT).replace("đ", "d").replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
  }

  private String json(Object value) throws Exception {
    if (value == null) return "null";
    return objectMapper.writeValueAsString(value);
  }
}
