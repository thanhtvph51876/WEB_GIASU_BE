package com.example.tutorplatform.platform;

import com.example.tutorplatform.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PlatformRequestSupport {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private PlatformRequestSupport() {
  }

  public static Object firstPresent(Map<String, Object> body, String... keys) {
    if (body == null) return null;
    for (String key : keys) {
      if (body.containsKey(key) && body.get(key) != null) return body.get(key);
    }
    return null;
  }

  public static String firstString(Map<String, Object> body, String... keys) {
    Object value = firstPresent(body, keys);
    return value == null || value.toString().isBlank() ? null : value.toString();
  }

  public static String string(Map<String, Object> body, String key) {
    return firstString(body, key);
  }

  public static String nestedString(Map<String, Object> body, String objectKey, String... keys) {
    if (body == null || !(body.get(objectKey) instanceof Map<?, ?> nested)) return null;
    for (String key : keys) {
      Object value = nested.get(key);
      if (value != null && !value.toString().isBlank()) return value.toString();
    }
    return null;
  }

  public static Integer firstInteger(Map<String, Object> body, String... keys) {
    Object value = firstPresent(body, keys);
    if (value == null || value.toString().isBlank()) return null;
    return ((Number) (value instanceof Number ? value : Integer.parseInt(value.toString()))).intValue();
  }

  public static Integer integer(Map<String, Object> body, String key) {
    return firstInteger(body, key);
  }

  public static Boolean bool(Map<String, Object> body, String key) {
    Object value = firstPresent(body, key);
    if (value == null) return null;
    return value instanceof Boolean b ? b : Boolean.parseBoolean(value.toString());
  }

  public static UUID uuid(Object value) {
    if (value == null) throw new BusinessException("INVALID_ID", "Thiếu ID bắt buộc.");
    return UUID.fromString(value.toString());
  }

  public static UUID uuidOrNull(Object value) {
    if (value == null || value.toString().isBlank()) return null;
    return UUID.fromString(value.toString());
  }

  public static String valueOr(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  public static Integer valueOr(Integer value, Integer fallback) {
    return value == null ? fallback : value;
  }

  public static List<Object> list(Object value) {
    if (value instanceof List<?> items) return new ArrayList<>(items);
    if (value == null) return new ArrayList<>();
    return new ArrayList<>(List.of(value));
  }

  public static String normalizeOnlineOffline(String mode) {
    return "offline".equals(mode) ? "offline" : "online";
  }

  public static String normalizeDateTime(String value) {
    if (value.endsWith("Z") || value.contains("+")) return value;
    return OffsetDateTime.parse(value + ZoneOffset.UTC).toString();
  }

  public static OffsetDateTime optionalDateTime(Map<String, Object> body, String... keys) {
    String value = firstString(body, keys);
    if (value == null) return null;
    return OffsetDateTime.parse(normalizeDateTime(value));
  }

  public static String jsonValue(Object value) {
    if (value == null) return "null";
    if (value instanceof Number || value instanceof Boolean) return value.toString();
    if (value instanceof Map || value instanceof List) {
      try {
        return OBJECT_MAPPER.writeValueAsString(value);
      } catch (Exception ex) {
        return "\"" + value + "\"";
      }
    }
    return "\"" + value.toString().replace("\"", "\\\"") + "\"";
  }

  public static Object parseJson(String value) {
    if (value == null) return null;
    try {
      return OBJECT_MAPPER.readValue(value, Object.class);
    } catch (Exception ex) {
      return value;
    }
  }

  public static String locationSummary(String province, String district) {
    if (province == null || province.isBlank()) return district == null ? "" : district;
    if (district == null || district.isBlank()) return province;
    return district + ", " + province;
  }
}
