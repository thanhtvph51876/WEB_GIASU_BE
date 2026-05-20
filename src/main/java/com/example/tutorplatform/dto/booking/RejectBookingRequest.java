package com.example.tutorplatform.dto.booking;

import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.Map;

public record RejectBookingRequest(@NotBlank String reason) {
  public Map<String, Object> toMap() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("reason", reason);
    return body;
  }
}
