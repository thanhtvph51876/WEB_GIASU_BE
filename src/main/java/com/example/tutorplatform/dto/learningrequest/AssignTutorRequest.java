package com.example.tutorplatform.dto.learningrequest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import jakarta.validation.constraints.NotNull;

public record AssignTutorRequest(@NotNull UUID tutorId, String learningMode) {
  public Map<String, Object> toMap() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tutorId", tutorId);
    if (learningMode != null) body.put("learningMode", learningMode);
    return body;
  }
}
