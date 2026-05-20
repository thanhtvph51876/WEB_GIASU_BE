package com.example.tutorplatform.payment.gateway.dto;

import java.util.Map;

public record VerifyWebhookRequest(
    Map<String, String> headers,
    String rawPayload
) {}
