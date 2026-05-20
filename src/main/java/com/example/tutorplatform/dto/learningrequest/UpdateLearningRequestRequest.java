package com.example.tutorplatform.dto.learningrequest;

import jakarta.validation.constraints.Email;
import java.util.LinkedHashMap;
import java.util.Map;

public record UpdateLearningRequestRequest(
    String studentName,
    String parentName,
    String phone,
    @Email String email,
    String note,
    String preferredSchedule
) {
  public Map<String, Object> toMap() {
    Map<String, Object> body = new LinkedHashMap<>();
    put(body, "studentName", studentName);
    put(body, "parentName", parentName);
    put(body, "phone", phone);
    put(body, "email", email);
    put(body, "note", note);
    put(body, "preferredSchedule", preferredSchedule);
    return body;
  }

  private static void put(Map<String, Object> body, String key, Object value) {
    if (value != null) body.put(key, value);
  }
}
