package com.example.tutorplatform.masterdata;

import com.example.tutorplatform.common.BusinessException;
import com.example.tutorplatform.db.DbService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MasterDataService {
  private final JdbcTemplate jdbc;

  public MasterDataService(DbService db) {
    this.jdbc = db.jdbc();
  }

  public List<Map<String, Object>> locations(String type, UUID parentId, Boolean activeOnly) {
    List<Object> args = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where 1=1 ");
    if (type != null && !type.isBlank()) {
      where.append(" and type = ? ");
      args.add(type.trim().toUpperCase());
    }
    if (parentId != null) {
      where.append(" and parent_id = ? ");
      args.add(parentId);
    }
    if (!Boolean.FALSE.equals(activeOnly)) where.append(" and is_active = true ");
    return jdbc.query("select * from locations" + where + " order by type, full_path, name", this::mapRow, args.toArray());
  }

  public List<Map<String, Object>> subjectCategories(Boolean activeOnly) {
    String active = Boolean.FALSE.equals(activeOnly) ? "" : " where is_active = true";
    return jdbc.query("select * from subject_categories" + active + " order by sort_order, name", this::mapRow);
  }

  public List<Map<String, Object>> subjects(UUID categoryId, String q, Boolean activeOnly) {
    List<Object> args = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where 1=1 ");
    if (categoryId != null) {
      where.append(" and s.category_id = ? ");
      args.add(categoryId);
    }
    if (q != null && !q.isBlank()) {
      where.append(" and (s.normalized_name like lower(unaccent(?)) or exists (select 1 from subject_aliases sa where sa.subject_id = s.id and sa.normalized_alias like lower(unaccent(?)))) ");
      args.add("%" + q + "%");
      args.add("%" + q + "%");
    }
    if (!Boolean.FALSE.equals(activeOnly)) where.append(" and s.is_active = true ");
    return jdbc.query("""
        select s.*, sc.name category_name, sc.slug category_slug,
          (select coalesce(count(*),0) from tutor_subjects ts where ts.subject_id = s.id) tutor_count
        from subjects s
        left join subject_categories sc on sc.id = s.category_id
        """ + where + " order by coalesce(sc.sort_order, 999), s.name", this::mapRow, args.toArray());
  }

  public List<Map<String, Object>> educationLevels(Boolean activeOnly) {
    String active = Boolean.FALSE.equals(activeOnly) ? "" : " where is_active = true";
    return jdbc.query("select * from education_levels" + active + " order by sort_order, name", this::mapRow);
  }

  public List<Map<String, Object>> grades(UUID educationLevelId, Boolean activeOnly) {
    List<Object> args = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where 1=1 ");
    if (educationLevelId != null) {
      where.append(" and g.education_level_id = ? ");
      args.add(educationLevelId);
    }
    if (!Boolean.FALSE.equals(activeOnly)) where.append(" and g.is_active = true ");
    return jdbc.query("""
        select g.*, el.name education_level_name, el.code education_level_code
        from grades g
        left join education_levels el on el.id = g.education_level_id
        """ + where + " order by coalesce(el.sort_order, 999), g.sort_order, g.name", this::mapRow, args.toArray());
  }

  public List<Map<String, Object>> languages(Boolean activeOnly) {
    String active = Boolean.FALSE.equals(activeOnly) ? "" : " where is_active = true";
    return jdbc.query("select * from languages" + active + " order by name", this::mapRow);
  }

  public List<Map<String, Object>> certificates(Boolean activeOnly) {
    String active = Boolean.FALSE.equals(activeOnly) ? "" : " where c.is_active = true";
    return jdbc.query("""
        select c.*, l.code language_code, l.name language_name
        from certificates c
        left join languages l on l.id = c.language_id
        """ + active + " order by c.name", this::mapRow);
  }

  public List<Map<String, Object>> teachingModes(Boolean activeOnly) {
    String active = Boolean.FALSE.equals(activeOnly) ? "" : " where is_active = true";
    return jdbc.query("select * from teaching_modes" + active + " order by name", this::mapRow);
  }

  public List<Map<String, Object>> cancellationPolicies(Boolean activeOnly) {
    String active = Boolean.FALSE.equals(activeOnly) ? "" : " where is_active = true";
    return jdbc.query("select * from cancellation_policies" + active + " order by applies_to, name", this::mapRow);
  }

  public UUID uuid(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException ex) {
      throw new BusinessException("INVALID_ID", "ID không hợp lệ.");
    }
  }

  private Map<String, Object> mapRow(ResultSet rs, int row) throws SQLException {
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
      if (c == '_') {
        upper = true;
      } else if (upper) {
        sb.append(Character.toUpperCase(c));
        upper = false;
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }
}
