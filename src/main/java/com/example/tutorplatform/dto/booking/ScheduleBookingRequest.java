package com.example.tutorplatform.dto.booking;

import java.util.LinkedHashMap;
import java.util.Map;

public record ScheduleBookingRequest(
    String scheduledStart,
    String scheduledEnd,
    String startTime,
    String endTime,
    String date,
    String schedule,
    String meetingUrl,
    String location
) {
  public Map<String, Object> toMap() {
    Map<String, Object> body = new LinkedHashMap<>();
    put(body, "scheduledStart", scheduledStart);
    put(body, "scheduledEnd", scheduledEnd);
    put(body, "startTime", startTime);
    put(body, "endTime", endTime);
    put(body, "date", date);
    put(body, "schedule", schedule);
    put(body, "meetingUrl", meetingUrl);
    put(body, "location", location);
    return body;
  }

  private static void put(Map<String, Object> body, String key, Object value) {
    if (value != null) body.put(key, value);
  }
}
