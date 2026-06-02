package com.example.tutorplatform.admin;

import com.example.tutorplatform.common.ApiResponse;
import com.example.tutorplatform.common.BusinessException;
import com.example.tutorplatform.db.DbService;
import com.example.tutorplatform.masterdata.MasterDataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.springframework.web.bind.annotation.RequestParam;
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

  @GetMapping("/master-data/subjects")
  public ApiResponse<List<Map<String, Object>>> subjects(@RequestParam(required = false) String categoryId, @RequestParam(required = false) String q, @RequestParam(required = false) Boolean activeOnly) {
    return ApiResponse.ok(masterData.subjects(masterData.uuid(categoryId), q, adminActiveOnly(activeOnly)));
  }

  @GetMapping("/master-data/locations")
  public ApiResponse<List<Map<String, Object>>> locations(@RequestParam(required = false) String type, @RequestParam(required = false) String parentId, @RequestParam(required = false) Boolean activeOnly) {
    return ApiResponse.ok(masterData.locations(type, masterData.uuid(parentId), adminActiveOnly(activeOnly)));
  }

  @GetMapping("/master-data/certificates")
  public ApiResponse<List<Map<String, Object>>> certificates(@RequestParam(required = false) Boolean activeOnly) {
    return ApiResponse.ok(masterData.certificates(adminActiveOnly(activeOnly)));
  }

  @GetMapping("/master-data/{kind}/{id}/usage")
  public ApiResponse<Map<String, Object>> masterDataUsage(@PathVariable String kind, @PathVariable UUID id) {
    return ApiResponse.ok(usage(kind, id));
  }

  @PostMapping("/master-data/{kind}/bulk-status")
  @Transactional
  public ApiResponse<Map<String, Object>> bulkStatus(@PathVariable String kind, @RequestBody Map<String, Object> body) {
    String table = tableForKind(kind);
    String entityType = entityForKind(kind);
    Boolean isActive = nullableBool(body.get("isActive"));
    if (isActive == null) throw new BusinessException("FIELD_REQUIRED", "Thiếu trường isActive.");
    Object rawIds = body.get("ids");
    if (!(rawIds instanceof List<?> rawList) || rawList.isEmpty()) {
      throw new BusinessException("FIELD_REQUIRED", "Thiếu danh sách ids.");
    }

    List<UUID> ids = rawList.stream().map(this::uuid).toList();
    int affected = 0;
    for (UUID id : ids) {
      affected += jdbc.update("update " + table + " set is_active = ?, updated_at = now() where id = ?", isActive, id);
    }
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("kind", kind);
    metadata.put("ids", ids);
    metadata.put("isActive", isActive);
    metadata.put("affected", affected);
    db.auditCurrent(
        isActive ? "admin.master_data.bulk_enable" : "admin.master_data.bulk_disable",
        entityType,
        null,
        "Admin cập nhật trạng thái hàng loạt " + kind + ".",
        metadata
    );
    return ApiResponse.ok(Map.of("kind", kind, "ids", ids, "isActive", isActive, "affected", affected));
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
    Map<String, Object> created = masterData.subjects(null, code, false).stream().filter(item -> code.equals(item.get("code"))).findFirst().orElse(Map.of("id", id.toString()));
    db.auditCurrent("admin.master_data.subject.create", "subject", id, "Admin tạo môn học.",
        Map.of("after", created));
    return ApiResponse.ok(created);
  }

  @PatchMapping("/master-data/subjects/{id}")
  @Transactional
  public ApiResponse<Map<String, Object>> updateSubject(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
    Map<String, Object> before = currentMasterData("subjects", id);
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
    Map<String, Object> after = currentMasterData("subjects", id);
    db.auditCurrent("admin.master_data.subject.update", "subject", id, "Admin cập nhật môn học.",
        Map.of("before", before, "after", after));
    return ApiResponse.ok(after);
  }

  @DeleteMapping("/master-data/subjects/{id}")
  @Transactional
  public ApiResponse<Map<String, Object>> deleteSubject(@PathVariable UUID id) {
    Map<String, Object> before = currentMasterData("subjects", id);
    jdbc.update("update subjects set is_active = false, updated_at = now() where id = ?", id);
    Map<String, Object> after = currentMasterData("subjects", id);
    db.auditCurrent("admin.master_data.subject.disable", "subject", id, "Admin ẩn môn học.",
        Map.of("before", before, "after", after, "usage", usage("subjects", id)));
    return ApiResponse.ok(after);
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
    Map<String, Object> created = currentMasterData("locations", id);
    db.auditCurrent("admin.master_data.location.create", "location", id, "Admin tạo địa điểm.",
        Map.of("after", created));
    return ApiResponse.ok(created);
  }

  @PatchMapping("/master-data/locations/{id}")
  @Transactional
  public ApiResponse<Map<String, Object>> updateLocation(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
    Map<String, Object> before = currentMasterData("locations", id);
    jdbc.update("""
        update locations set name = coalesce(?, name), type = coalesce(?, type), parent_id = coalesce(?, parent_id),
          full_path = coalesce(?, full_path), is_active = coalesce(?, is_active), updated_at = now()
        where id = ?
        """, stringOrNull(body.get("name")), stringOrNull(body.get("type")), uuid(body.get("parentId")),
        stringOrNull(body.get("fullPath")), nullableBool(body.get("isActive")), id);
    Map<String, Object> after = currentMasterData("locations", id);
    db.auditCurrent("admin.master_data.location.update", "location", id, "Admin cập nhật địa điểm.",
        Map.of("before", before, "after", after));
    return ApiResponse.ok(after);
  }

  @DeleteMapping("/master-data/locations/{id}")
  @Transactional
  public ApiResponse<Map<String, Object>> deleteLocation(@PathVariable UUID id) {
    Map<String, Object> before = currentMasterData("locations", id);
    jdbc.update("update locations set is_active = false, updated_at = now() where id = ?", id);
    Map<String, Object> after = currentMasterData("locations", id);
    db.auditCurrent("admin.master_data.location.disable", "location", id, "Admin ẩn địa điểm.",
        Map.of("before", before, "after", after, "usage", usage("locations", id)));
    return ApiResponse.ok(after);
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
    Map<String, Object> created = currentMasterData("certificates", id);
    db.auditCurrent("admin.master_data.certificate.create", "certificate", id, "Admin tạo chứng chỉ.",
        Map.of("after", created));
    return ApiResponse.ok(created);
  }

  @PatchMapping("/master-data/certificates/{id}")
  @Transactional
  public ApiResponse<Map<String, Object>> updateCertificate(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
    Map<String, Object> before = currentMasterData("certificates", id);
    jdbc.update("""
        update certificates set name = coalesce(?, name), language_id = coalesce(?, language_id), description = coalesce(?, description),
          is_active = coalesce(?, is_active), updated_at = now()
        where id = ?
        """, stringOrNull(body.get("name")), uuid(body.get("languageId")), stringOrNull(body.get("description")), nullableBool(body.get("isActive")), id);
    Map<String, Object> after = currentMasterData("certificates", id);
    db.auditCurrent("admin.master_data.certificate.update", "certificate", id, "Admin cập nhật chứng chỉ.",
        Map.of("before", before, "after", after));
    return ApiResponse.ok(after);
  }

  @DeleteMapping("/master-data/certificates/{id}")
  @Transactional
  public ApiResponse<Map<String, Object>> deleteCertificate(@PathVariable UUID id) {
    Map<String, Object> before = currentMasterData("certificates", id);
    jdbc.update("update certificates set is_active = false, updated_at = now() where id = ?", id);
    Map<String, Object> after = currentMasterData("certificates", id);
    db.auditCurrent("admin.master_data.certificate.disable", "certificate", id, "Admin ẩn chứng chỉ.",
        Map.of("before", before, "after", after, "usage", usage("certificates", id)));
    return ApiResponse.ok(after);
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

  @GetMapping("/system-settings/{key}/history")
  public ApiResponse<List<Map<String, Object>>> settingHistory(@PathVariable String key) {
    return ApiResponse.ok(jdbc.query("""
        select id, actor_id, actor_role, action, description, metadata, created_at
        from audit_logs
        where action like 'admin.system_setting.%'
          and (metadata ->> 'key' = ? or description ilike ?)
        order by created_at desc
        limit 50
        """, (rs, row) -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", rs.getObject("id").toString());
      m.put("actorId", rs.getObject("actor_id") == null ? null : rs.getObject("actor_id").toString());
      m.put("actorRole", rs.getString("actor_role"));
      m.put("action", rs.getString("action"));
      m.put("description", rs.getString("description"));
      m.put("metadata", rs.getObject("metadata"));
      m.put("createdAt", rs.getObject("created_at").toString());
      return m;
    }, key, "%" + key + "%"));
  }

  @PostMapping("/system-settings")
  @Transactional
  public ApiResponse<Map<String, Object>> createSetting(@RequestBody Map<String, Object> body) throws Exception {
    String key = required(body, "key");
    Map<String, Object> before = settingSnapshot(key);
    jdbc.update("""
        insert into system_settings(key, value, value_type, description, is_sensitive, updated_by)
        values (?, ?::jsonb, ?, ?, ?, ?)
        on conflict(key) do update set value = excluded.value, value_type = excluded.value_type,
          description = excluded.description, is_sensitive = excluded.is_sensitive, updated_by = excluded.updated_by, updated_at = now()
        """, key, json(body.get("value")), value(body, "valueType", "json"), stringOrNull(body.get("description")), bool(body, "isSensitive", false), db.currentUserIdOrThrow());
    db.auditCurrent("admin.system_setting.upsert", "systemSetting", null, "Admin cập nhật system setting " + key + ".",
        settingAuditMetadata(key, before, settingSnapshot(key)));
    return ApiResponse.ok(Map.of("key", key));
  }

  @PatchMapping("/system-settings/{key}")
  @Transactional
  public ApiResponse<Map<String, Object>> updateSetting(@PathVariable String key, @RequestBody Map<String, Object> body) throws Exception {
    Map<String, Object> before = settingSnapshot(key);
    jdbc.update("""
        update system_settings set value = coalesce(?::jsonb, value), value_type = coalesce(?, value_type),
          description = coalesce(?, description), is_sensitive = coalesce(?, is_sensitive), updated_by = ?, updated_at = now()
        where key = ?
        """, body.containsKey("value") ? json(body.get("value")) : null, stringOrNull(body.get("valueType")), stringOrNull(body.get("description")),
        nullableBool(body.get("isSensitive")), db.currentUserIdOrThrow(), key);
    db.auditCurrent("admin.system_setting.update", "systemSetting", null, "Admin cập nhật system setting " + key + ".",
        settingAuditMetadata(key, before, settingSnapshot(key)));
    return ApiResponse.ok(Map.of("key", key));
  }

  @DeleteMapping("/system-settings/{key}")
  @Transactional
  public ApiResponse<Map<String, Object>> deleteSetting(@PathVariable String key) {
    Map<String, Object> before = settingSnapshot(key);
    int updated = jdbc.update("delete from system_settings where key = ?", key);
    if (updated == 0) {
      throw new BusinessException("SETTING_NOT_FOUND", "Không tìm thấy system setting " + key + ".");
    }
    db.auditCurrent("admin.system_setting.delete", "systemSetting", null, "Admin xóa system setting " + key + ".",
        settingAuditMetadata(key, before, null));
    return ApiResponse.ok(Map.of("key", key, "deleted", true));
  }

  private Boolean adminActiveOnly(Boolean activeOnly) {
    return Boolean.TRUE.equals(activeOnly) ? Boolean.TRUE : Boolean.FALSE;
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

  private String tableForKind(String kind) {
    return switch (kind) {
      case "subjects" -> "subjects";
      case "locations" -> "locations";
      case "certificates" -> "certificates";
      default -> throw new BusinessException("INVALID_MASTER_DATA_KIND", "Loại danh mục không hợp lệ.");
    };
  }

  private String entityForKind(String kind) {
    return switch (kind) {
      case "subjects" -> "subject";
      case "locations" -> "location";
      case "certificates" -> "certificate";
      default -> throw new BusinessException("INVALID_MASTER_DATA_KIND", "Loại danh mục không hợp lệ.");
    };
  }

  private Map<String, Object> usage(String kind, UUID id) {
    Map<String, Object> counts = new LinkedHashMap<>();
    if ("subjects".equals(kind)) {
      counts.put("tutorSubjects", count("select count(*) from tutor_subjects where subject_id = ?", id));
      counts.put("learningRequests", count("select count(*) from learning_requests where subject_id = ?", id));
      counts.put("trialBookings", count("select count(*) from trial_bookings where subject_id = ?", id));
      counts.put("classes", count("select count(*) from tutoring_classes where subject_id = ?", id));
      counts.put("aliases", count("select count(*) from subject_aliases where subject_id = ?", id));
    } else if ("locations".equals(kind)) {
      counts.put("childLocations", count("select count(*) from locations where parent_id = ?", id));
      counts.put("availabilitySlots", count("select count(*) from tutor_availability_slots where location_id = ?", id));
    } else if ("certificates".equals(kind)) {
      counts.put("linkedLanguages", count("select count(*) from certificates where language_id = (select language_id from certificates where id = ?) and id <> ?", id, id));
      counts.put("documentVerifications", count("select count(*) from user_verifications where verification_type = 'tutor_certificate'"));
    } else {
      throw new BusinessException("INVALID_MASTER_DATA_KIND", "Loại danh mục không hợp lệ.");
    }
    long total = counts.values().stream().filter(Number.class::isInstance).map(Number.class::cast).mapToLong(Number::longValue).sum();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("kind", kind);
    result.put("id", id.toString());
    result.put("counts", counts);
    result.put("total", total);
    result.put("hasUsage", total > 0);
    return result;
  }

  private long count(String sql, Object... args) {
    Number value = jdbc.queryForObject(sql, Number.class, args);
    return value == null ? 0 : value.longValue();
  }

  private Map<String, Object> currentMasterData(String kind, UUID id) {
    return switch (kind) {
      case "subjects" -> masterData.subjects(null, null, false).stream()
          .filter(item -> id.toString().equals(item.get("id")))
          .findFirst()
          .orElseThrow(() -> new BusinessException("MASTER_DATA_NOT_FOUND", "Không tìm thấy môn học."));
      case "locations" -> masterData.locations(null, null, false).stream()
          .filter(item -> id.toString().equals(item.get("id")))
          .findFirst()
          .orElseThrow(() -> new BusinessException("MASTER_DATA_NOT_FOUND", "Không tìm thấy địa điểm."));
      case "certificates" -> masterData.certificates(false).stream()
          .filter(item -> id.toString().equals(item.get("id")))
          .findFirst()
          .orElseThrow(() -> new BusinessException("MASTER_DATA_NOT_FOUND", "Không tìm thấy chứng chỉ."));
      default -> throw new BusinessException("INVALID_MASTER_DATA_KIND", "Loại danh mục không hợp lệ.");
    };
  }

  private Map<String, Object> settingSnapshot(String key) {
    return jdbc.query("""
        select key, case when is_sensitive then null else value end value, value_type, description, is_sensitive, updated_at
        from system_settings
        where key = ?
        """, rs -> {
      if (!rs.next()) return null;
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("key", rs.getString("key"));
      m.put("value", rs.getObject("value"));
      m.put("valueType", rs.getString("value_type"));
      m.put("description", rs.getString("description"));
      m.put("isSensitive", rs.getBoolean("is_sensitive"));
      m.put("updatedAt", rs.getObject("updated_at").toString());
      return m;
    }, key);
  }

  private Map<String, Object> settingAuditMetadata(String key, Map<String, Object> before, Map<String, Object> after) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("key", key);
    metadata.put("before", sanitizeSetting(before));
    metadata.put("after", sanitizeSetting(after));
    metadata.put("changedFields", changedFields(before, after));
    return metadata;
  }

  private Map<String, Object> sanitizeSetting(Map<String, Object> value) {
    if (value == null) return null;
    Map<String, Object> sanitized = new LinkedHashMap<>(value);
    if (Boolean.TRUE.equals(sanitized.get("isSensitive"))) {
      sanitized.put("value", null);
      sanitized.put("valueHidden", true);
    }
    return sanitized;
  }

  private List<String> changedFields(Map<String, Object> before, Map<String, Object> after) {
    if (before == null && after == null) return List.of();
    if (before == null) return new ArrayList<>(after.keySet());
    if (after == null) return new ArrayList<>(before.keySet());
    List<String> fields = new ArrayList<>();
    for (String key : after.keySet()) {
      Object oldValue = before.get(key);
      Object newValue = after.get(key);
      if (oldValue == null ? newValue != null : !oldValue.equals(newValue)) fields.add(key);
    }
    return fields;
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
