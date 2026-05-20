package com.example.tutorplatform.dto.review;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record CreateReviewRequest(
    @NotNull UUID sessionId,
    @Min(1) @Max(5) Integer rating,
    String content,
    String comment
) {
  public Map<String, Object> toMap() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sessionId", sessionId);
    body.put("rating", rating);
    if (content != null) body.put("content", content);
    if (comment != null) body.put("comment", comment);
    return body;
  }
}
