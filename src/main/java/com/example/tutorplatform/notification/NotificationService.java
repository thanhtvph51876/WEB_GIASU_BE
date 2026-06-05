package com.example.tutorplatform.notification;

import static com.example.tutorplatform.platform.PlatformRequestSupport.firstString;
import static com.example.tutorplatform.platform.PlatformRequestSupport.list;
import static com.example.tutorplatform.platform.PlatformRequestSupport.uuid;
import static com.example.tutorplatform.platform.PlatformRequestSupport.valueOr;

import com.example.tutorplatform.common.BusinessException;
import com.example.tutorplatform.db.DbService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {
  private final DbService db;
  private final JdbcTemplate jdbc;

  public NotificationService(DbService db) {
    this.db = db;
    this.jdbc = db.jdbc();
  }

  public List<Map<String, Object>> notifications() {
    UUID userId = db.currentUserIdOrThrow();
    return jdbc.query("select * from notifications where user_id = ? and deleted_at is null order by created_at desc limit 200", db.notificationMapper(), userId);
  }

  public List<Map<String, Object>> notifications(int limit, int offset) {
    UUID userId = db.currentUserIdOrThrow();
    return jdbc.query("""
        select *
        from notifications
        where user_id = ? and deleted_at is null
        order by created_at desc
        limit ? offset ?
        """, db.notificationMapper(), userId, limit, offset);
  }

  public long notificationsCount() {
    UUID userId = db.currentUserIdOrThrow();
    Long total = jdbc.queryForObject("select count(*) from notifications where user_id = ? and deleted_at is null", Long.class, userId);
    return total == null ? 0 : total;
  }

  public Map<String, Object> unreadCount() {
    UUID userId = db.currentUserIdOrThrow();
    Integer count = jdbc.queryForObject("select count(*) from notifications where user_id = ? and status = 'unread' and deleted_at is null", Integer.class, userId);
    return Map.of("count", count == null ? 0 : count);
  }

  public Map<String, Object> markRead(UUID notificationId) {
    UUID userId = db.currentUserIdOrThrow();
    jdbc.update("update notifications set status = 'read', read_at = now() where id = ? and user_id = ? and deleted_at is null", notificationId, userId);
    return Map.of("success", true);
  }

  public Map<String, Object> readAll() {
    jdbc.update("update notifications set status = 'read', read_at = now() where user_id = ? and deleted_at is null", db.currentUserIdOrThrow());
    return Map.of("success", true);
  }

  public Map<String, Object> delete(UUID notificationId) {
    jdbc.update("update notifications set deleted_at = now(), updated_at = now() where id = ? and user_id = ?",
        notificationId, db.currentUserIdOrThrow());
    return Map.of("success", true);
  }

  public Map<String, Object> deleteAll() {
    jdbc.update("update notifications set deleted_at = now(), updated_at = now() where user_id = ? and deleted_at is null",
        db.currentUserIdOrThrow());
    return Map.of("success", true);
  }

  public List<Map<String, Object>> adminNotifications() {
    return jdbc.query("select * from notifications where deleted_at is null order by created_at desc limit 500", db.notificationMapper());
  }

  public List<Map<String, Object>> adminNotifications(int limit, int offset) {
    return jdbc.query("select * from notifications where deleted_at is null order by created_at desc limit ? offset ?", db.notificationMapper(), limit, offset);
  }

  public List<Map<String, Object>> adminNotifications(String status, String type, String search, int limit, int offset) {
    List<Object> args = new ArrayList<>();
    String where = adminNotificationWhere(status, type, search, args);
    args.add(limit);
    args.add(offset);
    return jdbc.query("""
        select n.*
        from notifications n
        left join users u on u.id = n.user_id
        """ + where + " order by n.created_at desc limit ? offset ?", db.notificationMapper(), args.toArray());
  }

  public long adminNotificationsCount(String status, String type, String search) {
    List<Object> args = new ArrayList<>();
    String where = adminNotificationWhere(status, type, search, args);
    Long total = jdbc.queryForObject("""
        select count(*)
        from notifications n
        left join users u on u.id = n.user_id
        """ + where, Long.class, args.toArray());
    return total == null ? 0 : total;
  }

  public Map<String, Object> adminSend(Map<String, Object> body) {
    UUID userId = uuid(firstString(body, "userId"));
    String type = valueOr(firstString(body, "type"), "info");
    String title = requiredText(body, "title");
    String message = requiredText(body, "message", "content");
    String actionUrl = firstString(body, "actionUrl", "link");
    db.notify(userId, type, title, message, actionUrl, null, null);
    db.auditCurrent("admin.send_notification", "notification", null, "Admin gửi thông báo tới 1 người dùng.");
    return Map.of("sent", true, "count", 1, "recipientIds", List.of(userId.toString()));
  }

  public Map<String, Object> adminSendBulk(Map<String, Object> body) {
    List<UUID> recipients = resolveRecipients(body);
    if (recipients.isEmpty()) {
      throw new BusinessException("NO_NOTIFICATION_RECIPIENTS", "Không có người nhận active phù hợp.");
    }

    String type = valueOr(firstString(body, "type"), "info");
    String title = requiredText(body, "title");
    String message = requiredText(body, "message", "content");
    String actionUrl = firstString(body, "actionUrl", "link");
    List<Object[]> rows = recipients.stream()
        .map(userId -> new Object[] {userId, type, title, message, actionUrl, "adminBroadcast", null})
        .toList();

    jdbc.batchUpdate("""
        insert into notifications(user_id, type, title, message, action_url, entity_type, entity_id)
        values (?, ?, ?, ?, ?, ?, ?)
        """, rows);
    String targetRole = valueOr(firstString(body, "targetRole", "role"), "all");
    db.auditCurrent("admin.send_notification_bulk", "notification", null,
        "Admin gửi thông báo hàng loạt tới " + recipients.size() + " người dùng.",
        Map.of("targetRole", targetRole, "recipientCount", recipients.size(), "title", title));
    return Map.of(
        "sent", true,
        "count", recipients.size(),
        "targetRole", targetRole,
        "recipientIds", recipients.stream().map(UUID::toString).toList()
    );
  }

  private List<UUID> resolveRecipients(Map<String, Object> body) {
    LinkedHashSet<UUID> recipients = new LinkedHashSet<>();
    String singleUserId = firstString(body, "userId");
    if (singleUserId != null) {
      recipients.add(uuid(singleUserId));
    }
    for (Object userId : list(body == null ? null : body.get("userIds"))) {
      if (userId != null && !userId.toString().isBlank()) {
        recipients.add(uuid(userId));
      }
    }
    if (!recipients.isEmpty()) {
      return recipients.stream().toList();
    }

    String role = valueOr(firstString(body, "targetRole", "role"), "all");
    String sql = "select id from users where status = 'active' and role <> 'guest' order by created_at desc";
    List<UUID> userIds = "all".equals(role)
        ? jdbc.query(sql, (rs, row) -> rs.getObject("id", UUID.class))
        : jdbc.query("select id from users where status = 'active' and role = ? order by created_at desc",
            (rs, row) -> rs.getObject("id", UUID.class), role);
    recipients.addAll(userIds);
    return recipients.stream().toList();
  }

  private String requiredText(Map<String, Object> body, String... keys) {
    String value = firstString(body, keys);
    if (value == null || value.isBlank()) {
      throw new BusinessException("INVALID_NOTIFICATION_PAYLOAD", "Thiếu tiêu đề hoặc nội dung thông báo.");
    }
    return value;
  }

  private String adminNotificationWhere(String status, String type, String search, List<Object> args) {
    StringBuilder where = new StringBuilder(" where n.deleted_at is null ");
    if (status != null && !status.isBlank() && !"all".equals(status)) {
      where.append(" and n.status = ? ");
      args.add(status);
    }
    if (type != null && !type.isBlank() && !"all".equals(type)) {
      where.append(" and n.type = ? ");
      args.add(type);
    }
    if (search != null && !search.isBlank()) {
      String pattern = "%" + search.trim().toLowerCase() + "%";
      where.append("""
          and (
            lower(coalesce(n.title, '')) like ?
            or lower(coalesce(n.message, '')) like ?
            or lower(coalesce(n.entity_type, '')) like ?
            or lower(coalesce(u.full_name, '')) like ?
            or n.user_id::text like ?
          )
          """);
      for (int i = 0; i < 5; i++) args.add(pattern);
    }
    return where.toString();
  }
}
