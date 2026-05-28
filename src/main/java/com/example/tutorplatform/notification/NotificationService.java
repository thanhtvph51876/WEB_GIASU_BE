package com.example.tutorplatform.notification;

import static com.example.tutorplatform.platform.PlatformRequestSupport.firstString;
import static com.example.tutorplatform.platform.PlatformRequestSupport.uuid;
import static com.example.tutorplatform.platform.PlatformRequestSupport.valueOr;

import com.example.tutorplatform.db.DbService;
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
    return jdbc.query("select * from notifications where user_id = ? and deleted_at is null order by created_at desc", db.notificationMapper(), userId);
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

  public Map<String, Object> adminSend(Map<String, Object> body) {
    UUID userId = uuid(firstString(body, "userId"));
    db.notify(userId, valueOr(firstString(body, "type"), "info"), firstString(body, "title"), firstString(body, "message", "content"), firstString(body, "actionUrl", "link"), null, null);
    return Map.of("sent", true);
  }
}
