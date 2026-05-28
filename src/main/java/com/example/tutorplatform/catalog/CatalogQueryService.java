package com.example.tutorplatform.catalog;

import com.example.tutorplatform.db.DbService;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CatalogQueryService {
  private final JdbcTemplate jdbc;

  public CatalogQueryService(DbService db) {
    this.jdbc = db.jdbc();
  }

  public List<Map<String, Object>> subjects() {
    return jdbc.query("""
        select s.*, (select count(distinct tutor_id) from tutor_subjects ts where ts.subject_id = s.id) tutor_count
        from subjects s order by name
        """, (rs, row) -> Map.of(
        "id", rs.getObject("id").toString(),
        "name", rs.getString("name"),
        "slug", rs.getString("slug"),
        "description", rs.getString("description"),
        "icon", "",
        "category", "school",
        "tutorCount", rs.getInt("tutor_count")
    ));
  }

  public List<Map<String, Object>> gradeLevels() {
    return jdbc.query("select * from grade_levels order by sort_order", (rs, row) -> Map.of(
        "id", rs.getObject("id").toString(),
        "name", rs.getString("name"),
        "group", groupForGrade(rs.getInt("sort_order")),
        "sortOrder", rs.getInt("sort_order")
    ));
  }

  public Map<String, Object> publicStats() {
    long totalTutors = jdbc.queryForObject("select count(*) from tutor_profiles where status = 'approved'", Long.class);
    long totalStudents = jdbc.queryForObject("select count(*) from users where role in ('student','parent')", Long.class);
    long completedSessions = jdbc.queryForObject("select count(*) from class_sessions where status = 'completed'", Long.class);
    long reviewCount = jdbc.queryForObject("select count(*) from reviews where status = 'visible'", Long.class);
    long positiveReviews = jdbc.queryForObject("select count(*) from reviews where status = 'visible' and rating >= 4", Long.class);
    Double averageRating = jdbc.queryForObject("select coalesce(avg(rating), 0) from reviews where status = 'visible'", Double.class);
    int satisfactionRate = reviewCount == 0 ? 0 : (int) Math.round(positiveReviews * 100.0 / reviewCount);
    return Map.of(
        "totalTutors", totalTutors,
        "totalStudents", totalStudents,
        "completedSessions", completedSessions,
        "satisfactionRate", satisfactionRate,
        "verifiedTutors", totalTutors,
        "averageRating", averageRating == null ? 0 : Math.round(averageRating * 10.0) / 10.0
    );
  }

  private String groupForGrade(int sortOrder) {
    if (sortOrder <= 5) return "primary";
    if (sortOrder <= 9) return "secondary";
    return "high_school";
  }
}
