package com.example.tutorplatform.dto.session;

import java.util.LinkedHashMap;
import java.util.Map;

public record CreateSessionRequest(
    String scheduledStart,
    String scheduledEnd,
    String startTime,
    String endTime,
    String tutorNote,
    String studentNote,
    String note,
    String status
) {
  public Map<String, Object> toMap() {
    Map<String, Object> body = new LinkedHashMap<>();
    put(body, "scheduledStart", scheduledStart);
    put(body, "scheduledEnd", scheduledEnd);
    put(body, "startTime", startTime);
    put(body, "endTime", endTime);
    put(body, "tutorNote", tutorNote);
    put(body, "studentNote", studentNote);
    put(body, "note", note);
    put(body, "status", status);
    return body;
  }

  private static void put(Map<String, Object> body, String key, Object value) {
    if (value != null) body.put(key, value);
  }
}
