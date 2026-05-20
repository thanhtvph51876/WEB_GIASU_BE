package com.example.tutorplatform.dto.tutoringclass;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record CreateClassRequest(
    UUID learningRequestId,
    @NotNull UUID studentId,
    @NotNull UUID tutorId,
    String subject,
    String subjectId,
    String grade,
    String gradeLevelId,
    String title,
    String mode,
    String learningMode,
    String location,
    String meetingUrl,
    @Min(0) Integer feePerSession,
    @Min(0) Integer hourlyRate,
    @Min(1) Integer sessionsPerWeek,
    String startDate,
    String endDate,
    String status
) {
  public Map<String, Object> toMap() {
    Map<String, Object> body = new LinkedHashMap<>();
    put(body, "learningRequestId", learningRequestId);
    put(body, "studentId", studentId);
    put(body, "tutorId", tutorId);
    put(body, "subject", subject);
    put(body, "subjectId", subjectId);
    put(body, "grade", grade);
    put(body, "gradeLevelId", gradeLevelId);
    put(body, "title", title);
    put(body, "mode", mode);
    put(body, "learningMode", learningMode);
    put(body, "location", location);
    put(body, "meetingUrl", meetingUrl);
    put(body, "feePerSession", feePerSession);
    put(body, "hourlyRate", hourlyRate);
    put(body, "sessionsPerWeek", sessionsPerWeek);
    put(body, "startDate", startDate);
    put(body, "endDate", endDate);
    put(body, "status", status);
    return body;
  }

  private static void put(Map<String, Object> body, String key, Object value) {
    if (value != null) body.put(key, value);
  }
}
