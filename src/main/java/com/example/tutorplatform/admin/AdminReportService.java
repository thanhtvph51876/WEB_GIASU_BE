package com.example.tutorplatform.admin;

import com.example.tutorplatform.db.DbService;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

@Service
public class AdminReportService {
  private static final Logger log = LoggerFactory.getLogger(AdminReportService.class);
  private static final Duration CACHE_TTL = Duration.ofSeconds(60);
  private final DbService db;
  private final JdbcTemplate jdbc;
  private final Map<String, CacheEntry<?>> cache = new ConcurrentHashMap<>();

  public AdminReportService(DbService db) {
    this.db = db;
    this.jdbc = db.jdbc();
  }

  public Map<String, Object> overview() {
    return cached("overview", () -> {
      try {
        return jdbc.queryForObject("""
            select total_users, total_tutors, pending_tutors, total_students,
                   new_requests, active_classes, pending_bookings, total_revenue
            from admin_report_overview_mv
            where id = 1
            """, (rs, row) -> row(
            "totalUsers", rs.getLong("total_users"),
            "totalTutors", rs.getLong("total_tutors"),
            "pendingTutors", rs.getLong("pending_tutors"),
            "totalStudents", rs.getLong("total_students"),
            "newRequests", rs.getLong("new_requests"),
            "activeClasses", rs.getLong("active_classes"),
            "pendingBookings", rs.getLong("pending_bookings"),
            "totalRevenue", rs.getLong("total_revenue")
        ));
      } catch (DataAccessException ex) {
        log.warn("Admin overview materialized view unavailable; falling back to live aggregate", ex);
        return liveOverview();
      }
    });
  }

  public List<Map<String, Object>> requestTrends() {
    return cached("requestTrends", () -> safeReport("requestTrends", """
        select to_char(bucket_month, 'YYYY-MM') as bucket_month, count as total
        from admin_report_request_trends_mv
        order by bucket_month
        """, (rs, row) -> row("month", rs.getString("bucket_month"), "count", rs.getLong("total")), this::liveRequestTrends));
  }

  public List<Map<String, Object>> conversionFunnel() {
    return cached("conversionFunnel", () -> safeReport("conversionFunnel", """
        select stage, count
        from admin_report_conversion_funnel_mv
        order by stage
        """, (rs, row) -> row("stage", rs.getString("stage"), "count", rs.getLong("count")), this::liveConversionFunnel));
  }

  public List<Map<String, Object>> tutorStatusDistribution() {
    return cached("tutorStatusDistribution", () -> safeReport("tutorStatusDistribution", """
        select status, count
        from admin_report_tutor_status_distribution_mv
        order by count desc
        """, (rs, row) -> row("status", rs.getString("status"), "count", rs.getLong("count")),
        () -> distribution("tutor_profiles", "status", "status")));
  }

  public List<Map<String, Object>> subjectDistribution() {
    return cached("subjectDistribution", () -> safeReport("subjectDistribution", """
        select subject, count
        from admin_report_subject_distribution_mv
        order by count desc
        """, (rs, row) -> row("subject", rs.getString("subject"), "count", rs.getLong("count")), this::liveSubjectDistribution));
  }

  public List<Map<String, Object>> teachingModeDistribution() {
    return cached("teachingModeDistribution", () -> safeReport("teachingModeDistribution", """
        select mode, count
        from admin_report_teaching_mode_distribution_mv
        order by mode
        """, (rs, row) -> row("mode", rs.getString("mode"), "count", rs.getLong("count")), this::liveTeachingModeDistribution));
  }

  public List<Map<String, Object>> revenue() {
    return cached("revenue", () -> safeReport("revenue", """
        select to_char(bucket_month, 'YYYY-MM') month, revenue
        from admin_report_revenue_mv
        order by month
        """, (rs, row) -> row("month", rs.getString("month"), "revenue", rs.getLong("revenue")), this::liveRevenue));
  }

  public List<Map<String, Object>> paymentStatusDistribution() {
    return cached("paymentStatusDistribution", () -> safeReport("paymentStatusDistribution", """
        select status, count
        from admin_report_payment_status_distribution_mv
        order by count desc
        """, (rs, row) -> row("status", rs.getString("status"), "count", rs.getLong("count")),
        () -> distribution("payments", "status", "status")));
  }

  public List<Map<String, Object>> lowRatingAlerts() {
    return cached("lowRatingAlerts", () -> safeReport("lowRatingAlerts", """
        select r.*, u.full_name reviewer_name, u.avatar_url
        from reviews r
        join users u on u.id = r.reviewer_id
        where r.rating < 3
        order by r.created_at desc
        limit 200
        """, db.reviewMapper(), () -> db.reviews(" where r.rating < 3").stream().limit(200).toList()));
  }

  public List<Map<String, Object>> auditLogs() {
    return jdbc.query("""
        select al.*, u.full_name actor_name from audit_logs al
        left join users u on u.id = al.actor_id
        order by al.created_at desc limit 500
        """, db.auditMapper());
  }

  public List<Map<String, Object>> auditLogs(int limit, int offset) {
    return jdbc.query("""
        select al.*, u.full_name actor_name from audit_logs al
        left join users u on u.id = al.actor_id
        order by al.created_at desc limit ? offset ?
        """, db.auditMapper(), limit, offset);
  }

  public void clearCache() {
    cache.clear();
  }

  private Map<String, Object> liveOverview() {
    return Map.of(
        "totalUsers", count("users"),
        "totalTutors", count("tutor_profiles"),
        "pendingTutors", countWhere("tutor_profiles", "status in ('submitted','pending','pending_verification','needs_more_documents','need_update','verified')"),
        "totalStudents", jdbc.queryForObject("select count(*) from users where role in ('student','parent')", Long.class),
        "newRequests", countWhere("learning_requests", "status = 'new'"),
        "activeClasses", countWhere("tutoring_classes", "status = 'active'"),
        "pendingBookings", countWhere("trial_bookings", "status in ('pending','assigned','accepted')"),
        "totalRevenue", jdbc.queryForObject("select coalesce(sum(amount),0) from payments where status in ('paid','completed')", Long.class)
    );
  }

  private List<Map<String, Object>> liveRequestTrends() {
    return safeReport("liveRequestTrends", """
        select to_char(date_trunc('month', coalesce(created_at, updated_at, now())), 'YYYY-MM') as bucket_month,
               count(*)::bigint as total
        from learning_requests
        group by bucket_month
        order by bucket_month
        """, (rs, row) -> row("month", rs.getString("bucket_month"), "count", rs.getLong("total")));
  }

  private List<Map<String, Object>> liveConversionFunnel() {
    return safeReport("liveConversionFunnel", """
        select coalesce(status, 'unknown') as stage, count(*)::bigint as count
        from learning_requests
        group by coalesce(status, 'unknown')
        order by stage
        """, (rs, row) -> row("stage", rs.getString("stage"), "count", rs.getLong("count")));
  }

  private List<Map<String, Object>> liveSubjectDistribution() {
    return safeReport("liveSubjectDistribution", """
        select coalesce(s.name, 'Chưa phân loại') subject, count(lr.id)::bigint count
        from subjects s
        left join learning_requests lr on lr.subject_id = s.id
        group by subject
        order by count desc
        """, (rs, row) -> row("subject", rs.getString("subject"), "count", rs.getLong("count")));
  }

  private List<Map<String, Object>> liveTeachingModeDistribution() {
    return safeReport("liveTeachingModeDistribution", """
        select coalesce(learning_mode, 'unknown') as mode, count(*)::bigint as count
        from learning_requests
        group by coalesce(learning_mode, 'unknown')
        order by mode
        """, (rs, row) -> row("mode", rs.getString("mode"), "count", rs.getLong("count")));
  }

  private List<Map<String, Object>> liveRevenue() {
    return safeReport("liveRevenue", """
        select to_char(date_trunc('month', coalesce(created_at, paid_at, now())), 'YYYY-MM') month,
               coalesce(sum(amount),0)::bigint revenue
        from payments
        where status in ('paid','completed')
        group by month
        order by month
        """, (rs, row) -> row("month", rs.getString("month"), "revenue", rs.getLong("revenue")));
  }

  private long count(String table) {
    return jdbc.queryForObject("select count(*) from " + table, Long.class);
  }

  private long countWhere(String table, String where) {
    return jdbc.queryForObject("select count(*) from " + table + " where " + where, Long.class);
  }

  private List<Map<String, Object>> distribution(String table, String column, String label) {
    return safeReport("distribution:" + table + "." + column,
        "select coalesce(" + column + "::text, 'unknown') value, count(*)::bigint count from " + table + " group by value order by count desc",
        (rs, row) -> row(label, rs.getString("value"), "count", rs.getLong("count")));
  }

  private List<Map<String, Object>> safeReport(
      String reportName,
      String sql,
      RowMapper<Map<String, Object>> mapper,
      Supplier<List<Map<String, Object>>> fallback
  ) {
    try {
      return jdbc.query(sql, mapper);
    } catch (DataAccessException ex) {
      log.warn("Admin report {} failed; using fallback dataset", reportName, ex);
      return fallback.get();
    }
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

  @SuppressWarnings("unchecked")
  private <T> T cached(String key, Supplier<T> loader) {
    CacheEntry<?> existing = cache.get(key);
    if (existing != null && existing.expiresAt().isAfter(Instant.now())) {
      return (T) existing.value();
    }
    T value = loader.get();
    cache.put(key, new CacheEntry<>(value, Instant.now().plus(CACHE_TTL)));
    return value;
  }

  private record CacheEntry<T>(T value, Instant expiresAt) {}
}
