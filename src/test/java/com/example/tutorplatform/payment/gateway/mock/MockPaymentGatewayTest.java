package com.example.tutorplatform.payment.gateway.mock;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.tutorplatform.payment.gateway.dto.GatewayPaymentStatus;
import com.example.tutorplatform.payment.gateway.dto.VerifyWebhookRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class MockPaymentGatewayTest {
  private final MockPaymentGateway gateway = new MockPaymentGateway();

  @Test
  void verifiesSignedWebhook() throws Exception {
    String payload = """
        {"eventId":"evt-1","gatewayOrderId":"mock-order-1","gatewayTransactionId":"txn-1","status":"success","amount":120000,"currency":"VND"}
        """;

    var result = gateway.verifyWebhook(new VerifyWebhookRequest(
        Map.of("x-mock-signature", hmac(payload)),
        payload
    ));

    assertThat(result.signatureValid()).isTrue();
    assertThat(result.eventId()).isEqualTo("evt-1");
    assertThat(result.gatewayOrderId()).isEqualTo("mock-order-1");
    assertThat(result.gatewayTransactionId()).isEqualTo("txn-1");
    assertThat(result.status()).isEqualTo(GatewayPaymentStatus.SUCCESS);
    assertThat(result.amount()).isEqualTo(120000);
  }

  @Test
  void rejectsInvalidSignature() {
    String payload = """
        {"eventId":"evt-2","gatewayOrderId":"mock-order-2","gatewayTransactionId":"txn-2","status":"success","amount":120000,"currency":"VND"}
        """;

    var result = gateway.verifyWebhook(new VerifyWebhookRequest(
        Map.of("x-mock-signature", "invalid"),
        payload
    ));

    assertThat(result.signatureValid()).isFalse();
    assertThat(result.processingError()).contains("Invalid");
  }

  private String hmac(String payload) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec("mock-gateway-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
    StringBuilder hex = new StringBuilder();
    for (byte b : digest) {
      hex.append(String.format("%02x", b));
    }
    return hex.toString();
  }
}
