package com.example.tutorplatform.student;

import com.example.tutorplatform.common.ApiResponse;
import com.example.tutorplatform.common.ForbiddenException;
import com.example.tutorplatform.db.DbService;
import com.example.tutorplatform.parent.ParentStudentService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/student")
public class StudentDashboardController {
  private final DbService db;
  private final JdbcTemplate jdbc;
  private final ParentStudentService parentStudentService;

  public StudentDashboardController(DbService db, ParentStudentService parentStudentService) {
    this.db = db;
    this.jdbc = db.jdbc();
    this.parentStudentService = parentStudentService;
  }

  @GetMapping("/dashboard")
  public ApiResponse<Map<String, Object>> dashboard() {
    requireStudent();
    UUID userId = db.currentUserIdOrThrow();
    UUID profileId = parentStudentService.studentProfileForCurrentStudent();
    return ApiResponse.ok(Map.of(
        "schedule", scheduleData(userId, profileId),
        "classes", classData(userId, profileId),
        "assignments", assignments(userId, profileId),
        "materials", materials(userId, profileId),
        "progress", progressData(userId, profileId)
    ));
  }

  @GetMapping("/schedule")
  public ApiResponse<List<Map<String, Object>>> schedule() {
    requireStudent();
    return ApiResponse.ok(scheduleData(db.currentUserIdOrThrow(), parentStudentService.studentProfileForCurrentStudent()));
  }

  @GetMapping("/classes")
  public ApiResponse<List<Map<String, Object>>> classes() {
    requireStudent();
    return ApiResponse.ok(classData(db.currentUserIdOrThrow(), parentStudentService.studentProfileForCurrentStudent()));
  }

  @GetMapping("/assignments")
  public ApiResponse<List<Map<String, Object>>> assignmentList() {
    requireStudent();
    return ApiResponse.ok(assignments(db.currentUserIdOrThrow(), parentStudentService.studentProfileForCurrentStudent()));
  }

  @GetMapping("/materials")
  public ApiResponse<List<Map<String, Object>>> materialList() {
    requireStudent();
    return ApiResponse.ok(materials(db.currentUserIdOrThrow(), parentStudentService.studentProfileForCurrentStudent()));
  }

  @GetMapping("/progress")
  public ApiResponse<Map<String, Object>> progress() {
    requireStudent();
    return ApiResponse.ok(progressData(db.currentUserIdOrThrow(), parentStudentService.studentProfileForCurrentStudent()));
  }

  @PostMapping("/sessions/{sessionId}/check-in")
  public ApiResponse<Map<String, Object>> checkIn(@PathVariable UUID sessionId, @RequestBody(required = false) Map<String, Object> body) {
    requireStudent();
    UUID userId = db.currentUserIdOrThrow();
    Integer allowed = jdbc.queryForObject("select count(*) from class_sessions where id = ? and student_id = ?", Integer.class, sessionId, userId);
    if (allowed == null || allowed == 0) throw new ForbiddenException("Bạn chỉ được check-in buổi học của mình.");
    jdbc.update("""
        insert into student_check_ins(session_id, student_id, note)
        values (?, ?, ?)
        on conflict(session_id, student_id) do update set note = excluded.note, checked_in_at = now()
        """, sessionId, userId, body == null ? null : body.get("note"));
    db.auditCurrent("student.session.check_in", "session", sessionId, "Học sinh check-in buổi học.");
    return ApiResponse.ok(Map.of("sessionId", sessionId.toString(), "checkedIn", true));
  }

  private List<Map<String, Object>> scheduleData(UUID userId, UUID profileId) {
    return jdbc.query("""
        select cs.id, cs.class_id, cs.scheduled_start, cs.scheduled_end, cs.status, tc.title, s.name subject_name,
               tu.full_name tutor_name, tc.meeting_url, tc.location
        from class_sessions cs
        join tutoring_classes tc on tc.id = cs.class_id
        join subjects s on s.id = tc.subject_id
        join tutor_profiles tp on tp.id = cs.tutor_id
        join users tu on tu.id = tp.user_id
        where cs.student_id = ? or (?::uuid is not null and cs.student_profile_id = ?)
        order by cs.scheduled_start desc limit 100
        """, this::mapAny, userId, profileId, profileId);
  }

  private List<Map<String, Object>> classData(UUID userId, UUID profileId) {
    return jdbc.query("""
        select tc.id, tc.title, tc.status, tc.learning_mode, tc.location, tc.meeting_url, s.name subject_name,
               gl.name grade_name, tu.full_name tutor_name, tc.start_date, tc.end_date
        from tutoring_classes tc
        join subjects s on s.id = tc.subject_id
        left join grade_levels gl on gl.id = tc.grade_level_id
        join tutor_profiles tp on tp.id = tc.tutor_id
        join users tu on tu.id = tp.user_id
        where tc.student_id = ? or (?::uuid is not null and tc.student_profile_id = ?)
        order by tc.created_at desc
        """, this::mapAny, userId, profileId, profileId);
  }

  private List<Map<String, Object>> assignments(UUID userId, UUID profileId) {
    return jdbc.query("""
        select a.*, tc.title class_title from assignments a join tutoring_classes tc on tc.id = a.class_id
        where a.student_id = ? or (?::uuid is not null and a.student_profile_id = ?)
        order by a.created_at desc
        """, this::mapAny, userId, profileId, profileId);
  }

  private List<Map<String, Object>> materials(UUID userId, UUID profileId) {
    return jdbc.query("""
        select lm.*, tc.title class_title, case when lm.file_id is null then null else '/api/v1/files/' || lm.file_id end file_url
        from learning_materials lm join tutoring_classes tc on tc.id = lm.class_id
        where lm.student_id = ? or (?::uuid is not null and lm.student_profile_id = ?)
        order by lm.created_at desc
        """, this::mapAny, userId, profileId, profileId);
  }

  private Map<String, Object> progressData(UUID userId, UUID profileId) {
    return Map.of(
        "completedSessions", count("select count(*) from class_sessions where student_id = ? and status = 'completed'", userId),
        "activeClasses", count("select count(*) from tutoring_classes where student_id = ? and status = 'active'", userId),
        "profileId", profileId == null ? "" : profileId.toString()
    );
  }

  private void requireStudent() {
    String role = db.currentUserOrThrow().get("role").toString();
    if (!"student".equals(role)) throw new ForbiddenException("Chức năng này dành cho học sinh.");
  }

  private int count(String sql, Object... args) {
    Integer value = jdbc.queryForObject(sql, Integer.class, args);
    return value == null ? 0 : value;
  }

  private Map<String, Object> mapAny(ResultSet rs, int row) throws SQLException {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
      String key = camel(rs.getMetaData().getColumnLabel(i));
      Object value = rs.getObject(i);
      if (value instanceof UUID uuid) value = uuid.toString();
      if (value instanceof OffsetDateTime time) value = time.toString();
      m.put(key, value);
    }
    return m;
  }

  private String camel(String label) {
    StringBuilder sb = new StringBuilder();
    boolean upper = false;
    for (char c : label.toCharArray()) {
      if (c == '_') upper = true;
      else if (upper) { sb.append(Character.toUpperCase(c)); upper = false; }
      else sb.append(c);
    }
    return sb.toString();
  }
}
