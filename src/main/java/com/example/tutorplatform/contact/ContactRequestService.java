package com.example.tutorplatform.contact;

import static com.example.tutorplatform.platform.PlatformRequestSupport.firstString;

import com.example.tutorplatform.common.BusinessException;
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
    return jdbc.query(contactSelect() + " order by cr.created_at desc limit 500", contactMapper());
  }

  public List<Map<String, Object>> adminContacts(int limit, int offset) {
    return adminContacts(null, null, limit, offset);
  }

  public List<Map<String, Object>> adminContacts(String status, String search, int limit, int offset) {
    List<Object> args = new java.util.ArrayList<>();
    String where = contactWhere(status, search, args);
    args.add(limit);
    args.add(offset);
    return jdbc.query(contactSelect() + where + " order by cr.created_at desc limit ? offset ?", contactMapper(), args.toArray());
  }

  public long adminContactsCount(String status, String search) {
    List<Object> args = new java.util.ArrayList<>();
    String where = contactWhere(status, search, args);
    Long total = jdbc.queryForObject("select count(*) from contact_requests cr " + where, Long.class, args.toArray());
    return total == null ? 0 : total;
  }

  private String contactWhere(String status, String search, List<Object> args) {
    StringBuilder where = new StringBuilder(" where 1 = 1 ");
    if (status != null && !status.isBlank() && !"all".equals(status)) {
      where.append(" and cr.status = ? ");
      args.add(status);
    }
    if (search != null && !search.isBlank()) {
      String pattern = "%" + search.trim() + "%";
      where.append(" and (lower(cr.full_name) like lower(?) or lower(cr.email) like lower(?) or coalesce(cr.phone, '') like ? or lower(cr.message) like lower(?)) ");
      args.add(pattern);
      args.add(pattern);
      args.add(pattern);
      args.add(pattern);
    }
    return where.toString();
  }

  public Map<String, Object> updateStatus(UUID contactId, Map<String, Object> body) {
    String status = firstString(body, "status");
    if (!List.of("new", "contacted", "resolved", "ignored").contains(status)) {
      throw new BusinessException("INVALID_CONTACT_STATUS", "Trạng thái liên hệ không hợp lệ.");
    }
    UUID actorId = db.currentUserIdOrThrow();
    String note = firstString(body, "handlerNote", "note", "reason");
    jdbc.update("""
        update contact_requests
        set status = ?,
            assigned_to = ?,
            handled_at = case when ? <> 'new' then now() else handled_at end,
            handler_note = coalesce(?, handler_note),
            updated_at = now()
        where id = ?
        """, status, actorId, status, note, contactId);
    db.auditCurrent("admin.update_contact_request", "contactRequest", contactId,
        "Admin cập nhật trạng thái liên hệ thành " + status + ".",
        Map.of("status", status));
    return contactById(contactId);
  }

  private Map<String, Object> contactById(UUID contactId) {
    return jdbc.queryForObject(contactSelect() + " where cr.id = ?", contactMapper(), contactId);
  }

  private String contactSelect() {
    return """
        select cr.*, au.full_name handled_by_name, au.email handled_by_email
        from contact_requests cr
        left join users au on au.id = cr.assigned_to
        """;
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
      Object assignedTo = rs.getObject("assigned_to");
      Object handledAt = rs.getObject("handled_at");
      String handledBy = rs.getString("handled_by_name");
      m.put("assignedTo", assignedTo == null ? null : assignedTo.toString());
      m.put("assignedToName", handledBy);
      m.put("handledById", assignedTo == null ? null : assignedTo.toString());
      m.put("handledBy", handledBy);
      m.put("handledByEmail", rs.getString("handled_by_email"));
      m.put("handledAt", handledAt == null ? null : rs.getObject("handled_at", OffsetDateTime.class).toString());
      m.put("handlerNote", rs.getString("handler_note"));
      return m;
    };
  }
}
