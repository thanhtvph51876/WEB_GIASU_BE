package com.example.tutorplatform.payment.gateway.mock;

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
import org.springframework.stereotype.Component;

@Component
public class MockPaymentGateway implements PaymentGateway {
  private static final String SECRET = "mock-gateway-secret";
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  public PaymentGatewayType type() {
    return PaymentGatewayType.MOCK;
  }

  @Override
  public CreateCheckoutResponse createCheckout(CreateCheckoutRequest request) {
    String orderId = request.orderCode();
    String checkoutUrl = "http://localhost:3000/payments/pending?paymentId=" + request.paymentId() + "&gateway=mock&orderId=" + orderId;
    String qrCodeUrl = "mock://qr/" + orderId + "/" + request.amount();
    String raw = "{\"gateway\":\"mock\",\"orderId\":\"" + orderId + "\",\"amount\":" + request.amount() + "}";
    return new CreateCheckoutResponse(type().code(), orderId, checkoutUrl, qrCodeUrl, GatewayPaymentStatus.PENDING, raw);
  }

  @Override
  public VerifyWebhookResponse verifyWebhook(VerifyWebhookRequest request) {
    try {
      String signature = request.headers().getOrDefault("x-mock-signature", "");
      boolean valid = hmac(request.rawPayload()).equals(signature);
      JsonNode root = objectMapper.readTree(request.rawPayload());
      return new VerifyWebhookResponse(
          valid,
          text(root, "eventId"),
          text(root, "gatewayOrderId"),
          text(root, "gatewayTransactionId"),
          GatewayPaymentStatus.valueOf(text(root, "status").toUpperCase()),
          root.path("amount").asInt(),
          root.path("currency").asText("VND"),
          valid ? null : "Invalid mock webhook signature"
      );
    } catch (Exception ex) {
      return new VerifyWebhookResponse(false, null, null, null, GatewayPaymentStatus.FAILED, 0, "VND", "Cannot parse webhook payload");
    }
  }

  private String text(JsonNode root, String field) {
    return root.path(field).asText(null);
  }

  private String hmac(String payload) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
    StringBuilder hex = new StringBuilder();
    for (byte b : digest) {
      hex.append(String.format("%02x", b));
    }
    return hex.toString();
  }
}
