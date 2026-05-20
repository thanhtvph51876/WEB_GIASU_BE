package com.example.tutorplatform.payment.gateway;

import com.example.tutorplatform.payment.gateway.dto.CreateCheckoutRequest;
import com.example.tutorplatform.payment.gateway.dto.CreateCheckoutResponse;
import com.example.tutorplatform.payment.gateway.dto.VerifyWebhookRequest;
import com.example.tutorplatform.payment.gateway.dto.VerifyWebhookResponse;

public interface PaymentGateway {
  PaymentGatewayType type();

  CreateCheckoutResponse createCheckout(CreateCheckoutRequest request);

  VerifyWebhookResponse verifyWebhook(VerifyWebhookRequest request);

  default VerifyWebhookResponse queryTransaction(String gatewayOrderId) {
    throw new UnsupportedOperationException("Gateway queryTransaction is not implemented yet: " + gatewayOrderId);
  }

  default String refund(String gatewayTransactionId, int amount, String reason) {
    return "refund-" + gatewayTransactionId;
  }
}
