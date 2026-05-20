package com.example.tutorplatform.dto.payout;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.Map;

public record CreatePayoutRequest(
    @Min(1) int amount,
    @NotBlank String bankName,
    @NotBlank String bankAccount,
    @NotBlank String accountHolder
) {
  public Map<String, Object> toMap() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("amount", amount);
    body.put("bankName", bankName);
    body.put("bankAccount", bankAccount);
    body.put("accountHolder", accountHolder);
    return body;
  }
}
