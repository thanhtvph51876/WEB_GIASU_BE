package com.example.tutorplatform.dto.booking;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record CreateBookingRequest(
    @NotNull UUID tutorId,
    UUID learningRequestId,
    String subject,
    String subjectId,
    String grade,
    String gradeLevelId,
    String studentName,
    String parentName,
    String phone,
    @Email String email,
    String preferredTime,
    String teachingMode,
    String learningMode,
    String message,
    String goal
) {
  public Map<String, Object> toMap() {
    Map<String, Object> body = new LinkedHashMap<>();
    put(body, "tutorId", tutorId);
    put(body, "learningRequestId", learningRequestId);
    put(body, "subject", subject);
    put(body, "subjectId", subjectId);
    put(body, "grade", grade);
    put(body, "gradeLevelId", gradeLevelId);
    put(body, "studentName", studentName);
    put(body, "parentName", parentName);
    put(body, "phone", phone);
    put(body, "email", email);
    put(body, "preferredTime", preferredTime);
    put(body, "teachingMode", teachingMode);
    put(body, "learningMode", learningMode);
    put(body, "message", message);
    put(body, "goal", goal);
    return body;
  }

  private static void put(Map<String, Object> body, String key, Object value) {
    if (value != null) body.put(key, value);
  }
}
