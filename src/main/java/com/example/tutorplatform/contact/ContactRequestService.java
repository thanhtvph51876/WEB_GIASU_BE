package com.example.tutorplatform.contact;

import static com.example.tutorplatform.platform.PlatformRequestSupport.firstString;

import com.example.tutorplatform.db.DbService;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

@Service
public class ContactRequestService {
  private final DbService db;
  private final JdbcTemplate jdbc;

  public ContactRequestService(DbService db) {
    this.db = db;
    this.jdbc = db.jdbc();
  }

  public Map<String, Object> create(Map<String, Object> body) {
    UUID id = jdbc.queryForObject("""
        insert into contact_requests(full_name, email, phone, message, status)
        values (?, ?, ?, ?, 'new') returning id
        """, UUID.class, firstString(body, "fullName"), firstString(body, "email"), firstString(body, "phone"), firstString(body, "message"));
    db.notifyAdmins("info", "Liên hệ mới", "Có yêu cầu liên hệ mới từ khách.", "/admin/contact-requests", "contactRequest", id);
    return contactById(id);
  }

  public List<Map<String, Object>> adminContacts() {
    return jdbc.query("select * from contact_requests order by created_at desc", contactMapper());
  }

  public Map<String, Object> updateStatus(UUID contactId, Map<String, Object> body) {
    jdbc.update("update contact_requests set status = ?, updated_at = now() where id = ?", firstString(body, "status"), contactId);
    return contactById(contactId);
  }

  private Map<String, Object> contactById(UUID contactId) {
    return jdbc.queryForObject("select * from contact_requests where id = ?", contactMapper(), contactId);
  }

  private RowMapper<Map<String, Object>> contactMapper() {
    return (rs, row) -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", rs.getObject("id").toString());
      m.put("fullName", rs.getString("full_name"));
      m.put("email", rs.getString("email"));
      m.put("phone", rs.getString("phone"));
      m.put("message", rs.getString("message"));
      m.put("status", rs.getString("status"));
      m.put("createdAt", rs.getObject("created_at", OffsetDateTime.class).toString());
      m.put("updatedAt", rs.getObject("updated_at", OffsetDateTime.class).toString());
      return m;
    };
  }
}
