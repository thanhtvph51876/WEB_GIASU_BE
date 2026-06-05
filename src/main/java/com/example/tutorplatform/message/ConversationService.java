package com.example.tutorplatform.message;

import static com.example.tutorplatform.platform.PlatformRequestSupport.list;
import static com.example.tutorplatform.platform.PlatformRequestSupport.firstString;
import static com.example.tutorplatform.platform.PlatformRequestSupport.uuid;

import com.example.tutorplatform.common.ForbiddenException;
import com.example.tutorplatform.db.DbService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationService {
  private final DbService db;
  private final JdbcTemplate jdbc;

  public ConversationService(DbService db) {
    this.db = db;
    this.jdbc = db.jdbc();
  }

  public List<Map<String, Object>> conversations() {
    UUID userId = db.currentUserIdOrThrow();
    return jdbc.query("""
        select c.* from conversations c
        join conversation_members cm on cm.conversation_id = c.id
        where cm.user_id = ?
        order by c.updated_at desc
        limit 200
        """, db.conversationMapper(userId), userId);
  }

  public List<Map<String, Object>> conversations(int limit, int offset) {
    UUID userId = db.currentUserIdOrThrow();
    return jdbc.query("""
        select c.* from conversations c
        join conversation_members cm on cm.conversation_id = c.id
        where cm.user_id = ?
        order by c.updated_at desc
        limit ? offset ?
        """, db.conversationMapper(userId), userId, limit, offset);
  }

  public long conversationsCount() {
    UUID userId = db.currentUserIdOrThrow();
    Long total = jdbc.queryForObject("""
        select count(*)
        from conversations c
        join conversation_members cm on cm.conversation_id = c.id
        where cm.user_id = ?
        """, Long.class, userId);
    return total == null ? 0 : total;
  }

  @Transactional
  public Map<String, Object> createConversation(Map<String, Object> body) {
    UUID userId = db.currentUserIdOrThrow();
    UUID id = jdbc.queryForObject("insert into conversations(title, type) values (?, coalesce(?, 'direct')) returning id",
        UUID.class, firstString(body, "title"), firstString(body, "type"));
    jdbc.update("insert into conversation_members(conversation_id, user_id) values (?, ?) on conflict do nothing", id, userId);
    for (Object member : list(body.get("participantIds"))) {
      jdbc.update("insert into conversation_members(conversation_id, user_id) values (?, ?) on conflict do nothing", id, uuid(member));
    }
    return jdbc.queryForObject("select * from conversations where id = ?", db.conversationMapper(userId), id);
  }

  public Map<String, Object> conversation(UUID conversationId) {
    UUID userId = db.currentUserIdOrThrow();
    requireConversationMember(conversationId, userId);
    return jdbc.queryForObject("select * from conversations where id = ?", db.conversationMapper(userId), conversationId);
  }

  public List<Map<String, Object>> messages(UUID conversationId) {
    UUID userId = db.currentUserIdOrThrow();
    requireConversationMember(conversationId, userId);
    return jdbc.query("""
        select * from (
          select * from messages where conversation_id = ? order by created_at desc limit 300
        ) recent_messages
        order by created_at
        """, db.messageMapper(userId), conversationId);
  }

  public List<Map<String, Object>> messages(UUID conversationId, int limit, int offset) {
    UUID userId = db.currentUserIdOrThrow();
    requireConversationMember(conversationId, userId);
    return jdbc.query("""
        select * from (
          select * from messages where conversation_id = ? order by created_at desc limit ? offset ?
        ) recent_messages
        order by created_at
        """, db.messageMapper(userId), conversationId, limit, offset);
  }

  public long messagesCount(UUID conversationId) {
    UUID userId = db.currentUserIdOrThrow();
    requireConversationMember(conversationId, userId);
    Long total = jdbc.queryForObject("select count(*) from messages where conversation_id = ?", Long.class, conversationId);
    return total == null ? 0 : total;
  }

  public Map<String, Object> sendMessage(UUID conversationId, String content) {
    UUID userId = db.currentUserIdOrThrow();
    requireConversationMember(conversationId, userId);
    UUID id = jdbc.queryForObject("insert into messages(conversation_id, sender_id, content, message_type) values (?, ?, ?, 'text') returning id",
        UUID.class, conversationId, userId, content);
    jdbc.update("update conversations set updated_at = now() where id = ?", conversationId);
    return jdbc.queryForObject("select * from messages where id = ?", db.messageMapper(userId), id);
  }

  public Map<String, Object> markConversationRead(UUID conversationId) {
    UUID userId = db.currentUserIdOrThrow();
    requireConversationMember(conversationId, userId);
    jdbc.update("update conversation_members set last_read_at = now() where conversation_id = ? and user_id = ?", conversationId, userId);
    return Map.of("success", true);
  }

  public List<Map<String, Object>> adminConversations() {
    return jdbc.query("select * from conversations order by updated_at desc limit 200", db.conversationMapper(db.currentUserIdOrThrow()));
  }

  public List<Map<String, Object>> adminConversations(int limit, int offset) {
    return jdbc.query("select * from conversations order by updated_at desc limit ? offset ?", db.conversationMapper(db.currentUserIdOrThrow()), limit, offset);
  }

  public Map<String, Object> adminConversation(UUID conversationId) {
    return jdbc.queryForObject("select * from conversations where id = ?", db.conversationMapper(db.currentUserIdOrThrow()), conversationId);
  }

  private void requireConversationMember(UUID conversationId, UUID userId) {
    if (db.isAdmin()) return;
    if (!exists("select 1 from conversation_members where conversation_id = ? and user_id = ?", conversationId, userId)) {
      throw new ForbiddenException("Bạn không có quyền xem hội thoại này.");
    }
  }

  private boolean exists(String sql, Object... args) {
    Integer count = jdbc.queryForObject("select count(*) from (" + sql + ") x", Integer.class, args);
    return count != null && count > 0;
  }
}
