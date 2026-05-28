package com.example.tutorplatform.admin;

import static com.example.tutorplatform.platform.PlatformRequestSupport.jsonValue;
import static com.example.tutorplatform.platform.PlatformRequestSupport.parseJson;

import com.example.tutorplatform.db.DbService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AdminSettingsService {
  private final DbService db;
  private final JdbcTemplate jdbc;

  public AdminSettingsService(DbService db) {
    this.db = db;
    this.jdbc = db.jdbc();
  }

  public Map<String, Object> settings() {
    Map<String, Object> result = new LinkedHashMap<>();
    jdbc.query("select key, value from system_settings order by key", rs -> {
      result.put(rs.getString("key"), parseJson(rs.getString("value")));
    });
    return result;
  }

  public Map<String, Object> update(Map<String, Object> body) {
    UUID actor = db.currentUserIdOrThrow();
    body.forEach((key, value) -> jdbc.update("""
        insert into system_settings(key, value, updated_by)
        values (?, ?::jsonb, ?)
        on conflict(key) do update set value = excluded.value, updated_by = excluded.updated_by, updated_at = now()
        """, key, jsonValue(value), actor));
    db.auditCurrent("admin.update_settings", "settings", null, "Admin cập nhật cấu hình hệ thống.");
    return settings();
  }
}
