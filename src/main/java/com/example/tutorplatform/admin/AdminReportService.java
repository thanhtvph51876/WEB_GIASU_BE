package com.example.tutorplatform.admin;

import com.example.tutorplatform.db.DbService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

@Service
public class AdminReportService {
  private static final Logger log = LoggerFactory.getLogger(AdminReportService.class);
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
        "pendingTutors", countWhere("tutor_profiles", "status in ('submitted','pending','pending_verification','needs_more_documents','need_update','verified')"),
        "totalStudents", jdbc.queryForObject("select count(*) from users where role in ('student','parent')", Integer.class),
        "newRequests", countWhere("learning_requests", "status = 'new'"),
        "activeClasses", countWhere("tutoring_classes", "status = 'active'"),
        "pendingBookings", countWhere("trial_bookings", "status in ('pending','assigned','accepted')"),
        "totalRevenue", jdbc.queryForObject("select coalesce(sum(amount),0) from payments where status in ('paid','completed')", Long.class)
    );
  }

  public List<Map<String, Object>> requestTrends() {
    return safeReport("requestTrends", """
        select to_char(date_trunc('month', coalesce(created_at, updated_at, now())), 'YYYY-MM') as bucket_month,
               count(*)::int as total
        from learning_requests
        group by bucket_month
        order by bucket_month
        """, (rs, row) -> row("month", rs.getString("bucket_month"), "count", rs.getInt("total")));
  }

  public List<Map<String, Object>> conversionFunnel() {
    return safeReport("conversionFunnel", """
        select coalesce(status, 'unknown') stage, count(*)::int count
        from learning_requests
        group by stage
        order by stage
        """, (rs, row) -> row("stage", rs.getString("stage"), "count", rs.getInt("count")));
  }

  public List<Map<String, Object>> tutorStatusDistribution() {
    return distribution("tutor_profiles", "status", "status");
  }

  public List<Map<String, Object>> subjectDistribution() {
    return safeReport("subjectDistribution", """
        select coalesce(s.name, 'Chưa phân loại') subject, count(lr.id)::int count
        from subjects s
        left join learning_requests lr on lr.subject_id = s.id
        group by subject
        order by count desc
        """, (rs, row) -> row("subject", rs.getString("subject"), "count", rs.getInt("count")));
  }

  public List<Map<String, Object>> teachingModeDistribution() {
    return safeReport("teachingModeDistribution", """
        select coalesce(learning_mode, 'unknown') mode, count(*)::int count
        from learning_requests
        group by mode
        order by mode
        """, (rs, row) -> row("mode", rs.getString("mode"), "count", rs.getInt("count")));
  }

  public List<Map<String, Object>> revenue() {
    return safeReport("revenue", """
        select to_char(date_trunc('month', coalesce(created_at, paid_at, now())), 'YYYY-MM') month,
               coalesce(sum(amount),0) revenue
        from payments
        where status in ('paid','completed')
        group by month
        order by month
        """, (rs, row) -> row("month", rs.getString("month"), "revenue", rs.getLong("revenue")));
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
    return safeReport("distribution:" + table + "." + column,
        "select coalesce(" + column + "::text, 'unknown') value, count(*)::int count from " + table + " group by value order by count desc",
        (rs, row) -> row(label, rs.getString("value"), "count", rs.getInt("count")));
  }

  private List<Map<String, Object>> safeReport(String reportName, String sql, RowMapper<Map<String, Object>> mapper) {
    try {
      return jdbc.query(sql, mapper);
    } catch (DataAccessException ex) {
      log.warn("Admin report {} failed; returning empty dataset", reportName, ex);
      return List.of();
    }
  }

  private Map<String, Object> row(Object... entries) {
    Map<String, Object> row = new LinkedHashMap<>();
    for (int i = 0; i + 1 < entries.length; i += 2) {
      String key = String.valueOf(entries[i]);
      Object value = entries[i + 1];
      row.put(key, value == null ? "unknown" : value);
    }
    return row;
  }
}
