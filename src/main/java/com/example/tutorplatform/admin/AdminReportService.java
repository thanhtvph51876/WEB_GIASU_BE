package com.example.tutorplatform.admin;

import com.example.tutorplatform.db.DbService;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AdminReportService {
  private final DbService db;
  private final JdbcTemplate jdbc;

  public AdminReportService(DbService db) {
    this.db = db;
    this.jdbc = db.jdbc();
  }

  public Map<String, Object> overview() {
    return Map.of(
        "totalUsers", count("users"),
        "totalTutors", count("tutor_profiles"),
        "pendingTutors", countWhere("tutor_profiles", "status = 'pending'"),
        "totalStudents", jdbc.queryForObject("select count(*) from users where role in ('student','parent')", Integer.class),
        "newRequests", countWhere("learning_requests", "status = 'new'"),
        "activeClasses", countWhere("tutoring_classes", "status = 'active'"),
        "pendingBookings", countWhere("trial_bookings", "status in ('pending','assigned','accepted')"),
        "totalRevenue", jdbc.queryForObject("select coalesce(sum(amount),0) from payments where status in ('paid','completed')", Long.class)
    );
  }

  public List<Map<String, Object>> requestTrends() {
    return jdbc.query("""
        select to_char(date_trunc('month', created_at), 'YYYY-MM') month, count(*) count
        from learning_requests group by 1 order by 1
        """, (rs, row) -> Map.of("month", rs.getString("month"), "count", rs.getInt("count")));
  }

  public List<Map<String, Object>> conversionFunnel() {
    return jdbc.query("""
        select status stage, count(*) count from learning_requests group by status order by status
        """, (rs, row) -> Map.of("stage", rs.getString("stage"), "count", rs.getInt("count")));
  }

  public List<Map<String, Object>> tutorStatusDistribution() {
    return distribution("tutor_profiles", "status", "status");
  }

  public List<Map<String, Object>> subjectDistribution() {
    return jdbc.query("""
        select s.name subject, count(lr.id) count from subjects s
        left join learning_requests lr on lr.subject_id = s.id
        group by s.name order by count desc
        """, (rs, row) -> Map.of("subject", rs.getString("subject"), "count", rs.getInt("count")));
  }

  public List<Map<String, Object>> teachingModeDistribution() {
    return jdbc.query("""
        select learning_mode mode, count(*) count from learning_requests
        where learning_mode is not null
        group by learning_mode order by learning_mode
        """, (rs, row) -> Map.of("mode", rs.getString("mode"), "count", rs.getInt("count")));
  }

  public List<Map<String, Object>> revenue() {
    return jdbc.query("""
        select to_char(date_trunc('month', created_at), 'YYYY-MM') month, coalesce(sum(amount),0) revenue
        from payments where status in ('paid','completed') group by 1 order by 1
        """, (rs, row) -> Map.of("month", rs.getString("month"), "revenue", rs.getLong("revenue")));
  }

  public List<Map<String, Object>> paymentStatusDistribution() {
    return distribution("payments", "status", "status");
  }

  public List<Map<String, Object>> lowRatingAlerts() {
    return db.reviews(" where r.rating < 3");
  }

  public List<Map<String, Object>> auditLogs() {
    return jdbc.query("""
        select al.*, u.full_name actor_name from audit_logs al
        left join users u on u.id = al.actor_id
        order by al.created_at desc limit 500
        """, db.auditMapper());
  }

  private int count(String table) {
    return jdbc.queryForObject("select count(*) from " + table, Integer.class);
  }

  private int countWhere(String table, String where) {
    return jdbc.queryForObject("select count(*) from " + table + " where " + where, Integer.class);
  }

  private List<Map<String, Object>> distribution(String table, String column, String label) {
    return jdbc.query("select " + column + " value, count(*) count from " + table + " group by " + column + " order by count desc",
        (rs, row) -> Map.of(label, rs.getString("value"), "count", rs.getInt("count")));
  }
}
