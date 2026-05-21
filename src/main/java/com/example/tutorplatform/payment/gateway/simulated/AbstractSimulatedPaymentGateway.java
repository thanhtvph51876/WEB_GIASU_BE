package com.example.tutorplatform.payment.gateway.simulated;

import com.example.tutorplatform.payment.gateway.PaymentGateway;
import com.example.tutorplatform.payment.gateway.PaymentGatewayType;
import com.example.tutorplatform.payment.gateway.dto.CreateCheckoutRequest;
import com.example.tutorplatform.payment.gateway.dto.CreateCheckoutResponse;
import com.example.tutorplatform.payment.gateway.dto.GatewayPaymentStatus;
import com.example.tutorplatform.payment.gateway.dto.VerifyWebhookRequest;
import com.example.tutorplatform.payment.gateway.dto.VerifyWebhookResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

abstract class AbstractSimulatedPaymentGateway implements PaymentGateway {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  public CreateCheckoutResponse createCheckout(CreateCheckoutRequest request) {
    String orderId = request.orderCode();
    String code = type().code();
    String checkoutUrl = "http://localhost:3000/payments/pending?paymentId=" + request.paymentId() + "&gateway=" + code + "&orderId=" + orderId;
    String qrCodeUrl = switch (type()) {
      case BANK_QR, PAYOS, MOMO -> "sandbox://qr/" + code + "/" + orderId + "/" + request.amount();
      default -> null;
    };
    String raw = "{\"gateway\":\"" + code + "\",\"orderId\":\"" + orderId + "\",\"amount\":" + request.amount() + ",\"mode\":\"simulated\"}";
    return new CreateCheckoutResponse(code, orderId, checkoutUrl, qrCodeUrl, GatewayPaymentStatus.PENDING, raw);
  }

  @Override
  public VerifyWebhookResponse verifyWebhook(VerifyWebhookRequest request) {
    try {
      String code = type().code();
      String signature = firstPresent(
          request.headers().get("x-" + code + "-signature"),
          request.headers().get("x-payment-signature")
      );
      boolean valid = hmac(request.rawPayload(), code + "-sandbox-secret").equals(signature);
      JsonNode root = objectMapper.readTree(request.rawPayload());
      return new VerifyWebhookResponse(
          valid,
          text(root, "eventId"),
          text(root, "gatewayOrderId"),
          text(root, "gatewayTransactionId"),
          GatewayPaymentStatus.valueOf(text(root, "status").toUpperCase()),
          root.path("amount").asInt(),
          root.path("currency").asText("VND"),
          valid ? null : "Invalid " + code + " webhook signature"
      );
    } catch (Exception ex) {
      return new VerifyWebhookResponse(false, null, null, null, GatewayPaymentStatus.FAILED, 0, "VND", "Cannot parse simulated webhook payload");
    }
  }

  private String text(JsonNode root, String field) {
    return root.path(field).asText(null);
  }

  private String firstPresent(String first, String second) {
    return first == null || first.isBlank() ? second : first;
  }

  private String hmac(String payload, String secret) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
    StringBuilder hex = new StringBuilder();
    for (byte b : digest) {
      hex.append(String.format("%02x", b));
    }
    return hex.toString();
  }
}
