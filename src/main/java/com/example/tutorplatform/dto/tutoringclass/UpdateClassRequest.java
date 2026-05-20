package com.example.tutorplatform.dto.tutoringclass;

import jakarta.validation.constraints.Min;
import java.util.LinkedHashMap;
import java.util.Map;

public record UpdateClassRequest(
    String title,
    String status,
    String location,
    String meetingUrl,
    @Min(0) Integer feePerSession,
    @Min(0) Integer hourlyRate
) {
  public Map<String, Object> toMap() {
    Map<String, Object> body = new LinkedHashMap<>();
    put(body, "title", title);
    put(body, "status", status);
    put(body, "location", location);
    put(body, "meetingUrl", meetingUrl);
    put(body, "feePerSession", feePerSession);
    put(body, "hourlyRate", hourlyRate);
    return body;
  }

  private static void put(Map<String, Object> body, String key, Object value) {
    if (value != null) body.put(key, value);
  }
}
