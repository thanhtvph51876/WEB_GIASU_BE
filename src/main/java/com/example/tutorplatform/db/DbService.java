package com.example.tutorplatform.db;

import com.example.tutorplatform.common.ForbiddenException;
import com.example.tutorplatform.common.NotFoundException;
import com.example.tutorplatform.security.SecurityUtils;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DbService {
  private final JdbcTemplate jdbc;

  public DbService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public JdbcTemplate jdbc() {
    return jdbc;
  }

  public UUID currentUserIdOrThrow() {
    return SecurityUtils.currentUserId().orElseThrow(() -> new ForbiddenException("Bạn cần đăng nhập."));
  }

  public Map<String, Object> currentUserOrThrow() {
    return userById(currentUserIdOrThrow()).orElseThrow(() -> new ForbiddenException("Phiên đăng nhập không hợp lệ."));
  }

  public boolean isAdmin() {
    return SecurityUtils.hasRole("ADMIN");
  }

  public boolean isTutor() {
    return SecurityUtils.hasRole("TUTOR");
  }

  public Optional<Map<String, Object>> userById(UUID id) {
    return optional("select * from users where id = ?", userMapper(), id);
  }

  public Optional<Map<String, Object>> userByEmail(String email) {
    return optional("select * from users where lower(email) = lower(?)", userMapper(), email);
  }

  public UUID requiredSubjectId(Object value) {
    if (value == null) {
      return jdbc.queryForObject("select id from subjects order by name limit 1", UUID.class);
    }
    String raw = value.toString();
    try {
      UUID id = UUID.fromString(raw);
      Integer exists = jdbc.queryForObject("select count(*) from subjects where id = ?", Integer.class, id);
      if (exists != null && exists > 0) return id;
    } catch (IllegalArgumentException ignored) {
    }
    return optional("select id from subjects where lower(name) = lower(?) or slug = lower(?)", (rs, row) -> rs.getObject("id", UUID.class), raw, slug(raw))
        .orElseGet(() -> jdbc.queryForObject(
            "insert into subjects(name, slug, description) values (?, ?, '') on conflict(slug) do update set name = excluded.name returning id",
            UUID.class,
            raw,
            slug(raw)
        ));
  }

  public UUID gradeLevelId(Object value) {
    if (value == null || value.toString().isBlank()) return null;
    String raw = value.toString();
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException ignored) {
      return optional("select id from grade_levels where lower(name) = lower(?)", (rs, row) -> rs.getObject("id", UUID.class), raw).orElse(null);
    }
  }

  public Optional<UUID> tutorIdByUser(UUID userId) {
    return optional("select id from tutor_profiles where user_id = ?", (rs, row) -> rs.getObject("id", UUID.class), userId);
  }

  public UUID tutorIdByUserOrThrow(UUID userId) {
    return tutorIdByUser(userId).orElseThrow(() -> new NotFoundException("Không tìm thấy hồ sơ gia sư."));
  }

  public void requireTutorOwner(UUID tutorId) {
    if (isAdmin()) return;
    UUID current = currentUserIdOrThrow();
    UUID owner = jdbc.queryForObject("select user_id from tutor_profiles where id = ?", UUID.class, tutorId);
    if (!current.equals(owner)) {
      throw new ForbiddenException("Bạn không có quyền thao tác hồ sơ gia sư này.");
    }
  }

  public void requireUserOwned(UUID userId) {
    if (!isAdmin() && !currentUserIdOrThrow().equals(userId)) {
      throw new ForbiddenException("Bạn không có quyền xem dữ liệu này.");
    }
  }

  public boolean canAccessStudentResource(UUID studentId) {
    return isAdmin() || currentUserIdOrThrow().equals(studentId);
  }

  public <T> Optional<T> optional(String sql, RowMapper<T> mapper, Object... args) {
    try {
      return Optional.ofNullable(jdbc.queryForObject(sql, mapper, args));
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  public <T> T required(String sql, RowMapper<T> mapper, Object... args) {
    return optional(sql, mapper, args).orElseThrow(() -> new NotFoundException("Không tìm thấy dữ liệu."));
  }

  public RowMapper<Map<String, Object>> userMapper() {
    return (rs, row) -> {
      Map<String, Object> m = base(rs);
      m.put("email", rs.getString("email"));
      m.put("fullName", rs.getString("full_name"));
      m.put("name", rs.getString("full_name"));
      m.put("phone", rs.getString("phone"));
      m.put("avatar", rs.getString("avatar_url"));
      m.put("avatarUrl", rs.getString("avatar_url"));
      m.put("role", rs.getString("role"));
      m.put("status", normalizeUserStatus(rs.getString("status")));
      m.put("emailVerified", rs.getBoolean("email_verified"));
      m.put("lastLoginAt", ts(rs, "last_login_at"));
      return m;
    };
  }

  public Map<String, Object> rawUserWithPassword(String email) {
    return required("select * from users where lower(email)=lower(?)", (rs, row) -> {
      Map<String, Object> user = userMapper().mapRow(rs, row);
      user.put("passwordHash", rs.getString("password_hash"));
      return user;
    }, email);
  }

  public RowMapper<Map<String, Object>> tutorMapper() {
    return (rs, row) -> {
      UUID id = rs.getObject("id", UUID.class);
      UUID userId = rs.getObject("user_id", UUID.class);
      int min = intOrZero(rs, "hourly_rate_min");
      int max = intOrZero(rs, "hourly_rate_max");
      Map<String, Object> m = base(rs);
      m.put("userId", userId.toString());
      m.put("status", rs.getString("status"));
      m.put("approvalStatus", rs.getString("status"));
      m.put("fullName", rs.getString("full_name"));
      m.put("avatar", rs.getString("avatar_url"));
      m.put("gender", valueOr(rs.getString("gender"), "other"));
      m.put("headline", rs.getString("headline"));
      m.put("university", valueOr(rs.getString("university"), ""));
      m.put("faculty", valueOr(rs.getString("education"), ""));
      m.put("education", rs.getString("education"));
      m.put("major", valueOr(rs.getString("major"), ""));
      m.put("studentCode", "");
      m.put("subjects", listStrings("select s.name from tutor_subjects ts join subjects s on s.id = ts.subject_id where ts.tutor_id = ? order by s.name", id));
      m.put("grades", listStrings("select distinct gl.name from tutor_subjects ts join grade_levels gl on gl.id = ts.grade_level_id where ts.tutor_id = ? order by gl.name", id));
      m.put("experienceYears", rs.getInt("experience_years"));
      m.put("teachingModes", teachingModes(id));
      m.put("locations", listStrings("select distinct province from tutor_locations where tutor_id = ? order by province", id));
      m.put("pricePerHour", min > 0 ? min : max);
      m.put("hourlyRateMin", nullableInt(rs, "hourly_rate_min"));
      m.put("hourlyRateMax", nullableInt(rs, "hourly_rate_max"));
      m.put("rating", rs.getBigDecimal("rating_avg") == null ? 0 : rs.getBigDecimal("rating_avg").doubleValue());
      m.put("ratingAvg", m.get("rating"));
      m.put("reviewCount", rs.getInt("rating_count"));
      m.put("ratingCount", rs.getInt("rating_count"));
      m.put("verified", "approved".equals(rs.getString("status")));
      m.put("bio", valueOr(rs.getString("bio"), ""));
      m.put("teachingMethod", valueOr(rs.getString("teaching_method"), ""));
      m.put("achievements", List.of());
      m.put("certificates", List.of());
      m.put("availableSlots", availability(id));
      m.put("totalStudents", rs.getInt("total_students"));
      m.put("totalClasses", jdbc.queryForObject("select count(*) from tutoring_classes where tutor_id = ?", Integer.class, id));
      m.put("totalSessions", rs.getInt("total_sessions"));
      m.put("responseRate", rs.getBigDecimal("response_rate") == null ? 0 : rs.getBigDecimal("response_rate").doubleValue());
      m.put("rejectReason", rs.getString("status_reason"));
      m.put("updateRequestNote", "need_update".equals(rs.getString("status")) ? rs.getString("status_reason") : null);
      m.put("suspensionReason", "suspended".equals(rs.getString("status")) ? rs.getString("status_reason") : null);
      m.put("documents", documents(id));
      return m;
    };
  }

  public RowMapper<Map<String, Object>> learningRequestMapper() {
    return (rs, row) -> {
      Map<String, Object> m = base(rs);
      m.put("requestCode", rs.getString("request_code"));
      m.put("userId", str(rs, "requester_id"));
      m.put("studentName", rs.getString("student_name"));
      m.put("parentName", rs.getString("parent_name"));
      m.put("phone", rs.getString("phone"));
      m.put("email", rs.getString("email"));
      m.put("grade", valueOr(rs.getString("student_grade"), rs.getString("grade_name")));
      m.put("studentGrade", rs.getString("student_grade"));
      m.put("subject", rs.getString("subject_name"));
      m.put("subjectId", str(rs, "subject_id"));
      m.put("gradeLevelId", str(rs, "grade_level_id"));
      m.put("goal", valueOr(rs.getString("goal"), "improve_grades"));
      m.put("teachingMode", rs.getString("learning_mode"));
      m.put("learningMode", rs.getString("learning_mode"));
      m.put("location", location(rs.getString("province"), rs.getString("district")));
      m.put("province", rs.getString("province"));
      m.put("district", rs.getString("district"));
      m.put("expectedFee", nullableInt(rs, "budget_max"));
      m.put("budgetMin", nullableInt(rs, "budget_min"));
      m.put("budgetMax", nullableInt(rs, "budget_max"));
      m.put("preferredSchedule", rs.getString("preferred_schedule"));
      m.put("learningGoal", rs.getString("learning_goal"));
      m.put("note", rs.getString("note"));
      m.put("status", rs.getString("status"));
      m.put("assignedTutorId", str(rs, "assigned_tutor_id"));
      return m;
    };
  }

  public RowMapper<Map<String, Object>> bookingMapper() {
    return (rs, row) -> {
      Map<String, Object> m = base(rs);
      m.put("tutorId", str(rs, "tutor_id"));
      m.put("studentId", str(rs, "student_id"));
      m.put("studentName", rs.getString("student_name"));
      m.put("parentName", rs.getString("parent_name"));
      m.put("phone", rs.getString("phone"));
      m.put("email", rs.getString("email"));
      m.put("subject", rs.getString("subject_name"));
      m.put("grade", valueOr(rs.getString("grade_name"), ""));
      m.put("preferredTime", rs.getString("preferred_time"));
      m.put("message", rs.getString("goal"));
      m.put("status", rs.getString("status"));
      m.put("rejectReason", rs.getString("tutor_response_note"));
      m.put("resultNote", rs.getString("result_note"));
      m.put("userId", str(rs, "student_id"));
      m.put("learningRequestId", str(rs, "learning_request_id"));
      m.put("classId", str(rs, "converted_class_id"));
      if (rs.getTimestamp("scheduled_start") != null) {
        OffsetDateTime start = rs.getObject("scheduled_start", OffsetDateTime.class);
        OffsetDateTime end = rs.getObject("scheduled_end", OffsetDateTime.class);
        m.put("schedule", Map.of(
            "date", start.toLocalDate().toString(),
            "startTime", start.toLocalTime().toString().substring(0, 5),
            "endTime", end.toLocalTime().toString().substring(0, 5),
            "mode", rs.getString("learning_mode"),
            "location", valueOr(rs.getString("location"), valueOr(rs.getString("meeting_url"), ""))
        ));
      }
      return m;
    };
  }

  public RowMapper<Map<String, Object>> classMapper() {
    return (rs, row) -> {
      UUID classId = rs.getObject("id", UUID.class);
      Map<String, Object> m = base(rs);
      m.put("studentId", str(rs, "student_id"));
      m.put("studentName", valueOr(rs.getString("student_name"), ""));
      m.put("parentName", rs.getString("parent_name"));
      m.put("tutorId", str(rs, "tutor_id"));
      m.put("tutorName", valueOr(rs.getString("tutor_name"), ""));
      m.put("learningRequestId", str(rs, "learning_request_id"));
      m.put("trialBookingId", str(rs, "trial_booking_id"));
      m.put("subject", rs.getString("subject_name"));
      m.put("grade", valueOr(rs.getString("grade_name"), ""));
      m.put("title", rs.getString("title"));
      m.put("mode", rs.getString("learning_mode"));
      m.put("learningMode", rs.getString("learning_mode"));
      m.put("location", valueOr(rs.getString("location"), rs.getString("meeting_url")));
      m.put("feePerSession", nullableInt(rs, "hourly_rate"));
      m.put("hourlyRate", nullableInt(rs, "hourly_rate"));
      m.put("scheduleText", "");
      m.put("startDate", rs.getObject("start_date") == null ? null : rs.getObject("start_date", LocalDate.class).toString());
      m.put("endDate", rs.getObject("end_date") == null ? null : rs.getObject("end_date", LocalDate.class).toString());
      m.put("status", rs.getString("status"));
      m.put("totalSessions", jdbc.queryForObject("select count(*) from class_sessions where class_id = ?", Integer.class, classId));
      m.put("completedSessions", jdbc.queryForObject("select count(*) from class_sessions where class_id = ? and status = 'completed'", Integer.class, classId));
      return m;
    };
  }

  public RowMapper<Map<String, Object>> sessionMapper() {
    return (rs, row) -> {
      Map<String, Object> m = base(rs);
      m.put("classId", str(rs, "class_id"));
      m.put("tutorId", str(rs, "tutor_id"));
      m.put("studentId", str(rs, "student_id"));
      m.put("tutorName", valueOr(rs.getString("tutor_name"), ""));
      m.put("studentName", valueOr(rs.getString("student_name"), ""));
      m.put("subject", rs.getString("subject_name"));
      m.put("grade", valueOr(rs.getString("grade_name"), ""));
      m.put("startTime", ts(rs, "scheduled_start"));
      m.put("endTime", ts(rs, "scheduled_end"));
      m.put("scheduledStart", ts(rs, "scheduled_start"));
      m.put("scheduledEnd", ts(rs, "scheduled_end"));
      m.put("mode", rs.getString("learning_mode"));
      m.put("location", valueOr(rs.getString("location"), rs.getString("meeting_url")));
      m.put("status", rs.getString("status"));
      m.put("note", valueOr(rs.getString("tutor_note"), rs.getString("student_note")));
      m.put("tutorNote", rs.getString("tutor_note"));
      m.put("studentNote", rs.getString("student_note"));
      m.put("isTrial", false);
      return m;
    };
  }

  public RowMapper<Map<String, Object>> reviewMapper() {
    return (rs, row) -> {
      Map<String, Object> m = base(rs);
      m.put("sessionId", str(rs, "session_id"));
      m.put("classId", str(rs, "class_id"));
      m.put("tutorId", str(rs, "tutor_id"));
      m.put("studentId", str(rs, "reviewer_id"));
      m.put("reviewerId", str(rs, "reviewer_id"));
      m.put("studentName", rs.getString("reviewer_name"));
      m.put("avatar", rs.getString("avatar_url"));
      m.put("rating", rs.getInt("rating"));
      m.put("content", rs.getString("comment"));
      m.put("comment", rs.getString("comment"));
      m.put("status", rs.getString("status"));
      return m;
    };
  }

  public RowMapper<Map<String, Object>> notificationMapper() {
    return (rs, row) -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", str(rs, "id"));
      m.put("userId", str(rs, "user_id"));
      m.put("type", rs.getString("type"));
      m.put("title", rs.getString("title"));
      m.put("content", rs.getString("message"));
      m.put("message", rs.getString("message"));
      m.put("read", "read".equals(rs.getString("status")));
      m.put("status", rs.getString("status"));
      m.put("actionUrl", rs.getString("action_url"));
      m.put("link", rs.getString("action_url"));
      m.put("entityType", rs.getString("entity_type"));
      m.put("entityId", str(rs, "entity_id"));
      m.put("createdAt", ts(rs, "created_at"));
      return m;
    };
  }

  public RowMapper<Map<String, Object>> paymentMapper() {
    return (rs, row) -> {
      Map<String, Object> m = base(rs);
      m.put("studentId", str(rs, "user_id"));
      m.put("userId", str(rs, "user_id"));
      m.put("tutorId", str(rs, "tutor_id"));
      m.put("classId", str(rs, "class_id"));
      m.put("sessionId", str(rs, "session_id"));
      m.put("amount", rs.getInt("amount"));
      m.put("currency", rs.getString("currency"));
      m.put("status", rs.getString("status"));
      m.put("description", rs.getString("description"));
      m.put("paymentMethod", hasColumn(rs, "payment_method") ? rs.getString("payment_method") : null);
      m.put("gateway", hasColumn(rs, "gateway") ? rs.getString("gateway") : null);
      m.put("checkoutUrl", hasColumn(rs, "checkout_url") ? rs.getString("checkout_url") : null);
      m.put("qrCodeUrl", hasColumn(rs, "qr_code_url") ? rs.getString("qr_code_url") : null);
      m.put("expiredAt", hasColumn(rs, "expired_at") ? ts(rs, "expired_at") : null);
      m.put("paidAt", ts(rs, "paid_at"));
      return m;
    };
  }

  public RowMapper<Map<String, Object>> earningMapper() {
    return (rs, row) -> {
      Map<String, Object> m = base(rs);
      m.put("tutorId", str(rs, "tutor_id"));
      m.put("sessionId", str(rs, "session_id"));
      m.put("paymentId", str(rs, "payment_id"));
      m.put("grossAmount", rs.getInt("gross_amount"));
      m.put("platformFee", rs.getInt("platform_fee"));
      m.put("netAmount", rs.getInt("net_amount"));
      m.put("amount", rs.getInt("net_amount"));
      m.put("status", rs.getString("status"));
      return m;
    };
  }

  public RowMapper<Map<String, Object>> payoutMapper() {
    return (rs, row) -> {
      Map<String, Object> m = base(rs);
      m.put("tutorId", str(rs, "tutor_id"));
      m.put("tutorName", rs.getString("tutor_name"));
      m.put("amount", rs.getInt("amount"));
      m.put("status", rs.getString("status"));
      m.put("bankName", rs.getString("bank_name"));
      m.put("bankAccount", rs.getString("bank_account"));
      m.put("accountHolder", rs.getString("account_holder"));
      m.put("reason", rs.getString("admin_note"));
      m.put("adminNote", rs.getString("admin_note"));
      m.put("requestedAt", ts(rs, "created_at"));
      m.put("processedAt", ts(rs, "processed_at"));
      return m;
    };
  }

  public RowMapper<Map<String, Object>> auditMapper() {
    return (rs, row) -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", str(rs, "id"));
      m.put("actorId", str(rs, "actor_id"));
      m.put("actorName", rs.getString("actor_name"));
      m.put("actorRole", rs.getString("actor_role"));
      m.put("action", rs.getString("action"));
      m.put("entityType", rs.getString("entity_type"));
      m.put("entityId", str(rs, "entity_id"));
      m.put("description", rs.getString("description"));
      m.put("note", rs.getString("description"));
      m.put("metadata", rs.getObject("metadata"));
      m.put("createdAt", ts(rs, "created_at"));
      return m;
    };
  }

  public RowMapper<Map<String, Object>> conversationMapper(UUID currentUserId) {
    return (rs, row) -> {
      UUID conversationId = rs.getObject("id", UUID.class);
      Map<String, Object> m = base(rs);
      m.put("title", rs.getString("title"));
      m.put("type", rs.getString("type"));
      m.put("participantIds", listStrings("select user_id::text from conversation_members where conversation_id = ? order by joined_at", conversationId));
      m.put("participantNames", listStrings("select u.full_name from conversation_members cm join users u on u.id = cm.user_id where cm.conversation_id = ? order by cm.joined_at", conversationId));
      m.put("lastMessage", optional("select content from messages where conversation_id = ? order by created_at desc limit 1", (r, i) -> r.getString("content"), conversationId).orElse(null));
      m.put("lastMessageAt", optional("select created_at from messages where conversation_id = ? order by created_at desc limit 1", (r, i) -> r.getObject("created_at", OffsetDateTime.class).toString(), conversationId).orElse(null));
      m.put("unreadCount", jdbc.queryForObject("""
          select count(*) from messages msg
          left join conversation_members cm on cm.conversation_id = msg.conversation_id and cm.user_id = ?
          where msg.conversation_id = ? and msg.sender_id <> ? and (cm.last_read_at is null or msg.created_at > cm.last_read_at)
          """, Integer.class, currentUserId, conversationId, currentUserId));
      return m;
    };
  }

  public RowMapper<Map<String, Object>> messageMapper(UUID currentUserId) {
    return (rs, row) -> {
      Map<String, Object> m = base(rs);
      m.put("conversationId", str(rs, "conversation_id"));
      m.put("senderId", str(rs, "sender_id"));
      m.put("receiverId", optional("""
          select user_id::text from conversation_members
          where conversation_id = ? and user_id <> ?
          order by joined_at limit 1
          """, (r, i) -> r.getString(1), rs.getObject("conversation_id", UUID.class), rs.getObject("sender_id", UUID.class)).orElse(""));
      m.put("content", rs.getString("content"));
      m.put("messageType", rs.getString("message_type"));
      m.put("read", currentUserId.equals(rs.getObject("sender_id", UUID.class)));
      return m;
    };
  }

  public List<Map<String, Object>> tutorList(String extraWhere, List<Object> args, int page, int pageSize, boolean admin) {
    String where = admin ? " where 1=1 " : " where tp.status = 'approved' ";
    if (extraWhere != null && !extraWhere.isBlank()) {
      where += extraWhere;
    }
    args.add(pageSize);
    args.add(Math.max(0, (page - 1) * pageSize));
    return jdbc.query("""
        select tp.*, u.full_name, u.avatar_url
        from tutor_profiles tp
        join users u on u.id = tp.user_id
        """ + where + " order by tp.rating_avg desc, tp.created_at desc limit ? offset ?", tutorMapper(), args.toArray());
  }

  public long tutorCount(String extraWhere, List<Object> args, boolean admin) {
    String where = admin ? " where 1=1 " : " where tp.status = 'approved' ";
    if (extraWhere != null && !extraWhere.isBlank()) where += extraWhere;
    return jdbc.queryForObject("select count(*) from tutor_profiles tp join users u on u.id = tp.user_id " + where, Long.class, args.toArray());
  }

  public Map<String, Object> tutorById(UUID id, boolean adminAllowed) {
    String statusFilter = adminAllowed ? "" : " and tp.status = 'approved'";
    return required("""
        select tp.*, u.full_name, u.avatar_url
        from tutor_profiles tp
        join users u on u.id = tp.user_id
        where tp.id = ?
        """ + statusFilter, tutorMapper(), id);
  }

  public List<Map<String, Object>> learningRequests(String where, Object... args) {
    return jdbc.query("""
        select lr.*, s.name subject_name, gl.name grade_name
        from learning_requests lr
        join subjects s on s.id = lr.subject_id
        left join grade_levels gl on gl.id = lr.grade_level_id
        """ + where + " order by lr.created_at desc", learningRequestMapper(), args);
  }

  public Map<String, Object> learningRequestById(UUID id) {
    return required("""
        select lr.*, s.name subject_name, gl.name grade_name
        from learning_requests lr
        join subjects s on s.id = lr.subject_id
        left join grade_levels gl on gl.id = lr.grade_level_id
        where lr.id = ?
        """, learningRequestMapper(), id);
  }

  public List<Map<String, Object>> bookings(String where, Object... args) {
    return jdbc.query("""
        select tb.*, s.name subject_name, gl.name grade_name
        from trial_bookings tb
        join subjects s on s.id = tb.subject_id
        left join grade_levels gl on gl.id = tb.grade_level_id
        """ + where + " order by tb.created_at desc", bookingMapper(), args);
  }

  public Map<String, Object> bookingById(UUID id) {
    return required("""
        select tb.*, s.name subject_name, gl.name grade_name
        from trial_bookings tb
        join subjects s on s.id = tb.subject_id
        left join grade_levels gl on gl.id = tb.grade_level_id
        where tb.id = ?
        """, bookingMapper(), id);
  }

  public List<Map<String, Object>> classes(String where, Object... args) {
    return jdbc.query(classSelect() + where + " order by tc.updated_at desc", classMapper(), args);
  }

  public Map<String, Object> classById(UUID id) {
    return required(classSelect() + " where tc.id = ?", classMapper(), id);
  }

  public List<Map<String, Object>> sessions(String where, Object... args) {
    return jdbc.query(sessionSelect() + where + " order by cs.scheduled_start asc", sessionMapper(), args);
  }

  public Map<String, Object> sessionById(UUID id) {
    return required(sessionSelect() + " where cs.id = ?", sessionMapper(), id);
  }

  public List<Map<String, Object>> reviews(String where, Object... args) {
    return jdbc.query("""
        select r.*, u.full_name reviewer_name, u.avatar_url
        from reviews r
        join users u on u.id = r.reviewer_id
        """ + where + " order by r.created_at desc", reviewMapper(), args);
  }

  public void notify(UUID userId, String type, String title, String message, String actionUrl, String entityType, UUID entityId) {
    jdbc.update("""
        insert into notifications(user_id, type, title, message, action_url, entity_type, entity_id)
        values (?, ?, ?, ?, ?, ?, ?)
        """, userId, type, title, message, actionUrl, entityType, entityId);
  }

  public void notifyAdmins(String type, String title, String message, String actionUrl, String entityType, UUID entityId) {
    List<UUID> admins = jdbc.query("select id from users where role = 'admin'", (rs, row) -> rs.getObject("id", UUID.class));
    for (UUID admin : admins) {
      notify(admin, type, title, message, actionUrl, entityType, entityId);
    }
  }

  public void audit(UUID actorId, String actorRole, String action, String entityType, UUID entityId, String description) {
    jdbc.update("""
        insert into audit_logs(actor_id, actor_role, action, entity_type, entity_id, description, metadata)
        values (?, ?, ?, ?, ?, ?, '{}'::jsonb)
        """, actorId, actorRole, action, entityType, entityId, description);
  }

  public void auditCurrent(String action, String entityType, UUID entityId, String description) {
    Map<String, Object> user = currentUserOrThrow();
    audit(UUID.fromString(user.get("id").toString()), user.get("role").toString(), action, entityType, entityId, description);
  }

  @Transactional
  public void refreshTutorRating(UUID tutorId) {
    Map<String, Object> stats = jdbc.queryForMap("""
        select coalesce(avg(rating), 0) avg_rating, count(*) count_rating
        from reviews
        where tutor_id = ? and status = 'visible'
        """, tutorId);
    jdbc.update("update tutor_profiles set rating_avg = ?, rating_count = ?, updated_at = now() where id = ?",
        stats.get("avg_rating"), ((Number) stats.get("count_rating")).intValue(), tutorId);
  }

  public int commissionFee(int amount) {
    Object value = optional("select value::text from system_settings where key='commissionRate'", (rs, row) -> rs.getString(1)).orElse("0.15");
    double rate;
    try {
      rate = Double.parseDouble(value.toString().replace("\"", ""));
    } catch (NumberFormatException ex) {
      rate = 0.15;
    }
    return (int) Math.round(amount * rate);
  }

  private String classSelect() {
    return """
        select tc.*, su.full_name student_name, tu.full_name tutor_name, s.name subject_name, gl.name grade_name,
               lr.parent_name
        from tutoring_classes tc
        join users su on su.id = tc.student_id
        join tutor_profiles tp on tp.id = tc.tutor_id
        join users tu on tu.id = tp.user_id
        join subjects s on s.id = tc.subject_id
        left join grade_levels gl on gl.id = tc.grade_level_id
        left join learning_requests lr on lr.id = tc.learning_request_id
        """;
  }

  private String sessionSelect() {
    return """
        select cs.*, su.full_name student_name, tu.full_name tutor_name, s.name subject_name, gl.name grade_name,
               tc.learning_mode, tc.location, tc.meeting_url
        from class_sessions cs
        join tutoring_classes tc on tc.id = cs.class_id
        join users su on su.id = cs.student_id
        join tutor_profiles tp on tp.id = cs.tutor_id
        join users tu on tu.id = tp.user_id
        join subjects s on s.id = tc.subject_id
        left join grade_levels gl on gl.id = tc.grade_level_id
        """;
  }

  private Map<String, Object> base(ResultSet rs) throws SQLException {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", str(rs, "id"));
    m.put("createdAt", ts(rs, "created_at"));
    m.put("updatedAt", hasColumn(rs, "updated_at") ? ts(rs, "updated_at") : null);
    return m;
  }

  private List<Map<String, Object>> availability(UUID tutorId) {
    return jdbc.query("""
        select id, day_of_week, start_time, end_time, is_active, created_at, updated_at
        from tutor_availability where tutor_id = ? and is_active = true order by day_of_week, start_time
        """, (rs, row) -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", str(rs, "id"));
      m.put("dayOfWeek", rs.getInt("day_of_week"));
      m.put("startTime", rs.getString("start_time").substring(0, 5));
      m.put("endTime", rs.getString("end_time").substring(0, 5));
      m.put("isActive", rs.getBoolean("is_active"));
      m.put("createdAt", ts(rs, "created_at"));
      m.put("updatedAt", ts(rs, "updated_at"));
      return m;
    }, tutorId);
  }

  private List<Map<String, Object>> documents(UUID tutorId) {
    return jdbc.query("""
        select * from tutor_documents where tutor_id = ? order by created_at desc
        """, (rs, row) -> {
      Map<String, Object> m = base(rs);
      m.put("tutorId", str(rs, "tutor_id"));
      m.put("name", rs.getString("document_type"));
      m.put("type", rs.getString("document_type"));
      m.put("documentType", rs.getString("document_type"));
      Object fileId = str(rs, "file_id");
      m.put("fileId", fileId);
      m.put("fileName", rs.getString("file_name"));
      m.put("fileUrl", fileId == null ? rs.getString("file_url") : "/api/v1/files/" + fileId);
      m.put("fileSize", rs.getLong("file_size"));
      m.put("mimeType", rs.getString("mime_type"));
      m.put("status", rs.getString("status"));
      m.put("note", rs.getString("review_note"));
      m.put("reviewNote", rs.getString("review_note"));
      m.put("reviewedAt", ts(rs, "reviewed_at"));
      m.put("reviewedBy", str(rs, "reviewed_by"));
      m.put("uploadedAt", ts(rs, "created_at"));
      return m;
    }, tutorId);
  }

  private String teachingModes(UUID tutorId) {
    List<String> modes = listStrings("select distinct teaching_mode from tutor_locations where tutor_id = ?", tutorId);
    if (modes.contains("both") || (modes.contains("online") && modes.contains("offline"))) return "both";
    if (modes.contains("offline")) return "offline";
    return "online";
  }

  private List<String> listStrings(String sql, Object... args) {
    return jdbc.query(sql, (rs, row) -> rs.getString(1), args);
  }

  private String normalizeUserStatus(String status) {
    if ("suspended".equals(status)) return "inactive";
    return status;
  }

  private String str(ResultSet rs, String column) throws SQLException {
    if (!hasColumn(rs, column)) return null;
    Object value = rs.getObject(column);
    return value == null ? null : value.toString();
  }

  private String ts(ResultSet rs, String column) throws SQLException {
    if (!hasColumn(rs, column) || rs.getObject(column) == null) return null;
    return rs.getObject(column, OffsetDateTime.class).toString();
  }

  private Integer nullableInt(ResultSet rs, String column) throws SQLException {
    int value = rs.getInt(column);
    return rs.wasNull() ? null : value;
  }

  private int intOrZero(ResultSet rs, String column) throws SQLException {
    int value = rs.getInt(column);
    return rs.wasNull() ? 0 : value;
  }

  private String valueOr(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private String location(String province, String district) {
    if (province == null) return district;
    if (district == null || district.isBlank()) return province;
    return district + ", " + province;
  }

  private String slug(String value) {
    String normalized = value.toLowerCase()
        .replace("đ", "d")
        .replaceAll("[^a-z0-9]+", "-")
        .replaceAll("(^-|-$)", "");
    return normalized.isBlank() ? "subject-" + UUID.randomUUID() : normalized;
  }

  private boolean hasColumn(ResultSet rs, String column) throws SQLException {
    int count = rs.getMetaData().getColumnCount();
    for (int i = 1; i <= count; i++) {
      if (rs.getMetaData().getColumnLabel(i).equalsIgnoreCase(column)) return true;
    }
    return false;
  }

  public List<Object> mutableArgs() {
    return new ArrayList<>();
  }
}
