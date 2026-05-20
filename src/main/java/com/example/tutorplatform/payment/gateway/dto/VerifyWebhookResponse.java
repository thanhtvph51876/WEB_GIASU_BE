package com.example.tutorplatform.payment.gateway.dto;

public record VerifyWebhookResponse(
    boolean signatureValid,
    String eventId,
    String gatewayOrderId,
    String gatewayTransactionId,
    GatewayPaymentStatus status,
    int amount,
    String currency,
    String processingError
) {}
