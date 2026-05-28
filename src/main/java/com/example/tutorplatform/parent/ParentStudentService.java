package com.example.tutorplatform.parent;

import com.example.tutorplatform.common.BusinessException;
import com.example.tutorplatform.common.ForbiddenException;
import com.example.tutorplatform.common.NotFoundException;
import com.example.tutorplatform.db.DbService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ParentStudentService {
  private final DbService db;
  private final JdbcTemplate jdbc;

  public ParentStudentService(DbService db) {
    this.db = db;
    this.jdbc = db.jdbc();
  }

  @Transactional
  public Map<String, Object> createStudent(Map<String, Object> body) {
    requireParent();
    UUID parentId = db.currentUserIdOrThrow();
    UUID householdId = ensureHousehold(parentId, string(body, "householdName", "Gia đình"));
    String fullName = required(body, "fullName");
    UUID gradeId = uuid(body.get("gradeId"));
    UUID studentId = jdbc.queryForObject("""
        insert into student_profiles(user_id, household_id, full_name, date_of_birth, gender, grade_id, school_name, learning_goal, note)
        values (?, ?, ?, ?::date, ?, ?, ?, ?, ?) returning id
        """, UUID.class, uuid(body.get("userId")), householdId, fullName, string(body, "dateOfBirth", null),
        string(body, "gender", null), gradeId, string(body, "schoolName", null), string(body, "learningGoal", null), string(body, "note", null));
    jdbc.update("""
        insert into guardian_student_links(guardian_user_id, student_profile_id, relationship, can_pay, can_book, can_message_tutor, can_view_report, can_manage_profile)
        values (?, ?, ?, ?, ?, ?, ?, ?)
        on conflict(guardian_user_id, student_profile_id) do update set
          relationship = excluded.relationship,
          can_pay = excluded.can_pay,
          can_book = excluded.can_book,
          can_message_tutor = excluded.can_message_tutor,
          can_view_report = excluded.can_view_report,
          can_manage_profile = excluded.can_manage_profile,
          updated_at = now()
        """, parentId, studentId, string(body, "relationship", "OTHER"), bool(body, "canPay", true), bool(body, "canBook", true),
        bool(body, "canMessageTutor", true), bool(body, "canViewReport", true), bool(body, "canManageProfile", true));
    db.auditCurrent("parent.student.create", "studentProfile", studentId, "Phụ huynh tạo hồ sơ học sinh.");
    return studentById(studentId);
  }

  public List<Map<String, Object>> myStudents() {
    requireParent();
    return jdbc.query(studentSelect() + " where gsl.guardian_user_id = ? order by sp.created_at desc", this::mapStudent, db.currentUserIdOrThrow());
  }

  public Map<String, Object> studentById(UUID studentProfileId) {
    requireGuardianAccess(studentProfileId, "can_view_report");
    return jdbc.query(studentSelect() + " where sp.id = ?", this::mapStudent, studentProfileId).stream()
        .findFirst().orElseThrow(() -> new NotFoundException("Không tìm thấy hồ sơ học sinh."));
  }

  @Transactional
  public Map<String, Object> updateStudent(UUID studentProfileId, Map<String, Object> body) {
    requireGuardianAccess(studentProfileId, "can_manage_profile");
    jdbc.update("""
        update student_profiles set full_name = coalesce(?, full_name), date_of_birth = coalesce(?::date, date_of_birth),
          gender = coalesce(?, gender), grade_id = coalesce(?, grade_id), school_name = coalesce(?, school_name),
          learning_goal = coalesce(?, learning_goal), note = coalesce(?, note), updated_at = now()
        where id = ?
        """, string(body, "fullName", null), string(body, "dateOfBirth", null), string(body, "gender", null), uuid(body.get("gradeId")),
        string(body, "schoolName", null), string(body, "learningGoal", null), string(body, "note", null), studentProfileId);
    db.auditCurrent("parent.student.update", "studentProfile", studentProfileId, "Phụ huynh cập nhật hồ sơ học sinh.");
    return studentById(studentProfileId);
  }

  public Map<String, Object> dashboard(UUID studentProfileId) {
    requireGuardianAccess(studentProfileId, "can_view_report");
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("student", studentById(studentProfileId));
    m.put("openRequests", count("select count(*) from learning_requests where student_profile_id = ? and status not in ('cancelled','completed','closed','converted_to_class')", studentProfileId));
    m.put("pendingProposals", count("""
        select count(*) from tutor_proposals tp join learning_requests lr on lr.id = tp.learning_request_id
        where lr.student_profile_id = ? and tp.status in ('SENT','VIEWED','SHORTLISTED')
        """, studentProfileId));
    m.put("trialBookingsNeedConfirmation", count("select count(*) from trial_bookings where student_profile_id = ? and status in ('requested','tutor_confirmed','parent_confirmed')", studentProfileId));
    m.put("upcomingLessons", schedule(studentProfileId));
    m.put("paymentSummary", paymentSummary(studentProfileId));
    m.put("progress", progress(studentProfileId));
    return m;
  }

  public List<Map<String, Object>> schedule(UUID studentProfileId) {
    requireGuardianAccess(studentProfileId, "can_view_report");
    return jdbc.query("""
        select cs.id, cs.class_id, cs.scheduled_start, cs.scheduled_end, cs.status, tc.title, s.name subject_name,
               tu.full_name tutor_name, tc.meeting_url, tc.location
        from class_sessions cs
        join tutoring_classes tc on tc.id = cs.class_id
        join subjects s on s.id = tc.subject_id
        join tutor_profiles tp on tp.id = cs.tutor_id
        join users tu on tu.id = tp.user_id
        where cs.student_profile_id = ? or tc.student_profile_id = ?
        order by cs.scheduled_start desc limit 100
        """, this::mapSchedule, studentProfileId, studentProfileId);
  }

  public Map<String, Object> progress(UUID studentProfileId) {
    requireGuardianAccess(studentProfileId, "can_view_report");
    return Map.of(
        "completedSessions", count("select count(*) from class_sessions where student_profile_id = ? and status = 'completed'", studentProfileId),
        "activeClasses", count("select count(*) from tutoring_classes where student_profile_id = ? and status = 'active'", studentProfileId),
        "latestReports", reports(studentProfileId)
    );
  }

  public List<Map<String, Object>> payments(UUID studentProfileId) {
    requireGuardianAccess(studentProfileId, "can_pay");
    return jdbc.query("""
        select distinct p.*
        from payments p
        join class_sessions cs on cs.id = p.session_id
        where cs.student_profile_id = ?
        order by p.created_at desc
        """, (rs, row) -> mapPayment(rs), studentProfileId);
  }

  public Map<String, Object> paymentSummary(UUID studentProfileId) {
    requireGuardianAccess(studentProfileId, "can_pay");
    return Map.of(
        "pending", count("""
            select count(*) from payments p join class_sessions cs on cs.id = p.session_id
            where cs.student_profile_id = ? and p.status in ('pending','processing','failed')
            """, studentProfileId),
        "paidAmount", jdbc.queryForObject("""
            select coalesce(sum(p.amount),0) from payments p join class_sessions cs on cs.id = p.session_id
            where cs.student_profile_id = ? and p.status in ('paid','completed')
            """, Integer.class, studentProfileId)
    );
  }

  public void requireGuardianAccess(UUID studentProfileId, String permissionColumn) {
    if (db.isAdmin()) return;
    UUID current = db.currentUserIdOrThrow();
    Integer count = jdbc.queryForObject("select count(*) from guardian_student_links where guardian_user_id = ? and student_profile_id = ? and " + permissionColumn + " = true", Integer.class, current, studentProfileId);
    if (count == null || count == 0) throw new ForbiddenException("Bạn không có quyền truy cập hồ sơ học sinh này.");
  }

  public UUID studentProfileForCurrentStudent() {
    UUID userId = db.currentUserIdOrThrow();
    return db.optional("select id from student_profiles where user_id = ?", (rs, row) -> rs.getObject("id", UUID.class), userId).orElse(null);
  }

  private UUID ensureHousehold(UUID parentId, String name) {
    return db.optional("select id from households where owner_parent_id = ? order by created_at limit 1", (rs, row) -> rs.getObject("id", UUID.class), parentId)
        .orElseGet(() -> jdbc.queryForObject("insert into households(name, owner_parent_id) values (?, ?) returning id", UUID.class, name, parentId));
  }

  private void requireParent() {
    String role = db.currentUserOrThrow().get("role").toString();
    if (!"parent".equals(role) && !db.isAdmin()) throw new ForbiddenException("Chức năng này dành cho phụ huynh.");
  }

  private List<Map<String, Object>> reports(UUID studentProfileId) {
    return jdbc.query("""
        select csr.*, cs.scheduled_start, tc.title class_title
        from class_session_reports csr
        join class_sessions cs on cs.id = csr.session_id
        join tutoring_classes tc on tc.id = cs.class_id
        where cs.student_profile_id = ?
        order by csr.created_at desc limit 10
        """, (rs, row) -> mapAny(rs), studentProfileId);
  }

  private String studentSelect() {
    return """
        select sp.*, h.name household_name, gsl.relationship, gsl.can_pay, gsl.can_book, gsl.can_message_tutor,
               gsl.can_view_report, gsl.can_manage_profile, g.name grade_name, g.code grade_code
        from student_profiles sp
        join guardian_student_links gsl on gsl.student_profile_id = sp.id
        left join households h on h.id = sp.household_id
        left join grades g on g.id = sp.grade_id
        """;
  }

  private Map<String, Object> mapStudent(ResultSet rs, int row) throws SQLException {
    Map<String, Object> m = mapAny(rs);
    m.put("grade", rs.getString("grade_name"));
    m.put("gradeCode", rs.getString("grade_code"));
    return m;
  }

  private Map<String, Object> mapSchedule(ResultSet rs, int row) throws SQLException {
    return mapAny(rs);
  }

  private Map<String, Object> mapPayment(ResultSet rs) throws SQLException {
    return mapAny(rs);
  }

  private Map<String, Object> mapAny(ResultSet rs) throws SQLException {
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

  private int count(String sql, Object... args) {
    Integer value = jdbc.queryForObject(sql, Integer.class, args);
    return value == null ? 0 : value;
  }

  private String required(Map<String, Object> body, String key) {
    String value = string(body, key, null);
    if (value == null || value.isBlank()) throw new BusinessException("FIELD_REQUIRED", "Thiếu trường " + key + ".");
    return value;
  }

  private String string(Map<String, Object> body, String key, String fallback) {
    Object value = body.get(key);
    return value == null || value.toString().isBlank() ? fallback : value.toString();
  }

  private UUID uuid(Object value) {
    if (value == null || value.toString().isBlank()) return null;
    return UUID.fromString(value.toString());
  }

  private boolean bool(Map<String, Object> body, String key, boolean fallback) {
    Object value = body.get(key);
    return value == null ? fallback : Boolean.parseBoolean(value.toString());
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
