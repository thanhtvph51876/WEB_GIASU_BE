package com.example.tutorplatform.payment.gateway.dto;

import java.util.Map;
import java.util.UUID;

public record CreateCheckoutRequest(
    UUID paymentId,
    String orderCode,
    int amount,
    String currency,
    String description,
    String returnUrl,
    String cancelUrl,
    Map<String, Object> metadata
) {}
