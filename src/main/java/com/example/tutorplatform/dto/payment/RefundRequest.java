package com.example.tutorplatform.dto.payment;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.Map;

public record RefundRequest(
    @Min(1) Integer amount,
    @NotBlank String reason
) {
  public Map<String, Object> toMap() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("amount", amount);
    body.put("reason", reason);
    return body;
  }
}
