package com.example.tutorplatform.payment.gateway;

public enum PaymentGatewayType {
  MOCK,
  BANK_QR,
  VNPAY,
  MOMO,
  PAYOS,
  STRIPE;

  public static PaymentGatewayType from(String value) {
    if (value == null || value.isBlank()) return MOCK;
    return PaymentGatewayType.valueOf(value.trim().toUpperCase().replace("-", "_"));
  }

  public String code() {
    return name().toLowerCase();
  }
}
