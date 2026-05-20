package com.example.tutorplatform.dto.learningrequest;

import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.Map;

public record UpdateLearningRequestStatusRequest(@NotBlank String status) {
  public Map<String, Object> toMap() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", status);
    return body;
  }
}
