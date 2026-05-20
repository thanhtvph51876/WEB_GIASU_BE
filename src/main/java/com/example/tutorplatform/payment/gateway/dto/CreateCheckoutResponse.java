package com.example.tutorplatform.payment.gateway.dto;

public record CreateCheckoutResponse(
    String gateway,
    String gatewayOrderId,
    String checkoutUrl,
    String qrCodeUrl,
    GatewayPaymentStatus status,
    String rawResponseJson
) {}
