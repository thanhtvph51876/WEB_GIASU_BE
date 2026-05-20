package com.example.tutorplatform.dto.payment;

import java.util.LinkedHashMap;
import java.util.Map;

public record CreateCheckoutRequest(
    String gateway,
    String returnUrl,
    String cancelUrl
) {
  public Map<String, Object> toMap() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("gateway", gateway);
    body.put("returnUrl", returnUrl);
    body.put("cancelUrl", cancelUrl);
    return body;
  }
}
